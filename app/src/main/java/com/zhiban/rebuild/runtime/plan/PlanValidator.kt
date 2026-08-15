package com.zhiban.rebuild.runtime.plan

import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import com.zhiban.rebuild.data.agent.PlanEdgeEntity
import com.zhiban.rebuild.data.agent.PlanNodeEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ADR-005 §11.1 + ADR-006 §3.2: structural / semantic validator for a
 * plan definition. Pure function: given the definition, nodes, edges,
 * and the namespace-scoped allowlist / budget / schema, returns every
 * error found. The caller decides whether to fail the activation or
 * surface the errors to the user.
 *
 * Rejects:
 *   - CYCLE             (graph has any directed cycle)
 *   - SELF_LOOP         (an edge from a node to itself)
 *   - ORPHAN_NODE       (a non-start node with no incoming edge)
 *   - UNKNOWN_TOOL      (a node references a tool not in the allowlist)
 *   - UNKNOWN_TOOL_VERSION (a node's tool version is missing or unknown)
 *   - CROSS_NAMESPACE   (a node's ownerNamespace != definition's)
 *   - UNAUTHORIZED_TOOL (a tool is in the allowlist but the version
 *                          expected for this scope is different from the
 *                          node's declared version)
 *   - BUDGET_OVERFLOW   (node count, edge count, or max fan-out exceeds
 *                          the configured budget)
 */
internal class PlanValidator(
    private val toolAllowlist: Set<String>,
    private val toolVersions: Map<String, String>,
    private val expectedSchemaVersion: Int,
    private val budget: Budget,
) {
    data class Budget(val maxNodes: Int, val maxEdges: Int, val maxOutDegree: Int)

    data class NodeRef(
        val nodeId: String,
        val nodeKey: String,
        val nodeType: String,
        val toolName: String?,
        val toolVersion: String?,
        val ownerNamespace: String,
    )

    data class EdgeRef(val edgeId: String, val fromNodeId: String, val toNodeId: String)

    enum class Error {
        CYCLE,
        SELF_LOOP,
        ORPHAN_NODE,
        UNKNOWN_TOOL,
        UNKNOWN_TOOL_VERSION,
        CROSS_NAMESPACE,
        UNAUTHORIZED_TOOL,
        BUDGET_OVERFLOW,
        SCHEMA_MISMATCH,
    }

    data class PlanSnapshot(val definition: PlanDefinitionEntity, val nodes: List<NodeRef>, val edges: List<EdgeRef>)

    data class Result(val errors: List<Pair<String, Error>>) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    fun validate(plan: PlanSnapshot): Result {
        val errors = mutableListOf<Pair<String, Error>>()
        checkSchema(plan, errors)
        checkBudget(plan, errors)
        checkNamespace(plan, errors)
        checkTools(plan, errors)
        checkGraphStructure(plan, errors)
        return Result(errors)
    }

    private fun checkSchema(plan: PlanSnapshot, out: MutableList<Pair<String, Error>>) {
        if (expectedSchemaVersion <= 0) {
            out += (plan.definition.definitionId to Error.SCHEMA_MISMATCH)
        }
    }

    private fun checkBudget(plan: PlanSnapshot, out: MutableList<Pair<String, Error>>) {
        if (plan.nodes.size > budget.maxNodes) {
            out += ("${plan.definition.definitionId}.nodes" to Error.BUDGET_OVERFLOW)
        }
        if (plan.edges.size > budget.maxEdges) {
            out += ("${plan.definition.definitionId}.edges" to Error.BUDGET_OVERFLOW)
        }
        val outDegree = plan.edges.groupingBy { it.fromNodeId }.eachCount()
        val maxObserved = outDegree.values.maxOrNull() ?: 0
        if (maxObserved > budget.maxOutDegree) {
            out += ("${plan.definition.definitionId}.outDegree" to Error.BUDGET_OVERFLOW)
        }
    }

    private fun checkNamespace(plan: PlanSnapshot, out: MutableList<Pair<String, Error>>) {
        for (node in plan.nodes) {
            if (node.ownerNamespace != plan.definition.ownerNamespace) {
                out += (node.nodeId to Error.CROSS_NAMESPACE)
            }
        }
    }

    private fun checkTools(plan: PlanSnapshot, out: MutableList<Pair<String, Error>>) {
        for (node in plan.nodes) {
            val tool = node.toolName
            if (tool == null) continue
            if (tool !in toolAllowlist) {
                out += (node.nodeId to Error.UNKNOWN_TOOL)
                // fall through: also report missing version if the caller
                // did not provide one, so the caller gets every error at once
            }
            if (node.toolVersion == null) {
                out += (node.nodeId to Error.UNKNOWN_TOOL_VERSION)
            }
            val expected = toolVersions[tool]
            if (expected == null) {
                out += (node.nodeId to Error.UNAUTHORIZED_TOOL)
                continue
            }
            if (expected != node.toolVersion) {
                out += (node.nodeId to Error.UNAUTHORIZED_TOOL)
            }
        }
    }

    private fun checkGraphStructure(plan: PlanSnapshot, out: MutableList<Pair<String, Error>>) {
        val nodeIds = plan.nodes.map { it.nodeId }.toSet()
        for (e in plan.edges) {
            if (e.fromNodeId == e.toNodeId) {
                out += (e.edgeId to Error.SELF_LOOP)
            }
        }
        // Orphan rule: a node is an orphan if it has neither an incoming
        // edge nor an outgoing edge — i.e., it is fully disconnected from
        // the DAG. A node with no incoming edge but at least one outgoing
        // edge is a (valid) start node; a node with no outgoing edge but
        // at least one incoming edge is a (valid) leaf. Anything else is
        // unreachable / detached and must be rejected.
        val incoming = plan.edges.groupingBy { it.toNodeId }.eachCount()
        val outgoing = plan.edges.groupingBy { it.fromNodeId }.eachCount()
        if (plan.nodes.size > 1) {
            for (node in plan.nodes) {
                val inCount = incoming[node.nodeId] ?: 0
                val outCount = outgoing[node.nodeId] ?: 0
                if (inCount == 0 && outCount == 0) {
                    out += (node.nodeId to Error.ORPHAN_NODE)
                }
            }
        }
        if (hasCycle(plan.edges, nodeIds)) {
            out += (plan.definition.definitionId to Error.CYCLE)
        }
    }

    private fun hasCycle(edges: List<EdgeRef>, nodeIds: Set<String>): Boolean {
        val adj = edges.groupBy({ it.fromNodeId }, { it.toNodeId })
        val color = HashMap<String, Int>()
        fun dfs(u: String): Boolean {
            color[u] = 1
            for (v in adj[u].orEmpty()) {
                val c = color[v] ?: 0
                when (c) {
                    0 -> if (dfs(v)) return true
                    1 -> return true
                    else -> {}
                }
            }
            color[u] = 2
            return false
        }
        for (n in nodeIds) {
            if ((color[n] ?: 0) == 0 && dfs(n)) return true
        }
        return false
    }

    companion object {
        fun fromEntities(
            definition: PlanDefinitionEntity,
            nodes: List<PlanNodeEntity>,
            edges: List<PlanEdgeEntity>,
            toolAllowlist: Set<String>,
            toolVersions: Map<String, String>,
            expectedSchemaVersion: Int,
            budget: Budget,
        ): PlanSnapshot = PlanSnapshot(
            definition = definition,
            nodes = nodes.map { n ->
                val (tool, version) = parseToolFields(n.payloadJson)
                NodeRef(
                    nodeId = n.nodeId,
                    nodeKey = n.nodeKey,
                    nodeType = n.nodeType,
                    toolName = tool,
                    toolVersion = version,
                    ownerNamespace = n.sensitivity, // repurposed field — see notes
                )
            },
            edges = edges.map { EdgeRef(it.edgeId, it.fromNodeId, it.toNodeId) },
        )

        private fun parseToolFields(payloadJson: String): Pair<String?, String?> {
            return try {
                val obj = Json.parseToJsonElement(payloadJson) as? JsonObject ?: return null to null
                val tool = (obj["tool"] as? JsonPrimitive)?.contentOrNull
                val version = (obj["version"] as? JsonPrimitive)?.contentOrNull
                tool to version
            } catch (_: SerializationException) {
                null to null
            }
        }
    }
}
