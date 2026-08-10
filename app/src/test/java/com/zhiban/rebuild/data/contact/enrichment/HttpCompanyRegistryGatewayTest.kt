package com.zhiban.rebuild.data.contact.enrichment

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCompanyRegistryGatewayTest {
    @Test
    fun `search sends only company query and validates registry evidence`() = runBlocking {
        val captured = AtomicReference<Request>()
        val client = respondingClient(captured, VALID_RESPONSE)
        val subject = HttpCompanyRegistryGateway(client, "https://enrichment.zhiban.test/")

        val result = subject.search("星河科技")

        assertTrue(subject.isConfigured)
        assertEquals("星河科技有限公司", result.single().canonicalName)
        assertEquals("91310000TEST", result.single().creditCode)
        val requestText = Buffer().also { captured.get().body!!.writeTo(it) }.readUtf8()
        assertTrue(requestText.contains("星河科技"))
        assertFalse(requestText.contains("contactId"))
        assertFalse(requestText.contains("phone"))
        assertFalse(requestText.contains("email"))
    }

    @Test
    fun `non https endpoint stays safely disabled`() = runBlocking {
        val subject = HttpCompanyRegistryGateway(OkHttpClient(), "http://127.0.0.1:8787")

        assertFalse(subject.isConfigured)
        val failure = runCatching { subject.search("星河科技") }.exceptionOrNull()
        assertEquals("COMPANY_GATEWAY_NOT_CONFIGURED", failure?.message)
    }

    @Test
    fun `oversized match list is rejected at the external boundary`() = runBlocking {
        val matches = (1..6).joinToString(",") { index ->
            """{"providerRecordId":"$index","canonicalName":"公司$index","confidence":0.8}"""
        }
        val client = respondingClient(AtomicReference(), """{"provider":"qichacha","matches":[$matches]}""")
        val subject = HttpCompanyRegistryGateway(client, "https://enrichment.zhiban.test")

        val failure = runCatching { subject.search("星河科技") }.exceptionOrNull()

        assertEquals("COMPANY_GATEWAY_TOO_MANY_MATCHES", failure?.message)
    }

    private fun respondingClient(captured: AtomicReference<Request>, responseJson: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private companion object {
        val VALID_RESPONSE = """
            {
              "provider":"qichacha",
              "matches":[{
                "providerRecordId":"qcc-1",
                "canonicalName":"星河科技有限公司",
                "creditCode":"91310000TEST",
                "registrationStatus":"存续",
                "registeredAddress":"上海市徐汇区",
                "confidence":0.96,
                "matchReasons":["名称高度一致"]
              }]
            }
        """.trimIndent()
    }
}
