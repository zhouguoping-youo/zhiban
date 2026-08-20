package com.zhiban.rebuild.data.contact

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames
import com.zhiban.rebuild.data.autowrite.insertVisibleAutoWrite
import com.zhiban.rebuild.data.contact.enrichment.canonicalRelationshipDigest
import com.zhiban.rebuild.foundation.sha256
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Builds bounded, low-confidence GROUP_MEMBER edges only from two resolved identities in one observed group. */
@Singleton
internal class CommonGroupRelationshipScanner @Inject constructor(private val database: AgentDatabase) {
    suspend fun scan(nowEpochMs: Long = System.currentTimeMillis()): Int {
        val memberships = database.contactIntelligenceDao().resolvedGroupMemberships(MAX_MEMBERSHIPS)
        var created = 0
        memberships.groupBy(ResolvedGroupMembershipProjection::groupId).forEach { (groupId, rows) ->
            val remaining = MAX_NEW_EDGES_PER_RUN - created
            if (remaining == 0) return created
            created += scanGroup(groupId, rows, nowEpochMs, remaining)
        }
        return created
    }

    private suspend fun scanGroup(groupId: String, rows: List<ResolvedGroupMembershipProjection>, nowEpochMs: Long, limit: Int): Int {
        val contactIds = rows.map(ResolvedGroupMembershipProjection::contactId).distinct().sorted()
        if (contactIds.size !in 2..MAX_GROUP_SIZE) return 0
        var created = 0
        for (firstIndex in 0 until contactIds.lastIndex) {
            for (secondIndex in firstIndex + 1 until contactIds.size) {
                if (created == limit) return created
                if (createIfAbsent(contactIds[firstIndex], contactIds[secondIndex], groupId, nowEpochMs)) created++
            }
        }
        return created
    }

    private suspend fun createIfAbsent(firstId: String, secondId: String, groupId: String, nowEpochMs: Long): Boolean = database.withTransaction {
        if (database.relationshipEdgeDao().findActiveBetween(firstId, secondId) != null) return@withTransaction false
        val pair = listOf(firstId, secondId).sorted()
        val edgeId = "common-group:${sha256("${pair[0]}:${pair[1]}").take(24)}"
        val groupRef = "group:${sha256(groupId).take(16)}"
        val edge = RelationshipEdgeEntity(
            edgeId = edgeId,
            fromContactId = pair[0],
            toContactId = pair[1],
            relationType = "GROUP_MEMBER",
            evidenceDigest = "共同群聊",
            evidenceRefsJson = buildJsonArray { add(JsonPrimitive(groupRef)) }.toString(),
            confidence = COMMON_GROUP_CONFIDENCE,
            userConfirmed = false,
            skillId = SKILL_ID,
            status = "ACTIVE",
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        database.relationshipEdgeDao().upsert(edge)
        val idempotencyKey = "common-group:$edgeId"
        database.insertVisibleAutoWrite(
            AutoWriteAuditDraft(
                changeId = "change:${sha256(idempotencyKey).take(32)}",
                runtimeRunId = null,
                toolName = AutoWriteToolNames.RELATIONSHIP_AUTO_INFER,
                idempotencyKey = idempotencyKey,
                targetDomain = "RELATIONSHIP",
                targetId = edgeId,
                operation = "CREATE",
                afterDigest = canonicalRelationshipDigest(edge),
                inversePayloadJson = "{\"edgeId\":\"$edgeId\"}",
                originType = "SYSTEM_PERCEPTION",
                subjectContactId = firstId,
                sourceType = "GROUP_MEMBERSHIP",
                sourceRef = groupRef,
                confidence = COMMON_GROUP_CONFIDENCE,
                presentationType = "RELATIONSHIP_INFERRED",
                correctionRoute = "RELATIONSHIP_EDITOR",
                createdAtEpochMs = nowEpochMs,
                summary = "关系：群友（共同群聊证据）",
            ),
        )
        true
    }

    private companion object {
        const val MAX_MEMBERSHIPS = 2_000
        const val MAX_GROUP_SIZE = 50
        const val MAX_NEW_EDGES_PER_RUN = 256
        const val COMMON_GROUP_CONFIDENCE = 0.35
        const val SKILL_ID = "common-group-inference-v1"
    }
}
