package com.zhiban.rebuild.runtime.embedding

import android.content.Context
import com.zhiban.rebuild.foundation.Sensitivity
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.provider.KeystoreCredentialVault
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundExportDecision
import com.zhiban.rebuild.provider.OutboundExportDescriptor
import com.zhiban.rebuild.provider.OutboundExportGate
import com.zhiban.rebuild.provider.OutboundPiiDetector
import com.zhiban.rebuild.provider.OutboundPurpose
import com.zhiban.rebuild.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.context.EmbeddingGateway
import com.zhiban.rebuild.runtime.context.EmbeddingInput
import com.zhiban.rebuild.runtime.context.EmbeddingPurpose
import com.zhiban.rebuild.runtime.context.EmbeddingSpace
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal interface EmbeddingTransport {
    fun embed(endpoint: String, model: String, credential: ByteArray, texts: List<String>): List<FloatArray>
}

internal class VolcEmbeddingTransport(private val client: OkHttpClient) : EmbeddingTransport {
    override fun embed(endpoint: String, model: String, credential: ByteArray, texts: List<String>): List<FloatArray> {
        require(endpoint == VolcEmbeddingEnvironment.OFFICIAL_ENDPOINT) { "EMBEDDING_ENDPOINT_NOT_TRUSTED" }
        require(texts.isNotEmpty() && texts.size <= 256 && texts.all { it.toByteArray().size in 1..100_000 }) {
            "EMBEDDING_INPUT_INVALID"
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
            put("encoding_format", JsonPrimitive("float"))
        }.toString().toRequestBody("application/json".toMediaType())
        val token = credential.toString(Charsets.UTF_8)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $token").post(body).build()
        return client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("EMBEDDING_HTTP_${response.code}")
            val data = Json.parseToJsonElement(responseText).jsonObject["data"]?.jsonArray
                ?: error("EMBEDDING_RESPONSE_INVALID")
            val ordered = data.map { element ->
                val value = element.jsonObject
                val index = value["index"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?: error("EMBEDDING_RESPONSE_INVALID")
                val vector = value["embedding"]?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray()
                    ?: error("EMBEDDING_RESPONSE_INVALID")
                index to vector
            }.sortedBy { it.first }
            require(ordered.map { it.first } == texts.indices.toList()) { "EMBEDDING_RESULT_INDEX_MISMATCH" }
            ordered.map { it.second }
        }
    }
}

interface EmbeddingConfiguration {
    suspend fun configure(apiKey: ByteArray, modelId: String): EmbeddingSpace

    suspend fun clear()

    suspend fun healthCheck(): Boolean

    suspend fun activeSpace(): EmbeddingSpace?
}

