package com.zhiban.rebuild.runtime.personalization

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class UserProfile(
    val name: String = "",
    val preferredName: String = "",
    val phone: String = "",
    val wechatId: String = "",
    val douyinId: String = "",
    val avatarUri: String? = null,
    val feishuId: String? = null,
    val wecomId: String? = null,
    val dingtalkId: String? = null,
    val qqId: String? = null,
    val additionalAccounts: Map<String, List<String>>? = null,
    val occupations: Set<String> = emptySet(),
    val customInstructions: String = "",
) {
    /**
     * Agent 消费的 user.md。字段只来自用户主动填写，不把聊天推断混入个人资料。
     */
    fun toUserMarkdown(): String = buildString {
        appendLine("# 用户资料")
        appendLine()
        appendLine("- 姓名：${name.safeMarkdownValue()}")
        appendLine("- 希望知伴如何称呼：${preferredName.safeMarkdownValue()}")
        appendLine("- 手机号：${phone.safeMarkdownValue()}")
        appendLine("- 微信号：${wechatId.safeMarkdownValue()}")
        appendLine("- 抖音号：${douyinId.safeMarkdownValue()}")
        appendLine("- 职业：${occupations.joinToString("、").safeMarkdownValue()}")
        append("- 给知伴的指令：${customInstructions.safeMarkdownValue()}")
    }
}

private fun String?.safeMarkdownValue(): String = orEmpty().trim().replace("\r", " ").replace("\n", " ").take(100).ifBlank { "未填写" }

/**
 * 用户身份的唯一来源。
 *
 * 手机号和社交账号属于个人资料，不属于 Agent 记忆；它们加密保存在本机，
 * Agent 通过结构化 user.md 读取这些用户主动填写的资料。
 */
