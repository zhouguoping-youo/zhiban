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
        undoAndMarkCorrected(changeId, nowEpochMs)
    }

    suspend fun promoteCandidateLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val lead = database.crmDao().findLead(leadId)
            ?.takeIf { it.status == com.zhiban.rebuild.data.crm.CrmLeadStatus.CANDIDATE }
            ?: return@withTransaction false
        val change = database.changeLogDao().findAvailableChangeForTarget("CRM_LEAD", lead.leadId)
        if (database.crmDao().promoteCandidateLead(leadId, nowEpochMs) != 1) return@withTransaction false
        change?.let {
            database.changeLogDao().markUnavailable(it.changeId)
            database.changeLogDao().markAutoWriteCorrected(it.changeId)
        }
        true
    }

    suspend fun ignoreCandidateLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val lead = database.crmDao().findLead(leadId)
            ?.takeIf { it.status == com.zhiban.rebuild.data.crm.CrmLeadStatus.CANDIDATE }
            ?: return@withTransaction false
        val change = database.changeLogDao().findAvailableChangeForTarget("CRM_LEAD", lead.leadId)
        if (change != null) {
            undoAndMarkCorrected(change.changeId, nowEpochMs)
        } else {
            // Compatibility for candidates created by builds that marked confirmed creation as
            // non-undoable. The status predicate still prevents deleting any formal lead.
            database.crmDao().deleteCandidateLead(lead.leadId) == 1
        }
    }

    private suspend fun undoAndMarkCorrected(changeId: String, nowEpochMs: Long): Boolean {
        val change = database.changeLogDao().find(changeId) ?: return false
        val coordinator = ChangeUndoCoordinator(database)
        val undone = if (database.changeLogDao().findAutoWriteReceipt(changeId) != null) {
            coordinator.undoVisibleInTransaction(changeId, nowEpochMs)
        } else {
            change.runtimeRunId?.let { coordinator.undoInTransaction(changeId, it, nowEpochMs) }
        }
        if (undone == null) return false
        // User-confirmed candidates have a normal change log but no auto-write receipt.
        database.changeLogDao().markAutoWriteCorrected(changeId)
        return true
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
