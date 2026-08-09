package com.zhiban.rebuild.runtime.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zhiban.rebuild.runtime.runSuspendCatching
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
            // The Android Keystore entry can be lost while the encrypted blob survives in prefs (observed
            // on some devices after an over-install). The credential is then unrecoverable, so drop the
            // orphaned blob and surface a clean re-configure signal instead of a persistent corrupt state.
            prefs.edit().remove(storageKey(credentialRef, keyVersion)).commit()
            error("CREDENTIAL_NOT_FOUND")
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
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM,
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    private fun key(alias: String): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(alias, null) as? SecretKey ?: error("CREDENTIAL_KEY_NOT_FOUND")
    }

    private fun alias(ref: String, version: Int) = "zhiban.provider.${digest(ref)}.v$version"
    private fun storageKey(ref: String, version: Int) = "${digest(ref)}:$version"
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