/** Production embedding configuration with probe-before-publish and Keystore-only credentials. */
class VolcEmbeddingEnvironment internal constructor(
    context: Context,
    private val vault: KeystoreCredentialVault,
    private val transport: EmbeddingTransport,
    private val outboundGate: OutboundExportGate,
) : EmbeddingGateway,
    EmbeddingConfiguration {
    private val prefs = context.getSharedPreferences("agent_embedding_profile", Context.MODE_PRIVATE)

    override suspend fun configure(apiKey: ByteArray, modelId: String): EmbeddingSpace = withContext(Dispatchers.IO) {
        val model = modelId.trim()
        require(model.matches(Regex("[A-Za-z0-9._-]{2,128}"))) { "EMBEDDING_MODEL_INVALID" }
        require(apiKey.size in 8..16_384) { "EMBEDDING_CREDENTIAL_INVALID" }
        val probeInput = EmbeddingInput(
            text = "知伴连接测试",
            sensitivity = Sensitivity.PUBLIC,
            purpose = EmbeddingPurpose.CONFIGURATION_PROBE,
            sourceKind = "embedding_config",
            sourceId = "probe",
        )
        requireExportAllowed(listOf(probeInput), "embedding-probe-${UUID.randomUUID()}")
        val temporaryRef = "embedding.temp.${UUID.randomUUID()}"
        val copy = apiKey.copyOf()
        try {
            vault.provision(temporaryRef, KEY_VERSION, copy)
            val probe = withSecret(temporaryRef) {
                transport.embed(OFFICIAL_ENDPOINT, model, it, listOf(probeInput.text))
            }
                .singleOrNull() ?: error("EMBEDDING_PROBE_INVALID")
            validateVector(probe)
            val space = EmbeddingSpace(PROVIDER_ID, model, probe.size)
            vault.provision(STABLE_REF, KEY_VERSION, copy)
            check(prefs.edit().putString(KEY_MODEL, model).putInt(KEY_DIMENSIONS, probe.size).commit())
            space
        } finally {
            copy.fill(0)
            runSuspendCatching { vault.delete(temporaryRef, KEY_VERSION) }
        }
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    override suspend fun clear() {
        prefs.edit().clear().commit()
        runSuspendCatching { vault.delete(STABLE_REF, KEY_VERSION) }
    }

    override suspend fun healthCheck(): Boolean = runSuspendCatching {
        val space = activeSpace() ?: return false
        embed(
            listOf(
                EmbeddingInput(
                    text = "知伴连接测试",
                    sensitivity = Sensitivity.PUBLIC,
                    purpose = EmbeddingPurpose.HEALTH_CHECK,
                    sourceKind = "embedding_health",
                    sourceId = "probe",
                ),
            ),
            space,
        ).single()
        true
    }.getOrDefault(false)

    override suspend fun activeSpace(): EmbeddingSpace? {
        val model = prefs.getString(KEY_MODEL, null) ?: return null
        val dimensions = prefs.getInt(KEY_DIMENSIONS, 0)
        if (dimensions !in 8..16_384 || !vault.contains(STABLE_REF, KEY_VERSION)) return null
        return EmbeddingSpace(PROVIDER_ID, model, dimensions)
    }

    override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray> = withContext(Dispatchers.IO) {
        require(space == activeSpace()) { "EMBEDDING_SPACE_NOT_ACTIVE" }
        require(inputs.isNotEmpty() && inputs.size <= 256) { "EMBEDDING_INPUT_INVALID" }
        requireExportAllowed(inputs, "embedding-${UUID.randomUUID()}")
        val vectors = withSecret(STABLE_REF) {
            transport.embed(OFFICIAL_ENDPOINT, space.modelId, it, inputs.map(EmbeddingInput::text))
        }
        require(vectors.size == inputs.size) { "EMBEDDING_RESULT_COUNT_MISMATCH" }
        vectors.onEach {
            require(it.size == space.dimensions)
            validateVector(it)
        }
    }

    private suspend fun requireExportAllowed(inputs: List<EmbeddingInput>, requestId: String) {
        val directIdentifierPresent = inputs.any { OutboundPiiDetector.containsDirectIdentifier(it.text) }
        val sensitivePresent = inputs.any { it.sensitivity == Sensitivity.SENSITIVE }
        val decision = outboundGate.evaluate(
            OutboundExportDescriptor(
                requestId = requestId,
                channel = OutboundChannel.EMBEDDING,
                purpose = if (inputs.all { it.purpose == EmbeddingPurpose.USER_QUERY }) {
                    OutboundPurpose.USER_AUTHORED
                } else {
                    OutboundPurpose.AUTO_RETRIEVED
                },
                sensitivities = inputs.mapTo(linkedSetOf()) { input ->
                    when (input.sensitivity) {
                        Sensitivity.PUBLIC -> OutboundSensitivity.PUBLIC
                        Sensitivity.PERSONAL -> OutboundSensitivity.PERSONAL
                        Sensitivity.SENSITIVE -> OutboundSensitivity.SENSITIVE
                    }
                },
                payloadCount = inputs.size,
                byteCount = inputs.sumOf { it.text.toByteArray().size.toLong() },
            ),
            contentAllowed = !sensitivePresent && !directIdentifierPresent,
        )
        when (decision) {
            OutboundExportDecision.ALLOWED -> Unit
            OutboundExportDecision.CONSENT_REQUIRED -> error("EMBEDDING_REMOTE_EXPORT_CONSENT_REQUIRED")
            OutboundExportDecision.CONTENT_BLOCKED -> error("EMBEDDING_SENSITIVE_INPUT_BLOCKED")
        }
    }

    private suspend fun <T> withSecret(ref: String, block: suspend (ByteArray) -> T): T = vault.withCredential(ref, KEY_VERSION, block)

    private fun validateVector(vector: FloatArray) {
        require(vector.size in 8..16_384 && vector.all(Float::isFinite) && vector.any { it != 0f }) {
            "EMBEDDING_VECTOR_INVALID"
        }
    }

    companion object {
        const val OFFICIAL_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/embeddings"
        private const val PROVIDER_ID = "volc-ark"
        private const val STABLE_REF = "embedding.volc.primary"
        private const val KEY_VERSION = 1
        private const val KEY_MODEL = "model_id"
        private const val KEY_DIMENSIONS = "dimensions"
    }
}
