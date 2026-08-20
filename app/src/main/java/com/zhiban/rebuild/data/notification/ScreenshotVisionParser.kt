package com.zhiban.rebuild.data.notification

import android.util.Log
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.provider.ModelAttachment
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelMessage
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundProvenance
import com.zhiban.rebuild.provider.OutboundPurpose
import com.zhiban.rebuild.provider.OutboundSensitivity
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.runtime.input.AttachmentStagingGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Optional screenshot fallback. OCR remains the first and local-only path. */
@Singleton
internal class ScreenshotVisionParser @Inject constructor(
    private val controls: AgentControlStore,
    private val staging: AttachmentStagingGateway,
    private val environment: ProviderEnvironmentManager,
    private val provider: ProviderAdapter,
) {
    suspend fun parse(contentRef: String): String? {
        if (!controls.screenshotVisionEnabled()) return null
        val now = System.currentTimeMillis()
        val profile = environment.activeProfile() ?: return null
        val capability = environment.healthCheck().capability
            ?.takeIf { "image" in it.modalities }
            ?: return null
        val attachment = try {
            staging.stage("screenshot-vision-$now", contentRef, now + ATTACHMENT_TTL_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Log.i(TAG, "screenshot_vision:stage_rejected")
            return null
        }
        return try {
            val request = ModelRequest(
                requestId = "screenshot-vision-${attachment.attachmentId}",
                channel = OutboundChannel.LLM_INFERENCE,
                profile = profile,
                messages = listOf(
                    ModelMessage(
                        role = "system",
                        content = SYSTEM_PROMPT,
                        sensitivity = OutboundSensitivity.PUBLIC,
                        purpose = OutboundPurpose.SYSTEM_INSTRUCTION,
                        provenance = OutboundProvenance("screenshot_vision", "schema"),
                    ),
                    // The OpenAI-compatible adapter attaches image parts to the last user
                    // message. Keep this instruction fixed and public; never echo OCR or image
                    // contents into the text channel.
                    ModelMessage(
                        role = "user",
                        content = "请分析这张用户主动分享的截图，并按要求返回 JSON。",
                        sensitivity = OutboundSensitivity.PUBLIC,
                        purpose = OutboundPurpose.SYSTEM_INSTRUCTION,
                        provenance = OutboundProvenance("screenshot_vision", "request"),
                    ),
                ),
                capability = capability,
                maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
                jsonSchema = RESPONSE_SCHEMA,
                attachments = listOf(
                    ModelAttachment(
                        attachmentId = attachment.attachmentId,
                        kind = attachment.kind.name,
                        mimeType = attachment.mimeType,
                        byteLength = attachment.byteLength,
                        sha256Digest = attachment.sha256Digest,
                        contentRef = attachment.contentRef,
                        expiresAtEpochMs = attachment.expiresAtEpochMs,
                        sensitivity = OutboundSensitivity.SENSITIVE,
                        purpose = OutboundPurpose.USER_SELECTED_ATTACHMENT,
                        provenance = OutboundProvenance("shared_screenshot", attachment.attachmentId),
                    ),
                ),
            )
            val raw = buildString {
                withTimeoutOrNull(VISION_TIMEOUT_MS) {
                    provider.stream(request)
                        .takeWhile { event ->
                            when (event) {
                                is ModelEvent.Delta -> append(event.text)
                                is ModelEvent.Final -> false
                                else -> true
                            }
                            event !is ModelEvent.Final
                        }
                        .collect()
                }
            }
            ScreenshotVisionCandidateFormatter.formatCandidate(raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Log.i(TAG, "screenshot_vision:unavailable")
            null
        } finally {
            withContext(NonCancellable) {
                try {
                    staging.discard(attachment.attachmentId)
                } catch (_: Throwable) {
                    Log.i(TAG, "screenshot_vision:cleanup_failed")
                }
            }
        }
    }

    private companion object {
        const val TAG = "ScreenshotVision"
        const val ATTACHMENT_TTL_MS = 5 * 60 * 1_000L
        const val VISION_TIMEOUT_MS = 30_000L
        const val MAX_OUTPUT_TOKENS = 700
        const val SYSTEM_PROMPT = """
            只从图片中提取可见信息，输出严格 JSON，不要解释，不要猜测。联系人和日程都可以为空。
            字段：contacts 数组(name, phone, email, company, title, relationship)，schedules 数组(title, dateTime, location)。
            无法确认的字段留空；不要输出图片外的信息。
        """
        const val RESPONSE_SCHEMA = """
            {"type":"object","additionalProperties":false,"properties":{
              "contacts":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{
                "name":{"type":"string"},"phone":{"type":"string"},"email":{"type":"string"},
                "company":{"type":"string"},"title":{"type":"string"},"relationship":{"type":"string"}}}},
              "schedules":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{
                "title":{"type":"string"},"dateTime":{"type":"string"},"location":{"type":"string"}}}}
            },"required":["contacts","schedules"]}
        """
    }
}

internal object ScreenshotVisionCandidateFormatter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }
    private val phonePattern = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val companyPattern = Regex("(?:有限公司|股份|集团|科技|医院|学校|大学|公司)")
    private val timePattern = Regex("(?:今天|明天|后天|\\d{1,2}月\\d{1,2}日|\\d{1,2}[:：]\\d{2}|\\d{1,2}点)")

    fun isStructuredOcr(text: String): Boolean = listOf(
        phonePattern.containsMatchIn(text),
        emailPattern.containsMatchIn(text),
        companyPattern.containsMatchIn(text),
        timePattern.containsMatchIn(text),
    ).count { it } >= 1

    fun formatCandidate(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw.trim()).jsonObject
        val contacts = root["contacts"]?.jsonArray.orEmpty().mapNotNull { it.asCandidate("联系人") }
        val schedules = root["schedules"]?.jsonArray.orEmpty().mapNotNull { it.asCandidate("日程") }
        (contacts + schedules).take(8).takeIf(List<String>::isNotEmpty)?.joinToString("\n")
    }.getOrNull()

    private fun JsonElement.asCandidate(prefix: String): String? {
        val values = jsonObject.entries.mapNotNull { (key, value) ->
            value.jsonPrimitive.content.trim().takeIf(String::isNotBlank)?.let { "$key: $it" }
        }
        return values.take(8).takeIf(List<String>::isNotEmpty)?.let { "${prefix}候选 · ${it.joinToString(" · ")}" }
    }
}
