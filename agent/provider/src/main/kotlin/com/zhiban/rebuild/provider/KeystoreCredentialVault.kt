package com.zhiban.rebuild.provider

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.zhiban.rebuild.foundation.runSuspendCatching
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KeystoreCredentialVault(context: Context) :
    CredentialResolver,
    CredentialProvisioner {
    private val prefs = context.getSharedPreferences("runtime_provider_credentials", Context.MODE_PRIVATE)

    override suspend fun provision(credentialRef: String, keyVersion: Int, credential: ByteArray) = withContext(Dispatchers.IO) {
        requireBinding(credentialRef, keyVersion)
        val bytes = credential.copyOf()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias(credentialRef, keyVersion)))
            val encrypted = cipher.doFinal(bytes)
            val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            check(prefs.edit().putString(storageKey(credentialRef, keyVersion), encoded).commit())
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun <T> withCredential(credentialRef: String, keyVersion: Int, block: suspend (ByteArray) -> T): T = withContext(Dispatchers.IO) {
        requireBinding(credentialRef, keyVersion)
        val encoded = prefs.getString(storageKey(credentialRef, keyVersion), null) ?: error("CREDENTIAL_NOT_FOUND")
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        check(packed.size > 12) { "CREDENTIAL_CORRUPT" }
        val key = runSuspendCatching { key(alias(credentialRef, keyVersion)) }.getOrElse {
            handleCredentialKeyReadFailure(it) {
                prefs.edit().remove(storageKey(credentialRef, keyVersion)).commit()
            }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, packed.copyOfRange(0, 12)),
        )
        val plain = cipher.doFinal(packed.copyOfRange(12, packed.size))
        try {
            block(plain)
        } finally {
            plain.fill(0)
            packed.fill(0)
        }
    }

    @android.annotation.SuppressLint("ApplySharedPref")
    override suspend fun delete(credentialRef: String, keyVersion: Int) = withContext(Dispatchers.IO) {
        requireBinding(credentialRef, keyVersion)
        prefs.edit().remove(storageKey(credentialRef, keyVersion)).commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias(credentialRef, keyVersion))
        }
        Unit
    }

    override suspend fun contains(credentialRef: String, keyVersion: Int): Boolean = withContext(Dispatchers.IO) {
        requireBinding(credentialRef, keyVersion)
        prefs.contains(storageKey(credentialRef, keyVersion)) &&
            runSuspendCatching { key(alias(credentialRef, keyVersion)) }.isSuccess
    }

    private fun requireBinding(ref: String, version: Int) {
        require(ref.matches(Regex("[A-Za-z0-9._-]{8,128}")) && version > 0) { "INVALID_CREDENTIAL_BINDING" }
    }

    private fun getOrCreateKey(alias: String): SecretKey = runCatching { key(alias) }.getOrElse {
        try {
            generateKey(alias, strongBox = true)
        } catch (failure: Exception) {
            // 无 StrongBox 芯片的设备回退普通 AndroidKeyStore 密钥(加固建议项,降级只发生在
            // 硬件不支持时,凭据安全级别不低于改动前)。API<28 无此异常类型,守卫后再判型。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && failure is StrongBoxUnavailableException) {
                generateKey(alias, strongBox = false)
            } else {
                throw failure
            }
        }
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore",
    ).apply {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(
                KeyProperties.BLOCK_MODE_GCM,
            ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(strongBox)
        }
        init(builder.build())
    }.generateKey()

    private fun key(alias: String): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(alias, null) as? SecretKey ?: throw CredentialKeyNotFoundException()
    }

    private fun alias(ref: String, version: Int) = "zhiban.provider.${digest(ref)}.v$version"
    private fun storageKey(ref: String, version: Int) = "${digest(ref)}:$version"
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class CredentialKeyNotFoundException : IllegalStateException("CREDENTIAL_KEY_NOT_FOUND")

internal fun handleCredentialKeyReadFailure(failure: Throwable, removeOrphan: () -> Unit): Nothing {
    if (failure is CredentialKeyNotFoundException) {
        removeOrphan()
        error("CREDENTIAL_NOT_FOUND")
    }
    throw IllegalStateException("CREDENTIAL_TEMPORARILY_UNAVAILABLE", failure)
}
