package com.zhiban.rebuild.runtime.provider

import java.security.MessageDigest

data class TrustedProviderEndpoint(
    val providerId: String,
    val endpointId: String,
    val chatUrl: String,
    val probeUrl: String,
    val modelContracts: Map<String, TrustedModelContract>,
    val maxTokensField: String = "max_tokens",
)

data class TrustedModelContract(val modalities: Set<String>, val features: Set<String>, val maxContextTokens: Int, val maxOutputTokens: Int)

data class ProviderPreset(val providerId: String, val displayName: String, val endpointId: String, val defaultModel: String, val models: List<String>)

class TrustedProviderRegistry(private val endpoints: Map<String, TrustedProviderEndpoint> = defaults()) {
    fun presets(): List<ProviderPreset> = PRESETS
    fun preset(providerId: String): ProviderPreset = PRESETS.firstOrNull { it.providerId == providerId }
        ?: error("UNTRUSTED_PROVIDER")
    fun resolve(profile: ProviderProfile): TrustedProviderEndpoint {
        val endpoint = endpoints[profile.endpointId] ?: error("UNTRUSTED_ENDPOINT")
        check(endpoint.providerId == profile.providerId) { "PROVIDER_ENDPOINT_MISMATCH" }
        check(profile.modelId in endpoint.modelContracts) { "UNTRUSTED_MODEL" }
        check(profile.credentialRef.isNotBlank() && profile.keyVersion > 0) { "INVALID_CREDENTIAL_BINDING" }
        return endpoint
    }

    fun digest(profile: ProviderProfile): String {
        resolve(profile)
        val canonical = listOf(
            profile.providerId,
            profile.endpointId,
            profile.modelId,
            profile.credentialRef,
            profile.keyVersion,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val STEPFUN_TEXT_MODEL = "step-3.5-flash"
        const val STEPFUN_VISION_MODEL = "step-1o-turbo-vision"
        const val STEPFUN_REALTIME_MODEL = "stepaudio-2.5-realtime"

        val PRESETS = listOf(
            ProviderPreset(
                "stepfun",
                "阶跃星辰",
                "stepfun-cn-openai-v1",
                STEPFUN_TEXT_MODEL,
                listOf(STEPFUN_TEXT_MODEL, STEPFUN_VISION_MODEL),
            ),
        )

        private fun contracts(models: List<String>, context: Int = 131_072, output: Int = 32_768, modalities: Set<String> = setOf("text", "image")) =
            models.associateWith {
                TrustedModelContract(modalities, setOf("stream", "tools", "usage", "cancel", "rerank"), context, output)
            }

        private fun defaults() = listOf(
            TrustedProviderEndpoint(
                providerId = "stepfun",
                endpointId = "stepfun-cn-openai-v1",
                chatUrl = "https://api.stepfun.com/v1/chat/completions",
                probeUrl = "https://api.stepfun.com/v1/models",
                modelContracts = mapOf(
                    STEPFUN_TEXT_MODEL to TrustedModelContract(
                        modalities = setOf("text"),
                        features = setOf("stream", "tools", "usage", "cancel", "rerank"),
                        maxContextTokens = 256_000,
                        maxOutputTokens = 8_192,
                    ),
                    STEPFUN_VISION_MODEL to TrustedModelContract(
                        modalities = setOf("text", "image"),
                        features = setOf("stream", "tools", "usage", "cancel"),
                        maxContextTokens = 64_000,
                        maxOutputTokens = 8_192,
                    ),
                ),
            ),
        ).associateBy { it.endpointId }
    }
}
