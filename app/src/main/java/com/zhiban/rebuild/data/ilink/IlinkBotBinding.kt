package com.zhiban.rebuild.data.ilink

/**
 * Non-sensitive binding metadata for the WeChat iLink channel. Safe to persist in plain
 * SharedPreferences and to show in the settings UI. The `botToken` is deliberately NOT here — it
 * lives only in `KeystoreCredentialVault` and is handled as a `ByteArray` inside a `withCredential`
 * closure. Splitting metadata from the secret mirrors `McpRemoteEnvironment` (config in prefs,
 * bearer in the vault).
 */
data class IlinkBotBinding(
    val ilinkBotId: String,
    /** The bot owner's iLink identity (`xxx@im.wechat`); NOT a contact's id. */
    val ilinkUserId: String,
    /** Base URL echoed by the server on confirm; always used as-is for subsequent calls. */
    val baseUrl: String,
    val boundAtEpochMs: Long,
    val lastValidatedAtEpochMs: Long,
    /** Set when the server answered `ret: -14`; the user must re-scan to send again. */
    val sessionExpired: Boolean = false,
)
