package com.zhiban.rebuild.data.notification

import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 备注漂移检测纯函数:第三级归一化名命中后的"可能是旧备注改名"判断。 */
class IdentityDriftTest {
    private fun identity(id: String, platform: String, handle: String, confirmed: Boolean, updatedAt: Long) = ContactPlatformIdentityEntity(
        identityId = id,
        contactId = "contact-1",
        platform = platform,
        handle = handle,
        normalizedHandle = handle.lowercase(),
        platformUserId = null,
        source = "USER_CONFIRMED_MESSAGE",
        userConfirmed = confirmed,
        createdAtEpochMs = updatedAt,
        updatedAtEpochMs = updatedAt,
    )

    @Test
    fun detectsDriftWhenContactHasDifferentConfirmedHandleOnSamePlatform() {
        val drift = detectIdentityDrift(
            platform = "WECHAT",
            currentNormalizedHandle = "lijiangguo",
            confirmedIdentities = listOf(identity("i-1", "WECHAT", "老李头", confirmed = true, updatedAt = 1_000L)),
        )

        assertNotNull(drift)
        assertEquals("老李头", drift!!.oldHandle)
        assertEquals("i-1", drift.oldIdentityId)
        assertEquals("lijiangguo", drift.newHandle)
        assertEquals("WECHAT", drift.platform)
    }

    @Test
    fun noDriftWithoutMatchingConfirmedIdentity() {
        // 没有已确认身份
        assertNull(detectIdentityDrift("WECHAT", "lijiangguo", emptyList()))
        // 同平台但 handle 相同(第一级就该命中的情形,兜底守卫)
        assertNull(
            detectIdentityDrift(
                "WECHAT",
                "lijiangguo",
                listOf(identity("i-1", "WECHAT", "lijiangguo", confirmed = true, updatedAt = 1_000L)),
            ),
        )
        // 其他平台的身份不算漂移
        assertNull(
            detectIdentityDrift(
                "WECHAT",
                "lijiangguo",
                listOf(identity("i-1", "QQ", "老李头", confirmed = true, updatedAt = 1_000L)),
            ),
        )
        // 未被用户确认的身份不算(系统导入/观察的 handle 不是可靠锚)
        assertNull(
            detectIdentityDrift(
                "WECHAT",
                "lijiangguo",
                listOf(identity("i-1", "WECHAT", "老李头", confirmed = false, updatedAt = 1_000L)),
            ),
        )
    }

    @Test
    fun multipleOldIdentitiesPickMostRecentlyUpdated() {
        val drift = detectIdentityDrift(
            platform = "WECHAT",
            currentNormalizedHandle = "lijiangguo",
            confirmedIdentities = listOf(
                identity("old-1", "WECHAT", "老李头", confirmed = true, updatedAt = 1_000L),
                identity("old-2", "WECHAT", "李哥", confirmed = true, updatedAt = 2_000L),
                identity("old-3", "QQ", "李总", confirmed = true, updatedAt = 3_000L),
            ),
        )

        assertNotNull(drift)
        assertEquals("李哥", drift!!.oldHandle)
        assertEquals("old-2", drift.oldIdentityId)
    }

    @Test
    fun jsonRoundTripSurvivesUiParsing() {
        val info = IdentityDriftInfo(platform = "WECHAT", newHandle = "lijiangguo", oldHandle = "老李头", oldIdentityId = "i-1")

        assertEquals(info, IdentityDriftInfo.fromJson(info.toJson()))
        assertNull(IdentityDriftInfo.fromJson("{broken"))
        assertNull(IdentityDriftInfo.fromJson(""))
    }
}
