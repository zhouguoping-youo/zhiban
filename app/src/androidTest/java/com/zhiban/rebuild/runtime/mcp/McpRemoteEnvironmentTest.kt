package com.zhiban.rebuild.runtime.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.agent.mcp.McpClient
import com.zhiban.agent.mcp.McpProtocolException
import com.zhiban.agent.mcp.McpTransport
import com.zhiban.rebuild.provider.CredentialProvisioner
import com.zhiban.rebuild.provider.OutboundAuditEvent
import com.zhiban.rebuild.provider.OutboundAuditOutcome
import com.zhiban.rebuild.provider.OutboundAuditSink
import com.zhiban.rebuild.provider.OutboundExportGate
import com.zhiban.rebuild.provider.OutboundPolicySettings
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpRemoteEnvironmentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun cleanup() {
        context.getSharedPreferences("runtime_mcp_servers", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun configureDiscoversPersistsTogglesAndRemovesWithoutPersistingToken() = runTest {
        val vault = FakeVault()
        val factory = ScriptedFactory()
        val environment = McpRemoteEnvironment(context, vault, factory, outboundGate()) { 1234L }
        val token = "private-token".toByteArray()

        val health = environment.configure("team", "团队服务", "https://mcp.example.test/rpc", token)

        assertTrue(health.available)
        assertTrue(token.all { it == 0.toByte() })
        assertEquals("mcp.team.tasks.search", environment.tools().single().canonicalName)
        assertEquals(1, environment.servers().single().toolCount)
        val persisted = context.getSharedPreferences(
            "runtime_mcp_servers",
            Context.MODE_PRIVATE,
        ).all.values.joinToString()
        assertFalse(persisted.contains("private-token"))
        assertTrue(factory.lastCredentialRef!!.startsWith("mcp.team.pending."))

        environment.setEnabled("team", false)
        assertTrue(environment.tools().isEmpty())
        environment.setEnabled("team", true)
        assertEquals(1, environment.tools().size)

        environment.remove("team")
        assertTrue(environment.servers().isEmpty())
        assertFalse(vault.contains("mcp.team.bearer", 1))
    }

    @Test fun failedDiscoveryDoesNotPublishServerAndRollsBackNewCredential() = runTest {
        val vault = FakeVault()
        val environment = McpRemoteEnvironment(
            context,
            vault,
            ScriptedFactory(failList = true),
            outboundGate(),
        ) { 99L }

        val failure = runCatching {
            environment.configure("broken", "故障服务", "https://mcp.example.test/rpc", "secret".toByteArray())
        }.exceptionOrNull()

        assertTrue(failure is McpProtocolException)
        assertTrue(environment.servers().isEmpty())
        assertFalse(vault.contains("mcp.broken.bearer", 1))
    }

    @Test fun failedCredentialRotationKeepsPreviouslyVerifiedCredentialAndConfiguration() = runTest {
        val vault = FakeVault()
        val factory = ScriptedFactory()
        val environment = McpRemoteEnvironment(context, vault, factory, outboundGate()) { 77L }
        environment.configure("team", "团队服务", "https://mcp.example.test/rpc", "old-token".toByteArray())
        factory.failList = true

        assertTrue(
            runCatching {
                environment.configure("team", "团队服务", "https://mcp.example.test/rpc", "bad-new-token".toByteArray())
            }.isFailure,
        )

        assertEquals("old-token", vault.read("mcp.team.bearer", 1))
        assertEquals(1, environment.servers().size)
    }

    @Test fun remoteArgumentsRequireIndependentConsentAndAuditContainsNoArguments() = runTest {
        val events = mutableListOf<OutboundAuditEvent>()
        val factory = ScriptedFactory()
        val environment = McpRemoteEnvironment(
            context,
            FakeVault(),
            factory,
            outboundGate(allow = false, events = events),
        ) { 1_234L }
        environment.configure("team", "团队服务", "https://mcp.example.test/rpc", null)
        val arguments = buildJsonObject { put("phone", "13800000000") }

        val failure = runCatching { environment.call("team", "tasks.search", arguments) }.exceptionOrNull()

        assertEquals("MCP_REMOTE_EXPORT_CONSENT_REQUIRED", failure?.message)
        assertEquals(0, factory.callCount)
        assertEquals(OutboundAuditOutcome.BLOCKED_CONSENT, events.single().outcome)
        assertFalse(events.single().toString().contains("13800000000"))
    }

    @Test fun consentedRemoteArgumentsReachDiscoveredToolOnce() = runTest {
        val factory = ScriptedFactory()
        val environment = McpRemoteEnvironment(
            context,
            FakeVault(),
            factory,
            outboundGate(allow = true),
        ) { 2_000L }
        environment.configure("team", "团队服务", "https://mcp.example.test/rpc", null)

        val result = environment.call("team", "tasks.search", buildJsonObject { put("query", "本周") })

        assertFalse(result.isError)
        assertEquals(1, factory.callCount)
    }

    private fun outboundGate(allow: Boolean = true, events: MutableList<OutboundAuditEvent> = mutableListOf()) = OutboundExportGate(
        settings = { OutboundPolicySettings(allowRemoteMcp = allow) },
        auditSink = OutboundAuditSink(events::add),
        clock = { 9_999L },
    )

    private class FakeVault : CredentialProvisioner {
        private val values = ConcurrentHashMap<String, ByteArray>()
        override suspend fun provision(credentialRef: String, keyVersion: Int, credential: ByteArray) {
            values["$credentialRef:$keyVersion"] = credential.copyOf()
        }
        override suspend fun delete(credentialRef: String, keyVersion: Int) {
            values.remove("$credentialRef:$keyVersion")?.fill(0)
        }
        override suspend fun contains(credentialRef: String, keyVersion: Int) = values.containsKey("$credentialRef:$keyVersion")
        fun read(credentialRef: String, keyVersion: Int) = values["$credentialRef:$keyVersion"]?.toString(Charsets.UTF_8)
    }

    private class ScriptedFactory(var failList: Boolean = false) : McpConnectionFactory {
        var lastCredentialRef: String? = null
        var callCount: Int = 0
        override fun create(endpoint: String, credentialRef: String?): McpClient {
            lastCredentialRef = credentialRef
            return McpClient(
                McpTransport { request ->
                    val id = request["id"]?.jsonPrimitive?.content
                    when (request["method"]?.jsonPrimitive?.content) {
                        "initialize" -> response(
                            id,
                            buildJsonObject {
                                put("protocolVersion", McpClient.PROTOCOL_VERSION)
                                put(
                                    "serverInfo",
                                    buildJsonObject {
                                        put("name", "test-server")
                                        put("version", "1.2")
                                    },
                                )
                            },
                        )

                        "notifications/initialized" -> null

                        "tools/list" -> if (failList) {
                            throw McpProtocolException("MCP_REMOTE_ERROR")
                        } else {
                            response(
                                id,
                                buildJsonObject {
                                    put(
                                        "tools",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("name", "tasks.search")
                                                    put("description", "查询团队任务")
                                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }

                        "tools/call" -> {
                            callCount++
                            response(
                                id,
                                buildJsonObject {
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", "ok")
                                                },
                                            )
                                        },
                                    )
                                    put("isError", false)
                                },
                            )
                        }

                        else -> null
                    }
                },
            )
        }

        private fun response(id: String?, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requireNotNull(id))
            put("result", result)
        }
    }
}
