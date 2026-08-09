package com.zhiban.rebuild.runtime.plan

import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-005 §11.1 + ADR-006 §3.2: each of the 9 error categories is
 * frozen by a dedicated test. The structural checks (CYCLE / SELF_LOOP
 * / ORPHAN_NODE) and the semantic checks (UNKNOWN_TOOL /
 * UNKNOWN_TOOL_VERSION / CROSS_NAMESPACE / UNAUTHORIZED_TOOL /
 * BUDGET_OVERFLOW / SCHEMA_MISMATCH) all reject on the
 * corresponding error and pass on a clean plan.
 */
class PlanValidatorTest {
    private val allowlist = setOf("calendar.write", "memory.commit")
    private val toolVersions = mapOf(
        "calendar.write" to "v1",
        "memory.commit" to "v2",
    )
    private val validator = PlanValidator(
        toolAllowlist = allowlist,
        toolVersions = toolVersions,
        expectedSchemaVersion = 1,
        budget = PlanValidator.Budget(maxNodes = 8, maxEdges = 16, maxOutDegree = 3),
    )

    @Test
    fun cleanPlanIsValid() {
        val plan = plan(
            nodes = listOf(
                node("a", tool = "calendar.write", version = "v1"),
                node("b", tool = "memory.commit", version = "v2"),
            ),
            edges = listOf(edge("a", "b")),
        )
        val result = validator.validate(plan)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun selfLoopIsRejected() {
        val plan = plan(
            nodes = listOf(node("a", tool = "calendar.write", version = "v1")),
            edges = listOf(edge("a", "a")),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.SELF_LOOP })
    }

