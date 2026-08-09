package com.zhiban.rebuild.runtime.provider

import com.zhiban.rebuild.runtime.runSuspendCatching

data class ProviderHealth(val available: Boolean, val checkedAtEpochMs: Long, val capability: CapabilitySnapshot?, val safeFailureCode: String?)

/** Agent-owned facade for credential configuration, profile selection and provider health. */
class ProviderEnvironmentManager(
    private val configuration: ProviderConfigurationManager,
    private val adapter: ProviderAdapter,
    private val healthCache: ProviderHealthCache = NoopProviderHealthCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun configureStepFun(credential: ByteArray, requestedModel: String): ProviderHealth =
        configure(ProviderConfigurationManager.DEFAULT_PROVIDER, credential, requestedModel)

    suspend fun configure(providerId: String, credential: ByteArray, requestedModel: String): ProviderHealth {
        val profile = configuration.provisionCandidate(providerId, credential, requestedModel)
        return try {
            requireHealthy(profile).also {
                configuration.publish(profile)
                healthCache.save(TrustedProviderRegistry().digest(profile), it)
            }
        } catch (failure: Throwable) {
            configuration.discard(profile)
            throw failure
        }
    }

    suspend fun selectModel(requestedModel: String): ProviderHealth {
        val previous = configuration.activeProfile()
        val profile = configuration.selectModel(requestedModel)
        return try {
            requireHealthy(profile)
        } catch (failure: Throwable) {
            previous?.let { configuration.restoreTrustedProfile(it) }
            throw failure
        }
    }

    suspend fun healthCheck(): ProviderHealth {
        val profile = configuration.activeProfile()
            ?: return ProviderHealth(false, clock(), null, "CREDENTIAL_MISSING")
        val now = clock()
        val digest = TrustedProviderRegistry().digest(profile)
        healthCache.load(digest, now)?.let { return it }
        return runSuspendCatching { requireHealthy(profile) }.getOrElse { failure ->
            ProviderHealth(false, clock(), null, safeFailureCode(failure))
        }.also { healthCache.save(digest, it) }
    }

    suspend fun activeProfile(): ProviderProfile? = configuration.activeProfile()
    suspend fun isConfigured(): Boolean = configuration.isConfigured()
    suspend fun clear() {
        configuration.clear()
        healthCache.clear()
    }

    private suspend fun requireHealthy(profile: ProviderProfile): ProviderHealth {
        val checkedAt = clock()
        val capability = adapter.probe(profile, "health-$checkedAt")
        capability.requireFresh(checkedAt, TrustedProviderRegistry().digest(profile))
        return ProviderHealth(true, checkedAt, capability, null)
    }

    private fun safeFailureCode(failure: Throwable): String = when (failure) {
        is ProviderFailure -> failure.code
        else -> "PROVIDER_UNAVAILABLE"
    }
}
