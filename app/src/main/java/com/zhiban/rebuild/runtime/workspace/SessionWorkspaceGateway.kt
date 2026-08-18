package com.zhiban.rebuild.runtime.workspace

import android.content.Context
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.store.RuntimeArtifactEntity
import com.zhiban.rebuild.data.store.RuntimeSessionEntity
import com.zhiban.rebuild.data.store.RuntimeSessionWorkspaceEntity
import com.zhiban.rebuild.runtime.input.AttachmentRef
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class ArtifactKind { ATTACHMENT, GENERATED_FILE, TOOL_RESULT, EXPORT }

data class SessionArtifact(
    val artifactId: String,
    val sessionId: String,
    val runId: String?,
    val kind: ArtifactKind,
    val displayName: String,
    val mimeType: String,
    val byteLength: Long,
    val sha256Digest: String,
    val createdAtEpochMs: Long,
)

interface SessionWorkspaceGateway {
    suspend fun ensure(sessionId: String)
    suspend fun delete(sessionId: String)
    suspend fun artifacts(sessionId: String, limit: Int = 30): List<SessionArtifact>
    fun observeArtifacts(sessionId: String, limit: Int = 30): Flow<List<SessionArtifact>>
    suspend fun save(
        sessionId: String,
        runId: String?,
        kind: ArtifactKind,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        provenance: String,
    ): SessionArtifact
    suspend fun preserveAttachment(sessionId: String, attachment: AttachmentRef): SessionArtifact
    suspend fun updateSummary(sessionId: String, summary: String, throughEpochMs: Long)
}

/**
 * Owns durable, app-private files belonging to one Agent session. Callers receive metadata,
 * never raw filesystem paths. This keeps provider/tool code outside the storage boundary.
 */
