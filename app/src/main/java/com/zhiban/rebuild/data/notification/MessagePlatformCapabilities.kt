package com.zhiban.rebuild.data.notification

/**
 * Single capability matrix for message-derived agent features. Collection and attribution remain
 * available for every supported platform; higher-level automation is enabled only where the
 * notification structure and handoff behavior have been verified.
 */
data class MessagePlatformCapability(
    val replySuggestions: Boolean,
    val profileExtraction: Boolean,
    val proactiveWakeup: Boolean,
    val relationshipInference: Boolean,
    val completionReplyTracking: Boolean,
)

object MessagePlatformCapabilities {
    private val enabled = mapOf(
        "WECHAT" to capable(completionReplyTracking = true),
        "QQ" to capable(),
        "WEWORK" to capable(),
    )
    private val unsupported = MessagePlatformCapability(
        replySuggestions = false,
        profileExtraction = false,
        proactiveWakeup = false,
        relationshipInference = false,
        completionReplyTracking = false,
    )

    fun forPlatform(platform: String): MessagePlatformCapability = enabled[platform] ?: unsupported

    val replySuggestionPlatforms: Set<String> = enabled.filterValues { it.replySuggestions }.keys
    val profileExtractionPlatforms: Set<String> = enabled.filterValues { it.profileExtraction }.keys

    private fun capable(completionReplyTracking: Boolean = false) = MessagePlatformCapability(
        replySuggestions = true,
        profileExtraction = true,
        proactiveWakeup = true,
        relationshipInference = true,
        completionReplyTracking = completionReplyTracking,
    )
}
