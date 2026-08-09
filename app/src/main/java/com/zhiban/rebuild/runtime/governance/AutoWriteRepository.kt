package com.zhiban.rebuild.runtime.governance

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.tool.changeIdFor
import com.zhiban.rebuild.runtime.tool.sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.autoWriteDataStore by preferencesDataStore("auto_write_preferences")

@Singleton
class AutoWriteRepository @Inject internal constructor(private val database: AgentDatabase, @ApplicationContext private val context: Context) {
    private val hintShownKey = booleanPreferencesKey("first_auto_write_hint_shown")

    fun observeReceipts(): Flow<List<AutoWriteReceiptRow>> = database.changeLogDao().observeAutoWriteReceipts()

    fun observeUnreviewedCount(): Flow<Int> = observeReceipts().map { rows ->
        rows.count { it.reviewState == "UNREVIEWED" }
    }

    suspend fun shouldShowFirstHint(): Boolean = context.autoWriteDataStore.data.first()[hintShownKey] != true

    suspend fun markFirstHintShown() {
        context.autoWriteDataStore.edit { it[hintShownKey] = true }
    }

    suspend fun markSeen(changeId: String) {
        database.changeLogDao().markAutoWriteSeen(changeId)
    }

    suspend fun undo(changeId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        ChangeUndoCoordinator(database).undoVisibleInTransaction(changeId, nowEpochMs) != null
    }

    suspend fun promoteCandidateLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val change = database.changeLogDao().findAvailableAutoChangeForTarget("CRM_LEAD", leadId)
            ?: return@withTransaction false
        if (database.crmDao().promoteCandidateLead(leadId, nowEpochMs) != 1) return@withTransaction false
        database.changeLogDao().markUnavailable(change.changeId)
        database.changeLogDao().markAutoWriteCorrected(change.changeId)
        true
    }

    suspend fun ignoreCandidateLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val change = database.changeLogDao().findAvailableAutoChangeForTarget("CRM_LEAD", leadId)
            ?: return@withTransaction false
        ChangeUndoCoordinator(database).undoVisibleInTransaction(change.changeId, nowEpochMs) != null
    }

    suspend fun correctInteractionContact(changeId: String, newContactId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            val change = database.changeLogDao().find(changeId) ?: return@withTransaction false
            if (change.toolName != AutoWriteToolNames.INTERACTION_SUMMARY || change.undoState != "AVAILABLE") {
                return@withTransaction false
            }
            val current = database.factDao().find(change.targetId) ?: return@withTransaction false
            if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(current), current)) {
                return@withTransaction false
            }
            if (database.contactDao().findById(newContactId) == null) return@withTransaction false
            val corrected = current.copy(
                contactId = newContactId,
                sourceType = "USER_CORRECTED",
                updatedAtEpochMs = nowEpochMs,
            )
            FactIndex(database).upsert(corrected)
            if (database.changeLogDao().markUnavailable(changeId) != 1) return@withTransaction false
            database.changeLogDao().markAutoWriteCorrected(changeId)
            database.changeLogDao().insert(
                ChangeLogEntity(
                    changeId = changeIdFor(sha256("correction:$changeId:$newContactId")),
                    runtimeRunId = null,
                    toolName = "contact.interactionSummary.correct",
                    idempotencyKey = sha256("correction:$changeId:$newContactId"),
                    targetDomain = "FACT",
                    targetId = corrected.factId,
                    operation = "UPDATE",
                    beforeDigest = change.afterDigest,
                    afterDigest = canonicalChangeDigest(corrected),
                    inversePayloadJson = "{}",
                    undoState = "UNAVAILABLE",
                    createdAtEpochMs = nowEpochMs,
                    undoneAtEpochMs = null,
                    originType = "USER_CORRECTION",
                ),
            )
            true
        }
}
