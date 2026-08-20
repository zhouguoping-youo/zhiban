package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactSearchProjection
import com.zhiban.rebuild.data.contact.RelationshipEdgeDao
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipSearchToolBindingTest {
    private val edge = RelationshipEdgeEntity(
        "e1", "a", "b", "FRIEND", "safe-digest", "[\"runtime:private-evidence\"]", .9, true, null, "ACTIVE", 1, 1,
    )
    private val dao = object : RelationshipEdgeDao {
        override suspend fun upsert(edge: RelationshipEdgeEntity) = Unit
        override suspend fun directCanonicalContactId(contactId: String): String? = null
        override suspend fun resolveCanonicalContactId(contactId: String) = contactId
        override fun observeActive() = flowOf(listOf(edge))
        override suspend fun touching(contactIds: List<String>, limit: Int) = if ("a" in
            contactIds
        ) {
            listOf(edge)
        } else {
            emptyList()
        }
        override suspend fun ownerRelationships(contactIds: List<String>) = emptyList<RelationshipEdgeEntity>()
        override suspend fun contactSummaries(contactIds: List<String>) = listOf(
            ContactSearchProjection("a", "张三", null, null, null, null, null, null),
            ContactSearchProjection("b", "李四", null, null, null, null, null, null),
        ).filter { it.contactId in contactIds }
        override suspend fun find(edgeId: String) = edge.takeIf { it.edgeId == edgeId }
        override suspend fun findActiveBetween(firstId: String, secondId: String) =
            edge.takeIf { setOf(firstId, secondId) == setOf(edge.fromContactId, edge.toContactId) }
        override suspend fun deleteConfirmed(edgeId: String) = if (edge.edgeId == edgeId) 1 else 0
        override suspend fun deactivateInferredEdge(edgeId: String, nowEpochMs: Long) = if (edge.edgeId == edgeId) 1 else 0
        override suspend fun deactivateForContacts(contactIds: List<String>, nowEpochMs: Long) = 0
    }

    @Test fun returnsBoundedGraphWithoutEvidenceRefs() = runTest {
        val spec = RuntimeToolCatalog.production().requireRegistered("relationship.search")
        val result = RelationshipSearchToolBinding(spec, dao).executeReadOnly(
            RuntimeToolCallRequest("call", spec.name, """{"contactId":"a","maxDepth":2}"""),
            RuntimeToolRouteContext("r", "s", "a", "o", 1, 1, 1),
        )
        assertTrue(result.safeResultJson.contains("FRIEND"))
        assertTrue(result.safeResultJson.contains("safe-digest"))
        assertFalse(result.safeResultJson.contains("private-evidence"))
    }

    @Test fun rejectsUnknownFieldsAndDepth() = runTest {
        val spec = RuntimeToolCatalog.production().requireRegistered("relationship.search")
        val binding = RelationshipSearchToolBinding(spec, dao)
        assertTrue(
            runCatching {
                binding.executeReadOnly(
                    RuntimeToolCallRequest("c", spec.name, """{"contactId":"a","sql":"x"}"""),
                    context(),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                binding.executeReadOnly(
                    RuntimeToolCallRequest("c", spec.name, """{"contactId":"a","maxDepth":3}"""),
                    context(),
                )
            }.isFailure,
        )
    }

    @Test fun evidenceReviewReturnsOnlyDigestAndSourceType() = runTest {
        val spec = RuntimeToolCatalog.production().requireRegistered("relationship.getEvidence")
        val result = RelationshipEvidenceToolBinding(spec, dao).executeReadOnly(
            RuntimeToolCallRequest("evidence-call", spec.name, """{"edgeId":"e1"}"""),
            context(),
        )
        assertTrue(result.safeResultJson.contains("safe-digest"))
        assertTrue(result.safeResultJson.contains("runtime"))
        assertFalse(result.safeResultJson.contains("private-evidence"))
    }

    private fun context() = RuntimeToolRouteContext("r", "s", "a", "o", 1, 1, 1)
}
