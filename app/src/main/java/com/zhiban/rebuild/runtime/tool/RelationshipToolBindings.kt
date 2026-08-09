package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.RelationshipEdgeDao
import com.zhiban.rebuild.data.contact.RelationshipEventDao
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.runtime.governance.RelationshipCandidateCall
import com.zhiban.rebuild.runtime.governance.RelationshipDomainWriter
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class RelationshipSearchToolBinding(
    override val spec: RuntimeToolSpec,
    private val relationships: RelationshipEdgeDao,
    private val events: RelationshipEventDao? = null,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("relationship.search is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseToolArgs(request.argumentsJson, setOf("contactId", "maxDepth", "limit")) {
            throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        }
        if (args.keys.any {
                it !in setOf("contactId", "maxDepth", "limit")
            }
        ) {
            throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        }
        val root = args["contactId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val depth = args["maxDepth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 2
        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        require(depth in 1..2 && limit in 1..50) { "INVALID_TOOL_ARGUMENTS" }
        val canonicalRoot = relationships.resolveCanonicalContactId(root)
        val first = relationships.touching(listOf(canonicalRoot), limit)
        val neighbors = first.flatMap {
            listOf(it.fromContactId, it.toContactId)
        }.filterNot { it == canonicalRoot }.distinct()
        val second = if (depth == 2 && neighbors.isNotEmpty()) relationships.touching(neighbors, limit) else emptyList()
        val edges = (first + second).distinctBy { it.edgeId }.take(limit)
        val contacts = relationships.contactSummaries(
            edges.flatMap {
                listOf(it.fromContactId, it.toContactId)
            }.distinct(),
        )
            .associateBy { it.contactId }
        val relatedEvents = events?.listForContact(canonicalRoot, limit).orEmpty()
        val canonicalParticipantIds = relatedEvents.flatMap { value -> value.participants.mapNotNull { it.contactId } }
            .distinct()
            .associateWith { contactId -> relationships.resolveCanonicalContactId(contactId) }
        val safe = buildJsonObject {
            put("rootContactId", canonicalRoot)
            put("maxDepth", depth)
            put("count", edges.size)
            put("edges", edgesJsonArray(edges, contacts))
            put("events", eventsJsonArray(relatedEvents, canonicalParticipantIds))
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }

    private fun edgesJsonArray(
        edges: List<com.zhiban.rebuild.data.contact.RelationshipEdgeEntity>,
        contacts: Map<String, com.zhiban.rebuild.data.contact.ContactSearchProjection>,
    ): kotlinx.serialization.json.JsonArray = buildJsonArray {
        edges.forEach { edge ->
            add(
                buildJsonObject {
                    put("edgeId", edge.edgeId)
                    put("fromContactId", edge.fromContactId)
                    put("toContactId", edge.toContactId)
                    put(
                        "fromName",
                        if (edge.fromContactId == RelationshipPersonIds.SELF) {
                            "我"
                        } else {
                            contacts[edge.fromContactId]?.displayName.orEmpty()
                        },
                    )
                    put(
                        "toName",
                        if (edge.toContactId == RelationshipPersonIds.SELF) {
                            "我"
                        } else {
                            contacts[edge.toContactId]?.displayName.orEmpty()
                        },
                    )
                    put("relationType", edge.relationType)
                    put("confidence", edge.confidence)
                    put("userConfirmed", edge.userConfirmed)
                    edge.skillId?.let { put("skillId", it) }
                    // Evidence text is intentionally excluded. The digest proves provenance without leaking source content.
                    put("evidenceDigest", edge.evidenceDigest)
                },
            )
        }
    }

    private fun eventsJsonArray(
        relatedEvents: List<com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants>,
        canonicalParticipantIds: Map<String, String>,
    ): kotlinx.serialization.json.JsonArray = buildJsonArray {
        relatedEvents.forEach { value ->
            add(
                buildJsonObject {
                    put("eventId", value.event.eventId)
                    put("eventType", value.event.eventType)
                    put("title", value.event.title)
                    put("userConfirmed", value.event.userConfirmed)
                    put(
                        "participants",
                        buildJsonArray {
                            value.participants.forEach { participant ->
                                add(
                                    buildJsonObject {
                                        put("kind", participant.participantKind)
                                        participant.contactId?.let {
                                            put(
                                                "contactId",
                                                canonicalParticipantIds[it] ?: it,
                                            )
                                        }
                                        put("role", participant.participantRole)
                                        put("displayName", participant.displayNameSnapshot)
                                    },
                                )
                            }
                        },
                    )
                    put("evidenceDigest", value.event.evidenceDigest)
                },
            )
        }
    }
}

internal class RelationshipCreateCandidateToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val writer: RelationshipDomainWriter,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val args = parseToolArgs(
            request.argumentsJson,
            setOf("fromContactId", "toContactId", "relationType", "evidenceSummary", "confidence", "skillId"),
        ) { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
        fun required(name: String, max: Int) = args[name]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= max } ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val from = required("fromContactId", 128)
        val to = required("toContactId", 128)
        require(from != to) { "INVALID_TOOL_ARGUMENTS" }
        val relation = required("relationType", 40)
        require(relation in ALLOWED_RELATIONS) { "INVALID_TOOL_ARGUMENTS" }
        val evidence = required("evidenceSummary", 1_000)
        val confidence = args["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.7
        require(confidence in 0.0..1.0) { "INVALID_TOOL_ARGUMENTS" }
        val skillId = args["skillId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            ?.also { require(it.length <= 100) { "INVALID_TOOL_ARGUMENTS" } }
        val digest = sha256(
            buildJsonObject {
                put("fromContactId", from)
                put("toContactId", to)
                put("relationType", relation)
                put("evidenceDigest", sha256(evidence))
                put("confidence", confidence)
                skillId?.let { put("skillId", it) }
            }.toString(),
        )
        val envelope = PlanEnvelopeFactory.create(
            request,
            context,
            "relationship.createCandidate",
            digest,
            "relationship-stage",
        )
        val call = RelationshipCandidateCall(
            providerCallId = request.providerCallId,
            logicalStepId = "step-${request.providerCallId}",
            proposalId = envelope.proposalId,
            payloadRef = envelope.payloadRef,
            revision = context.revision,
            canonicalInputDigest = digest,
            idempotencyKey = envelope.idempotencyKey,
            edgeId = "edge-${sha256("${context.runId}:${request.providerCallId}:$digest").take(24)}",
            fromContactId = from, toContactId = to, relationType = relation,
            evidenceDigest = sha256(evidence), confidence = confidence, skillId = skillId,
        )
        return store.requestRelationshipApproval(
            call,
            context.sessionId,
            context.runId,
            context.attemptId,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val value = parseToolArgs(planJson, null) { IllegalArgumentException("INVALID_TOOL_CALL") }
        fun required(name: String) = value[name]?.jsonPrimitive?.content ?: error("INVALID_TOOL_CALL")
        val call = RelationshipCandidateCall(
            required("providerCallId"), required("logicalStepId"), required("proposalId"), required("payloadRef"),
            required("revision").toLong(), required("canonicalInputDigest"), required("idempotencyKey"),
            required("edgeId"), required("fromContactId"), required("toContactId"), required("relationType"),
            required("evidenceDigest"), required("confidence").toDouble(), value["skillId"]?.jsonPrimitive?.content,
        )
        val result = writer.execute(
            context,
            call,
            ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest),
        )
        return RoutedToolResult(spec.name, call.providerCallId, result.safeResultJson)
    }

    private companion object {
        val ALLOWED_RELATIONS =
            setOf("FAMILY", "FRIEND", "COLLEAGUE", "CUSTOMER", "SUPPLIER", "TEACHER", "CLASSMATE", "PROJECT_PARTNER", "OTHER")
    }
}

internal class RelationshipEvidenceToolBinding(override val spec: RuntimeToolSpec, private val relationships: RelationshipEdgeDao) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("relationship.getEvidence is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseToolArgs(request.argumentsJson, setOf("edgeId")) { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
        val edgeId = args["edgeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val edge = relationships.find(edgeId) ?: throw IllegalArgumentException("RELATIONSHIP_NOT_FOUND")
        val sourceTypes = runSuspendCatching {
            Json.parseToJsonElement(edge.evidenceRefsJson).let { element ->
                (element as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { ref ->
                    ref.jsonPrimitive.content.substringBefore(':').takeIf(String::isNotBlank)
                }.distinct()
            }
        }.getOrDefault(emptyList())
        val safe = buildJsonObject {
            put("edgeId", edge.edgeId)
            put("evidenceDigest", edge.evidenceDigest)
            put("sourceTypes", buildJsonArray { sourceTypes.forEach { add(JsonPrimitive(it)) } })
            put("confidence", edge.confidence)
            put("userConfirmed", edge.userConfirmed)
            put("status", edge.status)
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }
}
