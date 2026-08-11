package com.zhiban.rebuild.data.contact

import com.zhiban.rebuild.runtime.tool.sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ContactSyncProjection(
    val displayName: String,
    val phones: List<String>,
    val emails: List<String>,
    val company: String?,
    val title: String?,
    val note: String?,
) {
    fun canonical(): ContactSyncProjection = copy(
        displayName = displayName.trim(),
        phones = phones.mapNotNull(::normalizeContactPhone).distinct().sorted(),
        emails = emails.map(String::trim).map(String::lowercase).filter { '@' in it }.distinct().sorted(),
        company = company.clean(),
        title = title.clean(),
        note = note.clean(),
    )

    fun encode(): String = JSON.encodeToString(canonical())

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun decode(value: String): ContactSyncProjection = JSON.decodeFromString<ContactSyncProjection>(value).canonical()
    }
}

data class ContactSyncConflict(val field: String, val baseValue: String?, val deviceValue: String?, val desiredValue: String?)

data class ContactThreeWayMergePlan(
    val scalarUpdates: Map<String, String?>,
    val phoneAdditions: List<String>,
    val emailAdditions: List<String>,
    val conflicts: List<ContactSyncConflict>,
) {
    val isNoOp: Boolean get() = scalarUpdates.isEmpty() && phoneAdditions.isEmpty() && emailAdditions.isEmpty()
    val canApply: Boolean get() = conflicts.isEmpty()
}

object ContactThreeWayMerge {
    fun plan(base: ContactSyncProjection, device: ContactSyncProjection, desired: ContactSyncProjection): ContactThreeWayMergePlan {
        val normalizedBase = base.canonical()
        val normalizedDevice = device.canonical()
        val normalizedDesired = desired.canonical()
        val updates = linkedMapOf<String, String?>()
        val conflicts = mutableListOf<ContactSyncConflict>()
        scalarFields.forEach { field ->
            val baseValue = field.read(normalizedBase)
            val deviceValue = field.read(normalizedDevice)
            val desiredValue = field.read(normalizedDesired)
            when {
                desiredValue == baseValue || desiredValue == deviceValue -> Unit
                deviceValue == baseValue -> updates[field.name] = desiredValue
                else -> conflicts += ContactSyncConflict(field.name, baseValue, deviceValue, desiredValue)
            }
        }
        return ContactThreeWayMergePlan(
            scalarUpdates = updates,
            phoneAdditions = normalizedDesired.phones.filterNot(normalizedDevice.phones::contains),
            emailAdditions = normalizedDesired.emails.filterNot(normalizedDevice.emails::contains),
            conflicts = conflicts,
        )
    }

    private data class ScalarField(val name: String, val read: (ContactSyncProjection) -> String?)

    private val scalarFields = listOf(
        ScalarField("displayName", ContactSyncProjection::displayName),
        ScalarField("company", ContactSyncProjection::company),
        ScalarField("title", ContactSyncProjection::title),
        ScalarField("note", ContactSyncProjection::note),
    )
}

object ContactSyncSnapshotState {
    fun observe(existing: ContactSyncSnapshotEntity?, linkId: String, observed: ContactSyncProjection, nowEpochMs: Long): ContactSyncSnapshotEntity {
        val observedJson = observed.encode()
        val observedDigest = sha256(observedJson)
        if (existing == null) {
            return ContactSyncSnapshotEntity(
                snapshotId = "android-sync:$linkId",
                linkId = linkId,
                baseProjectionJson = observedJson,
                baseDigest = observedDigest,
                desiredProjectionJson = null,
                desiredDigest = null,
                syncState = "IN_SYNC",
                lastVerifiedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
        }
        val desiredDigest = existing.desiredDigest
        return when {
            desiredDigest == null -> existing.copy(
                baseProjectionJson = observedJson,
                baseDigest = observedDigest,
                syncState = "IN_SYNC",
                lastVerifiedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )

            observedDigest == desiredDigest -> existing.copy(
                baseProjectionJson = observedJson,
                baseDigest = observedDigest,
                desiredProjectionJson = null,
                desiredDigest = null,
                syncState = "IN_SYNC",
                lastVerifiedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )

            observedDigest == existing.baseDigest -> existing.copy(
                syncState = "PENDING",
                lastVerifiedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )

            else -> existing.copy(
                syncState = "EXTERNAL_CHANGED",
                lastVerifiedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
        }
    }
}

fun ContactEntity.toSyncProjection(): ContactSyncProjection = ContactSyncProjection(
    displayName = displayName,
    phones = listOfNotNull(phone),
    emails = listOfNotNull(email),
    company = company,
    title = title,
    note = note,
).canonical()

fun SystemContactCandidate.toSyncProjection(): ContactSyncProjection = ContactSyncProjection(
    displayName = displayName,
    phones = phones,
    emails = emails,
    company = company,
    title = title,
    note = note,
).canonical()

private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)
