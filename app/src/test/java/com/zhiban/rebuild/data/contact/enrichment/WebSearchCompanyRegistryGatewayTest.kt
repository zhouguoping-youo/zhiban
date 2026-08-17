package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.provider.WebSearchGateway
import com.zhiban.rebuild.runtime.provider.WebSearchHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchCompanyRegistryGatewayTest {
    private var clock = 1_000L

    private fun gateway(webSearch: WebSearchGateway) = WebSearchCompanyRegistryGateway(webSearch) { clock }

    @Test
    fun `parses a full name containing the hint and exposes its source url`() = runBlocking {
        val webSearch = WebSearchGateway { _, _ ->
            listOf(WebSearchHit("北京星河科技有限公司-公司简介", "https://site-a.example/about", "北京星河科技有限公司 成立于2010年"))
        }
        val subject = gateway(webSearch)

        val match = subject.search("星河科技").single()

        assertEquals("北京星河科技有限公司", match.canonicalName)
        assertEquals("https://site-a.example/about", match.sourceUrl)
        assertEquals(0.65, match.confidence, 1e-9)
        assertTrue(match.matchReasons.any { it.contains("星河科技") })
        assertEquals("company-registry:websearch", subject.providerId)
        assertEquals("网络公开信息", subject.sourceLabel)
        assertTrue(subject.isConfigured)
    }

    @Test
    fun `an authoritative source lifts confidence to the cap`() = runBlocking {
        val webSearch = WebSearchGateway { _, _ ->
            listOf(WebSearchHit("北京星河科技有限公司 - 企查查", "https://www.qcc.com/firm/1", "北京星河科技有限公司"))
        }

        val match = gateway(webSearch).search("星河科技").single()

        assertEquals(0.80, match.confidence, 1e-9)
        assertTrue(match.matchReasons.any { it.contains("企查查") })
    }

    @Test
    fun `the same name on two independent domains is corroborated`() = runBlocking {
        val webSearch = WebSearchGateway { _, _ ->
            listOf(
                WebSearchHit("北京星河科技有限公司", "https://site-a.example/1", "北京星河科技有限公司"),
                WebSearchHit("北京星河科技有限公司", "https://site-b.example/2", "北京星河科技有限公司"),
            )
        }

        val match = gateway(webSearch).search("星河科技").single()

        assertEquals(0.80, match.confidence, 1e-9)
        assertTrue(match.matchReasons.any { it.contains("多来源一致") })
    }

    @Test
    fun `a corroborated name that does not contain the hint is never a completion`() = runBlocking {
        val webSearch = WebSearchGateway { _, _ ->
            listOf(
                WebSearchHit("荣耀终端有限公司 - 企查查", "https://www.qcc.com/firm/9", "荣耀终端有限公司"),
                WebSearchHit("荣耀终端有限公司 - 天眼查", "https://www.tianyancha.com/c/9", "荣耀终端有限公司"),
            )
        }

        assertTrue(gateway(webSearch).search("华为").isEmpty())
    }

    @Test
    fun `search failure degrades to empty without throwing`() = runBlocking {
        val webSearch = WebSearchGateway { _, _ -> throw ProviderFailure("WEB_SEARCH_PROVIDER_UNSUPPORTED", false) }

        assertTrue(gateway(webSearch).search("星河科技").isEmpty())
    }

    @Test
    fun `repeat lookups for the same hint hit the cache`() = runBlocking {
        var calls = 0
        val webSearch = WebSearchGateway { _, _ ->
            calls += 1
            listOf(WebSearchHit("星河科技有限公司", "https://site-a.example/1", "星河科技有限公司"))
        }
        val subject = gateway(webSearch)

        subject.search("星河科技")
        subject.search("星河科技")

        assertEquals(1, calls)
    }

    @Test
    fun `full names and non companies are never searched`() = runBlocking {
        var calls = 0
        val webSearch = WebSearchGateway { _, _ ->
            calls += 1
            emptyList()
        }
        val subject = gateway(webSearch)

        assertTrue(subject.search("华为技术有限公司").isEmpty())
        assertTrue(subject.search("无").isEmpty())

        assertEquals(0, calls)
    }
}
