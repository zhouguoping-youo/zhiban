package com.zhiban.rebuild.runtime.provider

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidProviderProfileStore(context: Context) : ProviderProfileStore {
    private val prefs = context.getSharedPreferences("runtime_provider_profile", Context.MODE_PRIVATE)

    override suspend fun load(): ProviderProfile? = withContext(Dispatchers.IO) {
        val providerId = prefs.getString("providerId", null) ?: return@withContext null
        val endpointId = prefs.getString("endpointId", null) ?: return@withContext null
        val modelId = prefs.getString("modelId", null) ?: return@withContext null
        val credentialRef = prefs.getString("credentialRef", null) ?: return@withContext null
        val keyVersion = prefs.getInt("keyVersion", 0).takeIf { it > 0 } ?: return@withContext null
        ProviderProfile(providerId, endpointId, modelId, credentialRef, keyVersion)
    }

    override suspend fun save(profile: ProviderProfile) = withContext(Dispatchers.IO) {
        check(
            prefs.edit()
                .putString("providerId", profile.providerId)
                .putString("endpointId", profile.endpointId)
                .putString("modelId", profile.modelId)
                .putString("credentialRef", profile.credentialRef)
                .putInt("keyVersion", profile.keyVersion)
                .commit(),
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().clear().commit())
    }
}
