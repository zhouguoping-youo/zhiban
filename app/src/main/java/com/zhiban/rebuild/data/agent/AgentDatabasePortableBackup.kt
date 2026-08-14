package com.zhiban.rebuild.data.agent

import java.io.File
import java.io.RandomAccessFile
import net.zetetic.database.sqlcipher.SQLiteDatabase

internal data class PortableBackupSummary(val schemaVersion: Int, val contactCount: Long, val relationshipCount: Long, val scheduleCount: Long)

/**
 * Creates and restores portable SQLCipher backups without ever materializing a plaintext database.
 *
 * The normal database key is device-bound by Android Keystore. A portable backup is instead
 * encrypted with the password supplied by the user, then re-encrypted with the destination
 * device's database key before it is installed on the next cold start.
 */
internal object AgentDatabasePortableBackup {
    private const val PENDING_SUFFIX = ".restore-pending"
    private const val PREVIOUS_SUFFIX = ".pre-restore"

    fun create(source: File, sourcePassphrase: ByteArray, target: File, portablePassphrase: ByteArray): PortableBackupSummary {
        require(source.isFile) { "BACKUP_SOURCE_MISSING" }
        require(portablePassphrase.isNotEmpty()) { "BACKUP_PASSWORD_REQUIRED" }
        securelyErase(target)
        export(source, sourcePassphrase, target, portablePassphrase)
        return try {
            validate(target, portablePassphrase)
        } catch (failure: Throwable) {
            securelyErase(target)
            throw failure
        }
    }

    fun stageRestore(contextDatabaseFile: File, portable: File, portablePassphrase: ByteArray, destinationPassphrase: ByteArray): PortableBackupSummary {
        require(portable.isFile) { "BACKUP_FILE_MISSING" }
        val summary = validate(portable, portablePassphrase)
        require(summary.schemaVersion == AGENT_DATABASE_SCHEMA_VERSION) { "BACKUP_SCHEMA_UNSUPPORTED" }
        val pending = pendingFile(contextDatabaseFile)
        securelyErase(pending)
        export(portable, portablePassphrase, pending, destinationPassphrase)
        try {
            validate(pending, destinationPassphrase)
        } catch (failure: Throwable) {
            securelyErase(pending)
            throw failure
        }
        return summary
    }

    fun cancelPendingRestore(databaseFile: File) = securelyErase(pendingFile(databaseFile))

    fun hasPendingRestore(databaseFile: File): Boolean = pendingFile(databaseFile).isFile

    /** Called before Room opens, when no connection can still hold a WAL or stale file handle. */
    fun installPendingRestoreIfPresent(databaseFile: File, destinationPassphrase: ByteArray) {
        recoverInterruptedRestore(databaseFile, destinationPassphrase)
        val pending = pendingFile(databaseFile)
        if (!pending.isFile) return
        validate(pending, destinationPassphrase)
        val previous = File(databaseFile.parentFile, databaseFile.name + PREVIOUS_SUFFIX)
        securelyErase(previous)
        sidecars(databaseFile).forEach(::securelyErase)
        if (databaseFile.exists()) {
            check(databaseFile.renameTo(previous)) { "BACKUP_CURRENT_DATABASE_PRESERVE_FAILED" }
        }
        try {
            check(pending.renameTo(databaseFile)) { "BACKUP_RESTORE_REPLACE_FAILED" }
            validate(databaseFile, destinationPassphrase)
            securelyErase(previous)
        } catch (failure: Throwable) {
            securelyErase(databaseFile)
            if (previous.exists()) {
                check(previous.renameTo(databaseFile)) { "BACKUP_RESTORE_ROLLBACK_FAILED" }
            }
            throw failure
        }
    }

    private fun recoverInterruptedRestore(databaseFile: File, destinationPassphrase: ByteArray) {
        val previous = File(databaseFile.parentFile, databaseFile.name + PREVIOUS_SUFFIX)
        if (!previous.isFile) return
        if (!databaseFile.exists()) {
            check(previous.renameTo(databaseFile)) { "BACKUP_RESTORE_RECOVERY_FAILED" }
            return
        }
        val replacementIsValid = runCatching { validate(databaseFile, destinationPassphrase) }.isSuccess
        if (replacementIsValid) {
            securelyErase(previous)
        } else {
            securelyErase(databaseFile)
            check(previous.renameTo(databaseFile)) { "BACKUP_RESTORE_ROLLBACK_FAILED" }
        }
    }

    private fun export(source: File, sourcePassphrase: ByteArray, target: File, targetPassphrase: ByteArray) {
        SQLiteDatabase.openOrCreateDatabase(source, sourcePassphrase, null, null).use { sourceDatabase ->
            sourceDatabase.rawExecSQL("ATTACH DATABASE ? AS portable KEY ?", target.absolutePath, targetPassphrase)
            try {
                sourceDatabase.rawQuery("SELECT sqlcipher_export('portable')", emptyArray()).use {
                    check(it.moveToFirst()) { "BACKUP_EXPORT_FAILED" }
                }
                sourceDatabase.rawExecSQL("PRAGMA portable.user_version = ${sourceDatabase.version}")
            } finally {
                sourceDatabase.rawExecSQL("DETACH DATABASE portable")
            }
        }
    }

    private fun validate(file: File, passphrase: ByteArray): PortableBackupSummary {
        require(file.isFile && !AgentDatabaseEncryption.hasPlaintextHeader(file)) { "BACKUP_NOT_ENCRYPTED" }
        return SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null).use { database ->
            database.rawQuery("PRAGMA integrity_check", emptyArray()).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                    "BACKUP_INTEGRITY_FAILED"
                }
            }
            database.rawQuery("PRAGMA cipher_integrity_check", emptyArray()).use { cursor ->
                check(!cursor.moveToFirst()) { "BACKUP_CIPHER_INTEGRITY_FAILED" }
            }
            REQUIRED_TABLES.forEach { table ->
                database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    arrayOf(table),
                ).use { cursor -> check(cursor.moveToFirst()) { "BACKUP_SCHEMA_INVALID" } }
            }
            PortableBackupSummary(
                schemaVersion = database.version,
                contactCount = database.countRows("contacts"),
                relationshipCount = database.countRows("relationship_edges"),
                scheduleCount = database.countRows("schedules"),
            )
        }
    }

    private fun SQLiteDatabase.countRows(table: String): Long = rawQuery("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun pendingFile(databaseFile: File) = File(databaseFile.parentFile, databaseFile.name + PENDING_SUFFIX)

    private fun sidecars(file: File) = listOf(File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))

    private fun securelyErase(file: File) {
        if (!file.exists()) return
        if (file.isFile) {
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
        }
        check(file.delete()) { "BACKUP_TEMP_DELETE_FAILED" }
    }

    private val REQUIRED_TABLES = setOf("contacts", "relationship_edges", "schedules", "memories", "crm_opportunities")
}
