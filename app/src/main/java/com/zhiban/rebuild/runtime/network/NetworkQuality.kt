package com.zhiban.rebuild.runtime.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class NetworkQuality { NORMAL, WEAK, EXTREME, OFFLINE }

fun interface NetworkQualityGateway {
    fun current(): NetworkQuality
}

@Singleton
class AndroidNetworkQualityGateway @Inject constructor(@ApplicationContext context: Context) : NetworkQualityGateway {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun current(): NetworkQuality {
        val network = connectivity.activeNetwork ?: return NetworkQuality.OFFLINE
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkQuality.OFFLINE
        return classifyNetworkQuality(
            hasInternetCapability = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            downKbps = capabilities.linkDownstreamBandwidthKbps,
        )
    }
}

/**
 * Validation probes can be blocked while a configured Provider remains reachable. Such a
 * network is weak/partial, not proven offline; the bounded Provider request is the authority.
 */
internal fun classifyNetworkQuality(hasInternetCapability: Boolean, isValidated: Boolean, downKbps: Int): NetworkQuality = when {
    !hasInternetCapability -> NetworkQuality.OFFLINE
    !isValidated -> NetworkQuality.WEAK
    downKbps in 1..127 -> NetworkQuality.EXTREME
    downKbps in 128..999 -> NetworkQuality.WEAK
    else -> NetworkQuality.NORMAL
}
