package com.zhiban.rebuild.data.agent

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactSyncOperationEntity
import com.zhiban.rebuild.data.contact.ContactSyncProjection
import com.zhiban.rebuild.data.contact.ContactSyncSnapshotEntity
import com.zhiban.rebuild.data.contact.ContactThreeWayMerge
import com.zhiban.rebuild.data.contact.ContactThreeWayMergePlan
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactDataRowSnapshot
import com.zhiban.rebuild.data.contact.SystemContactReader
import com.zhiban.rebuild.data.contact.SystemRawContactSnapshot
import com.zhiban.rebuild.data.contact.findExistingSystemContact
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.contact.toSyncProjection
import com.zhiban.rebuild.foundation.sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AndroidContactSyncPreview(
    val contact: ContactEntity,
    val deviceContact: SystemContactCandidate,
    val deviceProjection: ContactSyncProjection,
    val rawContact: SystemRawContactSnapshot,
    val base: ContactSyncProjection,
    val desired: ContactSyncProjection,
    val plan: ContactThreeWayMergePlan,
    val linkId: String,
)

data class AndroidContactSyncResult(val operationId: String, val message: String)

/**
 * Confirmed Android-contact writeback with optimistic concurrency, reread verification and guarded undo.
 * It never deletes a phone/email during normal sync and never writes a read-only account row.
 */
