package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.runtime.personalization.UserProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationOwnerProfileVisibilityTest {
    @Test
    fun `completed personal profile does not occupy a contact list row`() {
        val profile = UserProfile(
            name = "周国平",
            preferredName = "老周",
            phone = "13800000000",
            wechatId = "laozhou",
        )

        assertFalse(shouldShowOwnerProfilePrompt(profile, selectedCategory = "全部", query = ""))
    }

    @Test
    fun `incomplete personal profile shows one prompt only in the unfiltered list`() {
        val profile = UserProfile(preferredName = "老周", phone = "13800000000")

        assertTrue(shouldShowOwnerProfilePrompt(profile, selectedCategory = "全部", query = ""))
        assertFalse(shouldShowOwnerProfilePrompt(profile, selectedCategory = "工作", query = ""))
        assertFalse(shouldShowOwnerProfilePrompt(profile, selectedCategory = "全部", query = "老周"))
    }
}
