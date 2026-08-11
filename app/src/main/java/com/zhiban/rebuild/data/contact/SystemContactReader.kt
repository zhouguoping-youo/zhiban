package com.zhiban.rebuild.data.contact

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.zhiban.rebuild.runtime.runSuspendCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SystemContactCandidate(
    val sourceId: String,
    val displayName: String,
    val phones: List<String>,
    val emails: List<String>,
    val wechatIds: List<String> = emptyList(),
    val platformIdentities: List<SystemContactPlatformIdentity> = emptyList(),
    val company: String?,
    val title: String?,
    val department: String? = null,
    val jobDescription: String? = null,
    val officeLocation: String? = null,
    val addresses: List<SystemContactAddress> = emptyList(),
    val birthday: SystemContactBirthday? = null,
    val note: String? = null,
    val contactUri: String? = null,
    val aggregateContactId: Long? = null,
    val lookupKey: String? = null,
    val rawContacts: List<SystemRawContactSnapshot> = emptyList(),
    val organizations: List<SystemContactOrganization> = emptyList(),
)

data class SystemContactPlatformIdentity(val platform: String, val handle: String)

data class SystemContactOrganization(
    val company: String?,
    val title: String?,
    val department: String?,
    val jobDescription: String?,
    val officeLocation: String?,
)

data class SystemContactDataRowSnapshot(val rowId: Long, val mimeType: String, val value: String?, val isReadOnly: Boolean)

data class SystemRawContactSnapshot(
    val rawContactId: Long,
    val aggregateContactId: Long,
    val lookupKey: String,
    val accountName: String?,
    val accountType: String?,
    val sourceId: String?,
    val version: Long,
    val isDirty: Boolean,
    val isReadOnly: Boolean,
    val dataRows: List<SystemContactDataRowSnapshot>,
)

data class SystemContactAddress(val kind: String, val formattedAddress: String)

data class SystemContactBirthday(val year: Int?, val month: Int, val day: Int)

data class SystemContactReadResult(val contacts: List<SystemContactCandidate>, val rowsRead: Int, val blankRows: Int, val errorMessage: String? = null)

/**
 * Android Contacts Provider adapter.
 *
 * It only reads after the UI has obtained READ_CONTACTS. No row is persisted
 * here; import remains a separate, user-confirmed data-layer operation.
 */
