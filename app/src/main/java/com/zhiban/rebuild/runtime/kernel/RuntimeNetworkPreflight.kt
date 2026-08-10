package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.network.NetworkQuality

internal fun networkPreflightFailure(current: NetworkQuality, hasAttachments: Boolean): Pair<String, Boolean>? = when {
    current == NetworkQuality.OFFLINE -> "NETWORK_OFFLINE" to true
    current == NetworkQuality.EXTREME -> "NETWORK_TOO_SLOW" to true
    current == NetworkQuality.WEAK && hasAttachments -> "MULTIMODAL_PAUSED_ON_WEAK_NETWORK" to true
    else -> null
}
