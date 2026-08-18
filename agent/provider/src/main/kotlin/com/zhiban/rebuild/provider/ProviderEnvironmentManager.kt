package com.zhiban.rebuild.provider

import com.zhiban.rebuild.foundation.runSuspendCatching
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

data class ProviderHealth(val available: Boolean, val checkedAtEpochMs: Long, val capability: CapabilitySnapshot?, val safeFailureCode: String?)

/** Agent-owned facade for credential configuration, profile selection and provider health. */
class ProviderEnvironmentManager(
    private val configuration: ProviderConfigurationManager,
    private val adapter: ProviderAdapter,
    private val healthCache: ProviderHealthCache = NoopProviderHealthCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val registry = TrustedProviderRegistry()

    suspend fun configureStepFun(credential: ByteArray, requestedModel: String): ProviderHealth =
        configure(ProviderConfigurationManager.DEFAULT_PROVIDER, credential, requestedModel)

    suspend fun configure(providerId: String, credential: ByteArray, requestedModel: String): ProviderHealth {
        val previous = configuration.activeProfile()
        val profile = configuration.provisionCandidate(providerId, credential, requestedModel)
        return try {
            requireHealthy(profile).also {
                configuration.publish(profile)
                replaceCachedHealth(previous, profile, it)
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
            requireHealthy(profile).also { replaceCachedHealth(previous, profile, it) }
        } catch (failure: Throwable) {
            previous?.let { configuration.restoreTrustedProfile(it) }
            throw failure
        }
    }

    suspend fun healthCheck(forceRefresh: Boolean = false): ProviderHealth {
        val profile = configuration.activeProfile()
            ?: return ProviderHealth(false, clock(), null, "CREDENTIAL_MISSING")
        val now = clock()
        val digest = registry.digest(profile)
        if (!forceRefresh) {
            healthCache.load(digest, now)?.takeIf(ProviderHealth::available)?.let { return it }
        }
        return runSuspendCatching { requireHealthy(profile) }.getOrElse { failure ->
            ProviderHealth(false, clock(), null, safeFailureCode(failure))
        }.also { health ->
            if (health.available) healthCache.save(digest, health)
        }
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
        capability.requireFresh(checkedAt, registry.digest(profile))
        return ProviderHealth(true, checkedAt, capability, null)
    }

    private fun replaceCachedHealth(previous: ProviderProfile?, current: ProviderProfile, health: ProviderHealth) {
        val currentDigest = registry.digest(current)
        if (previous != null && registry.digest(previous) != currentDigest) healthCache.clear()
        healthCache.save(currentDigest, health)
    }

    private fun safeFailureCode(failure: Throwable): String = safeConfigurationFailureCode(failure)

    companion object {
        fun safeConfigurationFailureCode(failure: Throwable): String = when (failure) {
            is ProviderFailure -> failure.code
            is UnknownHostException -> "NETWORK_OFFLINE"
            is SocketTimeoutException -> "TIMEOUT"
            is SSLPeerUnverifiedException -> "TLS_VERIFICATION_FAILED"
            else -> "PROVIDER_UNAVAILABLE"
        }
    }
}
