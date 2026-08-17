package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.ilink.IlinkBotCredentialStore

/**
 * Decides which canonical tools a Work model request exposes. The only dynamic rule today is the
 * direct-WeChat tool (`communication.wechat.send`): it is hidden unless the iLink channel is bound with
 * a live session. A non-`INVALID_TOOL_ARGUMENTS` tool failure is run-fatal (not model-correctable), so
 * offering the tool to an unbound or expired user would turn every "发微信" request into a failed run
 * instead of the compose-only status quo. Hiding it keeps the Agent on `communication.message.compose`
 * until the channel can plausibly succeed.
 */
internal object WorkToolVisibility {
    const val WECHAT_SEND_TOOL = "communication.wechat.send"

    /**
     * The tool allowlist to pass to `providerToolsJson`, or null for "all enabled tools".
     * [forcedCanonicalTool]/[allowedTools] are the existing restrictions (forced tool / signed-skill
     * allowlist); this only subtracts the WeChat tool when the channel is unusable.
     */
    suspend fun resolveRestriction(
        forcedCanonicalTool: String?,
        allowedTools: Set<String>?,
        credentialStore: IlinkBotCredentialStore?,
        allCanonicalNames: Set<String>,
    ): Set<String>? {
        val base = forcedCanonicalTool?.let(::setOf) ?: allowedTools
        if (credentialStore?.hasUsableBinding() == true) return base
        return (base ?: allCanonicalNames) - WECHAT_SEND_TOOL
    }
}
