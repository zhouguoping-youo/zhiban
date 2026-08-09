val strictQualityGate = providers.gradleProperty("zhiban.quality.strict")
    .map(String::toBoolean)
    .orElse(true)

val productionKotlinSources = fileTree(projectDir) {
    include("app/src/main/java/**/*.kt")
    include("agent/*/src/main/kotlin/**/*.kt")
    exclude("**/build/**", "**/generated/**")
}

val auditKotlinFileSizes = tasks.register("auditKotlinFileSizes") {
    group = "verification"
    description = "Reports Kotlin production files over 600 effective lines and rejects files over 1000."
    inputs.files(productionKotlinSources)
    val reportFile = layout.buildDirectory.file("reports/quality/kotlin-file-sizes.txt")
    outputs.file(reportFile)
    doLast {
        val entries = productionKotlinSources.files.mapNotNull { source ->
            val physicalLines = source.useLines { it.count() }
            val effectiveLines = source.readLines().count { raw ->
                val line = raw.trim()
                val indentation = raw.length - raw.trimStart().length
                val structuralOnly = line in setOf("(", ")", "{", "}", "),", "},", "[", "]")
                val formatterContinuation = indentation >= 12 && line.endsWith(',') &&
                    listOf("=", "->", "if ", "when ", "for ", "while ", "return ", "check(", "require(")
                        .none(line::contains)
                line.isNotEmpty() &&
                    !line.startsWith("//") &&
                    !line.startsWith("package ") &&
                    !line.startsWith("import ") &&
                    !line.startsWith('@') &&
                    !structuralOnly &&
                    !formatterContinuation
            }
            when {
                effectiveLines > 1_000 ->
                    "ERROR|$effectiveLines|physical=$physicalLines|${source.relativeTo(projectDir).invariantSeparatorsPath}"
                effectiveLines > 600 ->
                    "WARN|$effectiveLines|physical=$physicalLines|${source.relativeTo(projectDir).invariantSeparatorsPath}"
                else -> null
            }
        }.sortedWith(compareByDescending<String> { it.substringAfter('|').substringBefore('|').toInt() })
        val output = buildString {
            appendLine("Kotlin effective-code-size audit: warning >600, error >1000; physical lines included for review")
            appendLine("findings=${entries.size}")
            entries.forEach(::appendLine)
        }
        val destination = reportFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(output)
        logger.warn(output.trimEnd())
        val errors = entries.count { it.startsWith("ERROR|") }
        if (strictQualityGate.get() && errors > 0) {
            throw GradleException("$errors Kotlin production files exceed 1000 lines; see ${destination.path}")
        }
    }
}

val auditKotlinDuplication = tasks.register("auditKotlinDuplication") {
    group = "verification"
    description = "Reports substantial exact Kotlin clones using normalized 20-line windows."
    inputs.files(productionKotlinSources)
    val reportFile = layout.buildDirectory.file("reports/quality/kotlin-duplication.txt")
    outputs.file(reportFile)
    doLast {
        val windows = linkedMapOf<String, MutableList<Pair<String, Int>>>()
        productionKotlinSources.files.sortedBy { it.path }.forEach { source ->
            val meaningful = source.readLines().mapIndexedNotNull { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("package ") || line.startsWith("import ")) {
                    null
                } else {
                    index + 1 to line.replace(Regex("\\s+"), " ")
                }
            }
            meaningful.windowed(size = 20, step = 1).forEach { block ->
                val fingerprint = block.joinToString("\n") { it.second }
                windows.getOrPut(fingerprint) { mutableListOf() } +=
                    source.relativeTo(projectDir).invariantSeparatorsPath to block.first().first
            }
        }
        val clones = windows.values.map(List<Pair<String, Int>>::distinct)
            .filter { locations -> locations.map { it.first }.distinct().size > 1 }
            .distinctBy { locations -> locations.joinToString { "${it.first}:${it.second}" } }
            .sortedBy { it.first().first }
        val output = buildString {
            appendLine("Kotlin exact-clone audit: normalized 20-line windows across files")
            appendLine("findings=${clones.size}")
            clones.forEachIndexed { index, locations ->
                appendLine("CLONE-${index + 1}|${locations.joinToString { "${it.first}:${it.second}" }}")
            }
        }
        val destination = reportFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(output)
        if (clones.isNotEmpty()) logger.warn(output.trimEnd())
        if (strictQualityGate.get() && clones.isNotEmpty()) {
            throw GradleException("${clones.size} substantial exact Kotlin clones remain; see ${destination.path}")
        }
    }
}