@Singleton
class AndroidContactSyncRepository @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val database: AgentDatabase,
    private val reader: SystemContactReader,
) {
    suspend fun prepare(contact: ContactEntity): AndroidContactSyncPreview {
        requirePermissions()
        val result = reader.readAll()
        check(result.errorMessage == null) { result.errorMessage ?: "无法读取手机通讯录" }
        val existing = findExistingSystemContact(contact, result.contacts)
            ?: error("手机通讯录里还没有此联系人，请先用系统联系人页新建")
        val raw = selectWritableRawContact(existing, contact)
            ?: error("这个联系人属于只读账号，无法由知伴直接更新")
        val linkId = stableContactKnowledgeId("android-raw-contact", raw.rawContactId.toString())
        val snapshot = database.contactIntelligenceDao().findSyncSnapshot(linkId)
        // Android's aggregate Contacts view can lag behind the raw Data rows that were just
        // committed. Include the authoritative writable-raw values so an immediate retry does
        // not insert the same phone or email a second time while aggregation catches up.
        val device = existing.toSyncProjection().includingRawContactValues(raw)
        val base = snapshot?.baseProjectionJson?.let(ContactSyncProjection::decode) ?: device
        val requested = contact.toSyncProjection()
        val desired = device.copy(
            displayName = requested.displayName,
            phones = device.phones + requested.phones,
            emails = device.emails + requested.emails,
            company = requested.company ?: device.company,
            title = requested.title ?: device.title,
            note = requested.note ?: device.note,
        ).canonical()
        return AndroidContactSyncPreview(
            contact = contact,
            deviceContact = existing,
            deviceProjection = device,
            rawContact = raw,
            base = base,
            desired = desired,
            plan = ContactThreeWayMerge.plan(base, device, desired),
            linkId = linkId,
        )
    }

    suspend fun apply(preview: AndroidContactSyncPreview, nowEpochMs: Long = System.currentTimeMillis()): AndroidContactSyncResult {
        requirePermissions()
        check(preview.plan.canApply) { "手机联系人已被修改，请先处理冲突" }
        val fresh = prepare(preview.contact)
        check(fresh.rawContact.rawContactId == preview.rawContact.rawContactId) { "联系人来源已变化，请重新检查" }
        check(fresh.base.encode() == preview.base.encode()) { "同步基线已变化，请重新检查" }
        check(fresh.plan.canApply) { "手机联系人刚刚发生了变化，请重新检查" }
        if (fresh.plan.isNoOp) return AndroidContactSyncResult("", "手机通讯录已经是最新")
        markPending(fresh, nowEpochMs)
        return withContext(Dispatchers.IO) {
            val applied = applyOperations(fresh)
            val verifiedVersion = awaitVerifiedProjection(fresh, fresh.desired)
            recordSuccess(fresh, applied, verifiedVersion, nowEpochMs)
        }
    }

    suspend fun undo(operationId: String, nowEpochMs: Long = System.currentTimeMillis()): String {
        requirePermissions()
        val operation = database.contactIntelligenceDao().findSyncOperation(operationId) ?: error("找不到这次同步记录")
        check(operation.state == "APPLIED") { "这次同步已经撤销或不可撤销" }
        check(nowEpochMs - operation.createdAtEpochMs <= UNDO_WINDOW_MS) { "这次同步已超过撤销期限" }
        val contact = database.contactDao().findById(operation.contactId) ?: error("联系人已不存在")
        val fresh = prepare(contact)
        val after = ContactSyncProjection.decode(operation.afterProjectionJson)
        check(fresh.deviceProjection.containsExpected(after)) { "手机联系人后来又被改过，请手动纠正，知伴不会覆盖" }
        val before = ContactSyncProjection.decode(operation.beforeProjectionJson)
        val reversePlan = ContactThreeWayMerge.plan(after, after, before)
        val reverse = fresh.copy(base = after, desired = before, plan = reversePlan)
        withContext(Dispatchers.IO) {
            applyOperations(reverse, JSON.decodeFromString(operation.insertedDataRowIdsJson))
        }
        awaitVerifiedProjection(fresh, before)
        database.withTransaction {
            database.contactIntelligenceDao().updateSyncOperationState(operationId, "UNDONE", nowEpochMs)
            database.changeLogDao().markUndone(operationId, nowEpochMs)
        }
        return "已撤销手机通讯录更新"
    }

    private suspend fun markPending(preview: AndroidContactSyncPreview, nowEpochMs: Long) {
        val dao = database.contactIntelligenceDao()
        val existing = dao.findSyncSnapshot(preview.linkId)
        dao.upsertSyncSnapshot(
            (existing ?: preview.newSnapshot(nowEpochMs)).copy(
                desiredProjectionJson = preview.desired.encode(),
                desiredDigest = sha256(preview.desired.encode()),
                syncState = "PENDING",
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private suspend fun recordSuccess(
        preview: AndroidContactSyncPreview,
        applied: AppliedContactOperations,
        verifiedVersion: Long,
        nowEpochMs: Long,
    ): AndroidContactSyncResult {
        val operationId = UUID.randomUUID().toString()
        val beforeJson = preview.deviceProjection.encode()
        val afterJson = preview.desired.encode()
        database.withTransaction {
            database.contactIntelligenceDao().insertSyncOperation(
                ContactSyncOperationEntity(
                    operationId = operationId,
                    linkId = preview.linkId,
                    contactId = preview.contact.contactId,
                    beforeProjectionJson = beforeJson,
                    afterProjectionJson = afterJson,
                    insertedDataRowIdsJson = JSON.encodeToString(applied.insertedDataRowIds),
                    rawContactVersionBefore = preview.rawContact.version,
                    rawContactVersionAfter = verifiedVersion,
                    state = "APPLIED",
                    createdAtEpochMs = nowEpochMs,
                    undoneAtEpochMs = null,
                ),
            )
            database.changeLogDao().insert(
                ChangeLogEntity(
                    changeId = operationId,
                    runtimeRunId = null,
                    toolName = "contact.android.sync",
                    idempotencyKey = "android-contact-sync:$operationId",
                    targetDomain = "ANDROID_CONTACT",
                    targetId = preview.contact.contactId,
                    operation = "SYNC",
                    beforeDigest = sha256(beforeJson),
                    afterDigest = sha256(afterJson),
                    inversePayloadJson = "{\"operationId\":\"$operationId\"}",
                    undoState = "AVAILABLE",
                    createdAtEpochMs = nowEpochMs,
                    undoneAtEpochMs = null,
                    originType = "USER_CONFIRMED_EXTERNAL_WRITE",
                ),
            )
            database.contactIntelligenceDao().upsertSyncSnapshot(
                preview.newSnapshot(nowEpochMs).copy(
                    baseProjectionJson = afterJson,
                    baseDigest = sha256(afterJson),
                    lastVerifiedAtEpochMs = nowEpochMs,
                ),
            )
        }
        return AndroidContactSyncResult(operationId, "已更新手机通讯录，可撤销")
    }

    private fun applyOperations(preview: AndroidContactSyncPreview, insertedRowsToDelete: List<Long> = emptyList()): AppliedContactOperations {
        val rawId = preview.rawContact.rawContactId
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newAssertQuery(ContactsContract.RawContacts.CONTENT_URI)
            .withSelection(
                "${ContactsContract.RawContacts._ID} = ? AND ${ContactsContract.RawContacts.VERSION} = ?",
                arrayOf(rawId.toString(), preview.rawContact.version.toString()),
            )
            .withExpectedCount(1)
            .build()
        insertedRowsToDelete.forEach { rowId ->
            operations += ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data._ID} = ? AND ${ContactsContract.Data.RAW_CONTACT_ID} = ?",
                    arrayOf(rowId.toString(), rawId.toString()),
                )
                .withExpectedCount(1)
                .build()
        }
        val insertOperationIndices = mutableListOf<Int>()
        appendScalarOperations(preview, operations, insertOperationIndices)
        preview.plan.phoneAdditions.forEach { phone ->
            insertOperationIndices += operations.size
            operations += insertData(rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        }
        preview.plan.emailAdditions.forEach { email ->
            insertOperationIndices += operations.size
            operations += insertData(rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                .build()
        }
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return AppliedContactOperations(
            insertedDataRowIds = insertOperationIndices.mapNotNull { index -> results[index].uri?.lastPathSegment?.toLongOrNull() },
        )
    }

    /**
     * ContactsProvider aggregation can expose a stale aggregate briefly after a successful batch write.
     * 验证只定向重读刚写入的那条 rawContact(P1-性能1):过去每次重试都整本重读通讯录,
     * 最坏 50 次全量读取;现在每次重试只查一个 rawContact 的元数据与数据行。
     * 返回写入后的 rawContact 版本号(供撤销审计记录)。
     */
    private suspend fun awaitVerifiedProjection(preview: AndroidContactSyncPreview, expected: ContactSyncProjection): Long {
        var raw = preview.rawContact
        repeat(CONTACT_PROVIDER_VERIFY_RETRIES) { attempt ->
            val device = preview.deviceContact.toSyncProjection().includingRawContactValues(raw)
            if (device.containsExpected(expected)) return raw.version
            if (attempt < CONTACT_PROVIDER_VERIFY_RETRIES - 1) {
                delay(CONTACT_PROVIDER_VERIFY_RETRY_MS)
                raw = reader.readRawContact(preview.rawContact.rawContactId) ?: raw
            }
        }
        val mismatch = contactProjectionMismatchFields(
            preview.deviceContact.toSyncProjection().includingRawContactValues(raw),
            expected,
        )
        error("手机通讯录写入后仍未完成聚合（${mismatch.joinToString()}），请稍后重试")
    }

    private fun appendScalarOperations(
        preview: AndroidContactSyncPreview,
        operations: MutableList<ContentProviderOperation>,
        insertIndices: MutableList<Int>,
    ) {
        val raw = preview.rawContact
        val updates = preview.plan.scalarUpdates
        if ("displayName" in updates) {
            appendUpsert(raw, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, operations, insertIndices) {
                withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, preview.desired.displayName)
            }
        }
        if ("company" in updates || "title" in updates) {
            appendUpsert(raw, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, operations, insertIndices) {
                withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, preview.desired.company)
                    .withValue(ContactsContract.CommonDataKinds.Organization.TITLE, preview.desired.title)
            }
        }
        if ("note" in updates) {
            appendUpsert(raw, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, operations, insertIndices) {
                withValue(ContactsContract.CommonDataKinds.Note.NOTE, preview.desired.note)
            }
        }
    }

    private fun appendUpsert(
        raw: SystemRawContactSnapshot,
        mimeType: String,
        operations: MutableList<ContentProviderOperation>,
        insertIndices: MutableList<Int>,
        values: ContentProviderOperation.Builder.() -> ContentProviderOperation.Builder,
    ) {
        val row = raw.dataRows.firstOrNull { it.mimeType == mimeType && !it.isReadOnly }
        if (row == null) {
            insertIndices += operations.size
            operations += insertData(raw.rawContactId, mimeType).values().build()
        } else {
            operations += ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(row.rowId.toString()))
                .values()
                .withExpectedCount(1)
                .build()
        }
    }

    private fun insertData(rawContactId: Long, mimeType: String): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)

    private fun requirePermissions() {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            "需要读取通讯录权限"
        }
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            "需要修改通讯录权限"
        }
    }

    private fun AndroidContactSyncPreview.newSnapshot(nowEpochMs: Long) = ContactSyncSnapshotEntity(
        snapshotId = stableContactKnowledgeId("android-sync", linkId),
        linkId = linkId,
        baseProjectionJson = base.encode(),
        baseDigest = sha256(base.encode()),
        desiredProjectionJson = null,
        desiredDigest = null,
        syncState = "IN_SYNC",
        lastVerifiedAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
    )

    private data class AppliedContactOperations(val insertedDataRowIds: List<Long>)

    private companion object {
        val JSON = Json
        const val UNDO_WINDOW_MS = 90L * 24L * 60L * 60L * 1_000L

        // Aggregation is asynchronous and can exceed one second on a busy device or a large book.
        // Keep verification strict, but allow the provider a bounded five-second convergence window.
        const val CONTACT_PROVIDER_VERIFY_RETRIES = 50
        const val CONTACT_PROVIDER_VERIFY_RETRY_MS = 100L
    }
}

