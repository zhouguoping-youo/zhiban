package com.zhiban.rebuild.data.calllog

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class CallLogRepository @Inject internal constructor(private val database: AgentDatabase) {
    fun observePendingNotes(): Flow<List<CallRecordEntity>> = database.callLogDao().observePendingNotes()
    fun observeForContact(contactId: String): Flow<List<CallRecordEntity>> = database.callLogDao().observeForContact(contactId)

    suspend fun markLatestCallPending(nowEpochMs: Long = System.currentTimeMillis()): String? = database.withTransaction {
        val call = database.callLogDao().findLatestSince(nowEpochMs - RECENT_CALL_WINDOW_MS)
            ?: return@withTransaction null
        if (!isEligibleForCallNote(call.direction, call.durationSeconds)) return@withTransaction null
        if (call.notePromptState != "NONE") return@withTransaction call.callRecordId
        database.callLogDao().updateNotePromptState(call.callRecordId, "PENDING", nowEpochMs)
        call.callRecordId
    }

    suspend fun dismissPendingNote(callRecordId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.callLogDao().updateNotePromptState(callRecordId, "DISMISSED", nowEpochMs) == 1

    suspend fun saveTypedNote(
        callRecordId: String,
        text: String,
        source: String = "TYPED",
        asrProvider: String? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = database.withTransaction {
        val call = database.callLogDao().findById(callRecordId) ?: error("这条通话记录已经不存在")
        val clean = text.trim().replace(Regex("\\s+"), " ").take(2_000)
        require(clean.isNotBlank()) { "请先填写通话要点" }
        val noteId = "call-note:$callRecordId"
        database.callLogDao().upsertNote(
            CallNoteEntity(
                callNoteId = noteId,
                callRecordId = callRecordId,
                noteText = clean,
                source = source,
                asrProvider = asrProvider,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        val contact = call.linkedContactId?.let { database.contactDao().findById(it) }
        FactIndex(database).upsert(
            FactEntity(
                factId = "call-note:$callRecordId",
                factType = "CALL_NOTE",
                textContent = buildString {
                    append("通话备注")
                    contact?.displayName?.let { append("（").append(it).append("）") }
                    append("：").append(clean)
                },
                structuredDataJson = buildJsonObject {
                    put("callRecordId", callRecordId)
                    put("direction", call.direction)
                    put("startedAtEpochMs", call.startedAtEpochMs)
                    put("durationSeconds", call.durationSeconds)
                }.toString(),
                sourceType = "USER_CALL_NOTE",
                sourceRef = callRecordId,
                contactId = call.linkedContactId,
                skillId = null,
                confidence = 1.0,
                sensitivity = "SENSITIVE",
                status = "ACTIVE",
                ttlDays = 0,
                expiresAtEpochMs = null,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        database.callLogDao().updateNotePromptState(callRecordId, "COMPLETED", nowEpochMs)
        noteId
    }

    suspend fun deleteNoteFact(factId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        if (!factId.startsWith("call-note:")) return@withTransaction false
        val callRecordId = factId.removePrefix("call-note:")
        database.callLogDao().deleteNote(factId)
        FactIndex(database).delete(factId)
        database.callLogDao().updateNotePromptState(callRecordId, "DISMISSED", nowEpochMs)
        true
    }

    companion object {
        private const val RECENT_CALL_WINDOW_MS = 10 * 60_000L
    }
}

internal fun isEligibleForCallNote(direction: String, durationSeconds: Long): Boolean =
    durationSeconds > 0 && direction in setOf("INCOMING", "OUTGOING", "ANSWERED_EXTERNALLY")
