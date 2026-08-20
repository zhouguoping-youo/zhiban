package com.zhiban.rebuild.data.calllog

import android.provider.CallLog
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.interaction.InteractionSourceType
import com.zhiban.rebuild.data.interaction.callInteraction
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class CallLogSyncResult(
    val rowsRead: Int,
    val rowsWritten: Int,
    val contactsLinked: Int,
    val ambiguousMatches: Int,
    val degradationReason: String? = null,
)

@Singleton
class CallLogSyncCoordinator @Inject internal constructor(
    private val database: AgentDatabase,
    private val preferences: CallLogCollectionPreferences,
    private val source: AndroidCallLogSource,
    private val crmRepository: com.zhiban.rebuild.data.agent.CrmAgentDataRepository,
) {
    suspend fun syncNow(nowEpochMs: Long = System.currentTimeMillis()): CallLogSyncResult {
        if (!preferences.isEnabled()) return CallLogSyncResult(0, 0, 0, 0)
        val storedCursor = preferences.cursor()
        val lowerBound = if (storedCursor > 0L) {
            (storedCursor - OVERLAP_MS).coerceAtLeast(0L)
        } else {
            (nowEpochMs - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
        }
        val rows = try {
            source.readChangedSince(lowerBound, MAX_ROWS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return CallLogSyncResult(0, 0, 0, 0, "call_log:permission")
        } catch (_: Throwable) {
            return CallLogSyncResult(0, 0, 0, 0, "call_log:failure")
        }
        val result = importCallsAndSuggestionsAtomically(
            database = database,
            crmRepository = crmRepository,
            rows = rows,
            nowEpochMs = nowEpochMs,
            generateFollowUps = storedCursor > 0L,
        )
        rows.maxOfOrNull(SystemCallLogRow::lastModifiedEpochMs)?.let { preferences.advanceCursor(it) }
        return result.copy(rowsRead = rows.size)
    }

    /**
     * For each newly synced call already linked to a contact, offer a CRM follow-up suggestion when
     * that contact has an open opportunity. Suggestion-only — nothing is written until the user accepts.
     */
    companion object {
        private const val OVERLAP_MS = 5 * 60_000L
        private const val INITIAL_LOOKBACK_MS = 90L * 24 * 60 * 60_000L
        private const val MAX_ROWS = 500
    }
}

internal suspend fun importCallsAndSuggestionsAtomically(
    database: AgentDatabase,
    crmRepository: com.zhiban.rebuild.data.agent.CrmAgentDataRepository,
    rows: List<SystemCallLogRow>,
    nowEpochMs: Long,
    generateFollowUps: Boolean = true,
): CallLogSyncResult = database.withTransaction {
    val result = CallLogImporter(database).import(rows, nowEpochMs)
    if (generateFollowUps) suggestCrmFollowUpsForSyncedCalls(database, crmRepository, rows, nowEpochMs)
    result
}

internal suspend fun suggestCrmFollowUpsForSyncedCalls(
    database: AgentDatabase,
    crmRepository: com.zhiban.rebuild.data.agent.CrmAgentDataRepository,
    rows: List<SystemCallLogRow>,
    nowEpochMs: Long,
) {
    for (row in rows.distinctBy { it.providerRowId }) {
        val call = database.callLogDao().findBySourceRow(CallLogImporter.SOURCE_ANDROID, row.providerRowId) ?: continue
        if (!isEligibleForCallFollowUp(call.direction, call.durationSeconds, call.startedAtEpochMs, nowEpochMs)) continue
        val contactId = call.linkedContactId ?: continue
        crmRepository.suggestCallFollowUpActivity(contactId, call.callRecordId, call.durationSeconds, nowEpochMs)
    }
}

internal fun isEligibleForCallFollowUp(direction: String, durationSeconds: Long, startedAtEpochMs: Long, nowEpochMs: Long): Boolean = durationSeconds > 0 &&
    direction in setOf("INCOMING", "OUTGOING", "ANSWERED_EXTERNALLY") &&
    startedAtEpochMs in (nowEpochMs - FOLLOW_UP_FRESHNESS_MS)..(nowEpochMs + CLOCK_SKEW_TOLERANCE_MS)

private const val FOLLOW_UP_FRESHNESS_MS = 24L * 60 * 60_000L
private const val CLOCK_SKEW_TOLERANCE_MS = 5 * 60_000L

internal class CallLogImporter(private val database: AgentDatabase) {
    suspend fun import(rows: List<SystemCallLogRow>, nowEpochMs: Long): CallLogSyncResult = database.withTransaction {
        var written = 0
        var linked = 0
        var ambiguous = 0
        for (row in rows.distinctBy { it.providerRowId }) {
            val existing = database.callLogDao().findBySourceRow(SOURCE_ANDROID, row.providerRowId)
            val numberCanIdentifyPerson = row.numberPresentation == CallLog.Calls.PRESENTATION_ALLOWED
            val normalized = row.number.takeIf { numberCanIdentifyPerson }?.let(::normalizeContactPhone)
            val matches = normalized?.let {
                database.contactKnowledgeDao().findContactsByMethod("PHONE", it)
            }.orEmpty().distinctBy { it.contactId }
            val userLinked = existing?.linkState == "USER_LINKED"
            val contactId = when {
                userLinked -> existing?.linkedContactId
                matches.size == 1 -> matches.single().contactId
                else -> null
            }
            if (matches.size == 1) linked++
            if (matches.size > 1) ambiguous++
            val createdAt = existing?.createdAtEpochMs ?: nowEpochMs
            val call = CallRecordEntity(
                callRecordId = existing?.callRecordId ?: UUID.randomUUID().toString(),
                source = SOURCE_ANDROID,
                providerRowId = row.providerRowId,
                rawNumber = row.number.takeIf { numberCanIdentifyPerson }?.take(128),
                normalizedNumber = normalized,
                numberPresentation = row.numberPresentation,
                systemType = row.systemType,
                direction = callDirection(row.systemType),
                startedAtEpochMs = row.startedAtEpochMs,
                durationSeconds = row.durationSeconds,
                lastModifiedEpochMs = row.lastModifiedEpochMs,
                phoneAccountId = row.phoneAccountId?.take(128),
                phoneAccountComponentName = row.phoneAccountComponentName?.take(256),
                linkedContactId = contactId,
                linkState = when {
                    userLinked -> "USER_LINKED"
                    matches.size == 1 -> "MATCHED"
                    matches.size > 1 -> "AMBIGUOUS"
                    else -> "UNMATCHED"
                },
                linkSource = when {
                    userLinked -> existing?.linkSource
                    matches.size == 1 -> "NORMALIZED_PHONE"
                    else -> null
                },
                sourceStatus = "ACTIVE",
                notePromptState = existing?.notePromptState ?: "NONE",
                createdAtEpochMs = createdAt,
                updatedAtEpochMs = nowEpochMs,
            )
            database.callLogDao().upsertCall(call)
            database.contactInteractionDao().deleteBySource(InteractionSourceType.CALL, call.callRecordId)
            callInteraction(call)?.let { database.contactInteractionDao().insertIgnore(it) }
            written++
        }
        CallLogSyncResult(rows.size, written, linked, ambiguous)
    }

    companion object {
        const val SOURCE_ANDROID = "ANDROID_CALL_LOG"
    }
}