val auditCancellationSafety = tasks.register("auditCancellationSafety") {
    group = "verification"
    description = "Reports generic catches that do not visibly preserve coroutine cancellation."
    inputs.files(productionKotlinSources)
    val reportFile = layout.buildDirectory.file("reports/quality/cancellation-safety.txt")
    outputs.file(reportFile)
    doLast {
        // CancellationException can only be thrown at a suspend point, so a generic catch can only
        // swallow coroutine cancellation when it sits inside a `suspend fun` body. Catches in plain
        // (non-suspend) functions guard framework/IO faults and are not cancellation risks. We
        // approximate "enclosing function is suspend" by scanning back to the nearest `fun` token
        // and checking for a `suspend` modifier on it; this also lets us broaden coverage from
        // Throwable|Exception to RuntimeException (CancellationException extends RuntimeException)
        // without flagging non-suspend catches such as MediaMetadataRetriever guards.
        val catchPattern = Regex("catch\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(Throwable|Exception|RuntimeException)\\s*\\)\\s*\\{")
        val funTokenPattern = Regex("\\bfun\\b")
        val suspendModifierPattern = Regex("suspend\\s*$")
        val findings = mutableListOf<String>()
        productionKotlinSources.files.sortedBy { it.path }.forEach { source ->
            val text = source.readText()
            catchPattern.findAll(text).forEach { match ->
                val variable = match.groupValues[1]
                val line = text.take(match.range.first).count { it == '\n' } + 1
                val preceding = text.substring(0, match.range.first)
                val enclosingFun = funTokenPattern.findAll(preceding).lastOrNull()
                val suspendContext = enclosingFun != null && suspendModifierPattern.containsMatchIn(
                    text.substring(maxOf(0, enclosingFun.range.first - 20), enclosingFun.range.first),
                )
                if (suspendContext) {
                    val following = text.substring(match.range.last + 1, minOf(text.length, match.range.last + 600))
                    // Cancellation handlers often contain a NonCancellable cleanup block before the
                    // following generic catch; keep enough context to recognize that explicit branch.
                    val context = text.substring(maxOf(0, match.range.first - 1_600), match.range.first)
                    val preservesCancellation = following.contains("throw $variable") ||
                        following.contains("$variable is CancellationException") ||
                        context.contains(Regex("catch\\s*\\([^)]*CancellationException"))
                    if (!preservesCancellation) {
                        findings += "RISK|${source.relativeTo(projectDir).invariantSeparatorsPath}:$line|${match.groupValues[2]}"
                    }
                }
            }
        }
        val output = buildString {
            appendLine("Coroutine cancellation audit: generic catches without a visible cancellation rethrow")
            appendLine("findings=${findings.size}")
            findings.forEach(::appendLine)
        }
        val destination = reportFile.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(output)
        if (findings.isNotEmpty()) logger.warn(output.trimEnd())
        if (strictQualityGate.get() && findings.isNotEmpty()) {
            throw GradleException("${findings.size} cancellation-safety risks remain; see ${destination.path}")
        }
    }
}

tasks.register("qualityReport") {
    group = "verification"
    description = "Runs the strict Kotlin quality checks; use -Pzhiban.quality.strict=false only for local diagnosis."
    dependsOn(auditKotlinFileSizes, auditKotlinDuplication, auditCancellationSafety)
}