internal fun contactProjectionMismatchFields(actual: ContactSyncProjection, expected: ContactSyncProjection): Set<String> = buildSet {
    if (actual.displayName != expected.displayName) add("displayName")
    if (!actual.phones.containsAll(expected.phones)) add("phones")
    if (!actual.emails.containsAll(expected.emails)) add("emails")
    if (actual.company != expected.company) add("company")
    if (actual.title != expected.title) add("title")
    if (actual.note != expected.note) add("note")
}

internal fun ContactSyncProjection.includingRawContactValues(raw: SystemRawContactSnapshot): ContactSyncProjection = copy(
    phones = phones + raw.dataRows
        .filter { it.mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }
        .mapNotNull(SystemContactDataRowSnapshot::value),
    emails = emails + raw.dataRows
        .filter { it.mimeType == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE }
        .mapNotNull(SystemContactDataRowSnapshot::value),
).canonical()

internal fun ContactSyncProjection.containsExpected(expected: ContactSyncProjection): Boolean {
    val actual = canonical()
    val normalizedExpected = expected.canonical()
    return actual.displayName == normalizedExpected.displayName &&
        actual.phones.containsAll(normalizedExpected.phones) &&
        actual.emails.containsAll(normalizedExpected.emails) &&
        actual.company == normalizedExpected.company &&
        actual.title == normalizedExpected.title &&
        actual.note == normalizedExpected.note
}

internal fun selectWritableRawContact(candidate: SystemContactCandidate, contact: ContactEntity): SystemRawContactSnapshot? {
    val writable = candidate.rawContacts.filterNot(SystemRawContactSnapshot::isReadOnly)
    if (writable.size < 2) return writable.firstOrNull()
    val phone = contact.phone?.let(::normalizeContactPhone)
    val email = contact.email?.trim()?.lowercase()?.takeIf { '@' in it }
    return writable.maxByOrNull { raw ->
        val rowValues = raw.dataRows.mapNotNull(SystemContactDataRowSnapshot::value)
        val phoneMatch = phone != null && rowValues.any { normalizeContactPhone(it) == phone }
        val emailMatch = email != null && rowValues.any { it.trim().lowercase() == email }
        when {
            phoneMatch -> 3
            emailMatch -> 2
            else -> 1
        }
    }
}
