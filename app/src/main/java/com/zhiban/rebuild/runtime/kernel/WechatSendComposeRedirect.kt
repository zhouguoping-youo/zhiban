package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.ilink.WechatRecipientResolution
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.CommunicationMessageToolBinding
import com.zhiban.rebuild.runtime.tool.RuntimeToolCallRequest
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.WechatSendToolBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Confirmation-request decorator that keeps an unsendable `communication.wechat.send` from dead-ending
 * the run. A direct iLink send is only possible once the recipient has a learned `userId` (they have
 * messaged the bound bot). When the contact exists but has no `userId`, the call is redirected to a
 * `communication.message.compose` confirmation — same recipient and message, platform WECHAT — so the
 * single card lands the user in WeChat with the draft pre-filled and they finish the send there. A
 * recipient that matches no contact is NOT redirected (a share sheet without a recipient helps nobody);
 * it fails with the typed `ILINK_CONTACT_NOT_FOUND` so the Agent can correct itself. Every other tool
 * call (and a cleanly-resolved WeChat send) is forwarded unchanged.
 */
internal class WechatSendComposeRedirect(private val router: CapabilityRouter, private val channel: IlinkWechatChannel?) {
    /** Redirect an unsendable WeChat send to compose; otherwise request confirmation for [event] as-is. */
    suspend fun requestApproval(event: ModelEvent.ToolCall, context: RuntimeToolRouteContext): Boolean {
        if (redirectToCompose(event, context)) return true
        return router.requestApproval(RuntimeToolCallRequest(event.providerCallId, event.name, event.argumentsJson), context)
    }

    /** Propose a compose draft when [event] is a WeChat send whose recipient can't be direct-sent. */
    private suspend fun redirectToCompose(event: ModelEvent.ToolCall, context: RuntimeToolRouteContext): Boolean {
        val resolver = channel?.resolver ?: return false
        if (router.canonicalName(event.name) != WechatSendToolBinding.TOOL_NAME) return false
        val arguments = runCatching { Json.parseToJsonElement(event.argumentsJson).jsonObject }
            .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val recipient = arguments["recipient"]?.jsonPrimitive?.content?.trim()
            ?.take(MAX_RECIPIENT_CHARS)?.takeIf(String::isNotBlank)
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val message = arguments["message"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        when (val resolution = resolver.resolveUserId(recipient)) {
            // 已学到 userId:直发路径,原样走 send 工具的确认卡。
            is WechatRecipientResolution.Resolved -> return false

            // 联系人不存在:明确报错让 Agent 改口/放弃——重定向成 compose 只会拉起无收件人的分享面板。
            is WechatRecipientResolution.ContactNotFound -> throw ProviderFailure("ILINK_CONTACT_NOT_FOUND", false)

            // 联系人存在但没学到 userId:落 compose 手动发(既有设计)。
            is WechatRecipientResolution.NoWechatLink -> Unit
        }
        val composeRequest = RuntimeToolCallRequest(
            providerCallId = event.providerCallId,
            name = CommunicationMessageToolBinding.TOOL_NAME,
            argumentsJson = buildJsonObject {
                put("platform", COMPOSE_PLATFORM_WECHAT)
                put("recipient", recipient)
                put("message", message)
            }.toString(),
        )
        return router.requestApproval(composeRequest, context)
    }

    private companion object {
        const val COMPOSE_PLATFORM_WECHAT = "WECHAT"
        const val MAX_RECIPIENT_CHARS = 120
    }
}
