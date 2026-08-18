package com.zhiban.rebuild.data.reply

import com.zhiban.rebuild.data.communication.CommunicationHandoffLauncher
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of attempting to hand a drafted reply to the user for sending. */
data class ReplyDeliveryResult(val requiresUserSend: Boolean, val launched: Boolean, val errorCode: String? = null)

/**
 * The "last mile" of a reply: get a draft to the user so THEY can send it. The interface exists so a
 * future direct-send executor can replace the
 * handoff without touching the suggestion pipeline. MVP ships only the handoff executor, which always
 * requires the user to press send in the target app — the draft is never auto-sent.
 */
internal interface ReplyDeliveryExecutor {
    suspend fun deliver(platform: String, recipientDisplayName: String, message: String): ReplyDeliveryResult
}

@Singleton
internal class HandoffDeliveryExecutor @Inject constructor(private val handoffLauncher: CommunicationHandoffLauncher) : ReplyDeliveryExecutor {
    override suspend fun deliver(platform: String, recipientDisplayName: String, message: String): ReplyDeliveryResult = try {
        val result = handoffLauncher.open(platform, recipientDisplayName, message)
        ReplyDeliveryResult(requiresUserSend = result.requiresUserSend, launched = result.status == HANDOFF_OPENED)
    } catch (failure: IllegalStateException) {
        ReplyDeliveryResult(requiresUserSend = true, launched = false, errorCode = failure.message)
    }

    private companion object {
        const val HANDOFF_OPENED = "HANDOFF_OPENED"
    }
}
