package com.zhiban.rebuild.runtime.store

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.store.ConversationSummary
import com.zhiban.rebuild.data.store.RuntimeConversationTurnEntity
import com.zhiban.rebuild.data.store.RuntimeEventEntity
import com.zhiban.rebuild.data.store.RuntimeRunEntity
import com.zhiban.rebuild.data.store.RuntimeSessionEntity
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ConversationTurn(val turnId: String, val role: String, val text: String)

/**
 * Runtime text inputs may be wrapped in an internal JSON envelope containing mode/model
 * metadata. That envelope must never leak into user-facing history or conversation context.
 * This also repairs legacy rows at read time without requiring a destructive migration.
 */
internal fun userFacingConversationText(raw: String): String {
    fun decode(value: String, depth: Int): String {
        if (depth > 2) return value
        val element = runCatching { Json.parseToJsonElement(value.trim()) }.getOrNull() ?: return value
        return when (element) {
            is JsonObject -> (element["text"] as? JsonPrimitive)?.content ?: "历史对话"
            is JsonPrimitive -> if (element.isString) decode(element.content, depth + 1) else value
            else -> value
        }
    }

    val decoded = decode(raw, 0)
    val normalized = decoded
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized.ifBlank { "历史对话" }
}

interface ConversationHistoryGateway {
    suspend fun list(limit: Int = 30): List<ConversationSummary>
    suspend fun turns(sessionId: String, limit: Int = 100): List<ConversationTurn>
    fun observeTurns(sessionId: String, limit: Int = 100): Flow<List<ConversationTurn>>
    suspend fun delete(sessionId: String): Boolean
    suspend fun recordRealtimeExchange(sessionId: String, exchangeId: String, transcript: String, reply: String): Boolean
}

internal class RoomConversationHistoryGateway(private val database: AgentDatabase) : ConversationHistoryGateway {
    override suspend fun list(limit: Int): List<ConversationSummary> = database.runtimeSessionDao().conversationSummaries(limit.coerceIn(1, 100))
        .map { summary -> summary.copy(preview = userFacingConversationText(summary.preview)) }

    override suspend fun turns(sessionId: String, limit: Int): List<ConversationTurn> =
        database.runtimeConversationTurnDao().listBySession(sessionId, limit.coerceIn(1, 300))
            .toConversationTurns()

    override fun observeTurns(sessionId: String, limit: Int): Flow<List<ConversationTurn>> =
        database.runtimeConversationTurnDao().observeBySession(sessionId, limit.coerceIn(1, 300))
            .map { rows -> rows.toConversationTurns() }

    override suspend fun delete(sessionId: String): Boolean = database.withTransaction {
        val runIds = database.runtimeRunDao().idsBySession(sessionId)
        if (runIds.isNotEmpty()) {
            // Conversation deletion is also a privacy purge. Keep non-sensitive audit metadata,
            // but remove tool summaries and inverse payloads that can contain titles or notes.
            database.toolAuditDao().scrubResultsByRuntimeRunIds(runIds)
            database.changeLogDao().expireAndScrubByRuntimeRunIds(runIds)
        }
        database.runtimeSessionDao().deleteById(sessionId) == 1
    }

    /**
     * Persists a provider-native realtime exchange as a completed Runtime run. This keeps
     * realtime voice behind the same session/history boundary as text conversations instead
     * of letting Compose or the provider write conversation tables directly.
     */
    override suspend fun recordRealtimeExchange(sessionId: String, exchangeId: String, transcript: String, reply: String): Boolean {
        if (transcript.isBlank() && reply.isBlank()) return false
        val now = System.currentTimeMillis()
        val safeExchangeId = exchangeId.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,80}")) } ?: return false
        val runId = "realtime-$safeExchangeId"
        return database.withTransaction {
            if (database.runtimeRunDao().find(runId)?.sessionId == sessionId) return@withTransaction true
            database.runtimeSessionDao().insert(RuntimeSessionEntity(sessionId = sessionId, updatedAtEpochMs = now))
            val session = requireNotNull(database.runtimeSessionDao().find(sessionId))
            val firstSequence = session.nextSequence
            check(
                database.runtimeRunDao().insert(
                    RuntimeRunEntity(
                        runId = runId,
                        sessionId = sessionId,
                        schemaVersion = RUNTIME_SCHEMA_VERSION,
                        status = RuntimeRunStatus.SUCCEEDED.name,
                        budgetJson = "{}",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                ) != -1L,
            )
            if (transcript.isNotBlank()) {
                database.runtimeConversationTurnDao().insert(
                    realtimeTurn(runId, sessionId, "user", transcript, now),
                )
            }
            if (reply.isNotBlank()) {
                database.runtimeConversationTurnDao().insert(
                    realtimeTurn(runId, sessionId, "assistant", reply, now + 1),
                )
            }
            val events = buildList {
                if (reply.isNotBlank()) {
                    add(
                        "AssistantDelta" to buildJsonObject {
                            put("ordinal", 0)
                            put("part", reply)
                            put("final", true)
                            put("providerOffset", 0)
                        }.toString(),
                    )
                }
                add("RunCompleted" to "{}")
            }
            events.forEachIndexed { index, (type, payload) ->
                database.runtimeEventDao().insert(
                    RuntimeEventEntity(
                        eventId = "event-$runId-$index",
                        schemaVersion = RUNTIME_SCHEMA_VERSION,
                        eventType = type,
                        sessionId = sessionId,
                        runId = runId,
                        attemptId = null,
                        sequence = firstSequence + index,
                        correlationId = runId,
                        producerVersion = "stepfun-realtime-v1",
                        payloadJson = payload,
                        createdAtEpochMs = now + index,
                        fencingEpoch = 0,
                    ),
                )
            }
            check(
                database.runtimeSessionDao().advanceSequence(
                    sessionId,
                    firstSequence,
                    firstSequence + events.size,
                    now + events.size,
                ) == 1,
            )
            true
        }
    }

    private fun realtimeTurn(runId: String, sessionId: String, role: String, content: String, now: Long) = RuntimeConversationTurnEntity(
        turnId = "turn-$runId-$role",
        sessionId = sessionId,
        runId = runId,
        role = role,
        content = content,
        contentDigest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) },
        tokenEstimate = (content.toByteArray().size / 4 + 1).coerceAtLeast(1),
        createdAtEpochMs = now,
    )

    private fun List<RuntimeConversationTurnEntity>.toConversationTurns(): List<ConversationTurn> = mapNotNull { turn ->
        val text = if (turn.role == "user") userFacingConversationText(turn.content) else turn.content.trim()
        text.takeIf(String::isNotBlank)?.let { ConversationTurn(turn.turnId, turn.role, it) }
    }
}