@Singleton
internal class AppPrivateSessionWorkspaceGateway @Inject constructor(@ApplicationContext context: Context, private val database: AgentDatabase) :
    SessionWorkspaceGateway {
    private val appFilesRoot = context.applicationContext.filesDir.canonicalFile
    private val cacheRoot = File(
        context.applicationContext.cacheDir.canonicalFile,
        "zhiban-runtime-input",
    ).canonicalFile
    private val workspaceRoot = File(appFilesRoot, "agent-session-workspaces").canonicalFile
    private val random = SecureRandom()

    init {
        require(workspaceRoot.toPath().startsWith(appFilesRoot.toPath()))
        require(workspaceRoot.mkdirs() || workspaceRoot.isDirectory)
    }

    override suspend fun ensure(sessionId: String) {
        requireValidSessionId(sessionId)
        val now = System.currentTimeMillis()
        val directoryName = sessionDirectoryName(sessionId)
        database.withTransaction {
            database.runtimeSessionDao().insert(RuntimeSessionEntity(sessionId = sessionId, updatedAtEpochMs = now))
            database.runtimeSessionWorkspaceDao().insert(
                RuntimeSessionWorkspaceEntity(
                    sessionId = sessionId,
                    directoryName = directoryName,
                    state = "ACTIVE",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
        }
        withContext(Dispatchers.IO) { ensureSessionDirectory(directoryName) }
    }

    override suspend fun delete(sessionId: String) {
        requireValidSessionId(sessionId)
        val directoryName = database.runtimeSessionWorkspaceDao().find(sessionId)?.directoryName
            ?: sessionDirectoryName(sessionId)
        require(DIRECTORY_PATTERN.matches(directoryName))
        val directory = File(workspaceRoot, directoryName).canonicalFile
        require(directory.toPath().startsWith(workspaceRoot.toPath()))
        withContext(Dispatchers.IO) {
            if (directory.exists()) {
                check(directory.deleteRecursively()) { "session workspace deletion failed" }
            }
        }
    }

    override suspend fun artifacts(sessionId: String, limit: Int): List<SessionArtifact> {
        requireValidSessionId(sessionId)
        return database.runtimeArtifactDao().listReadyBySession(sessionId, limit.coerceIn(1, 100))
            .map { it.toDomain() }
    }

    override fun observeArtifacts(sessionId: String, limit: Int): Flow<List<SessionArtifact>> {
        requireValidSessionId(sessionId)
        return database.runtimeArtifactDao().observeReadyBySession(sessionId, limit.coerceIn(1, 100))
            .map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun save(
        sessionId: String,
        runId: String?,
        kind: ArtifactKind,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        provenance: String,
    ): SessionArtifact {
        requireValidSessionId(sessionId)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ARTIFACT_BYTES)
        require(MIME_PATTERN.matches(mimeType))
        require(provenance in ALLOWED_PROVENANCE)
        val safeName = safeDisplayName(displayName)
        ensure(sessionId)
        val workspace = requireNotNull(database.runtimeSessionWorkspaceDao().find(sessionId))
        require(workspace.totalArtifactBytes + bytes.size <= MAX_SESSION_BYTES) { "session artifact quota exceeded" }

        val artifactId = randomId()
        val extension = safeName.substringAfterLast('.', "").takeIf { it.matches(EXTENSION_PATTERN) }
        val storedName = buildString {
            append(artifactId)
            if (extension != null) append('.').append(extension.lowercase())
        }
        val directory = withContext(Dispatchers.IO) { ensureSessionDirectory(workspace.directoryName) }
        val destination = File(directory, storedName).canonicalFile
        require(destination.toPath().startsWith(directory.toPath()))
        val temporary = File(directory, "$artifactId.part").canonicalFile
        val digest = sha256(bytes)
        withContext(Dispatchers.IO) {
            writeArtifactAtomically(temporary, destination, bytes)
        }

        val now = System.currentTimeMillis()
        val entity = RuntimeArtifactEntity(
            artifactId = artifactId,
            sessionId = sessionId,
            runId = runId,
            kind = kind.name,
            displayName = safeName,
            mimeType = mimeType,
            relativePath = "${workspace.directoryName}/$storedName",
            byteLength = bytes.size.toLong(),
            sha256Digest = digest,
            status = "READY",
            provenance = provenance,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        try {
            database.withTransaction {
                database.runtimeArtifactDao().insert(entity)
                check(
                    database.runtimeSessionWorkspaceDao().adjustArtifactBytes(sessionId, bytes.size.toLong(), now) == 1,
                )
            }
        } catch (failure: Throwable) {
            withContext(Dispatchers.IO) { destination.delete() }
            throw failure
        }
        return entity.toDomain()
    }

    override suspend fun preserveAttachment(sessionId: String, attachment: AttachmentRef): SessionArtifact {
        requireValidSessionId(sessionId)
        require(attachment.contentRef.startsWith(CACHE_SCHEME))
        val fileName = attachment.contentRef.removePrefix(CACHE_SCHEME)
        val match = STAGED_FILE_PATTERN.matchEntire(fileName) ?: error("invalid staged attachment")
        require(match.groupValues[2] == sha256(sessionId.toByteArray()).take(32)) { "attachment session mismatch" }
        val stagedFile = File(cacheRoot, fileName).canonicalFile
        require(stagedFile.toPath().startsWith(cacheRoot.toPath()) && stagedFile.isFile)
        require(stagedFile.length() == attachment.byteLength)
        val bytes = withContext(Dispatchers.IO) { stagedFile.readBytes() }
        return try {
            require(sha256(bytes) == attachment.sha256Digest) { "attachment digest mismatch" }
            val extension = extensionFor(attachment.mimeType)
            save(
                sessionId = sessionId,
                runId = null,
                kind = ArtifactKind.ATTACHMENT,
                displayName = "用户附件-${attachment.attachmentId.take(8)}.$extension",
                mimeType = attachment.mimeType,
                bytes = bytes,
                provenance = "user_attachment",
            )
        } finally {
            bytes.fill(0)
        }
    }

    override suspend fun updateSummary(sessionId: String, summary: String, throughEpochMs: Long) {
        requireValidSessionId(sessionId)
        val normalized = summary.replace(Regex("\\s+"), " ").trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_SUMMARY_CHARS)
        ensure(sessionId)
        check(
            database.runtimeSessionWorkspaceDao().updateSummary(
                sessionId,
                normalized,
                throughEpochMs,
                System.currentTimeMillis(),
            ) == 1,
        )
    }

    private fun ensureSessionDirectory(directoryName: String): File {
        require(DIRECTORY_PATTERN.matches(directoryName))
        val directory = File(workspaceRoot, directoryName).canonicalFile
        require(directory.toPath().startsWith(workspaceRoot.toPath()))
        require(directory.mkdirs() || directory.isDirectory)
        return directory
    }

    private fun sessionDirectoryName(sessionId: String): String = "session-${sha256(sessionId.toByteArray()).take(32)}"

    private fun requireValidSessionId(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.toByteArray().size <= 256)
        require(sessionId.none(Char::isISOControl))
    }

    private fun safeDisplayName(value: String): String {
        val normalized = value.replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
            .trim()
            .trimStart('.')
            .take(120)
        require(normalized.isNotBlank() && normalized !in setOf(".", ".."))
        return normalized
    }

    private fun randomId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun RuntimeArtifactEntity.toDomain() = SessionArtifact(
        artifactId = artifactId,
        sessionId = sessionId,
        runId = runId,
        kind = ArtifactKind.valueOf(kind),
        displayName = displayName,
        mimeType = mimeType,
        byteLength = byteLength,
        sha256Digest = sha256Digest,
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "application/pdf" -> "pdf"
        "audio/wav" -> "wav"
        "video/mp4" -> "mp4"
        else -> "bin"
    }

    private companion object {
        const val MAX_ARTIFACT_BYTES = 25 * 1024 * 1024
        const val MAX_SESSION_BYTES = 100L * 1024 * 1024
        const val MAX_SUMMARY_CHARS = 8_000
        val MIME_PATTERN = Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")
        val EXTENSION_PATTERN = Regex("[A-Za-z0-9]{1,10}")
        val DIRECTORY_PATTERN = Regex("session-[0-9a-f]{32}")
        const val CACHE_SCHEME = "cache://"
        val STAGED_FILE_PATTERN = Regex("zbi_(\\d+)_([0-9a-f]{32})_([0-9a-f]{32})\\.bin")
        val ALLOWED_PROVENANCE = setOf("user_attachment", "agent_generated", "tool_result", "user_export")
    }
}

internal fun writeArtifactAtomically(temporary: File, destination: File, bytes: ByteArray) {
    try {
        temporary.outputStream().use { it.write(bytes) }
        require(temporary.length() == bytes.size.toLong())
        require(temporary.renameTo(destination)) { "artifact atomic move failed" }
    } catch (failure: Throwable) {
        temporary.delete()
        throw failure
    }
}
