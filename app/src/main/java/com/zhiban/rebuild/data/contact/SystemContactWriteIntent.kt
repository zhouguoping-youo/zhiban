package com.zhiban.rebuild.data.contact

import android.content.Intent
import android.provider.ContactsContract

/**
 * Builds the Android-owned contact editor.
 *
 * ZhiBan never writes the Contacts Provider directly here. The system editor
 * shows every field and only persists after the user taps its save action.
 */
object SystemContactWriteIntent {
    fun create(contact: ContactEntity): Intent = Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, contact.displayName)
        contact.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        contact.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        contact.company?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        contact.title?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
        contact.note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
    }
}
