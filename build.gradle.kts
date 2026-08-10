plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

apply(from = "gradle/quality.gradle.kts")

val strictQualityGate = providers.gradleProperty("zhiban.quality.strict")
    .map(String::toBoolean)
    .orElse(true)

subprojects {
    fun configureKotlinQuality() {
        if (!pluginManager.hasPlugin("io.gitlab.arturbosch.detekt")) {
            pluginManager.apply("io.gitlab.arturbosch.detekt")
            pluginManager.apply("org.jlleitschuh.gradle.ktlint")

            extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
                config.setFrom(rootProject.files("config/detekt/detekt.yml"))
                buildUponDefaultConfig = false
                allRules = false
                ignoreFailures = !strictQualityGate.get()
                parallel = true
            }
            tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
                setSource(files("src/main/java", "src/main/kotlin"))
                exclude("**/build/**", "**/generated/**")
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                    sarif.required.set(true)
                    md.required.set(true)
                }
            }
            extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
                version.set("1.8.0")
                android.set(true)
                outputToConsole.set(true)
                ignoreFailures.set(!strictQualityGate.get())
                filter {
                    include("**/src/main/**/*.kt")
                    exclude("**/build/**", "**/generated/**")
                }
            }
            tasks.configureEach {
                val nonProductionSourceSet = name.startsWith("ktlint") &&
                    name.endsWith("SourceSetCheck") &&
                    name != "ktlintMainSourceSetCheck"
                if (nonProductionSourceSet || name == "ktlintKotlinScriptCheck") {
                    enabled = false
                }
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.android") { configureKotlinQuality() }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { configureKotlinQuality() }
}

tasks.register("verifyNoCommittedSecrets") {
    group = "verification"
    description = "Fails when source-controlled text contains a credential-shaped secret."
    doLast {
        val secretPatterns = listOf(
            Regex("sk-[A-Za-z0-9_-]{20,}"),
            Regex("AIza[0-9A-Za-z_-]{20,}"),
            Regex("AKIA[0-9A-Z]{16}"),
            Regex("(?i)Bearer\\s+[A-Za-z0-9._-]{24,}"),
        )
        val violations = fileTree(projectDir) {
            include("**/*.kt", "**/*.kts", "**/*.java", "**/*.xml", "**/*.html", "**/*.js", "**/*.properties")
            exclude(".git/**", ".gradle/**", "**/build/**", "**/local.properties", "**/signing.properties")
        }.filter { source ->
            val text = runCatching { source.readText() }.getOrDefault("")
            secretPatterns.any { pattern -> pattern.containsMatchIn(text) }
        }.map { it.relativeTo(projectDir).path }
        check(violations.isEmpty()) {
            violations.joinToString("\n", prefix = "Credential-shaped secret found in tracked source:\n")
        }
    }
}

