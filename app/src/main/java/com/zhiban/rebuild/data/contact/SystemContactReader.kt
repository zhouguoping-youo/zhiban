package com.zhiban.rebuild.data.contact
import android.Manifest
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
)

data class SystemContactPlatformIdentity(val platform: String, val handle: String)

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

        val rows = linkedMapOf<String, MutableContact>()
        var rowCount = 0
        var blankRows = 0
        val mimeTypes = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        )
        val projection = arrayOf(
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

        runSuspendCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                "${ContactsContract.Data.MIMETYPE} IN (${mimeTypes.joinToString(",") { "?" }})",
                mimeTypes,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rowCount++
                    val contactId = cursor.string(ContactsContract.Data.CONTACT_ID)
                    val lookupKey = cursor.string(ContactsContract.Data.LOOKUP_KEY)
                    val sourceId = lookupKey?.takeIf(String::isNotBlank) ?: contactId?.takeIf(String::isNotBlank)
                    if (sourceId == null) {
                        blankRows++
                        continue
                    }
                    val fallbackName = cursor.string(
                        ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Data.DISPLAY_NAME,
                    ).orEmpty().trim()
                    val target = rows.getOrPut(sourceId) {
                        MutableContact(sourceId = sourceId, displayName = fallbackName)
                    }
                    if (target.displayName.isBlank()) target.displayName = fallbackName
                    applyMimeTypeRow(cursor, target)
                }
            }
        }.fold(
            onSuccess = {
                SystemContactReadResult(
                    contacts = rows.values.mapNotNull(MutableContact::toCandidate)
                        .sortedBy(SystemContactCandidate::displayName),
                    rowsRead = rowCount,
                    blankRows = blankRows,
                )
            },
            onFailure = {
                SystemContactReadResult(emptyList(), rowCount, blankRows, "手机没有返回可读取的联系人")
            },
        )
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
                target.company = cursor.string(ContactsContract.CommonDataKinds.Organization.COMPANY)
                    ?.trim()?.takeIf(String::isNotEmpty)
                target.title = cursor.string(ContactsContract.CommonDataKinds.Organization.TITLE)
                    ?.trim()?.takeIf(String::isNotEmpty)
                target.department = cursor.string(ContactsContract.CommonDataKinds.Organization.DEPARTMENT)
                    ?.trim()?.takeIf(String::isNotEmpty)
                target.jobDescription =
                    cursor.string(ContactsContract.CommonDataKinds.Organization.JOB_DESCRIPTION)
                        ?.trim()?.takeIf(String::isNotEmpty)
                target.officeLocation =
                    cursor.string(ContactsContract.CommonDataKinds.Organization.OFFICE_LOCATION)
                        ?.trim()?.takeIf(String::isNotEmpty)
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
    ) {
        fun toCandidate(): SystemContactCandidate? {
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
            )
        }
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
