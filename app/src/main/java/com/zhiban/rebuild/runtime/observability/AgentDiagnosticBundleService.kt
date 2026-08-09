package com.zhiban.rebuild.runtime.observability

import android.content.Context
import com.zhiban.rebuild.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Produces a deliberately content-free support artifact.
 *
 * Conversation text, prompts, entities, tool arguments/results, attachment references,
 * credential references and request headers are not accepted by this API and therefore
 * cannot accidentally enter an exported bundle.
 */
class AgentDiagnosticBundleService @Inject internal constructor(@ApplicationContext private val context: Context, private val traces: AgentTraceService) {
    suspend fun create(nowEpochMs: Long = System.currentTimeMillis()): File = withContext(Dispatchers.IO) {
        val recent = traces.recent(MAX_RUNS)
        val metrics = traces.metrics(MAX_RUNS)
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("generatedAtEpochMs", nowEpochMs)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("runtime", "runtime-v2")
            put("privacy", "CONTENT_FREE_REDACTED")
            put(
                "metrics",
                buildJsonObject {
                    put("sampledRuns", metrics.sampledRuns)
                    put("successRatePercent", metrics.successRatePercent)
                    put("averageDurationMs", metrics.averageDurationMs)
                    put("toolExecutionCount", metrics.toolExecutionCount)
                    put("degradationRatePercent", metrics.degradationRatePercent)
                    metrics.firstTokenP95Ms?.let { put("firstTokenP95Ms", it) }
                    metrics.retrievalP95Ms?.let { put("retrievalP95Ms", it) }
                    metrics.averageToolDurationMs?.let { put("averageToolDurationMs", it) }
                },
            )
            put(
                "runs",
                buildJsonArray {
                    recent.forEach { trace ->
                        add(
                            buildJsonObject {
                                // Export a one-way local ordinal instead of the persisted run identifier.
                                put("ordinal", recent.indexOf(trace) + 1)
                                put("status", trace.status)
                                put("durationMs", trace.durationMs)
                                put("attemptCount", trace.attemptCount)
                                put("eventCount", trace.eventCount)
                                put("toolNames", buildJsonArray { trace.toolNames.forEach { add(JsonPrimitive(it)) } })
                                put(
                                    "degradationPaths",
                                    buildJsonArray {
                                        trace.degradationPaths.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put(
                                    "phases",
                                    buildJsonArray {
                                        trace.auditSteps.map { it.phase }.distinct().forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.filter { nowEpochMs - it.lastModified() > RETENTION_MS }?.forEach(File::delete)
        File(directory, "zhiban-agent-diagnostic-$nowEpochMs.json").apply {
            writeText(
                Json {
                    prettyPrint = true
                }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload),
            )
        }
    }

    private companion object {
        const val DIRECTORY = "diagnostics"
        const val MAX_RUNS = 50
        const val RETENTION_MS = 24L * 60 * 60 * 1_000
    }
}
