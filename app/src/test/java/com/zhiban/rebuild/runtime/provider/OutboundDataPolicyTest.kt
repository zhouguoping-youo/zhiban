package com.zhiban.rebuild.runtime.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundDataPolicyTest {
    @Test fun automaticallyRetrievedPersonalIdentifiersAreRedactedWithoutTruncatingContext() = runTest {
        val delegate = CapturingAdapter()
        val audits = mutableListOf<OutboundAuditEvent>()
        val adapter = PolicyEnforcingProviderAdapter(
            delegate,
            DefaultOutboundDataPolicy(),
            OutboundAuditSink(audits::add),
            clock = { 42L },
        )
        val longTail = "后续上下文".repeat(200)
        adapter.stream(
            request(
                message(
                    "联系人电话=13800000000，备用电话=+86 139-1234-5678，邮箱=test@example.com，身份证=11010519491231002X。$longTail",
                    OutboundSensitivity.PERSONAL,
                    OutboundPurpose.AUTO_RETRIEVED,
                ),
            ),
        ).toList()

        val sent = delegate.requests.single().messages.single().content
        assertFalse(sent.contains("13800000000"))
        assertFalse(sent.contains("139-1234-5678"))
        assertFalse(sent.contains("test@example.com"))
        assertFalse(sent.contains("11010519491231002X"))
        assertTrue(sent.contains("138****0000"))
        assertTrue(sent.contains("139****5678"))
        assertTrue(sent.endsWith(longTail))
        assertEquals(1, audits.single().redactedMessageCount)
        assertEquals(42L, audits.single().occurredAtEpochMs)
    }

    @Test fun toolObservationRemovesStructuredPrivateFieldsButKeepsUsefulProfileFields() = runTest {
        val delegate = CapturingAdapter()
        PolicyEnforcingProviderAdapter(delegate, DefaultOutboundDataPolicy()).stream(
            request(
                message(
                    "{\"displayName\":\"张三\",\"company\":\"示例公司\",\"phone\":\"13800000000\"," +
                        "\"wechatId\":\"wx_private_123\",\"note\":\"家庭住址与内部备注\"}",
                    OutboundSensitivity.PERSONAL,
                    OutboundPurpose.TOOL_OBSERVATION,
                ),
            ),
        ).toList()

        val sent = delegate.requests.single().messages.single().content
        assertTrue(sent.contains("张三"))
        assertTrue(sent.contains("示例公司"))
        assertFalse(sent.contains("13800000000"))
        assertFalse(sent.contains("wx_private_123"))
        assertFalse(sent.contains("家庭住址与内部备注"))
    }

    @Test fun userAuthoredIdentifiersRemainIntactButAutomaticSensitiveContentIsOmitted() = runTest {
        val delegate = CapturingAdapter()
        val adapter = PolicyEnforcingProviderAdapter(delegate, DefaultOutboundDataPolicy())
        adapter.stream(
            request(
                message("请给 13800000000 发消息", OutboundSensitivity.PERSONAL, OutboundPurpose.USER_AUTHORED),
                message("张三与李四是客户关系", OutboundSensitivity.SENSITIVE, OutboundPurpose.AUTO_RETRIEVED),
            ),
        ).toList()

        val sent = delegate.requests.single().messages
        assertTrue(sent[0].content.contains("13800000000"))
        assertEquals("[已省略敏感内容]", sent[1].content)
    }

    @Test fun userCanDisableAllAutomaticallyRetrievedPersonalContext() = runTest {
        val delegate = CapturingAdapter()
        val policy = DefaultOutboundDataPolicy {
            OutboundPolicySettings(allowRedactedAutomaticPersonalContext = false)
        }
        PolicyEnforcingProviderAdapter(delegate, policy).stream(
            request(
                message("姓名=张三，公司=示例公司", OutboundSensitivity.PERSONAL, OutboundPurpose.AUTO_RETRIEVED),
            ),
        ).toList()

        assertEquals("[已关闭自动个人资料发送]", delegate.requests.single().messages.single().content)
    }

    @Test fun policyAndAuditRunOnlyWhenFlowIsCollectedAndCancelDelegates() = runTest {
        val delegate = CapturingAdapter()
        val audits = mutableListOf<OutboundAuditEvent>()
        val adapter = PolicyEnforcingProviderAdapter(
            delegate,
            DefaultOutboundDataPolicy(),
            OutboundAuditSink(audits::add),
        )
        adapter.stream(request(message("公开", OutboundSensitivity.PUBLIC, OutboundPurpose.SYSTEM_INSTRUCTION)))
        assertTrue(delegate.requests.isEmpty())
        assertTrue(audits.isEmpty())

        assertTrue(adapter.cancel("request-1"))
        assertEquals("request-1", delegate.cancelled)
    }

    @Test fun automaticAttachmentsFailClosed() = runTest {
        val attachment = ModelAttachment(
            attachmentId = "attachment-1",
            kind = "IMAGE",
            mimeType = "image/png",
            byteLength = 8,
            sha256Digest = "a".repeat(64),
            contentRef = "cache://attachment-1",
            expiresAtEpochMs = 100,
            sensitivity = OutboundSensitivity.SENSITIVE,
            purpose = OutboundPurpose.AUTO_RETRIEVED,
            provenance = OutboundProvenance("automatic_attachment", "attachment-1"),
        )
        val failure = runCatching {
            PolicyEnforcingProviderAdapter(CapturingAdapter(), DefaultOutboundDataPolicy())
                .stream(request(attachments = listOf(attachment))).toList()
        }.exceptionOrNull()
        assertEquals("AUTOMATIC_SENSITIVE_ATTACHMENT_BLOCKED", failure?.message)
    }

    @Test fun nonModelChannelsDefaultClosedAndRecordMetadataOnlyBlock() = runTest {
        val audits = mutableListOf<OutboundAuditEvent>()
        val gate = OutboundExportGate(
            settings = { OutboundPolicySettings() },
            auditSink = OutboundAuditSink(audits::add),
            clock = { 77L },
        )

        val decision = gate.evaluate(
            OutboundExportDescriptor(
                requestId = "safe-request-id",
                channel = OutboundChannel.MCP_REMOTE,
                purpose = OutboundPurpose.USER_AUTHORED,
                sensitivities = setOf(OutboundSensitivity.SENSITIVE),
                payloadCount = 1,
                byteCount = 128,
            ),
        )

        assertEquals(OutboundExportDecision.CONSENT_REQUIRED, decision)
        assertEquals(OutboundAuditOutcome.BLOCKED_CONSENT, audits.single().outcome)
        assertEquals(128L, audits.single().byteCount)
        assertEquals(77L, audits.single().occurredAtEpochMs)
    }

    @Test fun contentPolicyStillBlocksSensitiveEmbeddingAfterConsent() = runTest {
        val audits = mutableListOf<OutboundAuditEvent>()
        val gate = OutboundExportGate(
            settings = { OutboundPolicySettings(allowRemoteEmbedding = true) },
            auditSink = OutboundAuditSink(audits::add),
        )

        val decision = gate.evaluate(
            OutboundExportDescriptor(
                requestId = "embedding-request",
                channel = OutboundChannel.EMBEDDING,
                purpose = OutboundPurpose.AUTO_RETRIEVED,
                sensitivities = setOf(OutboundSensitivity.SENSITIVE),
                payloadCount = 2,
            ),
            contentAllowed = false,
        )

        assertEquals(OutboundExportDecision.CONTENT_BLOCKED, decision)
        assertEquals(OutboundAuditOutcome.BLOCKED_POLICY, audits.single().outcome)
        assertEquals(2, audits.single().omittedMessageCount)
    }

    @Test fun embeddingDetectorFindsDirectIdentifiersButNotOrdinaryCompanyContext() {
        assertTrue(OutboundPiiDetector.containsDirectIdentifier("电话 13800000000"))
        assertTrue(OutboundPiiDetector.containsDirectIdentifier("邮箱 zhang@example.com"))
        assertTrue(OutboundPiiDetector.containsDirectIdentifier("api_key=secret-value"))
        assertFalse(OutboundPiiDetector.containsDirectIdentifier("张三在示例科技担任销售经理"))
    }

    private fun request(vararg messages: ModelMessage, attachments: List<ModelAttachment> = emptyList()) = ModelRequest(
        requestId = "request-1",
        channel = OutboundChannel.LLM_INFERENCE,
        profile = ProviderProfile("stepfun", "endpoint", "model", "credential", 1),
        messages = messages.toList(),
        capability = CapabilitySnapshot("digest", setOf("text"), emptySet(), 8_000, 1_000, 0, Long.MAX_VALUE),
        maxTokens = 100,
        attachments = attachments,
    )

    private fun message(content: String, sensitivity: OutboundSensitivity, purpose: OutboundPurpose) =
        ModelMessage("user", content, sensitivity, purpose, OutboundProvenance("test_source", "test-1"))

    private class CapturingAdapter : ProviderAdapter {
        val requests = mutableListOf<ModelRequest>()
        var cancelled: String? = null
        override suspend fun probe(profile: ProviderProfile) = error("unused")
        override fun stream(request: ModelRequest): Flow<ModelEvent> {
            requests += request
            return flowOf(ModelEvent.Final("stop"))
        }
        override fun cancel(requestId: String): Boolean {
            cancelled = requestId
            return true
        }
    }
}
