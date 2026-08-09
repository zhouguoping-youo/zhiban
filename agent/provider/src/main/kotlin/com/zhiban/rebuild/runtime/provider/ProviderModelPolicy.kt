package com.zhiban.rebuild.runtime.provider

/** Prevents a model selected for one provider from leaking into another provider's request. */
object ProviderModelPolicy {
    fun select(profile: ProviderProfile, requestedModel: String?): ProviderProfile {
        val allowed = TrustedProviderRegistry().preset(profile.providerId).models
        return requestedModel?.trim()?.takeIf { it in allowed }?.let { profile.copy(modelId = it) } ?: profile
    }

    /**
     * Routes by input capability instead of asking non-technical users to
     * understand model names. A valid explicit selection is honored for text;
     * any image forces a model whose trusted contract includes image input.
     */
    fun selectForInput(profile: ProviderProfile, requestedModel: String?, hasImage: Boolean): ProviderProfile {
        if (profile.providerId != "stepfun") return select(profile, requestedModel)
        return profile.copy(
            modelId = if (hasImage) {
                TrustedProviderRegistry.STEPFUN_VISION_MODEL
            } else {
                requestedModel?.trim()
                    ?.takeIf { it == TrustedProviderRegistry.STEPFUN_TEXT_MODEL }
                    ?: TrustedProviderRegistry.STEPFUN_TEXT_MODEL
            },
        )
    }
}
