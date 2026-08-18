package com.zhiban.rebuild.runtime.context

import com.zhiban.agent.memory.MemoryQuery
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.contact.ContactSearchProjection
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.searchNatural
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal class RoomContextRetrievalPipeline(
    private val database: AgentDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val messageCollectionPreferences: MessageCollectionPreferences? = null,
    private val embeddingGateway: EmbeddingGateway? = null,
    private val pathTimeoutMs: Long = 500L,
    private val pathDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun retrieve(
        inputText: String,
        queryContext: QueryContext,
        includeMemory: Boolean,
        tokenBudget: Int = 1_200,
        allowRemoteVector: Boolean = true,
        remoteVectorSkipReason: String = "weak_network",
        recallLimit: Int = 20,
    ): ContextRetrievalResult = coroutineScope {
        require(tokenBudget in 0..32_768)
        require(recallLimit in 1..100)
        val degradation = mutableListOf<String>()
        val structured = withTimeoutOrNull(STRUCTURED_TIMEOUT_MS) { structuredCandidates(queryContext) }
            ?: emptyList<RetrievalCandidate>().also { degradation += "structured_filter_timeout" }
        messageCollectionPreferences?.consumeNotificationGapReason()?.let(degradation::add)
        val query = retrievalQuery(inputText, queryContext)
        val contactJob = async(pathDispatcher) { path("contact_fts") { contactCandidates(queryContext, query) } }
        val scheduleJob = async(pathDispatcher) { path("schedule_fts") { scheduleCandidates(queryContext, query) } }
        val memoryJob = async(pathDispatcher) {
            if (!includeMemory) {
                PathResult(emptyList())
            } else {
                val searched = pathResult("memory_fts") { memoryCandidates(inputText) }
                if (searched.items.isNotEmpty() || queryContext.intentLabel != IntentLabel.MEMORY_QUERY) {
                    searched
                } else {
                    val recent = path("memory_recent") { recentMemoryCandidates() }
                    if (recent.items.isEmpty()) {
                        recent
                    } else {
                        PathResult(recent.items, recent.degradations + "memory_recent_fallback")
                    }
                }
            }
        }
        val factJob = async(pathDispatcher) { path("fact_fulltext") { factCandidates(query) } }
        val vectorJob = async(pathDispatcher) {
            if (!allowRemoteVector) {
                return@async PathResult(
                    emptyList(),
                    listOf("vector_skipped:$remoteVectorSkipReason"),
                )
            }
            val gateway = embeddingGateway
                ?: return@async PathResult(emptyList(), listOf("vector_skipped:not_configured"))
            val attempted = attemptRetrieval("vector_search", pathTimeoutMs) {
                EmbeddingIndex(database, gateway, clock).search(inputText, 20)
            }
            val result = attempted.value
                ?: return@async PathResult(emptyList(), listOf(requireNotNull(attempted.degradation)))
            PathResult(result.candidates, listOfNotNull(result.degradation))
        }
        val graphJob = async(pathDispatcher) {
            val anchors = queryContext.entities.mapNotNull { it.linkedId }.distinct()
            if (anchors.isEmpty()) {
                PathResult(emptyList(), listOf("graph_skipped:no_linked_entity"))
            } else {
                path("graph_2hop") { graphCandidates(anchors) }
            }
        }
        val contact = contactJob.await()
        val schedule = scheduleJob.await()
        val memory = memoryJob.await()
        val fact = factJob.await()
        val vector = vectorJob.await()
        val graph = graphJob.await()
        listOf(contact, schedule, memory, fact, vector, graph)
            .flatMap(PathResult::degradations)
            .forEach(degradation::add)
        val merged = reciprocalRankFusion(
            listOf(
                RetrievalPath.STRUCTURED to structured,
                RetrievalPath.FTS to contact.items,
                RetrievalPath.FTS to schedule.items,
                RetrievalPath.FTS to memory.items,
                RetrievalPath.FTS to fact.items,
                RetrievalPath.VECTOR to vector.items,
                RetrievalPath.GRAPH to graph.items,
            ),
            limit = recallLimit,
        )
        var usedTokens = 0
        val selected = merged.mapNotNull { item ->
            val cost = estimateTokens(item.candidate.summary)
            if (usedTokens + cost > tokenBudget) null else item.also { usedTokens += cost }
        }.take(MAX_CONTEXT_ITEMS)
        ContextRetrievalResult(selected, structured.size, degradation.distinct(), usedTokens)
    }

    private suspend fun structuredCandidates(context: QueryContext): List<RetrievalCandidate> {
        val ownerEmployments = database.contactIntelligenceDao().listConfirmedOwnerEmployments(RelationshipPersonIds.SELF)
            .asSequence()
            .distinctBy { employment ->
                listOf(
                    employment.companyNameSnapshot.trim().lowercase(),
                    employment.currentState,
                    employment.validFromEpochMs,
                    employment.validToEpochMs,
                )
            }
            .take(MAX_OWNER_EMPLOYMENTS)
            .map { employment ->
                RetrievalCandidate(
                    id = "owner-employment:${employment.episodeId}",
                    sourceKind = "owner_employment",
                    sourceRef = employment.episodeId,
                    summary = buildString {
                        append(if (employment.currentState == "CURRENT") "用户本人当前任职" else "用户本人过往任职")
                        append("：公司=").append(employment.companyNameSnapshot)
                        employment.title?.takeIf(String::isNotBlank)?.let { append("，职位=").append(it) }
                    },
                    entityRefs = listOf(RelationshipPersonIds.SELF),
                    timestampEpochMs = employment.updatedAtEpochMs,
                    sensitivity = Sensitivity.PERSONAL,
                )
            }
            .toList()
        // 两步批量解析替代逐实体 findById(N 条单查→2 条查询,P2-structured 路):输入 id 先解析
        // 到 canonical(合并源→canonical),再一次 findByIds 取全。
        val linkedIds = context.entities.mapNotNull { it.linkedId }.distinct()
        val canonicalById = database.contactDao().resolveCanonicalIds(linkedIds)
            .associate { it.inputId to it.canonicalId }
        val byId = database.contactDao().findByIds(canonicalById.values.distinct())
            .associateBy { it.contactId }
        val contacts = linkedIds.mapNotNull { id ->
            canonicalById[id]?.let(byId::get)?.let { contact ->
                RetrievalCandidate(
                    "contact:${contact.contactId}",
                    "contact",
                    contact.contactId,
                    contactSummary(contact.displayName, contact.phone, contact.email, contact.company, contact.title),
                    entityRefs = listOf(contact.contactId),
                    timestampEpochMs = contact.updatedAtEpochMs,
                )
            }
        }
        val schedules = context.timeRange?.let { range ->
            database.scheduleDao().listRange(
                range.startEpochMs,
                range.endExclusiveEpochMs - 1,
                50,
            ).map(::scheduleCandidate)
        }.orEmpty()
        return ownerEmployments + contacts + schedules
    }

    private suspend fun contactCandidates(context: QueryContext, query: String): List<RetrievalCandidate> {
        if (context.intentLabel !in
            setOf(
                IntentLabel.CONTACT_QUERY,
                IntentLabel.CONTACT_CREATE,
                IntentLabel.GENERAL_WORK,
                IntentLabel.SALES_CRM,
                IntentLabel.PERSONAL_LIFE,
                IntentLabel.SOCIAL_PLANNING,
            ) &&
            context.entities.none { it.type == ExtractedEntityType.PERSON }
        ) {
            return emptyList()
        }
        if (query.isBlank()) return emptyList()
        return database.contactDao().searchNatural(query, 20).map { contact ->
            RetrievalCandidate(
                "contact:${contact.contactId}",
                "contact",
                contact.contactId,
                contactSummary(contact.displayName, contact.phone, contact.email, contact.company, contact.title),
                entityRefs = listOf(contact.contactId),
            )
        }
    }

    private suspend fun scheduleCandidates(context: QueryContext, query: String): List<RetrievalCandidate> {
        if (context.intentLabel !in
            setOf(
                IntentLabel.CALENDAR_QUERY,
                IntentLabel.CALENDAR_CREATE,
                IntentLabel.GENERAL_WORK,
                IntentLabel.PERSONAL_LIFE,
                IntentLabel.SOCIAL_PLANNING,
            ) &&
            context.timeRange == null
        ) {
            return emptyList()
        }
        val from = context.timeRange?.startEpochMs ?: 0L
        val to = context.timeRange?.endExclusiveEpochMs ?: Long.MAX_VALUE
        val scheduleQuery = context.keywords.firstOrNull { it in setOf("项目", "会议", "日程", "提醒") }.orEmpty()
        return database.scheduleDao().searchRange(
            scheduleQuery.ifBlank {
                query.takeIf { it.length <= 32 }.orEmpty()
            },
            from,
            to,
            20,
        ).map(::scheduleCandidate)
    }

    private suspend fun memoryCandidates(query: String): PathResult {
        val result = RoomMemoryGate(database, clock)
            .search(MemoryQuery("runtime-global", "local-user", "default", query, 20, 600))
        return PathResult(
            result.items.map { memory ->
                RetrievalCandidate(
                    "memory:${memory.memoryId}",
                    "memory",
                    memory.memoryId,
                    memory.canonicalText,
                    memory.sourceRefs,
                    sensitivity = memory.sensitivity.toSensitivity(),
                )
            },
            result.degradationReasons,
        )
    }

    private suspend fun recentMemoryCandidates(): List<RetrievalCandidate> {
        val now = clock()
        return database.memoryPersistenceDao().recall("runtime-global", now).take(10).map { memory ->
            RetrievalCandidate(
                "memory:${memory.memoryId}",
                "memory",
                memory.memoryId,
                memory.canonicalText,
                sensitivity = memory.sensitivity.toSensitivity(),
            )
        }
    }

    private fun factCandidates(query: String): List<RetrievalCandidate> = FactIndex(database).search(query, clock(), 20).map { fact ->
        RetrievalCandidate(
            id = fact.factId,
            sourceKind = fact.factType.lowercase(),
            sourceRef = fact.factId.substringAfter(':', fact.factId),
            summary = fact.textContent,
            entityRefs = listOfNotNull(fact.contactId),
            timestampEpochMs = fact.updatedAtEpochMs,
            sensitivity = fact.sensitivity.toSensitivity(),
        )
    }

    private suspend fun graphCandidates(anchorIds: List<String>): List<RetrievalCandidate> {
        val dao = database.relationshipEdgeDao()
        val first = dao.touching(anchorIds, 20)
        val firstNeighbors = first.flatMap {
            listOf(it.fromContactId, it.toContactId)
        }.filterNot(anchorIds::contains).distinct()
        val second = if (firstNeighbors.isEmpty()) emptyList() else dao.touching(firstNeighbors, 40)
        val edges = (first + second).distinctBy { it.edgeId }.take(40)
        if (edges.isEmpty()) return emptyList()
        val contacts = dao.contactSummaries(edges.flatMap { listOf(it.fromContactId, it.toContactId) }.distinct())
            .associateBy { it.contactId }
        return edges.mapNotNull { edge ->
            val from = contacts[edge.fromContactId] ?: return@mapNotNull null
            val to = contacts[edge.toContactId] ?: return@mapNotNull null
            RetrievalCandidate(
                id = "relationship:${edge.edgeId}",
                sourceKind = "relationship",
                sourceRef = edge.edgeId,
                summary = "${from.displayName} 与 ${to.displayName} 的关系=${edge.relationType}（置信度=${edge.confidence}）",
                entityRefs = listOf(edge.fromContactId, edge.toContactId),
                timestampEpochMs = edge.updatedAtEpochMs,
                sensitivity = Sensitivity.SENSITIVE,
            )
        }
    }

    private suspend fun path(name: String, block: suspend () -> List<RetrievalCandidate>): PathResult {
        val result = attemptRetrieval(name, pathTimeoutMs, block)
        return PathResult(result.value.orEmpty(), listOfNotNull(result.degradation))
    }

    private suspend fun pathResult(name: String, block: suspend () -> PathResult): PathResult {
        val result = attemptRetrieval(name, pathTimeoutMs, block)
        return result.value ?: PathResult(emptyList(), listOf(requireNotNull(result.degradation)))
    }

    private fun retrievalQuery(input: String, context: QueryContext): String = context.entities.firstOrNull { it.type == ExtractedEntityType.PERSON }?.value
        ?: context.keywords.firstOrNull()
        ?: input.trim().take(64)

    private fun scheduleCandidate(schedule: ScheduleProjection) = RetrievalCandidate(
        "schedule:${schedule.id}",
        "schedule",
        schedule.id,
        buildString {
            append(schedule.title).append("，开始时间=").append(schedule.startAtEpochMs)
            append("，时长=").append(schedule.durationMinutes).append("分钟")
            schedule.note?.takeIf(String::isNotBlank)?.let { append("，备注=").append(it) }
        },
        timestampEpochMs = schedule.startAtEpochMs,
    )

    private fun contactSummary(name: String, phone: String?, email: String?, company: String?, title: String?) = buildString {
        append(name)
        company?.takeIf(String::isNotBlank)?.let { append("，公司=").append(it) }
        title?.takeIf(String::isNotBlank)?.let { append("，职位=").append(it) }
        phone?.takeIf(String::isNotBlank)?.let { append("，电话=").append(it) }
        email?.takeIf(String::isNotBlank)?.let { append("，邮箱=").append(it) }
    }

    private fun estimateTokens(value: String) = ceil(value.toByteArray().size / 4.0).toInt().coerceAtLeast(1)

    // "NORMAL" is a legacy free-text label for ordinary personal facts, not a Sensitivity
    // enum value; mapping it to SENSITIVE wrongly omitted benign schedule/contact facts.
    private fun String.toSensitivity(): Sensitivity = when (uppercase()) {
        "PUBLIC" -> Sensitivity.PUBLIC
        "PERSONAL" -> Sensitivity.PERSONAL
        "NORMAL" -> Sensitivity.PERSONAL
        "SENSITIVE" -> Sensitivity.SENSITIVE
        else -> Sensitivity.SENSITIVE
    }
    private data class PathResult(val items: List<RetrievalCandidate>, val degradations: List<String> = emptyList())

    private companion object {
        const val STRUCTURED_TIMEOUT_MS = 30L
        const val MAX_CONTEXT_ITEMS = 15
        const val MAX_OWNER_EMPLOYMENTS = 8
    }
}
