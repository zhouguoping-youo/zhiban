package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.runtime.provider.OutboundAuditSink
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPolicySettings
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class IlinkOutboundGateTest {
    @Test fun sendRequiresConsent() = runTest {
        val gate = IlinkOutboundGate(gateWith(allowWechatIlink = false))
        val failure = runCatching { gate.requireSendAllowed("req-1", 128L) }.exceptionOrNull()
        assertConsentRequired(failure)
    }

    @Test fun fetchAndBindAlsoRequireConsent() = runTest {
        val gate = IlinkOutboundGate(gateWith(allowWechatIlink = false))
        assertConsentRequired(runCatching { gate.requireFetchAllowed("req-2") }.exceptionOrNull())
        assertConsentRequired(runCatching { gate.requireBindAllowed("req-3") }.exceptionOrNull())
    }

    @Test fun allOperationsPassOnceConsentGranted() = runTest {
        val gate = IlinkOutboundGate(gateWith(allowWechatIlink = true))
        // Should not throw.
        gate.requireSendAllowed("req-4", 128L)
        gate.requireFetchAllowed("req-5")
        gate.requireBindAllowed("req-6")
    }

    private fun gateWith(allowWechatIlink: Boolean) = OutboundExportGate(
        settings = { OutboundPolicySettings(allowWechatIlink = allowWechatIlink) },
        auditSink = OutboundAuditSink { },
    )

    private fun assertConsentRequired(failure: Throwable?) {
        val providerFailure = failure as? ProviderFailure
            ?: throw AssertionError("expected ProviderFailure, got $failure")
        assertEquals("WECHAT_ILINK_CONSENT_REQUIRED", providerFailure.code)
    }
}