    @Test
    fun cycleIsRejected() {
        // a -> b -> c -> a
        val plan = plan(
            nodes = listOf(
                node("a", tool = "calendar.write", version = "v1"),
                node("b", tool = "memory.commit", version = "v2"),
                node("c", tool = "calendar.write", version = "v1"),
            ),
            edges = listOf(edge("a", "b"), edge("b", "c"), edge("c", "a")),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.CYCLE })
    }

    @Test
    fun orphanNodeIsRejected() {
        // 'b' is not reachable from any other node (no incoming edge)
        val plan = plan(
            nodes = listOf(
                node("a", tool = "calendar.write", version = "v1"),
                node("orphan", tool = "memory.commit", version = "v2"),
            ),
            edges = emptyList(),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.ORPHAN_NODE && it.first == "orphan" })
    }

    @Test
    fun unknownToolIsRejected() {
        val plan = plan(
            nodes = listOf(node("a", tool = "missing.tool", version = "v1")),
            edges = emptyList(),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.UNKNOWN_TOOL })
    }

    @Test
    fun unknownToolVersionIsRejected() {
        val plan = plan(
            nodes = listOf(node("a", tool = "calendar.write", version = null)),
            edges = emptyList(),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.UNKNOWN_TOOL_VERSION })
    }

    @Test
    fun unauthorizedToolVersionIsRejected() {
        val plan = plan(
            nodes = listOf(node("a", tool = "calendar.write", version = "wrong-version")),
            edges = emptyList(),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.UNAUTHORIZED_TOOL })
    }

    @Test
    fun crossNamespaceIsRejected() {
        val plan = plan(
            nodes = listOf(node("a", tool = "calendar.write", version = "v1", ownerNamespaceOverride = "OTHER")),
            edges = emptyList(),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.CROSS_NAMESPACE })
    }

    @Test
    fun budgetOverflowOnNodeCountIsRejected() {
        val tight = PlanValidator(
            toolAllowlist = allowlist,
            toolVersions = toolVersions,
            expectedSchemaVersion = 1,
            budget = PlanValidator.Budget(maxNodes = 1, maxEdges = 16, maxOutDegree = 3),
        )
        val plan = plan(
            nodes = listOf(
                node("a", tool = "calendar.write", version = "v1"),
                node("b", tool = "memory.commit", version = "v2"),
            ),
            edges = listOf(edge("a", "b")),
        )
        val result = tight.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.BUDGET_OVERFLOW })
    }

    @Test
    fun budgetOverflowOnOutDegreeIsRejected() {
        val tight = PlanValidator(
            toolAllowlist = allowlist,
            toolVersions = toolVersions,
            expectedSchemaVersion = 1,
            budget = PlanValidator.Budget(maxNodes = 16, maxEdges = 32, maxOutDegree = 1),
        )
        // a -> b, a -> c, a -> d : outDegree 3 > 1
        val plan = plan(
            nodes = listOf(
                node("a", tool = "calendar.write", version = "v1"),
                node("b", tool = "memory.commit", version = "v2"),
                node("c", tool = "memory.commit", version = "v2"),
                node("d", tool = "memory.commit", version = "v2"),
            ),
            edges = listOf(edge("a", "b"), edge("a", "c"), edge("a", "d")),
        )
        val result = tight.validate(plan)
        assertFalse(result.isValid)
        assertTrue(
            result.errors.any {
                it.second == PlanValidator.Error.BUDGET_OVERFLOW &&
                    it.first.endsWith("outDegree")
            },
        )
    }

    @Test
    fun schemaMismatchIsRejected() {
        val broken = PlanValidator(
            toolAllowlist = allowlist,
            toolVersions = toolVersions,
            expectedSchemaVersion = 0, // invalid
            budget = PlanValidator.Budget(maxNodes = 8, maxEdges = 16, maxOutDegree = 3),
        )
        val plan = plan(nodes = listOf(node("a", tool = "calendar.write", version = "v1")), edges = emptyList())
        val result = broken.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.second == PlanValidator.Error.SCHEMA_MISMATCH })
    }

    @Test
    fun multipleErrorsAreReportedTogether() {
        val plan = plan(
            nodes = listOf(
                node("a", tool = "missing", version = null, ownerNamespaceOverride = "OTHER"),
                node("orphan", tool = "calendar.write", version = "v1"),
            ),
            edges = listOf(edge("a", "a")),
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        val kinds = result.errors.map { it.second }.toSet()
        assertTrue(kinds.contains(PlanValidator.Error.SELF_LOOP))
        assertTrue(kinds.contains(PlanValidator.Error.UNKNOWN_TOOL))
        assertTrue(kinds.contains(PlanValidator.Error.UNKNOWN_TOOL_VERSION))
        assertTrue(kinds.contains(PlanValidator.Error.CROSS_NAMESPACE))
        assertTrue(kinds.contains(PlanValidator.Error.ORPHAN_NODE))
    }

    // ---- helpers ----

    private fun plan(
        definitionId: String = "def-1",
        nodes: List<PlanValidator.NodeRef>,
        edges: List<PlanValidator.EdgeRef>,
        ownerNamespace: String = "USER",
    ): PlanValidator.PlanSnapshot {
        val def = PlanDefinitionEntity(
            definitionId = definitionId,
            versionId = "v-1",
            ownerNamespace = ownerNamespace,
            fingerprint = "fp-$definitionId",
            payloadJson = "{}",
            createdAtEpochMs = 1,
        )
        return PlanValidator.PlanSnapshot(definition = def, nodes = nodes, edges = edges)
    }

    private fun node(id: String, tool: String? = null, version: String? = null, ownerNamespaceOverride: String? = null): PlanValidator.NodeRef =
        PlanValidator.NodeRef(
            nodeId = id,
            nodeKey = "k-$id",
            nodeType = if (tool != null) "TOOL" else "CONTROL",
            toolName = tool,
            toolVersion = version,
            ownerNamespace = ownerNamespaceOverride ?: "USER",
        )

    private fun edge(from: String, to: String): PlanValidator.EdgeRef = PlanValidator.EdgeRef(edgeId = "e-$from-$to", fromNodeId = from, toNodeId = to)
}
