package com.zhiban.rebuild.runtime.plan

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.PLAN_STATUS_ACTIVE
import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import com.zhiban.rebuild.data.agent.PlanEdgeEntity
import com.zhiban.rebuild.data.agent.PlanNodeEntity
import com.zhiban.rebuild.data.agent.PlanRunEntity
import com.zhiban.rebuild.data.agent.PlanVersionEntity
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.runtime.tool.RuntimeToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class RuntimePlanStep(
    val runId: String,
    val attemptId: String,
    val providerCallId: String,
    val logicalStepId: String,
    val toolName: String,
    val toolSpecVersion: Int,
    val requiresApproval: Boolean,
    val inputDigest: String,
    val nowEpochMs: Long,
)

/**
 * Persists every executed or proposed tool step in one bounded, validated DAG.
 *
 * The provider may refine its next step after each observation, so this is an execution-backed plan:
 * each committed node is a real step, the edge is its observed dependency, and validation happens
 * inside the caller's Room transaction before any plan row is written.
 */
internal class RuntimePlanRecorder(private val database: AgentDatabase, private val catalog: RuntimeToolCatalog = RuntimeToolCatalog.production()) {
    suspend fun record(step: RuntimePlanStep) {
        val dao = database.planDao()
        val definition = definition(step)
        val existingNodes = dao.nodesForDefinition(definition.definitionId)
        val existingEdges = dao.edgesForDefinition(definition.definitionId)
        val node = node(step, definition.definitionId)
        val alreadyRecorded = existingNodes.any { it.nodeId == node.nodeId }
        val candidateNodes = if (alreadyRecorded) existingNodes else existingNodes + node
        val candidateEdges = if (alreadyRecorded || existingNodes.isEmpty()) {
            existingEdges
        } else {
            existingEdges + edge(definition.definitionId, requireNotNull(leaf(existingNodes, existingEdges)), node, existingEdges.size)
        }
        validate(definition, candidateNodes, candidateEdges, step)

        dao.insertVersionIgnore(
            PlanVersionEntity(VERSION_ID, SCHEMA_VERSION, step.nowEpochMs, "Runtime execution-backed plan"),
        )
        dao.insertDefinitionIgnore(definition)
        if (!alreadyRecorded) {
            dao.insertNodeIgnore(node)
            candidateEdges.lastOrNull()?.takeIf { it !in existingEdges }?.let { dao.insertEdgeIgnore(it) }
        }
        dao.insertRunIgnore(
            PlanRunEntity(step.runId, definition.definitionId, PLAN_STATUS_ACTIVE, step.attemptId, step.nowEpochMs, null),
        )
        val run = requireNotNull(dao.runById(step.runId))
        check(run.definitionId == definition.definitionId && run.runStatus == PLAN_STATUS_ACTIVE) {
            "RUNTIME_PLAN_NOT_ACTIVE"
        }
        check(dao.updateActiveAttempt(step.runId, PLAN_STATUS_ACTIVE, step.attemptId) == 1) {
            "RUNTIME_PLAN_ATTEMPT_NOT_UPDATED"
        }
    }

    private fun validate(definition: PlanDefinitionEntity, nodes: List<PlanNodeEntity>, edges: List<PlanEdgeEntity>, step: RuntimePlanStep) {
        val versions = catalog.specs.mapValues { it.value.version.toString() }.toMutableMap()
        nodes.forEach { node ->
            val payload = Json.parseToJsonElement(node.payloadJson).jsonObject
            val tool = payload["tool"]?.jsonPrimitive?.content ?: return@forEach
            val version = payload["version"]?.jsonPrimitive?.content ?: return@forEach
            if (tool !in versions) versions[tool] = version
        }
        if (step.toolName !in versions) versions[step.toolName] = step.toolSpecVersion.toString()
        val snapshot = PlanValidator.fromEntities(
            definition,
            nodes,
            edges,
            versions.keys,
            versions,
            SCHEMA_VERSION,
            PlanValidator.Budget(MAX_NODES, MAX_NODES - 1, 1),
        )
        val result = PlanValidator(versions.keys, versions, SCHEMA_VERSION, PlanValidator.Budget(MAX_NODES, MAX_NODES - 1, 1))
            .validate(snapshot)
        check(result.isValid) {
            "RUNTIME_PLAN_INVALID:${result.errors.map { it.second.name }.distinct().sorted().joinToString(",")}"
        }
    }

    private fun definition(step: RuntimePlanStep): PlanDefinitionEntity = PlanDefinitionEntity(
        definitionId = "runtime-plan-${step.runId}",
        versionId = VERSION_ID,
        ownerNamespace = OWNER_NAMESPACE,
        fingerprint = sha256("definition:${step.runId}"),
        payloadJson = buildJsonObject {
            put("runtimeRunId", step.runId)
            put("planningMode", "EXECUTION_BACKED_DAG")
            put("schemaVersion", SCHEMA_VERSION)
        }.toString(),
        createdAtEpochMs = step.nowEpochMs,
    )

    private fun node(step: RuntimePlanStep, definitionId: String): PlanNodeEntity = PlanNodeEntity(
        nodeId = "node-${sha256("${step.runId}:${step.providerCallId}").take(24)}",
        definitionId = definitionId,
        nodeKey = step.logicalStepId,
        nodeType = "TOOL",
        payloadJson = buildJsonObject {
            put("tool", step.toolName)
            put("version", step.toolSpecVersion.toString())
            put("providerCallId", step.providerCallId)
            put("inputDigest", step.inputDigest)
        }.toString(),
        requiresApproval = step.requiresApproval,
        sensitivity = OWNER_NAMESPACE,
        createdAtEpochMs = step.nowEpochMs,
    )

    private fun leaf(nodes: List<PlanNodeEntity>, edges: List<PlanEdgeEntity>): PlanNodeEntity? {
        val parents = edges.mapTo(mutableSetOf()) { it.fromNodeId }
        return nodes.singleOrNull { it.nodeId !in parents } ?: nodes.lastOrNull()
    }

    private fun edge(definitionId: String, previous: PlanNodeEntity, current: PlanNodeEntity, ordinal: Int) = PlanEdgeEntity(
        edgeId = "edge-${sha256("${previous.nodeId}:${current.nodeId}").take(24)}",
        definitionId = definitionId,
        fromNodeId = previous.nodeId,
        toNodeId = current.nodeId,
        condition = "after_success",
        ordinal = ordinal,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
        const val VERSION_ID = "runtime-plan-schema-v1"
        const val OWNER_NAMESPACE = "runtime"
        const val MAX_NODES = 12
    }
}
