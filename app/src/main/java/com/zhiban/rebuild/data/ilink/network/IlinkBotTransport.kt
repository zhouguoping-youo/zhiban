package com.zhiban.rebuild.data.ilink.network

import com.zhiban.rebuild.runtime.provider.ProviderFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * HTTP 401/403 与协议层 `ret: -14` 同语义:会话被服务端吊销。协调层(拉取/发送)据此清理绑定
 * 与 cursor,不能只认 [IlinkSessionExpiredException](P1-5)。
 */
internal const val ILINK_SESSION_EXPIRED_CODE = "ILINK_SESSION_EXPIRED"

/**
 * Raw iLink Bot API operations. Single-shot, no retry and no credential/outbound-gate concerns —
 * those live in the caller (binding controller / fetch coordinator / send executor), mirroring how
 * `OkHttpStepFunWebSearchTransport` stays a dumb transport under `StepFunWebSearchGateway`.
 */
internal interface IlinkBotTransport {
    /** Fetch a fresh bind QR code. Unauthenticated. */
    suspend fun getBotQrcode(): IlinkBotQrcode

    /** Poll the scan/confirm state of a previously fetched QR code. Unauthenticated. */
    suspend fun getQrcodeStatus(qrcode: String): IlinkBindStatus

    /**
     * Long-poll for inbound messages after [getUpdatesBuf]. The client read timeout must exceed the
     * server hold ([longPollTimeoutMs] + buffer); an empty page on timeout is normal, not an error.
     */
    suspend fun getUpdates(credential: ByteArray, baseUrl: String, getUpdatesBuf: String): IlinkUpdatesPage

    /** Send one outbound text message. Server dedups on [IlinkOutboundMessage.clientId]. */
    suspend fun sendMessage(credential: ByteArray, baseUrl: String, message: IlinkOutboundMessage): IlinkSendResult
}

/**
 * OkHttp [IlinkBotTransport]. Every protocol literal (paths, header names, channel versions, the
 * "ghost" message fields without which the server silently drops a send) is confined here so a
 * real-device protocol correction touches exactly one file.
 */
