package com.zhiban.rebuild.runtime.context

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.runtime.embedding.EmbeddingTransport
import com.zhiban.rebuild.runtime.embedding.VolcEmbeddingEnvironment
import com.zhiban.rebuild.runtime.provider.KeystoreCredentialVault
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPolicySettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VolcEmbeddingEnvironmentTest {
    private lateinit var context: Context
    private lateinit var environment: VolcEmbeddingEnvironment
    private lateinit var transport: FakeTransport

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        transport = FakeTransport()
        environment = VolcEmbeddingEnvironment(
            context,
            KeystoreCredentialVault(context),
            transport,
            outboundGate(allow = true),
        )
        environment.clear()
    }

    @After fun tearDown() = runBlocking { environment.clear() }

    @Test fun probePublishesProfileAndCredentialNeverEntersPreferences() = runBlocking {
        val secret = "volc-secret-good".toByteArray()
        val space = environment.configure(secret, "ep-embedding-1")

        assertEquals(8, space.dimensions)
        assertEquals(space, environment.activeSpace())
        assertEquals(1, environment.embed(listOf(input("季度目标")), space).size)
        assertTrue(environment.healthCheck())
        val prefsFile = context.applicationInfo.dataDir + "/shared_prefs/agent_embedding_profile.xml"
        val disk = runCatching { java.io.File(prefsFile).readText() }.getOrDefault("")
        assertFalse(disk.contains("volc-secret-good"))
    }

    @Test fun failedFirstConfigurationDoesNotPublishAndFailedRotationKeepsOldKey() = runBlocking {
        transport.rejectToken = "bad-first"
        assertTrue(runCatching { environment.configure("bad-first".toByteArray(), "ep-one") }.isFailure)
        assertNull(environment.activeSpace())

        transport.rejectToken = null
        val old = environment.configure("old-good-key".toByteArray(), "ep-one")
        transport.rejectToken = "bad-rotation"
        assertTrue(runCatching { environment.configure("bad-rotation".toByteArray(), "ep-two") }.isFailure)

        assertEquals(old, environment.activeSpace())
        assertEquals(8, environment.embed(listOf(input("仍可用")), old).single().size)
        assertEquals("old-good-key", transport.lastAcceptedToken)
    }

    @Test fun remoteEmbeddingRequiresConsentBeforeProbeOrCredentialPublish() = runBlocking {
        val blocked = VolcEmbeddingEnvironment(
            context,
            KeystoreCredentialVault(context),
            transport,
            outboundGate(allow = false),
        )

        val failure = runCatching { blocked.configure("blocked-key".toByteArray(), "ep-blocked") }.exceptionOrNull()

        assertEquals("EMBEDDING_REMOTE_EXPORT_CONSENT_REQUIRED", failure?.message)
        assertEquals(0, transport.callCount)
        assertNull(blocked.activeSpace())
    }

    @Test fun directIdentifiersAndSensitiveInputsNeverReachTransport() = runBlocking {
        val space = environment.configure("good-key".toByteArray(), "ep-safe")
        val before = transport.callCount

        val phoneFailure = runCatching {
            environment.embed(listOf(input("客户电话 13800000000")), space)
        }.exceptionOrNull()
        val sensitiveFailure = runCatching {
            environment.embed(
                listOf(input("张三与李四的关系边", Sensitivity.SENSITIVE)),
                space,
            )
        }.exceptionOrNull()

        assertEquals("EMBEDDING_SENSITIVE_INPUT_BLOCKED", phoneFailure?.message)
        assertEquals("EMBEDDING_SENSITIVE_INPUT_BLOCKED", sensitiveFailure?.message)
        assertEquals(before, transport.callCount)
    }

    private fun input(text: String, sensitivity: Sensitivity = Sensitivity.PERSONAL) = EmbeddingInput(
        text = text,
        sensitivity = sensitivity,
        purpose = EmbeddingPurpose.USER_QUERY,
        sourceKind = "test",
        sourceId = "query",
    )

    private fun outboundGate(allow: Boolean) = OutboundExportGate(
        settings = { OutboundPolicySettings(allowRemoteEmbedding = allow) },
    )

    private class FakeTransport : EmbeddingTransport {
        var rejectToken: String? = null
        var lastAcceptedToken: String? = null
        var callCount: Int = 0
        override fun embed(endpoint: String, model: String, credential: ByteArray, texts: List<String>): List<FloatArray> {
            callCount++
            assertEquals(VolcEmbeddingEnvironment.OFFICIAL_ENDPOINT, endpoint)
            val token = credential.toString(Charsets.UTF_8)
            if (token == rejectToken) error("AUTHENTICATION_FAILED")
            lastAcceptedToken = token
            return texts.mapIndexed { index, _ -> FloatArray(8) { dimension -> (index + dimension + 1).toFloat() } }
        }
    }
}
