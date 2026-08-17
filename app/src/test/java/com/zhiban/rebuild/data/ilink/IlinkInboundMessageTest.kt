package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.ilink.network.IlinkInboundMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IlinkInboundMessageTest {
    @Test fun onlyUserMessageTypeIsUserAuthored() {
        val user = message(messageType = IlinkInboundMessage.MESSAGE_TYPE_USER)
        val bot = message(messageType = IlinkInboundMessage.MESSAGE_TYPE_BOT)
        assertTrue(user.isUserAuthored)
        assertFalse(bot.isUserAuthored)
    }

    private fun message(messageType: Int) = IlinkInboundMessage(
        seq = 1L,
        messageId = 1L,
        fromUserId = "sender@im.wechat",
        toUserId = "bot@im.wechat",
        createTimeMs = 1_000L,
        messageType = messageType,
        itemType = IlinkInboundMessage.ITEM_TEXT,
        text = "你好",
        contextToken = "ctx",
    )
}
