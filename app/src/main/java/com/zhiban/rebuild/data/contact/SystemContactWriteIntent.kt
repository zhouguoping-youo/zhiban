package com.zhiban.rebuild.data.contact

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract

/**
 * Builds the Android-owned contact editor.
 *
 * ZhiBan never writes the Contacts Provider directly here. The system editor
 * shows every field and only persists after the user taps its save action.
 */
object SystemContactWriteIntent {
    fun create(contact: ContactEntity, existing: SystemContactCandidate? = null): Intent {
        val existingUri = existing?.contactUri?.let(Uri::parse)
        return if (existingUri == null) {
            Intent(Intent.ACTION_INSERT).apply {
                type = ContactsContract.Contacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.NAME, contact.displayName)
                contact.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                contact.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                contact.company?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
                contact.title?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
                contact.note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
            }
        } else {
            Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(existingUri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                putExtra("finishActivityOnSaveCompleted", true)
                putMissingFields(contact, existing)
            }
        }
    }

    private fun Intent.putMissingFields(contact: ContactEntity, existing: SystemContactCandidate) {
        val existingPhones = existing.phones.mapNotNull(::normalizeContactPhone).toSet()
        contact.phone?.takeIf { normalizeContactPhone(it) !in existingPhones }
            ?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        val existingEmails = existing.emails.map(String::normalizedContactText).toSet()
        contact.email?.takeIf { it.normalizedContactText() !in existingEmails }
            ?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        contact.company?.takeIf { existing.company.isNullOrBlank() }
            ?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        contact.title?.takeIf { existing.title.isNullOrBlank() }
            ?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
        contact.note?.takeIf { existing.note.isNullOrBlank() }
            ?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
    }
}

internal fun findExistingSystemContact(contact: ContactEntity, candidates: List<SystemContactCandidate>): SystemContactCandidate? {
    val sourceId = contact.source.takeIf { it.startsWith(SYSTEM_CONTACT_SOURCE_PREFIX) }
        ?.removePrefix(SYSTEM_CONTACT_SOURCE_PREFIX)
    sourceId?.let { expected -> candidates.firstOrNull { it.sourceId == expected }?.let { return it } }
    val phone = contact.phone?.let(::normalizeContactPhone)
    if (phone != null) {
        candidates.firstOrNull { candidate ->
            candidate.phones.any { normalizeContactPhone(it) == phone }
        }?.let { return it }
    }
    val email = contact.email?.normalizedContactText()
    if (!email.isNullOrBlank()) {
        candidates.firstOrNull { candidate ->
            candidate.emails.any { it.normalizedContactText() == email }
        }?.let { return it }
    }
    return null
}

private fun String.normalizedContactText(): String = trim().lowercase()

private const val SYSTEM_CONTACT_SOURCE_PREFIX = "SYSTEM_CONTACT:"
