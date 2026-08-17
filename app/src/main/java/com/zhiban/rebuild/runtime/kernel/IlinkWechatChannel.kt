package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.ilink.ContactWechatResolver
import com.zhiban.rebuild.data.ilink.IlinkBotCredentialStore
import com.zhiban.rebuild.data.ilink.IlinkMessageSender
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.tool.RuntimeToolBinding
import com.zhiban.rebuild.runtime.tool.RuntimeToolSpec
import com.zhiban.rebuild.runtime.tool.WechatSendToolBinding
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The collaborators the WeChat iLink channel needs, bundled because they are only meaningful together
 * (a send needs the recipient resolver, the message sender, and — for tool visibility — the binding
 * state). Grouping them keeps `ProviderEngineInfrastructure` to one optional field and lets the engine
 * treat the whole channel as present-or-absent.
 */
@Singleton
internal class IlinkWechatChannel @Inject constructor(
    val resolver: ContactWechatResolver,
    val sender: IlinkMessageSender,
    val credentialStore: IlinkBotCredentialStore,
) {
    fun sendBinding(spec: RuntimeToolSpec, store: RoomRuntimeStore): RuntimeToolBinding = WechatSendToolBinding(spec, store, resolver, sender)
}
