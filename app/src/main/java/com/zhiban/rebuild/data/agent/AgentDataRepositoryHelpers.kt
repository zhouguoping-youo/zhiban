package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import java.nio.charset.StandardCharsets
import java.util.UUID

internal fun stableContactKnowledgeId(vararg parts: String): String =
    UUID.nameUUIDFromBytes(parts.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)).toString()

internal fun fillMissingContactFields(canonical: ContactEntity, source: ContactEntity): ContactEntity = canonical.copy(
    phone = canonical.phone.ifNullOrBlank(source.phone),
    email = canonical.email.ifNullOrBlank(source.email),
    wechatId = canonical.wechatId.ifNullOrBlank(source.wechatId),
    company = canonical.company.ifNullOrBlank(source.company),
    title = canonical.title.ifNullOrBlank(source.title),
    aliasesJson = canonical.aliasesJson.takeUnless { it == "[]" } ?: source.aliasesJson,
    tagsJson = canonical.tagsJson.takeUnless { it == "[]" } ?: source.tagsJson,
    note = canonical.note.ifNullOrBlank(source.note),
    avatarUri = canonical.avatarUri.ifNullOrBlank(source.avatarUri),
    updatedAtEpochMs = maxOf(canonical.updatedAtEpochMs, source.updatedAtEpochMs),
)

private fun String?.ifNullOrBlank(fallback: String?): String? = this?.takeIf(String::isNotBlank) ?: fallback?.takeIf(String::isNotBlank)

internal fun canonicalizeRelationshipEvents(
    events: List<RelationshipEventWithParticipants>,
    links: List<ContactMergeLinkEntity>,
): List<RelationshipEventWithParticipants> {
    val canonicalBySource = links.associateBy(
        ContactMergeLinkEntity::sourceContactId,
        ContactMergeLinkEntity::canonicalContactId,
    )
    return events.map { value ->
        value.copy(
            participants = value.participants.map { participant ->
                participant.copy(contactId = participant.contactId?.let { canonicalBySource[it] ?: it })
            }.distinctBy { participant ->
                listOf(
                    participant.participantKind,
                    participant.contactId.orEmpty(),
                    participant.participantRole,
                    participant.displayNameSnapshot,
                ).joinToString("\u001f")
            },
        )
    }
}
