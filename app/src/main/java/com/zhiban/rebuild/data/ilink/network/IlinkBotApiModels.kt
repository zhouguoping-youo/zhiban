package com.zhiban.rebuild.data.ilink.network

/*
 * Request/response models for the iLink Bot API (`ilinkai.weixin.qq.com`).
 *
 * These are deliberately plain data classes; serialization is done with `JsonObject` navigation in
 * [OkHttpIlinkBotClient] rather than strict `@Serializable` mapping, because the protocol is only
 * documented through community reverse-engineering and may carry extra/optional fields that a strict
 * schema would reject. Every protocol-specific literal (endpoints, header names, channel versions,
 * "ghost" fields) is isolated to the network layer so a real-device correction touches one place.
 */

/** QR code returned by `get_bot_qrcode`, shown to the user so they can scan it with WeChat. */
data class IlinkBotQrcode(val qrcode: String, val qrcodeImgUrl: String?)

/** Credentials material returned once the user confirms the scan (`status == "confirmed"`). */
data class IlinkConfirmedSession(val botToken: String, val ilinkBotId: String, val ilinkUserId: String, val baseUrl: String)

/** Bind status machine for the QR scan poll loop. */
sealed interface IlinkBindStatus {
    /** Code shown, not yet scanned. */
    data object Waiting : IlinkBindStatus

    /** User scanned the code in WeChat, confirmation pending on their phone. */
    data object Scanned : IlinkBindStatus

    /** QR code expired before confirmation; the controller should fetch a fresh one. */
    data object Expired : IlinkBindStatus

    /** User confirmed on their phone; credentials are ready to persist. */
    data class Confirmed(val session: IlinkConfirmedSession) : IlinkBindStatus
}

/** One inbound message as delivered by `getupdates`. Only user messages (`messageType == 1`) matter. */
data class IlinkInboundMessage(
    val seq: Long,
    val messageId: Long,
    val fromUserId: String,
    val toUserId: String,
    val createTimeMs: Long,
    val messageType: Int,
    val itemType: Int,
    /** Full text for text messages, or the server-side ASR transcript for voice messages. */
    val text: String?,
    /** Opaque token that must be echoed back verbatim when replying to this conversation. */
    val contextToken: String?,
) {
    val isUserAuthored: Boolean get() = messageType == MESSAGE_TYPE_USER

    companion object {
        const val MESSAGE_TYPE_USER = 1
        const val MESSAGE_TYPE_BOT = 2

        const val ITEM_TEXT = 1
        const val ITEM_IMAGE = 2
        const val ITEM_VOICE = 3
        const val ITEM_FILE = 4
        const val ITEM_VIDEO = 5
    }
}

/** A page of `getupdates` results plus the opaque cursor to echo on the next call. */
data class IlinkUpdatesPage(
    val messages: List<IlinkInboundMessage>,
    /** Opaque server cursor; never parsed or modified, persisted and echoed verbatim. */
    val getUpdatesBuf: String,
    val longpollingTimeoutMs: Long?,
)

/** One outbound text message to send via `sendmessage`. */
data class IlinkOutboundMessage(
    val toUserId: String,
    /** Unique-per-message random hex id used by the server for dedup/routing. */
    val clientId: String,
    val text: String,
    /** Echo of the inbound `contextToken` when replying; empty string for a cold proactive push. */
    val contextToken: String,
)

/** Successful `sendmessage` acknowledgement (`ret == 0`). */
data class IlinkSendResult(val messageId: Long?)
