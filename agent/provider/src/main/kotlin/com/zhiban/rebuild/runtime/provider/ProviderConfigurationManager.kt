package com.zhiban.rebuild.runtime.provider
import com.zhiban.rebuild.runtime.runSuspendCatching

interface CredentialProvisioner {
    suspend fun provision(credentialRef: String, keyVersion: Int, credential: ByteArray)
    suspend fun delete(credentialRef: String, keyVersion: Int)
    suspend fun contains(credentialRef: String, keyVersion: Int): Boolean
}

interface ProviderProfileStore {
    suspend fun load(): ProviderProfile?
    suspend fun save(profile: ProviderProfile)
    suspend fun clear()
}

class ProviderConfigurationManager(private val vault: CredentialProvisioner, private val profiles: ProviderProfileStore) {
    suspend fun provisionStepFun(credential: ByteArray, requestedModel: String): ProviderProfile {
        val candidate = provisionCandidate(DEFAULT_PROVIDER, credential, requestedModel)
        publish(candidate)
        return candidate
    }

    suspend fun provisionCandidate(providerId: String, credential: ByteArray, requestedModel: String): ProviderProfile {
        require(credential.isNotEmpty()) { "API_KEY_REQUIRED" }
        require(providerId == DEFAULT_PROVIDER) { "UNSUPPORTED_PROVIDER" }
        val preset = TrustedProviderRegistry().preset(providerId)
        val previous = profiles.load()
        val credentialRef = "$providerId.primary"
        val keyVersion = if (previous?.credentialRef == credentialRef) previous.keyVersion + 1 else 1
        val profile = ProviderProfile(
            providerId = preset.providerId,
            endpointId = preset.endpointId,
            modelId = requestedModel.takeIf { it in preset.models } ?: preset.defaultModel,
            credentialRef = credentialRef,
            keyVersion = keyVersion,
        )
        TrustedProviderRegistry().resolve(profile)
        vault.provision(profile.credentialRef, profile.keyVersion, credential)
        return profile
    }

    suspend fun publish(profile: ProviderProfile) {
        TrustedProviderRegistry().resolve(profile)
        check(vault.contains(profile.credentialRef, profile.keyVersion))
        val previous = profiles.load()
        profiles.save(profile)
        if (previous != null &&
            (previous.credentialRef != profile.credentialRef || previous.keyVersion != profile.keyVersion)
        ) {
            vault.delete(previous.credentialRef, previous.keyVersion)
        }
    }

    suspend fun discard(profile: ProviderProfile) {
        if (profiles.load() != profile) vault.delete(profile.credentialRef, profile.keyVersion)
    }

    suspend fun activeProfile(): ProviderProfile? {
        val stored = profiles.load() ?: return null
        val migrated = if (
            stored.providerId == DEFAULT_PROVIDER &&
            stored.endpointId == TrustedProviderRegistry().preset(DEFAULT_PROVIDER).endpointId &&
            stored.modelId == LEGACY_STEPFUN_MODEL
        ) {
            stored.copy(modelId = DEFAULT_MODEL).also { profiles.save(it) }
        } else {
            stored
        }
        return migrated.takeIf {
            runSuspendCatching { TrustedProviderRegistry().resolve(it) }.isSuccess &&
                vault.contains(it.credentialRef, it.keyVersion)
        }
    }

    suspend fun isConfigured(): Boolean = activeProfile() != null

    suspend fun selectModel(requestedModel: String): ProviderProfile {
        val current = requireNotNull(activeProfile()) { "CREDENTIAL_NOT_CONFIGURED" }
        val preset = TrustedProviderRegistry().preset(current.providerId)
        val updated = current.copy(modelId = requestedModel.takeIf { it in preset.models } ?: preset.defaultModel)
        TrustedProviderRegistry().resolve(updated)
        profiles.save(updated)
        return updated
    }

    internal suspend fun restoreTrustedProfile(profile: ProviderProfile) {
        TrustedProviderRegistry().resolve(profile)
        check(vault.contains(profile.credentialRef, profile.keyVersion)) { "CREDENTIAL_NOT_CONFIGURED" }
        profiles.save(profile)
    }

    suspend fun clear() {
        val previous = profiles.load()
        profiles.clear()
        previous?.let { vault.delete(it.credentialRef, it.keyVersion) }
    }

    companion object {
        const val DEFAULT_PROVIDER = "stepfun"
        const val DEFAULT_MODEL = TrustedProviderRegistry.STEPFUN_TEXT_MODEL
        private const val LEGACY_STEPFUN_MODEL = "step-3.7-flash"
    }
}
