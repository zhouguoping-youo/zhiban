package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundExportDecision
import com.zhiban.rebuild.runtime.provider.OutboundExportDescriptor
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single choke point through which every iLink network call (bind handshake, inbound poll, outbound
 * send) passes before touching the wire (R18). Consent comes from the `WECHAT_ILINK` channel flag,
 * which is set when the user enables the WeChat channel and cleared on unbind. Every call is
 * recorded as metadata-only audit; message text never reaches the audit sink.
 */
@Singleton
class IlinkOutboundGate @Inject constructor(private val gate: OutboundExportGate) {

    /** Gate an outbound message send (user-confirmed text leaves the device). */
    suspend fun requireSendAllowed(requestId: String, byteCount: Long) =
        requireAllowed(requestId, OutboundPurpose.USER_AUTHORED, OutboundSensitivity.SENSITIVE, byteCount)

    /** Gate an inbound `getupdates` poll (only an opaque cursor leaves the device). */
    suspend fun requireFetchAllowed(requestId: String) = requireAllowed(requestId, OutboundPurpose.AUTO_RETRIEVED, OutboundSensitivity.PUBLIC, byteCount = 0L)

    /** Gate a bind-handshake call (QR fetch / status poll; carries no user content). */
    suspend fun requireBindAllowed(requestId: String) = requireAllowed(requestId, OutboundPurpose.USER_AUTHORED, OutboundSensitivity.PUBLIC, byteCount = 0L)

    private suspend fun requireAllowed(requestId: String, purpose: OutboundPurpose, sensitivity: OutboundSensitivity, byteCount: Long) {
        val decision = gate.evaluate(
            OutboundExportDescriptor(
                requestId = requestId,
                channel = OutboundChannel.WECHAT_ILINK,
                purpose = purpose,
                sensitivities = setOf(sensitivity),
                payloadCount = 1,
                byteCount = byteCount,
            ),
        )
        if (decision != OutboundExportDecision.ALLOWED) {
            throw ProviderFailure("WECHAT_ILINK_CONSENT_REQUIRED", retryable = false)
        }
    }
}