@Singleton
class SystemContactReader @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun readAll(): SystemContactReadResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext SystemContactReadResult(emptyList(), 0, 0, "需要通讯录权限才能读取联系人")
        }

        val accumulator = ContactReadAccumulator()
        runSuspendCatching {
            readContactData(accumulator)
            hydrateRawContacts(accumulator.rawContacts)
        }.fold(
            onSuccess = {
                SystemContactReadResult(
                    contacts = accumulator.rows.values.mapNotNull { it.toCandidate(accumulator.rawContacts) }
                        .sortedBy(SystemContactCandidate::displayName),
                    rowsRead = accumulator.rowCount,
                    blankRows = accumulator.blankRows,
                )
            },
            onFailure = {
                SystemContactReadResult(
                    emptyList(),
                    accumulator.rowCount,
                    accumulator.blankRows,
                    "手机没有返回可读取的联系人",
                )
            },
        )
    }

    private fun readContactData(accumulator: ContactReadAccumulator) {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            CONTACT_DATA_PROJECTION,
            "${ContactsContract.Data.MIMETYPE} IN (${SUPPORTED_MIME_TYPES.joinToString(",") { "?" }})",
            SUPPORTED_MIME_TYPES,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) readContactDataRow(cursor, accumulator)
        }
    }

    private fun readContactDataRow(cursor: Cursor, accumulator: ContactReadAccumulator) {
        accumulator.rowCount++
        val contactId = cursor.string(ContactsContract.Data.CONTACT_ID)
        val lookupKey = cursor.string(ContactsContract.Data.LOOKUP_KEY)
        val sourceId = lookupKey?.takeIf(String::isNotBlank) ?: contactId?.takeIf(String::isNotBlank)
        if (sourceId == null) {
            accumulator.blankRows++
            return
        }
        val fallbackName = cursor.string(
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.DISPLAY_NAME,
        ).orEmpty().trim()
        val target = accumulator.rows.getOrPut(sourceId) {
            MutableContact(sourceId, contactId, lookupKey, fallbackName)
        }
        captureRawContactRow(cursor, target, accumulator.rawContacts)
        if (target.displayName.isBlank()) target.displayName = fallbackName
        applyMimeTypeRow(cursor, target)
    }

    private fun captureRawContactRow(cursor: Cursor, target: MutableContact, rawContacts: MutableMap<Long, MutableRawContact>) {
        val rawContactId = cursor.long(ContactsContract.Data.RAW_CONTACT_ID) ?: return
        val aggregateContactId = target.contactId?.toLongOrNull() ?: return
        val lookupKey = target.lookupKey.orEmpty()
        target.rawContactIds += rawContactId
        val raw = rawContacts.getOrPut(rawContactId) {
            MutableRawContact(rawContactId, aggregateContactId, lookupKey)
        }
        val rowId = cursor.long(ContactsContract.Data._ID) ?: return
        val mimeType = cursor.string(ContactsContract.Data.MIMETYPE).orEmpty()
        raw.dataRows += SystemContactDataRowSnapshot(
            rowId = rowId,
            mimeType = mimeType,
            value = cursor.string(ContactsContract.Data.DATA1)?.take(500),
            isReadOnly = false,
        )
    }

    private fun hydrateRawContacts(rawContacts: MutableMap<Long, MutableRawContact>) {
        rawContacts.keys.chunked(RAW_CONTACT_QUERY_CHUNK)
            .forEach { hydrateRawContactChunk(it, rawContacts) }
    }

    private fun hydrateRawContactChunk(ids: List<Long>, rawContacts: MutableMap<Long, MutableRawContact>) {
        val placeholders = ids.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            RAW_CONTACT_PROJECTION,
            "${ContactsContract.RawContacts._ID} IN ($placeholders)",
            ids.map(Long::toString).toTypedArray(),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) applyRawContactMetadata(cursor, rawContacts)
        }
    }

    private fun applyRawContactMetadata(cursor: Cursor, rawContacts: MutableMap<Long, MutableRawContact>) {
        val id = cursor.long(ContactsContract.RawContacts._ID) ?: return
        val rawContact = rawContacts[id] ?: return
        rawContact.accountName = cursor.string(ContactsContract.RawContacts.ACCOUNT_NAME)
        rawContact.accountType = cursor.string(ContactsContract.RawContacts.ACCOUNT_TYPE)
        rawContact.sourceId = cursor.string(ContactsContract.RawContacts.SOURCE_ID)
        rawContact.version = cursor.long(ContactsContract.RawContacts.VERSION) ?: 0L
        rawContact.isDirty = cursor.int(ContactsContract.RawContacts.DIRTY) == 1
        rawContact.isReadOnly = !accountSupportsContactWrites(rawContact.accountType)
    }

    private fun accountSupportsContactWrites(accountType: String?): Boolean {
        if (accountType.isNullOrBlank()) return true
        return ContentResolver.getSyncAdapterTypes().any { adapter ->
            adapter.authority == ContactsContract.AUTHORITY &&
                adapter.accountType == accountType &&
                adapter.supportsUploading()
        }
    }

    private fun applyMimeTypeRow(cursor: Cursor, target: MutableContact) {
        when (cursor.string(ContactsContract.Data.MIMETYPE)) {
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                val fullName = cursor.string(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
                val givenName = cursor.string(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                val familyName = cursor.string(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                target.displayName = fullName?.trim().takeUnless(String?::isNullOrBlank)
                    ?: listOfNotNull(familyName, givenName).joinToString("").trim()
            }

            ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE -> {
                val handle = cursor.string(ContactsContract.CommonDataKinds.Im.DATA)
                    ?.let(::normalizeContactMethodHandle)
                val platform = systemImPlatform(
                    protocol = cursor.string(ContactsContract.CommonDataKinds.Im.PROTOCOL)?.toIntOrNull(),
                    customProtocol = cursor.string(ContactsContract.CommonDataKinds.Im.CUSTOM_PROTOCOL),
                )
                if (handle != null && platform != null) {
                    target.platformIdentities += SystemContactPlatformIdentity(platform, handle)
                    if (platform == "WECHAT") target.wechatIds += handle
                }
            }

            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                cursor.string(
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )?.let(::normalizeContactPhone)?.let(target.phones::add)
            }

            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                cursor.string(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    ?.trim()?.lowercase()?.takeIf { it.contains("@") }?.let(target.emails::add)
            }

            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                val organization = SystemContactOrganization(
                    company = cursor.clean(ContactsContract.CommonDataKinds.Organization.COMPANY),
                    title = cursor.clean(ContactsContract.CommonDataKinds.Organization.TITLE),
                    department = cursor.clean(ContactsContract.CommonDataKinds.Organization.DEPARTMENT),
                    jobDescription = cursor.clean(ContactsContract.CommonDataKinds.Organization.JOB_DESCRIPTION),
                    officeLocation = cursor.clean(ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION),
                )
                if (organization.company != null || organization.title != null) {
                    target.organizations += organization
                    target.company = organization.company
                    target.title = organization.title
                    target.department = organization.department
                    target.jobDescription = organization.jobDescription
                    target.officeLocation = organization.officeLocation
                }
            }

            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                val address = cursor.string(
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                )
                    ?.trim()?.replace(Regex("\\s+"), " ")?.take(300)?.takeIf(String::isNotEmpty)
                if (address != null) {
                    val type = cursor.string(
                        ContactsContract.CommonDataKinds.StructuredPostal.TYPE,
                    )?.toIntOrNull()
                    val kind = when (type) {
                        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> "HOME"
                        ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "WORK"
                        else -> "OTHER"
                    }
                    target.addresses.add(SystemContactAddress(kind, address))
                }
            }

            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                val type = cursor.string(ContactsContract.CommonDataKinds.Event.TYPE)?.toIntOrNull()
                if (type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) {
                    target.birthday = parseSystemContactBirthday(
                        cursor.string(ContactsContract.CommonDataKinds.Event.START_DATE),
                    ) ?: target.birthday
                }
            }

            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                target.note = cursor.string(ContactsContract.CommonDataKinds.Note.NOTE)
                    ?.trim()?.take(500)?.takeIf(String::isNotEmpty)
            }
        }
    }

    private data class MutableContact(
        val sourceId: String,
        val contactId: String?,
        val lookupKey: String?,
        var displayName: String,
        val phones: LinkedHashSet<String> = linkedSetOf(),
        val emails: LinkedHashSet<String> = linkedSetOf(),
        val wechatIds: LinkedHashSet<String> = linkedSetOf(),
        val platformIdentities: LinkedHashSet<SystemContactPlatformIdentity> = linkedSetOf(),
        var company: String? = null,
        var title: String? = null,
        var department: String? = null,
        var jobDescription: String? = null,
        var officeLocation: String? = null,
        val addresses: LinkedHashSet<SystemContactAddress> = linkedSetOf(),
        var birthday: SystemContactBirthday? = null,
        var note: String? = null,
        val rawContactIds: LinkedHashSet<Long> = linkedSetOf(),
        val organizations: MutableList<SystemContactOrganization> = mutableListOf(),
    ) {
        fun toCandidate(rawContacts: Map<Long, MutableRawContact>): SystemContactCandidate? {
            val name = displayName.trim().take(100)
            if (name.isBlank()) return null
            return SystemContactCandidate(
                sourceId = sourceId,
                displayName = name,
                phones = phones.toList(),
                emails = emails.toList(),
                wechatIds = wechatIds.toList(),
                platformIdentities = platformIdentities.toList(),
                company = company,
                title = title,
                department = department,
                jobDescription = jobDescription,
                officeLocation = officeLocation,
                addresses = addresses.toList(),
                birthday = birthday,
                note = note,
                aggregateContactId = contactId?.toLongOrNull(),
                lookupKey = lookupKey,
                rawContacts = rawContactIds.mapNotNull(rawContacts::get).map(MutableRawContact::snapshot),
                organizations = organizations.distinct(),
                contactUri = contactId?.toLongOrNull()?.let { id ->
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id).toString()
                },
            )
        }
    }

    private data class MutableRawContact(
        val rawContactId: Long,
        val aggregateContactId: Long,
        val lookupKey: String,
        var accountName: String? = null,
        var accountType: String? = null,
        var sourceId: String? = null,
        var version: Long = 0,
        var isDirty: Boolean = false,
        var isReadOnly: Boolean = false,
        val dataRows: MutableList<SystemContactDataRowSnapshot> = mutableListOf(),
    ) {
        fun snapshot() = SystemRawContactSnapshot(
            rawContactId = rawContactId,
            aggregateContactId = aggregateContactId,
            lookupKey = lookupKey,
            accountName = accountName,
            accountType = accountType,
            sourceId = sourceId,
            version = version,
            isDirty = isDirty,
            isReadOnly = isReadOnly,
            dataRows = dataRows.toList(),
        )
    }

    private data class ContactReadAccumulator(
        val rows: MutableMap<String, MutableContact> = linkedMapOf(),
        val rawContacts: MutableMap<Long, MutableRawContact> = linkedMapOf(),
        var rowCount: Int = 0,
        var blankRows: Int = 0,
    )

    private companion object {
        const val RAW_CONTACT_QUERY_CHUNK = 400

        val SUPPORTED_MIME_TYPES = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        )

        val CONTACT_DATA_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4,
            ContactsContract.Data.DATA5,
            ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA9,
        )

        val RAW_CONTACT_PROJECTION = arrayOf(
            ContactsContract.RawContacts._ID,
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
            ContactsContract.RawContacts.SOURCE_ID,
            ContactsContract.RawContacts.VERSION,
            ContactsContract.RawContacts.DIRTY,
        )
    }
}

