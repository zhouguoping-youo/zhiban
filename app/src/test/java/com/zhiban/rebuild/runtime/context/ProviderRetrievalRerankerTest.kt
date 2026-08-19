package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.foundation.Sensitivity
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.DefaultOutboundDataPolicy
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundPolicySettings
import com.zhiban.rebuild.provider.PolicyEnforcingProviderAdapter
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.runtime.kernel.ProviderRetrievalReranker
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetrievalRerankerTest {
    private val profile = ProviderProfile("p", "e", "m", "c", 1)
    private val capability = CapabilitySnapshot("d", setOf("text"), setOf("rerank"), 10_000, 1_000, 0, 10_000)
    private val candidates = listOf(
        RankedRetrievalCandidate(RetrievalCandidate("a", "fact", "a", "甲"), .2, setOf(RetrievalPath.FTS)),
        RankedRetrievalCandidate(RetrievalCandidate("b", "fact", "b", "乙"), .1, setOf(RetrievalPath.GRAPH)),
    )

    @Test fun acceptsOnlyKnownUniqueIds() = runTest {
        val adapter = fake("[\"b\",\"a\"]")
        assertEquals(
            listOf("b", "a"),
            ProviderRetrievalReranker(adapter)
                .rerank("乙", candidates, profile, capability, "r").orderedIds,
        )
    }

    @Test fun rejectsInjectedOrDuplicateIdsAndSkipsUnsupportedCapability() = runTest {
        assertTrue(
            runCatching {
                ProviderRetrievalReranker(fake("[\"unknown\"]"))
                    .rerank("x", candidates, profile, capability, "r")
            }.isFailure,
        )
        val unsupported = ProviderRetrievalReranker(fake("[]")).rerank(
            "x",
            candidates,
            profile,
            capability.copy(features = emptySet()),
            "r2",
        )
        assertEquals("rerank_skipped:capability_unavailable", unsupported.degradation)
    }

    @Test fun governedRerankRedactsPersonalIdentifiersAndKeepsSensitiveCandidatesLocal() = runTest {
        val capturing = CapturingAdapter("[\"a\",\"b\"]")
        val governed = PolicyEnforcingProviderAdapter(
            capturing,
            DefaultOutboundDataPolicy { OutboundPolicySettings(allowUnmaskedPhoneNumbers = false) },
        )
        val values = candidates + RankedRetrievalCandidate(
            RetrievalCandidate(
                "relationship-1",
                "relationship",
                "relationship-1",
                "张三与李四是客户关系",
                sensitivity = Sensitivity.SENSITIVE,
            ),
            .3,
            setOf(RetrievalPath.GRAPH),
        ) + RankedRetrievalCandidate(
            RetrievalCandidate("phone", "contact", "phone", "张三，电话=13800000000"),
            .4,
            setOf(RetrievalPath.FTS),
        )

        ProviderRetrievalReranker(governed).rerank("查联系人", values, profile, capability, "rerank-1")

        val request = capturing.requests.single()
        val outbound = request.messages.joinToString("\n", transform = { it.content })
        assertEquals(OutboundChannel.LLM_RERANK, request.channel)
        assertFalse(outbound.contains("13800000000"))
        assertFalse(outbound.contains("张三与李四是客户关系"))
        assertTrue(outbound.contains("138****0000"))
    }

    private fun fake(output: String) = object : ProviderAdapter {
        override suspend fun probe(profile: ProviderProfile) = capability
        override fun stream(request: ModelRequest) = flowOf<ModelEvent>(ModelEvent.Delta(0, output), ModelEvent.Final("stop"))
        override fun cancel(requestId: String) = true
    }

    private inner class CapturingAdapter(private val output: String) : ProviderAdapter {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun probe(profile: ProviderProfile) = capability
        override fun stream(request: ModelRequest) = flowOf<ModelEvent>(
            ModelEvent.Delta(0, output),
            ModelEvent.Final("stop"),
        ).also { requests += request }
        override fun cancel(requestId: String) = true
    }
}
