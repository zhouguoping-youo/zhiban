package com.zhiban.rebuild.runtime.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelPolicyTest {
    @Test fun `stepfun accepts only its maintained model`() {
        TrustedProviderRegistry.PRESETS.forEach { preset ->
            val profile = ProviderProfile(preset.providerId, preset.endpointId, preset.defaultModel, "credential-v1", 1)
            preset.models.forEach { requested ->
                assertEquals(requested, ProviderModelPolicy.select(profile, requested).modelId)
            }
            assertEquals(preset.defaultModel, ProviderModelPolicy.select(profile, "foreign-model").modelId)
        }
    }

    @Test fun `stepfun routes text and image to matching trusted models`() {
        val profile = ProviderProfile(
            "stepfun",
            "stepfun-cn-openai-v1",
            TrustedProviderRegistry.STEPFUN_TEXT_MODEL,
            "credential-v1",
            1,
        )

        assertEquals(
            TrustedProviderRegistry.STEPFUN_TEXT_MODEL,
            ProviderModelPolicy.selectForInput(profile, null, hasImage = false).modelId,
        )
        assertEquals(
            TrustedProviderRegistry.STEPFUN_VISION_MODEL,
            ProviderModelPolicy.selectForInput(profile, null, hasImage = true).modelId,
        )
        assertEquals(
            TrustedProviderRegistry.STEPFUN_VISION_MODEL,
            ProviderModelPolicy.selectForInput(
                profile,
                TrustedProviderRegistry.STEPFUN_TEXT_MODEL,
                hasImage = true,
            ).modelId,
        )
    }

    @Test fun `trusted contracts do not claim unsupported modalities`() {
        val registry = TrustedProviderRegistry()
        val text = registry.resolve(
            ProviderProfile("stepfun", "stepfun-cn-openai-v1", TrustedProviderRegistry.STEPFUN_TEXT_MODEL, "c", 1),
        ).modelContracts.getValue(TrustedProviderRegistry.STEPFUN_TEXT_MODEL)
        val vision = registry.resolve(
            ProviderProfile("stepfun", "stepfun-cn-openai-v1", TrustedProviderRegistry.STEPFUN_VISION_MODEL, "c", 1),
        ).modelContracts.getValue(TrustedProviderRegistry.STEPFUN_VISION_MODEL)

        assertEquals(setOf("text"), text.modalities)
        assertEquals(setOf("text", "image"), vision.modalities)
    }
}
