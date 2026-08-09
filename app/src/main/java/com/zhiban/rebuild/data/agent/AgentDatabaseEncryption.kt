package com.zhiban.rebuild.data.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Keystore-wrapped, app-generated SQLCipher key. Plain key bytes are never persisted. */
internal class AgentDatabaseKeyManager(context: Context) {
    private val prefs = context.getSharedPreferences("agent_database_key", Context.MODE_PRIVATE)

    fun <T> withPassphrase(block: (ByteArray) -> T): T {
        val passphrase = loadOrCreate()
        return try {
            block(passphrase)
        } finally {
            passphrase.fill(0)
        }
    }

    private fun loadOrCreate(): ByteArray {
        val stored = prefs.getString(KEY, null)
        if (stored != null) return decrypt(Base64.decode(stored, Base64.NO_WRAP))
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val packed = encrypt(passphrase)
        check(prefs.edit().putString(KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
        packed.fill(0)
        return passphrase
    }

    private fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(packed: ByteArray): ByteArray {
        check(packed.size > IV_BYTES) { "DATABASE_KEY_CORRUPT" }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
            cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
        } finally {
            packed.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey = runCatching { key() }.getOrElse {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    private fun key(): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(ALIAS, null) as? SecretKey ?: error("DATABASE_KEY_NOT_FOUND")
    }

    private companion object {
        const val ALIAS = "zhiban.agent.database.v1"
        const val KEY = "wrapped.v1"
        const val IV_BYTES = 12
    }
}

/** One-time fail-closed plaintext-to-SQLCipher conversion performed before Room opens. */
internal object AgentDatabaseEncryption {
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun initializeLibrary() {
        System.loadLibrary("sqlcipher")
        // No database paths or native error details should enter production Logcat.
        Logger.setTarget(NoopTarget())
    }

    fun migratePlaintextIfNeeded(context: Context, databaseName: String, passphrase: ByteArray) {
        val source = context.getDatabasePath(databaseName)
        val encrypted = File(source.parentFile, "$databaseName.encrypted-migration")
        val backup = File(source.parentFile, "$databaseName.plaintext-backup")
        recoverInterruptedMigration(source, encrypted, backup)
        if (!source.isFile || !hasPlaintextHeader(source)) return
        checkpointPlaintext(source)
        exportEncrypted(source, encrypted, passphrase)
        validateEncrypted(encrypted, passphrase)
        check(source.renameTo(backup)) { "DATABASE_PLAINTEXT_BACKUP_FAILED" }
        try {
            check(encrypted.renameTo(source)) { "DATABASE_ENCRYPTED_REPLACE_FAILED" }
            sidecars(source).forEach(File::delete)
            securelyErase(backup)
        } catch (failure: Throwable) {
            if (!source.exists()) backup.renameTo(source)
            throw failure
        } finally {
            encrypted.delete()
        }
    }

    fun hasPlaintextHeader(file: File): Boolean = file.inputStream().use { input ->
        val actual = ByteArray(SQLITE_HEADER.size)
        input.read(actual) == actual.size && actual.contentEquals(SQLITE_HEADER)
    }

    private fun checkpointPlaintext(source: File) {
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { it.moveToFirst() }
            db.rawExecSQL("PRAGMA journal_mode=DELETE")
        }
        sidecars(source).forEach(File::delete)
    }

    private fun exportEncrypted(source: File, target: File, passphrase: ByteArray) {
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            db.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", target.absolutePath, passphrase)
            try {
                db.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { check(it.moveToFirst()) }
                db.rawExecSQL("PRAGMA encrypted.user_version = ${db.version}")
            } finally {
                db.rawExecSQL("DETACH DATABASE encrypted")
            }
        }
    }

    private fun validateEncrypted(file: File, passphrase: ByteArray) {
        check(file.isFile && !hasPlaintextHeader(file)) { "DATABASE_ENCRYPTION_NOT_APPLIED" }
        SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null).use { db ->
            db.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "DATABASE_INTEGRITY_FAILED"
                }
            }
            // cipher_integrity_check returns no rows on success and one row per detected error.
            db.rawQuery("PRAGMA cipher_integrity_check", emptyArray()).use { cursor ->
                check(!cursor.moveToFirst()) { "DATABASE_CIPHER_INTEGRITY_FAILED" }
            }
        }
    }

    private fun sidecars(file: File) = listOf(File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))

    private fun recoverInterruptedMigration(source: File, encrypted: File, backup: File) {
        encrypted.delete()
        if (!backup.exists()) return
        if (!source.exists()) {
            check(backup.renameTo(source)) { "DATABASE_MIGRATION_RECOVERY_FAILED" }
        } else if (!hasPlaintextHeader(source)) {
            securelyErase(backup)
        } else {
            securelyErase(backup)
        }
    }

    private fun securelyErase(file: File) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { output ->
            val zeros = ByteArray(64 * 1024)
            var remaining = output.length()
            output.seek(0)
            while (remaining > 0) {
                val count = minOf(remaining, zeros.size.toLong()).toInt()
                output.write(zeros, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        check(file.delete()) { "DATABASE_PLAINTEXT_ERASE_FAILED" }
    }
}

/**
 * Defers the potentially expensive plaintext export until Room first opens the database.
 * Room performs database opens on its query executor, so Application/Hilt construction never
 * copies the full database on the main thread.
 */
internal class MigratingSqlCipherOpenHelperFactory(context: Context, passphrase: ByteArray) : SupportSQLiteOpenHelper.Factory {
    private val appContext = context.applicationContext
    private var factoryPassphrase: ByteArray? = passphrase.copyOf()

    @Synchronized
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val source = checkNotNull(factoryPassphrase) { "DATABASE_FACTORY_ALREADY_CREATED" }
        val migrationPassphrase = source.copyOf()
        val delegatePassphrase = source.copyOf()
        source.fill(0)
        factoryPassphrase = null
        val delegate = SupportOpenHelperFactory(delegatePassphrase, null, true).create(configuration)
        return MigratingSqlCipherOpenHelper(
            appContext,
            configuration.name,
            migrationPassphrase,
            delegate,
        )
    }
}

private class MigratingSqlCipherOpenHelper(
    private val context: Context,
    private val targetDatabaseName: String?,
    private val migrationPassphrase: ByteArray,
    private val delegate: SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper {
    @Volatile private var migrated = false

    override val databaseName: String?
        get() = delegate.databaseName

    override val writableDatabase: SupportSQLiteDatabase
        get() {
            migrateOnce()
            return delegate.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        get() {
            migrateOnce()
            return delegate.readableDatabase
        }

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = delegate.setWriteAheadLoggingEnabled(enabled)

    override fun close() {
        migrationPassphrase.fill(0)
        delegate.close()
    }

    private fun migrateOnce() {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            targetDatabaseName?.let {
                AgentDatabaseEncryption.migratePlaintextIfNeeded(context, it, migrationPassphrase)
            }
            migrated = true
            migrationPassphrase.fill(0)
        }
    }
}
