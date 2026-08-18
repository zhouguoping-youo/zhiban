package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.store.RuntimeInputStagingEntity
import com.zhiban.rebuild.runtime.spi.RuntimeV2DisabledException
import com.zhiban.rebuild.runtime.spi.StagedTextInput
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import java.security.MessageDigest
import java.security.SecureRandom

internal class RoomTextInputGateway(
    private val database: AgentDatabase,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMs: Long = MAX_TTL_MS,
) : TextInputGateway {
    init {
        require(ttlMs in 1..MAX_TTL_MS)
    }

    override suspend fun stage(rawText: String): StagedTextInput {
        if (!enabled()) throw RuntimeV2DisabledException()
        val bytes = rawText.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_UTF8_BYTES) { "text input must be 1..64KiB UTF-8" }
        val now = clock()
        database.runtimeInputStagingDao().deleteExpired(now)
        val ref = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val entity = RuntimeInputStagingEntity(ref, rawText, bytes.size, digest, now, Math.addExact(now, ttlMs))
        database.runtimeInputStagingDao().insert(entity)
        return StagedTextInput(ref, bytes.size, digest, entity.expiresAtEpochMs)
    }

    override suspend fun discard(inputRef: String) {
        database.runtimeInputStagingDao().delete(inputRef)
    }

    companion object {
        const val MAX_UTF8_BYTES = 65_536
        const val MAX_TTL_MS = 24 * 60 * 60 * 1_000L
    }
}