private fun normalizeContactMethodHandle(raw: String): String = raw.trim().trimStart('@').lowercase()

internal fun systemImPlatform(protocol: Int?, customProtocol: String?): String? = when (protocol) {
    ContactsContract.CommonDataKinds.Im.PROTOCOL_QQ -> "QQ"

    ContactsContract.CommonDataKinds.Im.PROTOCOL_SKYPE -> "SKYPE"

    ContactsContract.CommonDataKinds.Im.PROTOCOL_GOOGLE_TALK -> "GOOGLE_CHAT"

    ContactsContract.CommonDataKinds.Im.PROTOCOL_JABBER -> "JABBER"

    ContactsContract.CommonDataKinds.Im.PROTOCOL_CUSTOM -> when {
        customProtocol.matchesPlatform("微信", "wechat", "weixin") -> "WECHAT"
        customProtocol.matchesPlatform("企业微信", "企微", "wecom", "wework") -> "WE_COM"
        customProtocol.matchesPlatform("飞书", "feishu", "lark") -> "FEISHU"
        customProtocol.matchesPlatform("钉钉", "dingtalk") -> "DINGTALK"
        customProtocol.matchesPlatform("qq") -> "QQ"
        else -> null
    }

    else -> null
}

private fun String?.matchesPlatform(vararg aliases: String): Boolean {
    val normalized = orEmpty().trim().lowercase().replace(" ", "")
    return aliases.any { normalized == it.lowercase().replace(" ", "") }
}

