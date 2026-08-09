package com.zhiban.rebuild

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedIntentExtractionTest {
    @Test
    fun malformedExtraTypesAreRejectedWithoutEscapingAnException() {
        val malformed = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, Uri.parse("content://invalid-subject"))
            putExtra(Intent.EXTRA_TEXT, Uri.parse("content://invalid-text"))
            putExtra(Intent.EXTRA_STREAM, "not-a-uri")
        }

        assertNull(safeSharedSubject(malformed))
        assertNull(safeSharedText(malformed))
        assertNull(safeSharedImageUri(malformed))
    }

    @Test
    fun validSpecialCharactersAndImageUriArePreserved() {
        val image = Uri.parse("content://images/chat-screenshot")
        val valid = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "客户🙂")
            putExtra(Intent.EXTRA_TEXT, "第一行🙂\nhttps://example.com/?q=知伴")
            putExtra(Intent.EXTRA_STREAM, image)
        }

        assertEquals("客户🙂", safeSharedSubject(valid))
        assertEquals("第一行🙂\nhttps://example.com/?q=知伴", safeSharedText(valid))
        assertEquals(image, safeSharedImageUri(valid))
    }
}
