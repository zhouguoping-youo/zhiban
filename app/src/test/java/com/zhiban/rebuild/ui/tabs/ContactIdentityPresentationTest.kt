package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactIdentityPresentationTest {
    @Test
    fun `merged identical platform handles render once and prefer confirmed identity`() {
        val identities = listOf(
            identity("old", userConfirmed = false, updatedAt = 20L, handle = "kiko_zhou2011"),
            identity("confirmed", userConfirmed = true, updatedAt = 10L, handle = "kiko_zhou2011"),
            identity("other", userConfirmed = false, updatedAt = 30L, handle = "different"),
        )

        val visible = deduplicatePlatformIdentities(identities)

        assertEquals(2, visible.size)
        assertEquals("confirmed", visible.first().identityId)
    }

    private fun identity(
        id: String,
        userConfirmed: Boolean,
        updatedAt: Long,
        handle: String,
    ) = ContactPlatformIdentityEntity(
        identityId = id,
        contactId = "contact",
        platform = "WECHAT",
        handle = handle,
        normalizedHandle = handle.lowercase(),
        platformUserId = null,
        source = "TEST",
        userConfirmed = userConfirmed,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = updatedAt,
    )
}
