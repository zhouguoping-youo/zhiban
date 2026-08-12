package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactIdentityDao
import com.zhiban.rebuild.data.contact.ContactIdentityResolver
import com.zhiban.rebuild.data.contact.ContactIntelligenceDao
import com.zhiban.rebuild.data.contact.ContactKnowledgeDao
import com.zhiban.rebuild.data.contact.ContactMaintenanceEvaluator
import com.zhiban.rebuild.data.contact.ContactMaintenanceIssue
import com.zhiban.rebuild.data.contact.ContactMaintenanceOverview
import com.zhiban.rebuild.data.contact.IdentityResolutionDecision
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ContactSearchToolBinding(override val spec: RuntimeToolSpec, private val contacts: ContactDao) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("contact.search is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseContactArgs(request.argumentsJson)
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(query.isNotBlank() && query.length <= 100) { "INVALID_TOOL_ARGUMENTS" }
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val results = contacts.search(query, normalizeContactQuery(query), limit)
        val safe = buildJsonObject {
            put("query", query)
            put("count", results.size)
            put(
                "contacts",
                buildJsonArray {
                    results.forEach { item ->
                        add(
                            buildJsonObject {
                                put("contactId", item.contactId)
                                put("displayName", item.displayName)
                                item.phone?.let { put("phone", it) }
                                item.email?.let { put("email", it) }
                                item.wechatId?.let { put("wechatId", it) }
                                item.company?.let { put("company", it) }
                                item.title?.let { put("title", it) }
                                item.note?.let { put("note", it.take(500)) }
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }
}

internal class ContactDetailToolBinding(override val spec: RuntimeToolSpec, private val contacts: ContactDao) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("contact.getDetail is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseContactArgs(request.argumentsJson)
        val id = args["contactId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val contact = contacts.findById(id)
        val safe = if (contact == null) {
            buildJsonObject {
                put("found", false)
                put("contactId", id)
            }
        } else {
            buildJsonObject {
                put("found", true)
                put("contactId", contact.contactId)
                put("displayName", contact.displayName)
                contact.phone?.let { put("phone", it) }
                contact.email?.let { put("email", it) }
                contact.wechatId?.let { put("wechatId", it) }
                contact.company?.let { put("company", it) }
                contact.title?.let { put("title", it) }
                contact.note?.let { put("note", it.take(1000)) }
                put(
                    "roles",
                    buildJsonArray {
                        contacts.roles(id).forEach { role ->
                            add(
                                buildJsonObject {
                                    put("skillId", role.skillId)
                                    put("roleType", role.roleType)
                                    put("confidence", role.confidence)
                                    put("userConfirmed", role.userConfirmed)
                                },
                            )
                        }
                    },
                )
            }
        }
        return RoutedToolResult(spec.name, request.providerCallId, safe.toString())
    }
}

internal class ContactMaintenanceToolBinding(
    override val spec: RuntimeToolSpec,
    private val contacts: ContactDao,
    private val identities: ContactIdentityDao,
    private val intelligence: ContactIntelligenceDao,
    private val knowledge: ContactKnowledgeDao,
    private val ownerProfile: () -> ContactOwnerProfileSnapshot = { ContactOwnerProfileSnapshot() },
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("contact.maintenance.list is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseContactArgs(request.argumentsJson)
        val issue = args["issue"]?.jsonPrimitive?.content?.let { value ->
            runCatching { ContactMaintenanceIssue.valueOf(value) }.getOrNull()
                ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        }
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val snapshot = loadSnapshot(context.nowEpochMs)
        val actionableItems = snapshot.overview.items.filter { item ->
            item.issues.isNotEmpty() && (issue == null || issue in item.issues)
        }
        val items = actionableItems.take(limit)
        val unresolvedIdentities = if (issue == null) intelligence.listUnresolvedIdentities(limit) else emptyList()
        val unresolvedIdentityCount = if (issue == null) intelligence.countUnresolvedIdentities() else 0
        val result = buildJsonObject {
            put("totalContactCount", snapshot.overview.items.size)
            put("totalIssueCount", actionableItems.size)
            put("returnedCount", items.size)
            put("truncated", actionableItems.size > items.size)
            put("count", items.size)
            put("duplicateReviewCount", snapshot.overview.duplicateReviewCount)
            put("automaticallyResolvedDuplicateCount", snapshot.automaticallyResolvedDuplicateCount)
            put("userConfirmedMergeCount", snapshot.userConfirmedMergeCount)
            put("enrichmentReviewCount", snapshot.overview.enrichmentReviewCount)
            put("deferredRelationshipEvidenceCount", snapshot.deferredRelationshipEvidenceCount)
            put("unresolvedIdentityCount", unresolvedIdentityCount)
            put("unresolvedIdentityReturnedCount", unresolvedIdentities.size)
            put("unresolvedIdentityTruncated", unresolvedIdentityCount > unresolvedIdentities.size)
            put("ownerProfile", ownerProfileJson(ownerProfile(), snapshot.ownerLinks, snapshot.employments))
            put(
                "interactionPolicy",
                buildJsonObject {
                    put("askAtMostOneQuestion", true)
                    put("doNotAskEveryContactEmploymentDate", true)
                    put("deferUnknownRelationships", true)
                },
            )
            put(
                "issueCounts",
                buildJsonObject {
                    ContactMaintenanceIssue.entries.forEach { issueKind ->
                        put(issueKind.name, snapshot.overview.items.count { issueKind in it.issues })
                    }
                },
            )
            put(
                "items",
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("contactId", item.contact.contactId)
                                put("displayName", item.contact.displayName)
                                put("qualityScore", item.quality.score)
                                put(
                                    "issues",
                                    buildJsonArray {
                                        item.issues.sortedBy(ContactMaintenanceIssue::name).forEach { add(JsonPrimitive(it.name)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put(
                "unresolvedIdentities",
                buildJsonArray {
                    unresolvedIdentities.forEach { identity ->
                        add(
                            buildJsonObject {
                                put("sourceIdentityId", identity.sourceIdentityId)
                                put("platform", identity.sourceType)
                                put("visibleHandle", identity.visibleHandle)
                                identity.conversationScopeId?.let { put("conversationScope", it) }
                                put("confidence", identity.confidence)
                                put("instruction", "只根据更多证据核实归属，不得凭同名合并")
                            },
                        )
                    }
                },
            )
        }
        return RoutedToolResult(spec.name, request.providerCallId, result.toString())
    }

    private suspend fun loadSnapshot(nowEpochMs: Long): ContactMaintenanceSnapshot {
        val contactList = contacts.listActiveForIntelligence()
        val employments = intelligence.listAllEmployments()
        val platformIdentities = identities.listPlatformIdentities()
        val activeMergeLinks = identities.listActiveMergeLinks()
        val duplicateReviewCount = ContactIdentityResolver.resolve(
            contactList,
            identities.listAliases(),
            platformIdentities,
        ).count { it.decision == IdentityResolutionDecision.REVIEW }
        val overview = ContactMaintenanceEvaluator.evaluate(
            contacts = contactList,
            employments = employments,
            platformIdentities = platformIdentities,
            duplicateReviewCount = duplicateReviewCount,
            enrichmentReviewCount = knowledge.countAllPendingEnrichment(nowEpochMs),
            nowEpochMs = nowEpochMs,
        )
        return ContactMaintenanceSnapshot(
            overview = overview,
            employments = employments,
            ownerLinks = knowledge.listActiveOwnerContactLinks(),
            automaticallyResolvedDuplicateCount = activeMergeLinks.count { !it.userConfirmed },
            userConfirmedMergeCount = activeMergeLinks.count { it.userConfirmed },
            deferredRelationshipEvidenceCount = employments.asSequence()
                .filter { it.status == "ACTIVE" && it.currentState == "UNKNOWN" }
                .map(PersonEmploymentEpisodeEntity::personId)
                .distinct()
                .count(),
        )
    }
}

private data class ContactMaintenanceSnapshot(
    val overview: ContactMaintenanceOverview,
    val employments: List<PersonEmploymentEpisodeEntity>,
    val ownerLinks: List<OwnerContactLinkEntity>,
    val automaticallyResolvedDuplicateCount: Int,
    val userConfirmedMergeCount: Int,
    val deferredRelationshipEvidenceCount: Int,
)

internal data class ContactOwnerProfileSnapshot(val name: String = "", val occupations: Set<String> = emptySet(), val hasConfiguredIdentity: Boolean = false)

private fun ownerProfileJson(profile: ContactOwnerProfileSnapshot, ownerLinks: List<OwnerContactLinkEntity>, employments: List<PersonEmploymentEpisodeEntity>) =
    buildJsonObject {
        val ownerContactIds = ownerLinks.mapTo(hashSetOf(), OwnerContactLinkEntity::contactId)
        val ownerEmployments = employments.filter {
            it.status == "ACTIVE" && (it.personId == RelationshipPersonIds.SELF || it.personId in ownerContactIds)
        }
        val profilePresent = profile.hasConfiguredIdentity || profile.name.isNotBlank() || profile.occupations.isNotEmpty()
        val currentEmploymentConfirmed = ownerEmployments.any {
            it.currentState == "CURRENT" && it.verificationState == "USER_CONFIRMED"
        }
        put("identityKnown", profilePresent || ownerLinks.isNotEmpty())
        profile.name.takeIf(String::isNotBlank)?.let { put("knownName", it) }
        put("contactCardLinked", ownerLinks.isNotEmpty())
        put("linkedContactCount", ownerLinks.size)
        put("knownOccupations", buildJsonArray { profile.occupations.sorted().forEach { add(JsonPrimitive(it)) } })
        put("currentEmploymentConfirmed", currentEmploymentConfirmed)
        put("hasKnownEmploymentDates", ownerEmployments.any { it.validFromEpochMs != null || it.validToEpochMs != null })
        put("relationshipClassificationReady", profilePresent || ownerLinks.isNotEmpty())
        put("workRelationshipClassificationReady", (profilePresent || ownerLinks.isNotEmpty()) && currentEmploymentConfirmed)
        put(
            "relationshipPrerequisites",
            buildJsonObject {
                RelationshipGroup.entries.forEach { group ->
                    put(
                        group.name,
                        buildJsonObject {
                            put("guidance", RelationshipTaxonomy.groupGuidance(group))
                            put("requiresCurrentEmployment", group == RelationshipGroup.WORK)
                        },
                    )
                }
            },
        )
        put(
            "nextStep",
            when {
                !profilePresent && ownerLinks.isEmpty() -> "本轮只问：应该如何称呼你？"

                !currentEmploymentConfirmed ->
                    "非工作关系可按各自证据继续整理；仅当判断同事或上下级时，再询问你目前任职的公司全称"

                else -> "可依据双方时间证据判断当前或历史关系；证据不足的联系人保持待发现"
            },
        )
        put(
            "employments",
            buildJsonArray {
                ownerEmployments.forEach { employment ->
                    add(
                        buildJsonObject {
                            put("company", employment.companyNameSnapshot)
                            employment.title?.let { put("title", it) }
                            employment.validFromEpochMs?.let { put("validFromEpochMs", it) }
                            employment.validToEpochMs?.let { put("validToEpochMs", it) }
                            put("currentState", employment.currentState)
                            put("verificationState", employment.verificationState)
                        },
                    )
                }
            },
        )
    }

internal fun normalizeContactQuery(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase()

private fun parseContactArgs(value: String) = runCatching {
    Json.parseToJsonElement(value).jsonObject
}.getOrElse { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
