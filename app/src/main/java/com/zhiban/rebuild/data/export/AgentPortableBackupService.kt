package com.zhiban.rebuild.data.export

import android.content.Context
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AGENT_DATABASE_FILE_NAME
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.AgentDatabaseKeyManager
import com.zhiban.rebuild.data.agent.AgentDatabasePortableBackup
import com.zhiban.rebuild.data.agent.PortableBackupSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PortableRestoreSummary(val contactCount: Long, val relationshipCount: Long, val scheduleCount: Long)

/** Portable, password-encrypted backup for the complete Room/SQLCipher data graph. */
class AgentPortableBackupService @Inject internal constructor(@ApplicationContext private val context: Context, private val database: AgentDatabase) {
    private var databaseFileName: String = AGENT_DATABASE_FILE_NAME

    internal constructor(context: Context, database: AgentDatabase, databaseFileName: String) : this(context, database) {
        this.databaseFileName = databaseFileName
    }

    suspend fun create(password: CharArray, nowEpochMs: Long = System.currentTimeMillis()): File = withContext(Dispatchers.IO) {
        val portableKey = encodePassword(password)
        try {
            checkpointDatabase()
            val directory = File(context.cacheDir, DIRECTORY).apply { check(isDirectory || mkdirs()) }
            purgeExpired(directory, nowEpochMs)
            val target = File(directory, "zhiban-backup-$nowEpochMs.$EXTENSION")
            val partial = File(directory, target.name + PARTIAL_SUFFIX)
            partial.delete()
            var committed = false
            try {
                AgentDatabaseKeyManager(context).withPassphrase { databaseKey ->
                    AgentDatabasePortableBackup.create(
                        context.getDatabasePath(databaseFileName),
                        databaseKey,
                        partial,
                        portableKey,
                    )
                }
                check(!target.exists() || target.delete()) { "BACKUP_REPLACE_FAILED" }
                check(partial.renameTo(target)) { "BACKUP_COMMIT_FAILED" }
                committed = true
                target
            } finally {
                if (!committed) partial.delete()
            }
        } finally {
            portableKey.fill(0)
            password.fill('\u0000')
        }
    }

    suspend fun stageRestore(input: InputStream, password: CharArray): PortableRestoreSummary = withContext(Dispatchers.IO) {
        val portableKey = encodePassword(password)
        val directory = File(context.cacheDir, DIRECTORY).apply { check(isDirectory || mkdirs()) }
        val imported = File(directory, "restore-import-${System.nanoTime()}.$EXTENSION")
        try {
            input.use { source -> imported.outputStream().use { target -> source.copyBoundedTo(target, MAX_BACKUP_BYTES) } }
            AgentDatabaseKeyManager(context).withPassphrase { databaseKey ->
                AgentDatabasePortableBackup.stageRestore(
                    context.getDatabasePath(databaseFileName),
                    imported,
                    portableKey,
                    databaseKey,
                )
            }.toPublicSummary()
        } finally {
            imported.delete()
            portableKey.fill(0)
            password.fill('\u0000')
        }
    }

    suspend fun cancelPendingRestore() = withContext(Dispatchers.IO) {
        AgentDatabasePortableBackup.cancelPendingRestore(context.getDatabasePath(databaseFileName))
    }

    suspend fun hasPendingRestore(): Boolean = withContext(Dispatchers.IO) {
        AgentDatabasePortableBackup.hasPendingRestore(context.getDatabasePath(databaseFileName))
    }

    private suspend fun checkpointDatabase() {
        database.withTransaction { Unit }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            check(cursor.moveToFirst()) { "BACKUP_CHECKPOINT_FAILED" }
        }
    }

    private fun encodePassword(password: CharArray): ByteArray {
        require(password.size >= MIN_PASSWORD_LENGTH) { "BACKUP_PASSWORD_TOO_SHORT" }
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password))
        return ByteArray(encoded.remaining()).also(encoded::get).also {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun purgeExpired(directory: File, nowEpochMs: Long) {
        directory.listFiles()?.filter { nowEpochMs - it.lastModified() > RETENTION_MS }?.forEach(File::delete)
    }

    private fun InputStream.copyBoundedTo(target: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "BACKUP_FILE_TOO_LARGE" }
            target.write(buffer, 0, count)
        }
        target.flush()
    }

    private fun PortableBackupSummary.toPublicSummary() = PortableRestoreSummary(
        contactCount = contactCount,
        relationshipCount = relationshipCount,
        scheduleCount = scheduleCount,
    )

    companion object {
        const val MIME_TYPE = "application/vnd.zhiban.backup"
        const val EXTENSION = "zhibanbackup"
        const val MIN_PASSWORD_LENGTH = 10
        private const val DIRECTORY = "portable-backup"
        private const val PARTIAL_SUFFIX = ".part"
        private const val RETENTION_MS = 24L * 60 * 60 * 1_000
        private const val MAX_BACKUP_BYTES = 2L * 1024 * 1024 * 1024
    }
}