internal fun parseSystemContactBirthday(raw: String?): SystemContactBirthday? {
    val value = raw.orEmpty().trim()
    val full = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matchEntire(value)
    val partial = Regex("^(?:--)?(\\d{1,2})-(\\d{1,2})$").matchEntire(value)
    val (year, month, day) = when {
        full != null -> Triple(
            full.groupValues[1].toIntOrNull(),
            full.groupValues[2].toIntOrNull(),
            full.groupValues[3].toIntOrNull(),
        )

        partial != null -> Triple(
            null,
            partial.groupValues[1].toIntOrNull(),
            partial.groupValues[2].toIntOrNull(),
        )

        else -> return null
    }
    val validMonth = month?.takeIf { it in 1..12 } ?: return null
    val validDay = day?.takeIf { it in 1..31 } ?: return null
    return SystemContactBirthday(year?.takeIf { it in 1..9999 }, validMonth, validDay)
}

internal fun normalizeContactPhone(raw: String?): String? {
    val value = raw.orEmpty().trim()
    if (value.isBlank()) return null
    val leadingPlus = value.startsWith("+")
    val digits = value.filter(Char::isDigit)
    if (digits.length < 5) return null
    // The domestic and +86 forms identify the same Chinese mobile subscriber. Keeping a single
    // canonical value is required for contact import, SMS and CallLog matching to agree.
    if (digits.length == 13 && digits.startsWith("86") && digits[2] == '1') {
        return digits.drop(2)
    }
    return if (leadingPlus) "+$digits" else digits
}

private fun android.database.Cursor.string(vararg columns: String): String? {
    columns.forEach { column ->
        val index = getColumnIndex(column)
        if (index >= 0 && !isNull(index)) return getString(index)
    }
    return null
}

private fun Cursor.long(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}

private fun Cursor.int(column: String): Int? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else null
}

private fun Cursor.clean(column: String): String? = string(column)?.trim()?.takeIf(String::isNotEmpty)