internal class OkHttpIlinkBotTransport(
    private val client: OkHttpClient,
    private val uinGenerator: WechatUinGenerator,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : IlinkBotTransport {

    override suspend fun getBotQrcode(): IlinkBotQrcode {
        val url = requireNotNull(
            httpUrl(DEFAULT_BASE_URL).newBuilder()
                .encodedPath(PATH_GET_QRCODE)
                .addQueryParameter("bot_type", BOT_TYPE)
                .build(),
        )
        val request = Request.Builder().url(url).get().build()
        return execute(request).use { response ->
            val root = parseObject(readSuccessBody(response, OPERATION_GET_QRCODE))
            val qrcode = root["qrcode"]?.jsonPrimitive?.contentOrNull
                ?: throw ProviderFailure("ILINK_QRCODE_INVALID", false)
            IlinkBotQrcode(
                qrcode = qrcode,
                qrcodeImgUrl = root["qrcode_img_content"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun getQrcodeStatus(qrcode: String): IlinkBindStatus {
        require(qrcode.isNotBlank()) { "ILINK_QRCODE_BLANK" }
        val url = requireNotNull(
            httpUrl(DEFAULT_BASE_URL).newBuilder()
                .encodedPath(PATH_QRCODE_STATUS)
                .addQueryParameter("qrcode", qrcode)
                .build(),
        )
        val request = Request.Builder()
            .url(url)
            .header(HEADER_APP_CLIENT_VERSION, QRCODE_STATUS_CLIENT_VERSION)
            .get()
            .build()
        return execute(request).use { response ->
            parseBindStatus(parseObject(readSuccessBody(response, OPERATION_QRCODE_STATUS)))
        }
    }

    override suspend fun getUpdates(credential: ByteArray, baseUrl: String, getUpdatesBuf: String): IlinkUpdatesPage {
        val body = buildJsonObject {
            put("get_updates_buf", getUpdatesBuf)
            put("base_info", buildJsonObject { put("channel_version", UPDATES_CHANNEL_VERSION) })
        }.toString()
        val request = authed(validateBaseUrl(baseUrl) + PATH_GET_UPDATES, credential)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request).use { response ->
            val text = readSuccessBody(response, OPERATION_GET_UPDATES)
            parseUpdatesPage(parseObject(text))
        }
    }

    override suspend fun sendMessage(credential: ByteArray, baseUrl: String, message: IlinkOutboundMessage): IlinkSendResult {
        val body = buildJsonObject {
            put(
                "msg",
                buildJsonObject {
                    // The five "ghost fields" below are all required: omitting any one makes the API
                    // return HTTP 200 + `{}` yet silently drop the message instead of delivering it.
                    put("from_user_id", "")
                    put("to_user_id", message.toUserId)
                    put("client_id", message.clientId)
                    put("message_type", MESSAGE_TYPE_BOT)
                    put("message_state", MESSAGE_STATE_FINISH)
                    put(
                        "item_list",
                        kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", ITEM_TYPE_TEXT)
                                    put("text_item", buildJsonObject { put("text", message.text) })
                                },
                            )
                        },
                    )
                    put("context_token", message.contextToken)
                },
            )
            put(
                "base_info",
                buildJsonObject {
                    put("channel_version", UPDATES_CHANNEL_VERSION)
                    put("bot_agent", BOT_AGENT)
                },
            )
        }.toString()
        val request = authed(validateBaseUrl(baseUrl) + PATH_SEND_MESSAGE, credential)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request).use { response ->
            val text = readSuccessBody(response, OPERATION_SEND_MESSAGE)
            val root = parseObject(text)
            checkRet(root)
            IlinkSendResult(messageId = root["msg_id"]?.jsonPrimitive?.longOrNull)
        }
    }

    // --- internals ---

    private fun authed(url: String, credential: ByteArray): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${credential.decodeToString()}")
        .header("AuthorizationType", HEADER_AUTH_TYPE_VALUE)
        .header(HEADER_APP_ID, HEADER_APP_ID_VALUE)
        .header(HEADER_APP_CLIENT_VERSION, APP_CLIENT_VERSION)
        .header(HEADER_WECHAT_UIN, uinGenerator.generate())

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(failure.toIlinkFailure())
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            },
        )
    }

    private fun readSuccessBody(response: Response, operation: String): String {
        if (!response.isSuccessful) throw httpFailure(response.code, operation)
        val source = response.body?.source() ?: throw ProviderFailure("ILINK_RESPONSE_INVALID", false)
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw ProviderFailure("ILINK_RESPONSE_TOO_LARGE", false)
        return source.readUtf8()
    }

    private fun parseObject(body: String): JsonObject = (json.parseToJsonElement(body) as? JsonObject) ?: throw ProviderFailure("ILINK_RESPONSE_INVALID", false)

    /** Throws [IlinkSessionExpiredException] on `ret/errcode: -14`, [ProviderFailure] on other non-zero. */
    private fun checkRet(root: JsonObject) {
        val ret = root["ret"]?.jsonPrimitive?.intOrNull ?: root["errcode"]?.jsonPrimitive?.intOrNull ?: 0
        when {
            ret == 0 -> Unit

            ret == RET_SESSION_EXPIRED || root["errcode"]?.jsonPrimitive?.intOrNull == RET_SESSION_EXPIRED ->
                throw IlinkSessionExpiredException()

            else -> throw ProviderFailure("ILINK_REJECTED_$ret", false)
        }
    }

    private fun parseBindStatus(root: JsonObject): IlinkBindStatus {
        checkRet(root)
        return when (root["status"]?.jsonPrimitive?.contentOrNull) {
            "confirmed" -> IlinkBindStatus.Confirmed(
                IlinkConfirmedSession(
                    botToken = root["bot_token"]?.jsonPrimitive?.contentOrNull
                        ?: throw ProviderFailure("ILINK_CONFIRM_INVALID", false),
                    ilinkBotId = root["ilink_bot_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ilinkUserId = root["ilink_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    baseUrl = root["baseurl"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
                ),
            )

            "scaned", "scanned" -> IlinkBindStatus.Scanned

            "expired" -> IlinkBindStatus.Expired

            else -> IlinkBindStatus.Waiting
        }
    }

    private fun parseUpdatesPage(root: JsonObject): IlinkUpdatesPage {
        checkRet(root)
        val messages = root["msgs"]?.jsonArray.orEmpty().mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val firstItem = value["item_list"]?.jsonArray?.firstOrNull() as? JsonObject
            val itemType = firstItem?.get("type")?.jsonPrimitive?.intOrNull ?: IlinkInboundMessage.ITEM_TEXT
            val text = when (itemType) {
                IlinkInboundMessage.ITEM_VOICE -> firstItem?.get("voice_item")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                else -> firstItem?.get("text_item")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            }
            IlinkInboundMessage(
                seq = value["seq"]?.jsonPrimitive?.longOrNull ?: 0L,
                messageId = value["message_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                fromUserId = value["from_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                toUserId = value["to_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                createTimeMs = value["create_time_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                messageType = value["message_type"]?.jsonPrimitive?.intOrNull ?: IlinkInboundMessage.MESSAGE_TYPE_USER,
                itemType = itemType,
                text = text,
                contextToken = value["context_token"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return IlinkUpdatesPage(
            messages = messages,
            getUpdatesBuf = root["get_updates_buf"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            longpollingTimeoutMs = root["longpolling_timeout_ms"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun validateBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.startsWith("https://") && '@' !in trimmed) { "ILINK_BASE_URL_INVALID" }
        return trimmed.ifBlank { DEFAULT_BASE_URL }
    }

    private fun httpUrl(baseUrl: String): okhttp3.HttpUrl = validateBaseUrl(baseUrl).toHttpUrl()

    private fun httpFailure(status: Int, operation: String): ProviderFailure = ProviderFailure(
        code = when (status) {
            401, 403 -> ILINK_SESSION_EXPIRED_CODE
            408 -> "TIMEOUT"
            429 -> "RATE_LIMITED"
            in 500..599 -> "PROVIDER_UNAVAILABLE"
            else -> "ILINK_${operation}_REJECTED"
        },
        retryable = status == 408 || status == 429 || status >= 500,
    )

    private fun IOException.toIlinkFailure(): ProviderFailure = ProviderFailure(
        code = when (this) {
            is SocketTimeoutException -> "TIMEOUT"
            is UnknownHostException -> "NETWORK_OFFLINE"
            is SSLPeerUnverifiedException -> "TLS_VERIFICATION_FAILED"
            else -> "ILINK_UNAVAILABLE"
        },
        retryable = this !is SSLPeerUnverifiedException,
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        const val PATH_GET_QRCODE = "/ilink/bot/get_bot_qrcode"
        const val PATH_QRCODE_STATUS = "/ilink/bot/get_qrcode_status"
        const val PATH_GET_UPDATES = "/ilink/bot/getupdates"
        const val PATH_SEND_MESSAGE = "/ilink/bot/sendmessage"
        const val BOT_TYPE = "3"

        const val HEADER_AUTH_TYPE_VALUE = "ilink_bot_token"
        const val HEADER_APP_ID = "iLink-App-Id"
        const val HEADER_APP_ID_VALUE = "bot"
        const val HEADER_APP_CLIENT_VERSION = "iLink-App-ClientVersion"
        const val HEADER_WECHAT_UIN = "X-WECHAT-UIN"
        const val APP_CLIENT_VERSION = "132102"
        const val QRCODE_STATUS_CLIENT_VERSION = "1"
        const val UPDATES_CHANNEL_VERSION = "1.0.2"
        const val BOT_AGENT = "iLinkBot"

        const val MESSAGE_TYPE_BOT = 2
        const val MESSAGE_STATE_FINISH = 2
        const val ITEM_TYPE_TEXT = 1

        const val RET_SESSION_EXPIRED = -14
        const val MAX_RESPONSE_BYTES = 1_048_576L

        const val OPERATION_GET_QRCODE = "QRCODE"
        const val OPERATION_QRCODE_STATUS = "QRCODE_STATUS"
        const val OPERATION_GET_UPDATES = "GET_UPDATES"
        const val OPERATION_SEND_MESSAGE = "SEND"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
