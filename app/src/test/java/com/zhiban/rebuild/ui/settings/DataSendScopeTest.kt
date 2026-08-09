package com.zhiban.rebuild.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSendScopeTest {
    @Test
    fun everyScopeHasDataTypeNoteAndStatusLabel() {
        DataSendScope.entries.forEach { scope ->
            assertTrue(scope.dataType.isNotBlank())
            assertTrue(scope.note.isNotBlank())
            assertEquals(if (scope.isSent) "发送" else "不发送", scope.statusLabel)
        }
    }

    @Test
    fun directIdentifiersAndRawContentAreNeverSent() {
        assertFalse(DataSendScope.DIRECT_IDENTIFIER.isSent)
        assertFalse(DataSendScope.MESSAGE_CONTENT.isSent)
        assertFalse(DataSendScope.CALL_LOG.isSent)
    }

    @Test
    fun contactIdentityAndVoiceAreMarkedSent() {
        assertTrue(DataSendScope.CONTACT_IDENTITY.isSent)
        assertTrue(DataSendScope.VOICE.isSent)
    }
}