@Singleton
class UserProfileStore internal constructor(private val context: Context, preferencesName: String, private val avatarDirectoryName: String) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, PREFERENCES_NAME, AVATAR_DIR)

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val preferences = EncryptedSharedPreferences.create(
        context,
        preferencesName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val mutableProfile = MutableStateFlow(read())
    val profile = mutableProfile.asStateFlow()

    private fun read() = UserProfile(
        name = preferences.getString(KEY_NAME, "").orEmpty(),
        preferredName = preferences.getString(KEY_PREFERRED_NAME, "").orEmpty(),
        phone = preferences.getString(KEY_PHONE, "").orEmpty(),
        wechatId = preferences.getString(KEY_WECHAT_ID, "").orEmpty(),
        douyinId = preferences.getString(KEY_DOUYIN_ID, "").orEmpty(),
        avatarUri = preferences.getString(KEY_AVATAR_URI, null)?.takeIf(String::isNotBlank),
        feishuId = preferences.getString(KEY_FEISHU_ID, null)?.takeIf(String::isNotBlank),
        wecomId = preferences.getString(KEY_WECOM_ID, null)?.takeIf(String::isNotBlank),
        dingtalkId = preferences.getString(KEY_DINGTALK_ID, null)?.takeIf(String::isNotBlank),
        qqId = preferences.getString(KEY_QQ_ID, null)?.takeIf(String::isNotBlank),
        additionalAccounts = decodeAccounts(preferences.getString(KEY_ADDITIONAL_ACCOUNTS, null)),
        occupations = decodeOccupations(preferences.getString(KEY_OCCUPATIONS, null)),
        customInstructions = preferences.getString(KEY_CUSTOM_INSTRUCTIONS, "").orEmpty(),
    )

    fun hasIdentity(): Boolean = read().let { profile ->
        profile.name.isNotBlank() || profile.phone.isNotBlank() || profile.wechatId.isNotBlank() || profile.douyinId.isNotBlank()
    }

    fun mergeMissingIdentity(name: String?, phone: String?, wechatId: String?) {
        val current = read()
        val merged = current.copy(
            name = current.name.ifBlank { name.orEmpty().trim() },
            phone = current.phone.ifBlank { phone.orEmpty().trim() },
            wechatId = current.wechatId.ifBlank { wechatId.orEmpty().trim().trimStart('@') },
        )
        if (merged != current) {
            save(merged)
        }
    }

    fun save(value: UserProfile) {
        val normalized = value.copy(
            name = value.name.trim(),
            preferredName = value.preferredName.trim(),
            phone = value.phone.trim(),
            wechatId = value.wechatId.trim(),
            douyinId = value.douyinId.trim(),
            feishuId = value.feishuId?.trim()?.takeIf(String::isNotEmpty),
            wecomId = value.wecomId?.trim()?.takeIf(String::isNotEmpty),
            dingtalkId = value.dingtalkId?.trim()?.takeIf(String::isNotEmpty),
            qqId = value.qqId?.trim()?.takeIf(String::isNotEmpty),
            customInstructions = value.customInstructions.trim(),
        )
        check(
            preferences.edit()
                .putString(KEY_NAME, normalized.name)
                .putString(KEY_PREFERRED_NAME, normalized.preferredName)
                .putString(KEY_PHONE, normalized.phone)
                .putString(KEY_WECHAT_ID, normalized.wechatId)
                .putString(KEY_DOUYIN_ID, normalized.douyinId)
                .putString(KEY_AVATAR_URI, normalized.avatarUri.orEmpty())
                .putString(KEY_FEISHU_ID, normalized.feishuId.orEmpty())
                .putString(KEY_WECOM_ID, normalized.wecomId.orEmpty())
                .putString(KEY_DINGTALK_ID, normalized.dingtalkId.orEmpty())
                .putString(KEY_QQ_ID, normalized.qqId.orEmpty())
                .putString(KEY_ADDITIONAL_ACCOUNTS, encodeAccounts(normalized.additionalAccounts))
                .putString(KEY_OCCUPATIONS, encodeOccupations(normalized.occupations))
                .putString(KEY_CUSTOM_INSTRUCTIONS, normalized.customInstructions)
                .commit(),
        )
        mutableProfile.value = normalized
    }

    fun clear() {
        check(preferences.edit().clear().commit())
        mutableProfile.value = UserProfile()
    }

    /**
     * Persists a picked avatar image into app-private storage and records its path on the profile.
     * Returns the absolute file path, or null when the image could not be read.
     */
    suspend fun persistAvatar(source: android.net.Uri): String? = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return@withContext null
        persistAvatarBytes(bytes)
    }

    internal fun persistAvatarBytes(bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "AVATAR_EMPTY" }
        val previous = safeAvatarFile(read().avatarUri)
        val target = newEncryptedAvatarFile()
        try {
            encryptedFile(target).openFileOutput().use { it.write(bytes) }
            val path = target.absolutePath
            save(read().copy(avatarUri = path))
            previous?.takeIf { it != target }?.delete()
            return path
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    suspend fun readAvatarBytes(path: String?): ByteArray? = withContext(Dispatchers.IO) {
        val source = safeAvatarFile(path) ?: return@withContext null
        if (source.name == LEGACY_AVATAR_FILE) {
            val bytes = source.readBytes()
            persistAvatarBytes(bytes)
            return@withContext bytes
        }
        encryptedFile(source).openFileInput().use { it.readBytes() }
    }

    private fun safeAvatarFile(path: String?): java.io.File? {
        val source = path?.takeIf(String::isNotBlank)?.let { java.io.File(it) }?.canonicalFile ?: return null
        val root = java.io.File(context.filesDir, avatarDirectoryName).canonicalFile
        return source.takeIf { it.parentFile == root && it.isFile }
    }

    private fun newEncryptedAvatarFile(): java.io.File = java.io.File(
        java.io.File(context.filesDir, avatarDirectoryName).apply { mkdirs() },
        "avatar_${java.util.UUID.randomUUID()}.enc",
    )

    private fun encryptedFile(file: java.io.File) = EncryptedFile.Builder(
        context,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_PREFERRED_NAME = "preferred_name"
        const val KEY_PHONE = "phone"
        const val KEY_WECHAT_ID = "wechat_id"
        const val KEY_DOUYIN_ID = "douyin_id"
        const val KEY_AVATAR_URI = "avatar_uri"
        const val KEY_FEISHU_ID = "feishu_id"
        const val KEY_WECOM_ID = "wecom_id"
        const val KEY_DINGTALK_ID = "dingtalk_id"
        const val KEY_QQ_ID = "qq_id"
        const val KEY_ADDITIONAL_ACCOUNTS = "additional_accounts"
        const val KEY_OCCUPATIONS = "occupations"
        const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
        const val PREFERENCES_NAME = "user_profile_secure"
        const val AVATAR_DIR = "avatar"
        const val LEGACY_AVATAR_FILE = "avatar.png"

        val json = Json { ignoreUnknownKeys = true }
        val accountsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
        val occupationsSerializer = ListSerializer(String.serializer())

        fun encodeAccounts(accounts: Map<String, List<String>>?): String =
            accounts?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(accountsSerializer, it) }.orEmpty()

        fun decodeAccounts(raw: String?): Map<String, List<String>>? = raw?.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString(accountsSerializer, it) }.getOrNull()
        }

        fun encodeOccupations(occupations: Set<String>): String =
            occupations.takeIf { it.isNotEmpty() }?.let { json.encodeToString(occupationsSerializer, it.toList()) }.orEmpty()

        fun decodeOccupations(raw: String?): Set<String> = raw?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString(occupationsSerializer, it) }.getOrNull() }
            ?.toSet()
            .orEmpty()
    }
}
