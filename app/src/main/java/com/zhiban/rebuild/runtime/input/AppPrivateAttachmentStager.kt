package com.zhiban.rebuild.runtime.input

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface AttachmentContentSource {
    fun declaredMimeType(contentRef: String): String?
    fun byteLength(contentRef: String): Long?
    fun durationMs(contentRef: String): Long?
    fun open(contentRef: String): InputStream
}

internal class AndroidAttachmentContentSource(context: Context) : AttachmentContentSource {
    private val resolver = context.applicationContext.contentResolver
    override fun declaredMimeType(contentRef: String): String? {
        val uri = Uri.parse(contentRef)
        val declared = resolver.getType(uri)
        if (!declared.isNullOrBlank() && declared != "application/octet-stream") return declared
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
        val extension = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: declared
    }
    override fun byteLength(contentRef: String): Long? = resolver.query(Uri.parse(contentRef), arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }
    override fun durationMs(contentRef: String): Long? = MediaMetadataRetriever().let { retriever ->
        try {
            resolver.openFileDescriptor(Uri.parse(contentRef), "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }
    override fun open(contentRef: String): InputStream = requireNotNull(resolver.openInputStream(Uri.parse(contentRef)))
}

internal class AppPrivateAttachmentStager private constructor(
    private val rootDirectory: File,
    private val source: AttachmentContentSource,
    private val limits: InputLimits,
    private val nowEpochMs: () -> Long,
    private val random: SecureRandom,
    private val ioDispatcher: CoroutineDispatcher,
) : AttachmentStagingGateway {
    private val ledger = StagingLedger(rootDirectory, limits)
    init {
        require(rootDirectory.mkdirs() || rootDirectory.isDirectory)
    }

    override suspend fun inspect(contentRef: String): AttachmentInspection = withContext(ioDispatcher) {
        streamToTemp(
            sha256("inspection".toByteArray()).take(32),
            contentRef,
            persist = false,
            expiresAtEpochMs =
                nowEpochMs() + MAX_TTL_MS,
            reserve = false,
        ).inspection
    }

    override suspend fun stage(sessionId: String, contentRef: String, expiresAtEpochMs: Long): AttachmentRef {
        require(sessionId.isNotBlank() && sessionId.toByteArray().size <= 256)
        val now = nowEpochMs()
        require(expiresAtEpochMs > now && expiresAtEpochMs - now <= MAX_TTL_MS) { "invalid staging TTL" }
        val declaredBytes = withContext(ioDispatcher) { source.byteLength(contentRef) }
        require(declaredBytes == null || declaredBytes in 1..limits.maxPerItemBytes) { "invalid content length" }
        val sessionHash = sha256(sessionId.toByteArray()).take(32)
        // Several Android DocumentProviders (cloud drives and vendor file managers included)
        // legitimately omit OpenableColumns.SIZE. Reserve the per-item ceiling for such sources;
        // streamToTemp still enforces the byte limit while reading and verifies signatures.
        val reservation = ledger.reserve(sessionHash, declaredBytes ?: limits.maxPerItemBytes)
        return try {
            withContext(ioDispatcher) {
                streamToTemp(
                    sessionHash,
                    contentRef,
                    persist = true,
                    expiresAtEpochMs = expiresAtEpochMs,
                    reserve = true,
                ).let { result ->
                    if (declaredBytes != null) {
                        require(result.inspection.byteLength == declaredBytes) { "content length changed" }
                    }
                    AttachmentRef(
                        result.inspection.attachmentId,
                        kindFor(result.inspection.detectedMimeType),
                        result.inspection.detectedMimeType,
                        result.inspection.byteLength,
                        result.inspection.sha256Digest,
                        CACHE_SCHEME + requireNotNull(result.finalFile).name,
                        expiresAtEpochMs,
                    )
                }
            }
        } finally {
            ledger.release(reservation)
        }
    }

    override suspend fun discard(attachmentId: String) = withContext(ioDispatcher) {
        require(attachmentId.matches(ID_PATTERN))
        rootDirectory.listFiles().orEmpty().filter {
            it.name.endsWith("_$attachmentId.bin") ||
                it.name.endsWith("_$attachmentId.part")
        }.forEach(File::delete)
    }

    suspend fun purgeExpired(now: Long = nowEpochMs()): Int = withContext(ioDispatcher) {
        var removed = 0
        rootDirectory.listFiles().orEmpty().forEach { file ->
            coroutineContext.ensureActive()
            val expiry = FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if ((file.extension == "part" || (expiry != null && expiry <= now)) && file.delete()) removed++
        }
        removed
    }

    internal fun resolveForTest(contentRef: String): File {
        require(contentRef.startsWith(CACHE_SCHEME))
        val name = contentRef.removePrefix(CACHE_SCHEME)
        require(!name.contains('/') && FILE_PATTERN.matches(name))
        return File(rootDirectory, name)
    }
    internal val rootForTest: File get() = rootDirectory

    private suspend fun streamToTemp(sessionHash: String, contentRef: String, persist: Boolean, expiresAtEpochMs: Long, reserve: Boolean): StagingResult {
        val declaredMime =
            source.declaredMimeType(contentRef) ?: throw IllegalArgumentException("missing declared MIME")
        require(declaredMime in limits.allowedMimeTypes) { "MIME not allowed" }
        val declaredDuration = source.durationMs(contentRef)
        if (declaredMime.startsWith("audio/") || declaredMime.startsWith("video/")) {
            require(declaredDuration != null && declaredDuration in 0..limits.maxAudioDurationMs) {
                "missing or invalid media duration"
            }
        }
        val attachmentId = randomId()
        val temp = File(rootDirectory, "zbi_${expiresAtEpochMs}_${sessionHash}_$attachmentId.part")
        val finalFile = File(rootDirectory, "zbi_${expiresAtEpochMs}_${sessionHash}_$attachmentId.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(32)
        var headerLength = 0
        var total = 0L
        try {
            source.open(contentRef).use { input ->
                temp.outputStream().buffered().use { output ->
                    val streamed = copyAttachment(input, output, header, digest)
                    total = streamed.totalBytes
                    headerLength = streamed.headerBytes
                }
            }
            val detectedMime =
                MagicMimeDetector.detect(header.copyOf(headerLength))
                    ?: throw IllegalArgumentException("unknown file signature")
            require(declaredMime == detectedMime) { "MIME signature mismatch" }
            require(total > 0) { "empty attachment" }
            val inspection =
                AttachmentInspection(
                    attachmentId,
                    declaredMime,
                    detectedMime,
                    total,
                    declaredDuration,
                    digest.digest().hex(),
                )
            MultimodalInputPolicy(limits).validate(listOf(inspection)).let {
                require(it == InputValidation.Accepted) { "attachment rejected: $it" }
            }
            if (!persist) {
                temp.delete()
                return StagingResult(inspection, null)
            }
            require(reserve)
            require(temp.renameTo(finalFile)) { "atomic staging rename failed" }
            return StagingResult(inspection, finalFile)
        } catch (failure: Throwable) {
            temp.delete()
            finalFile.delete()
            throw failure
        }
    }

    private suspend fun copyAttachment(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        header: ByteArray,
        digest: MessageDigest,
    ): StreamedAttachment {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var headerLength = 0
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total = Math.addExact(total, read.toLong())
            require(total <= limits.maxPerItemBytes) { "attachment exceeds per-item limit" }
            if (headerLength < header.size) {
                val copied = minOf(read, header.size - headerLength)
                buffer.copyInto(header, headerLength, 0, copied)
                headerLength += copied
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
        }
        return StreamedAttachment(total, headerLength)
    }

    private fun randomId(): String = ByteArray(16).also(random::nextBytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun kindFor(mime: String) = when {
        mime.startsWith("image/") -> InputKind.IMAGE
        mime.startsWith("audio/") -> InputKind.AUDIO
        mime.startsWith("video/") -> InputKind.VIDEO
        mime == "text/plain" -> InputKind.TEXT
        else -> InputKind.FILE
    }
    private data class StagingResult(val inspection: AttachmentInspection, val finalFile: File?)

    companion object {
        private const val BUFFER_BYTES = 8 * 1024
        private const val MAX_TTL_MS = 24 * 60 * 60 * 1000L
        private const val CACHE_SCHEME = "cache://"
        private val ID_PATTERN = Regex("[0-9a-f]{32}")
        private val FILE_PATTERN = Regex("zbi_(\\d+)_([0-9a-f]{32})_([0-9a-f]{32})\\.(?:part|bin)")

        fun forProduction(context: Context, source: AttachmentContentSource, limits: InputLimits): AppPrivateAttachmentStager {
            val cacheRoot = context.applicationContext.cacheDir.canonicalFile
            val root = File(cacheRoot, "zhiban-runtime-input").canonicalFile
            require(root.toPath().startsWith(cacheRoot.toPath()))
            return AppPrivateAttachmentStager(
                root,
                source,
                limits,
                System::currentTimeMillis,
                SecureRandom(),
                Dispatchers.IO,
            )
        }

        internal fun forTest(root: File, source: AttachmentContentSource, limits: InputLimits, nowEpochMs: () -> Long): AppPrivateAttachmentStager =
            AppPrivateAttachmentStager(root.canonicalFile, source, limits, nowEpochMs, SecureRandom(), Dispatchers.IO)
    }

    private class StagingLedger(private val root: File, private val limits: InputLimits) {
        private val mutex = Mutex()
        private val active = mutableMapOf<String, MutableMap<String, Long>>()
        suspend fun reserve(sessionHash: String, expectedBytes: Long): Reservation = mutex.withLock {
            val files = root.listFiles().orEmpty()
            require(
                files.filter {
                    it.extension == "bin"
                }.all { FILE_PATTERN.matches(it.name) },
            ) { "corrupt staging ledger" }
            val persisted = files.filter {
                it.extension == "bin" &&
                    FILE_PATTERN.matchEntire(it.name)?.groupValues?.get(2) == sessionHash
            }
            val reservations = active.getOrPut(sessionHash) { mutableMapOf() }
            require(persisted.size + reservations.size < limits.maxAttachmentItems) { "attachment count exceeded" }
            val persistedBytes = persisted.fold(0L) { total, file -> StagingMath.add(total, file.length()) }
            val reservedBytes = reservations.values.fold(0L, StagingMath::add)
            val usedBytes = StagingMath.add(StagingMath.add(persistedBytes, reservedBytes), expectedBytes)
            require(usedBytes <= limits.maxAggregateBytes) { "attachment aggregate exceeded" }
            val token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            reservations[token] = expectedBytes
            Reservation(sessionHash, token)
        }
        suspend fun release(reservation: Reservation) = mutex.withLock {
            active[reservation.sessionHash]?.let { values ->
                values.remove(reservation.token)
                if (values.isEmpty()) active.remove(reservation.sessionHash)
            }
        }
    }
    private data class Reservation(val sessionHash: String, val token: String)
}

private data class StreamedAttachment(val totalBytes: Long, val headerBytes: Int)

internal object StagingMath {
    fun add(left: Long, right: Long): Long = Math.addExact(left, right)
}

internal object MagicMimeDetector {
    fun detect(header: ByteArray): String? = when {
        header.startsWith(PNG_SIGNATURE) -> "image/png"

        header.startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) -> "image/jpeg"

        header.startsWith("GIF87a".toByteArray()) || header.startsWith("GIF89a".toByteArray()) -> "image/gif"

        header.size >= 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"

        header.startsWith("%PDF-".toByteArray()) -> "application/pdf"

        header.size >= 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) -> "audio/wav"

        header.size >= 12 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> "video/mp4"

        else -> null
    }
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