tasks.register("verifyAgentModuleBoundaries") {
    group = "verification"
    description = "Fails when an established Agent module or UI crosses its declared ownership boundary."
    doLast {
        data class Rule(val root: String, val forbidden: List<String>)
        val rules = listOf(
            Rule("agent/provider/src/main/kotlin/com/zhiban/rebuild/runtime/provider", listOf(
                "com.zhiban.rebuild.ui.", "com.zhiban.rebuild.data.", "androidx.room.",
            )),
            Rule("agent/context/src/main/kotlin/com/zhiban/rebuild/runtime/context", listOf(
                "com.zhiban.rebuild.ui.", "com.zhiban.rebuild.runtime.provider.",
            )),
            Rule("agent/skills/src/main/kotlin", listOf(
                "com.zhiban.rebuild.ui.", "com.zhiban.rebuild.data.", "android.", "androidx.",
            )),
            Rule("agent/mcp/src/main/kotlin", listOf(
                "com.zhiban.rebuild.ui.", "com.zhiban.rebuild.data.", "android.", "androidx.",
            )),
            Rule("agent/memory/src/main/kotlin", listOf(
                "com.zhiban.rebuild.ui.", "com.zhiban.rebuild.data.", "android.", "androidx.",
            )),
            Rule("agent/feature-ask/src/main/kotlin", listOf(
                "com.zhiban.rebuild.data.", "android.", "androidx.",
            )),
            Rule("agent/runtime/src/main/kotlin/com/zhiban/rebuild/runtime/kernel", listOf(
                "com.zhiban.rebuild.ui.",
            )),
            Rule("app/src/main/java/com/zhiban/rebuild/ui", listOf(
                "com.zhiban.rebuild.data.agent.AgentDatabase", ".memoryPersistenceDao()",
                ".runtimeRunDao()", ".runtimeEventDao()",
            )),
        )
        val violations = mutableListOf<String>()
        rules.forEach { rule ->
            file(rule.root).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
                val text = source.readText()
                rule.forbidden.filter(text::contains).forEach { token ->
                    violations += "${source.relativeTo(projectDir)} contains forbidden dependency '$token'"
                }
            }
        }
        val preferences = file("app/src/main/java/com/zhiban/rebuild/ui/chat/PreferencesManager.kt").readText()
        listOf("fun getApiKey(", "fun saveApiKey(", "fun saveSettings(").filter(preferences::contains).forEach {
            violations += "PreferencesManager exposes legacy Provider credential API '$it'"
        }
        check(violations.isEmpty()) { violations.joinToString("\n", prefix = "Agent module boundary violations:\n") }
    }
}

tasks.register("verifyUiConsistency") {
    group = "verification"
    description = "Prevents feature pages from bypassing the shared visual system."
    doLast {
        val uiRoot = file("app/src/main/java/com/zhiban/rebuild/ui")
        val violations = mutableListOf<String>()
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
            val relative = source.relativeTo(projectDir).path
            val text = source.readText()
            if (!relative.endsWith("ui/components/ZhiBanToggles.kt") && Regex("\\bSwitch\\s*\\(").containsMatchIn(text)) {
                violations += "$relative uses Switch directly; use ZhiBanSwitch/ZhiBanToggleRow"
            }
            if (!relative.endsWith("ui/components/ZhiBanDialogs.kt")) {
                if (Regex("\\bAlertDialog\\s*\\(").containsMatchIn(text)) {
                    violations += "$relative uses AlertDialog directly; use ZhiBanAlertDialog"
                }
                if (Regex("\\bModalBottomSheet\\s*\\(").containsMatchIn(text)) {
                    violations += "$relative uses ModalBottomSheet directly; use ZhiBanBottomSheet"
                }
                if (Regex("\\bDialog\\s*\\(").containsMatchIn(text)) {
                    violations += "$relative uses Dialog directly; use ZhiBanDialogHost/ZhiBanTaskDialog/ZhiBanPopoverDialog"
                }
            }
            if (!relative.contains("/ui/theme/") && !relative.contains("/ui/icons/") && Regex("Color\\(0x").containsMatchIn(text)) {
                violations += "$relative declares a raw color; use a theme semantic role"
            }
            if (!relative.endsWith("ui/theme/Type.kt") && !relative.endsWith("ui/components/ZhiBanVisual.kt") && Regex("fontSize\\s*=").containsMatchIn(text)) {
                violations += "$relative declares a font size; use MaterialTheme.typography"
            }
        }
        check(violations.isEmpty()) {
            violations.joinToString("\n", prefix = "UI consistency violations:\n")
        }
    }
}

val check = tasks.register("check") {
    group = "verification"
    description = "Runs project checks together with Agent module ownership verification."
    dependsOn("verifyAgentModuleBoundaries", "verifyNoCommittedSecrets", "verifyUiConsistency", "qualityReport")
}

gradle.projectsEvaluated {
    tasks.named("qualityReport").configure {
        dependsOn(subprojects.flatMap { subproject ->
            subproject.tasks.matching { task ->
                task.name == "detekt" || task.name == "ktlintCheck"
            }.toList()
        })
    }
    check.configure {
        dependsOn(subprojects.mapNotNull { it.tasks.findByName("check") })
    }
}
