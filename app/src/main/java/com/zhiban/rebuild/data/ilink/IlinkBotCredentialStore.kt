package com.zhiban.rebuild.data.ilink

import android.content.Context
import com.zhiban.rebuild.data.ilink.network.IlinkConfirmedSession
import com.zhiban.rebuild.runtime.provider.CredentialProvisioner
import com.zhiban.rebuild.runtime.provider.CredentialResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the WeChat iLink binding: the bearer `botToken` in `KeystoreCredentialVault`, and the
 * non-sensitive [IlinkBotBinding] metadata in SharedPreferences.
 *
 * The token is only ever exposed inside [withSession] as a `ByteArray` that the vault zeroes after
 * the closure returns (R21). Metadata reads never touch the vault, so the settings UI can render
 * bind status without decrypting anything.
 */
@Singleton
class IlinkBotCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
    private val provisioner: CredentialProvisioner,
    private val resolver: CredentialResolver,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist a freshly confirmed bind: token into the vault, metadata into prefs. */
    suspend fun saveBinding(session: IlinkConfirmedSession, nowEpochMs: Long) {
        val token = session.botToken.toByteArray(Charsets.UTF_8)
        try {
            provisioner.provision(CREDENTIAL_REF, KEY_VERSION, token)
        } finally {
            token.fill(0)
        }
        check(
            prefs.edit()
                .putBoolean(KEY_BOUND, true)
                .putString(KEY_BOT_ID, session.ilinkBotId)
                .putString(KEY_USER_ID, session.ilinkUserId)
                .putString(KEY_BASE_URL, session.baseUrl)
                .putLong(KEY_BOUND_AT, nowEpochMs)
                .putLong(KEY_LAST_VALIDATED, nowEpochMs)
                .putBoolean(KEY_SESSION_EXPIRED, false)
                .commit(),
        ) { "ILINK_BINDING_SAVE_FAILED" }
    }

    /** Non-sensitive binding metadata, or null when never bound. Never decrypts the token. */
    fun bindingInfo(): IlinkBotBinding? {
        if (!prefs.getBoolean(KEY_BOUND, false)) return null
        return IlinkBotBinding(
            ilinkBotId = prefs.getString(KEY_BOT_ID, null).orEmpty(),
            ilinkUserId = prefs.getString(KEY_USER_ID, null).orEmpty(),
            baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            boundAtEpochMs = prefs.getLong(KEY_BOUND_AT, 0L),
            lastValidatedAtEpochMs = prefs.getLong(KEY_LAST_VALIDATED, 0L),
            sessionExpired = prefs.getBoolean(KEY_SESSION_EXPIRED, false),
        )
    }

    /** True only when both the metadata and the vault token are present and the session is live. */
    suspend fun hasUsableBinding(): Boolean = bindingInfo()?.takeIf { !it.sessionExpired } != null && provisioner.contains(CREDENTIAL_REF, KEY_VERSION)

    /**
     * Run [block] with the decrypted token and the current binding. Throws
     * `ILINK_NOT_BOUND` when there is nothing usable, so callers never see a half-configured state.
     */
    suspend fun <T> withSession(block: suspend (token: ByteArray, binding: IlinkBotBinding) -> T): T {
        val binding = bindingInfo()?.takeIf { !it.sessionExpired } ?: throw IllegalStateException("ILINK_NOT_BOUND")
        return resolver.withCredential(CREDENTIAL_REF, KEY_VERSION) { token -> block(token, binding) }
    }

    /** Mark the binding expired after a `ret: -14`; the next send/fetch will require re-binding. */
    fun markSessionExpired() {
        check(prefs.edit().putBoolean(KEY_SESSION_EXPIRED, true).commit()) { "ILINK_BINDING_SAVE_FAILED" }
    }

    /** Record a successful authenticated call, refreshing the last-validated timestamp. */
    fun markValidated(nowEpochMs: Long) {
        check(
            prefs.edit().putLong(KEY_LAST_VALIDATED, nowEpochMs).putBoolean(KEY_SESSION_EXPIRED, false).commit(),
        ) { "ILINK_BINDING_SAVE_FAILED" }
    }

    /** Unbind: delete the vault token and wipe the metadata. */
    suspend fun clear() {
        provisioner.delete(CREDENTIAL_REF, KEY_VERSION)
        check(prefs.edit().clear().commit()) { "ILINK_BINDING_SAVE_FAILED" }
    }

    private companion object {
        const val PREFS = "ilink_bot_binding"
        const val CREDENTIAL_REF = "wechat.ilink.bot.bearer"
        const val KEY_VERSION = 1
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"

        const val KEY_BOUND = "bound"
        const val KEY_BOT_ID = "ilink_bot_id"
        const val KEY_USER_ID = "ilink_user_id"
        const val KEY_BASE_URL = "base_url"
        const val KEY_BOUND_AT = "bound_at"
        const val KEY_LAST_VALIDATED = "last_validated_at"
        const val KEY_SESSION_EXPIRED = "session_expired"
    }
}
