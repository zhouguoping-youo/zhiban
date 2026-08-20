package com.zhiban.rebuild.data.completion

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.ActionDecision
import com.zhiban.rebuild.data.autowrite.ActionPolicy
import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames
import com.zhiban.rebuild.data.autowrite.ReversibleWriteReadiness
import com.zhiban.rebuild.data.autowrite.insertVisibleAutoWrite
import com.zhiban.rebuild.data.autowrite.recordWriteVerificationFailure
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.enrichment.COMPLETION_FIELD_NAMES
import com.zhiban.rebuild.data.contact.enrichment.canonicalContactCompletionDigest
import com.zhiban.rebuild.data.contact.enrichment.contactField
import com.zhiban.rebuild.foundation.RuntimeToolRisk
import com.zhiban.rebuild.foundation.changeIdFor
import com.zhiban.rebuild.foundation.sha256
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Applies deterministic contact replies as visible, reversible writes without overwriting user data. */
internal class ContactCompletionAutoWriter @Inject constructor(private val database: AgentDatabase) {
    suspend fun apply(
        request: ContactCompletionRequestEntity,
        candidate: ContactEnrichmentCandidateEntity,
        pendingCandidates: List<ContactEnrichmentCandidateEntity>,
        nowEpochMs: Long,
    ): Boolean {
        if (!candidate.isEligible()) return false
        if (ActionPolicy().evaluate(
                RuntimeToolRisk.REVERSIBLE_AUTO_WRITE,
                reversibleWriteReadiness = ReversibleWriteReadiness.Ready,
            ) != ActionDecision.AutoExecuteReversibleWrite
        ) {
            return false
        }
        val patch = decodePatch(candidate.proposedValueJson) ?: return false
        val idempotencyKey = sha256("contact-completion-reply:${candidate.candidateId}")
        return try {
            database.withTransaction {
                val knowledge = database.contactKnowledgeDao()
                if (database.changeLogDao().findByIdempotencyKey(idempotencyKey) != null) {
                    knowledge.upsertEnrichmentCandidate(candidate.copy(status = STATUS_APPROVED, updatedAtEpochMs = nowEpochMs))
                    pendingCandidates.forEach { knowledge.upsertEnrichmentCandidate(it) }
                    database.contactCompletionRequestDao().markResponseReceived(request.requestId, candidate.candidateId, nowEpochMs)
                    return@withTransaction true
                }
                val current = database.contactDao().findRawById(request.contactId) ?: return@withTransaction false
                val updated = current.applyMissing(patch, nowEpochMs)
                if (updated != current) {
                    val before = canonicalContactCompletionDigest(current)
                    val after = canonicalContactCompletionDigest(updated)
                    database.contactDao().update(updated)
                    val persisted = database.contactDao().findRawById(current.contactId)
                    check(persisted != null && persisted.matches(updated)) { "CONTACT_WRITE_VERIFY_FAILED" }
                    database.insertVisibleAutoWrite(
                        AutoWriteAuditDraft(
                            changeId = changeIdFor(idempotencyKey),
                            runtimeRunId = null,
                            toolName = AutoWriteToolNames.CONTACT_COMPLETION,
                            idempotencyKey = idempotencyKey,
                            targetDomain = "CONTACT",
                            targetId = current.contactId,
                            operation = "UPDATE",
                            beforeDigest = before,
                            afterDigest = after,
                            inversePayloadJson = inversePayload(current),
                            originType = "SYSTEM_PERCEPTION",
                            subjectContactId = current.contactId,
                            sourceType = "CONTACT_REPLY",
                            sourceRef = candidate.sourceRef.orEmpty(),
                            confidence = candidate.confidence,
                            presentationType = "CONTACT_COMPLETION",
                            correctionRoute = "CONTACT_PROFILE",
                            createdAtEpochMs = nowEpochMs,
                            summary = patch.summary(),
                        ),
                    )
                }
                knowledge.upsertEnrichmentCandidate(candidate.copy(status = STATUS_APPROVED, updatedAtEpochMs = nowEpochMs))
                pendingCandidates.forEach { knowledge.upsertEnrichmentCandidate(it) }
                database.contactCompletionRequestDao().markResponseReceived(request.requestId, candidate.candidateId, nowEpochMs)
                true
            }
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (failure.message == "CONTACT_WRITE_VERIFY_FAILED") {
                database.recordWriteVerificationFailure(
                    toolName = AutoWriteToolNames.CONTACT_COMPLETION,
                    targetId = request.contactId,
                    idempotencyKey = "contact-completion-reply:${candidate.candidateId}",
                    reasonCode = failure.message.orEmpty(),
                    nowEpochMs = nowEpochMs,
                )
            }
            throw failure
        }
    }

    private fun ContactEnrichmentCandidateEntity.isEligible(): Boolean =
        fieldKind == KIND_COMMUNICATION && confidence >= MIN_CONFIDENCE && status == STATUS_PENDING

    private fun decodePatch(raw: String): CompletionPatch? = runCatching {
        val json = Json.parseToJsonElement(raw).jsonObject
        CompletionPatch(
            phone = json.text("phone"),
            email = json.text("email"),
            wechatId = json.text("wechatId"),
        ).takeIf { it.hasValue() }
    }.getOrNull()

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun ContactEntity.applyMissing(patch: CompletionPatch, nowEpochMs: Long): ContactEntity {
        val nextPhone = phone?.takeIf(String::isNotBlank) ?: patch.phone
        val nextEmail = email?.takeIf(String::isNotBlank) ?: patch.email
        val nextWechat = wechatId?.takeIf(String::isNotBlank) ?: patch.wechatId
        if (nextPhone == phone && nextEmail == email && nextWechat == wechatId) return this
        return copy(phone = nextPhone, email = nextEmail, wechatId = nextWechat, updatedAtEpochMs = nowEpochMs)
    }

    private fun ContactEntity.matches(expected: ContactEntity): Boolean = phone == expected.phone &&
        email == expected.email &&
        wechatId == expected.wechatId &&
        updatedAtEpochMs == expected.updatedAtEpochMs

    private fun inversePayload(contact: ContactEntity): String = buildJsonObject {
        put(
            "fields",
            buildJsonObject {
                COMPLETION_FIELD_NAMES.forEach { (kind, name) ->
                    val old = contactField(contact, kind)
                    if (old == null) put(name, JsonNull) else put(name, JsonPrimitive(old))
                }
            },
        )
    }.toString()

    private data class CompletionPatch(val phone: String?, val email: String?, val wechatId: String?) {
        fun hasValue(): Boolean = phone != null || email != null || wechatId != null
        fun summary(): String = listOfNotNull(
            phone?.let { "电话：$it" },
            email?.let { "邮箱：$it" },
            wechatId?.let { "微信：$it" },
        ).joinToString(" · ")
    }

    private companion object {
        const val KIND_COMMUNICATION = "COMMUNICATION_METHOD"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val MIN_CONFIDENCE = 0.9
    }
}
