package com.zhiban.rebuild

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.calendar.ScheduleReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedIntentExtractionTest {
    @Test fun scheduleReminderFocusAcceptsOnlyPositiveEpochAndIgnoresMissingInput() {
        assertNull(safeScheduleReminderEpoch(null))
        assertNull(safeScheduleReminderEpoch(Intent()))
        assertNull(
            safeScheduleReminderEpoch(
                Intent().putExtra(ScheduleReminderWorker.EXTRA_OPEN_SCHEDULE_AT, 0L),
            ),
        )
        assertEquals(
            1_800_000L,
            safeScheduleReminderEpoch(
                Intent().putExtra(ScheduleReminderWorker.EXTRA_OPEN_SCHEDULE_AT, 1_800_000L),
            ),
        )
    }
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

    @Test
    fun oversizedSharedTextIsUtf8BoundedWithoutRejectingLanguagesOrEmoji() {
        val prefix = "مرحبا བཀྲ་ཤིས་ 🙂\u0000"
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, prefix + "文".repeat(8_000))
        }

        val payload = safeSharedTextPayload(intent)

        assertTrue(payload.truncated)
        assertTrue(payload.text!!.toByteArray(Charsets.UTF_8).size <= 16 * 1_024)
        assertTrue(payload.text!!.contains("مرحبا"))
        assertTrue(payload.text!!.contains("བཀྲ་ཤིས་"))
        assertTrue(payload.text!!.contains("🙂"))
        assertFalse(payload.text!!.contains('\u0000'))
    }
}
