package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.ilink.ContactWechatResolver
import com.zhiban.rebuild.data.ilink.WechatRecipientResolution
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.CommunicationMessageToolBinding
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.WechatSendToolBinding
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1 边界回归:ContactNotFound 与 NoWechatLink 不再混同。联系人不存在时明确抛
 * ILINK_CONTACT_NOT_FOUND(而不是重定向成无收件人的微信分享面板);有联系人但没学到 userId
 * 才重定向 compose;已学到 userId 与其他工具原样透传。
 */
class WechatSendComposeRedirectTest {

    private fun context() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 1L, 1L, 1L)

    private fun wechatSendEvent(recipient: String, message: String = "你好") = ModelEvent.ToolCall(
        ordinal = 0L,
        providerCallId = "pc-1",
        name = WechatSendToolBinding.TOOL_NAME,
        argumentsJson = """{"recipient":"$recipient","message":"$message"}""",
    )

    private fun contact(name: String) = ContactEntity(
        contactId = "c-$name",
        displayName = name,
        normalizedName = name,
        phone = null,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "USER",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        responsibilities = null,
    )

    private fun redirect(router: CapabilityRouter, resolver: ContactWechatResolver): WechatSendComposeRedirect {
        val channel = mockk<IlinkWechatChannel>(relaxed = true)
        every { channel.resolver } returns resolver
        every { router.canonicalName(WechatSendToolBinding.TOOL_NAME) } returns WechatSendToolBinding.TOOL_NAME
        return WechatSendComposeRedirect(router, channel)
    }

    @Test fun contactNotFoundFailsWithTypedCodeInsteadOfRedirect() = runTest {
        val router = mockk<CapabilityRouter>(relaxed = true)
        val resolver = mockk<ContactWechatResolver>(relaxed = true)
        coEvery { resolver.resolveUserId("不存在的人") } returns WechatRecipientResolution.ContactNotFound

        try {
            redirect(router, resolver).requestApproval(wechatSendEvent("不存在的人"), context())
            fail("联系人不存在应抛 ProviderFailure")
        } catch (failure: ProviderFailure) {
            assertEquals("ILINK_CONTACT_NOT_FOUND", failure.code)
        }
        coVerify(exactly = 0) { router.requestApproval(any(), any()) }
    }

    @Test fun contactWithoutWechatLinkRedirectsToCompose() = runTest {
        val router = mockk<CapabilityRouter>(relaxed = true)
        val resolver = mockk<ContactWechatResolver>(relaxed = true)
        coEvery { resolver.resolveUserId("张三") } returns WechatRecipientResolution.NoWechatLink(contact("张三"))
        coEvery { router.requestApproval(any(), any()) } returns true

        val approved = redirect(router, resolver).requestApproval(wechatSendEvent("张三"), context())

        assertTrue(approved)
        coVerify { router.requestApproval(match { it.name == CommunicationMessageToolBinding.TOOL_NAME }, any()) }
    }

    @Test fun resolvedRecipientGoesThroughTheSendToolUnchanged() = runTest {
        val router = mockk<CapabilityRouter>(relaxed = true)
        val resolver = mockk<ContactWechatResolver>(relaxed = true)
        coEvery { resolver.resolveUserId("张三") } returns WechatRecipientResolution.Resolved(contact("张三"), "zhangsan@im.wechat")
        coEvery { router.requestApproval(any(), any()) } returns true

        val approved = redirect(router, resolver).requestApproval(wechatSendEvent("张三"), context())

        assertTrue(approved)
        coVerify { router.requestApproval(match { it.name == WechatSendToolBinding.TOOL_NAME }, any()) }
    }

    @Test fun otherToolsPassThroughUnchanged() = runTest {
        val router = mockk<CapabilityRouter>(relaxed = true)
        val resolver = mockk<ContactWechatResolver>(relaxed = true)
        every { router.canonicalName("relationship.find") } returns "relationship.find"
        coEvery { router.requestApproval(any(), any()) } returns false

        val approved = redirect(router, resolver).requestApproval(
            ModelEvent.ToolCall(0L, "pc-2", "relationship.find", "{}"),
            context(),
        )

        assertFalse(approved)
        coVerify { router.requestApproval(match { it.name == "relationship.find" }, any()) }
    }
}
