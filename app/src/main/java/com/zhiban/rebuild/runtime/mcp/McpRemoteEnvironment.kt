package com.zhiban.rebuild.runtime.mcp

import android.content.Context
import com.zhiban.agent.mcp.McpClient
import com.zhiban.agent.mcp.McpToolDescriptor
import com.zhiban.agent.mcp.McpToolResult
import com.zhiban.agent.mcp.StreamableHttpMcpTransport
import com.zhiban.rebuild.runtime.provider.CredentialProvisioner
import com.zhiban.rebuild.runtime.provider.CredentialResolver
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundExportDecision
import com.zhiban.rebuild.runtime.provider.OutboundExportDescriptor
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.tool.RuntimeToolCatalog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

data class McpRemoteServer(
    val id: String,
    val displayName: String,
    val endpoint: String,
    val enabled: Boolean,
    val serverName: String,
    val serverVersion: String,
    val protocolVersion: String,
    val toolCount: Int,
    val credentialConfigured: Boolean,
    val checkedAtEpochMs: Long,
)

data class McpRemoteHealth(val available: Boolean, val checkedAtEpochMs: Long, val server: McpRemoteServer?, val safeFailureCode: String?)

data class McpRemoteTool(val serverId: String, val remoteName: String, val description: String?, val inputSchema: JsonObject) {
    val canonicalName: String get() = "mcp.$serverId.$remoteName"
}

internal interface McpConnectionFactory {
    fun create(endpoint: String, credentialRef: String?): McpClient
}

/**
 * Agent-owned MCP configuration and discovery cache.
 *
 * Endpoints and public schemas are persisted; bearer credentials live only in Android Keystore.
 * A configuration is published atomically only after initialize + tools/list both succeed.
 */
class McpRemoteEnvironment internal constructor(
    context: Context,
    private val vault: CredentialProvisioner,
    private val connections: McpConnectionFactory,
    private val outboundGate: OutboundExportGate,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun configure(id: String, displayName: String, endpoint: String, bearerToken: ByteArray?): McpRemoteHealth = mutex.withLock {
        require(ID.matches(id)) { "MCP_SERVER_ID_INVALID" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "MCP_DISPLAY_NAME_INVALID" }
        require(endpoint.length <= 2_048) { "MCP_ENDPOINT_INVALID" }
        require(bearerToken == null || bearerToken.size in 1..16_384) { "MCP_CREDENTIAL_INVALID" }
        val ref = credentialRef(id)
        val hadCredential = vault.contains(ref, KEY_VERSION)
        val pendingRef = bearerToken?.let { "mcp.$id.pending.${clock()}" }
        if (pendingRef != null) vault.provision(pendingRef, KEY_VERSION, bearerToken)
        try {
            val client = connections.create(endpoint, pendingRef ?: ref.takeIf { hadCredential })
            val initialized = client.initialize()
            val tools = client.listTools()
            validateTools(id, tools)
            if (bearerToken != null) vault.provision(ref, KEY_VERSION, bearerToken)
            val now = clock()
            val server = McpRemoteServer(
                id, displayName.trim(), endpoint, true,
                initialized.serverInfo.name, initialized.serverInfo.version, initialized.protocolVersion,
                tools.size, bearerToken != null || hadCredential, now,
            )
            check(prefs.edit().putString(serverKey(id), encodeDescriptors(server, tools)).commit())
            McpRemoteHealth(true, now, server, null)
        } catch (failure: Throwable) {
            throw failure
        } finally {
            pendingRef?.let { runSuspendCatching { vault.delete(it, KEY_VERSION) } }
            bearerToken?.fill(0)
        }
    }

    suspend fun healthCheck(id: String): McpRemoteHealth = mutex.withLock {
        val stored = stored(id) ?: return McpRemoteHealth(false, clock(), null, "MCP_NOT_CONFIGURED")
        val now = clock()
        runSuspendCatching {
            val client = connections.create(
                stored.server.endpoint,
                stored.server.credentialConfigured.takeIf {
                    it
                }?.let { credentialRef(id) },
            )
            val initialized = client.initialize()
            val tools = client.listTools()
            validateTools(id, tools)
            val refreshed = stored.server.copy(
                serverName = initialized.serverInfo.name,
                serverVersion = initialized.serverInfo.version,
                protocolVersion = initialized.protocolVersion,
                toolCount = tools.size,
                checkedAtEpochMs = now,
            )
            check(prefs.edit().putString(serverKey(id), encodeDescriptors(refreshed, tools)).commit())
            McpRemoteHealth(true, now, refreshed, null)
        }.getOrElse { McpRemoteHealth(false, now, stored.server, safeCode(it)) }
    }

    fun servers(): List<McpRemoteServer> = prefs.all.keys.asSequence()
        .filter { it.startsWith(SERVER_PREFIX) }
        .mapNotNull { key -> prefs.getString(key, null)?.let { runCatching { decode(it).server }.getOrNull() } }
        .sortedBy { it.displayName }
        .toList()

    fun tools(): List<McpRemoteTool> = prefs.all.keys.asSequence()
        .filter { it.startsWith(SERVER_PREFIX) }
        .mapNotNull { prefs.getString(it, null) }
        .mapNotNull { runCatching { decode(it) }.getOrNull() }
        .filter { it.server.enabled }
        .flatMap { it.tools.asSequence() }
        .toList()

    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val stored = requireNotNull(stored(id)) { "MCP_NOT_CONFIGURED" }
        check(
            prefs.edit().putString(serverKey(id), encode(stored.server.copy(enabled = enabled), stored.tools)).commit(),
        )
    }

    suspend fun remove(id: String) = mutex.withLock {
        stored(id)?.server?.takeIf { it.credentialConfigured }?.let {
            runSuspendCatching { vault.delete(credentialRef(id), KEY_VERSION) }
        }
        check(prefs.edit().remove(serverKey(id)).commit())
    }

    internal suspend fun call(serverId: String, remoteName: String, arguments: JsonObject): McpToolResult {
        val stored = requireNotNull(stored(serverId)) { "MCP_NOT_CONFIGURED" }
        check(stored.server.enabled) { "MCP_SERVER_DISABLED" }
        check(stored.tools.any { it.remoteName == remoteName }) { "MCP_TOOL_NOT_DISCOVERED" }
        val serializedBytes = arguments.toString().toByteArray().size.toLong()
        val decision = outboundGate.evaluate(
            OutboundExportDescriptor(
                requestId = "mcp-$serverId-$remoteName-${clock()}",
                channel = OutboundChannel.MCP_REMOTE,
                purpose = OutboundPurpose.USER_AUTHORED,
                sensitivities = setOf(OutboundSensitivity.SENSITIVE),
                payloadCount = 1,
                byteCount = serializedBytes,
            ),
        )
        check(decision == OutboundExportDecision.ALLOWED) { "MCP_REMOTE_EXPORT_CONSENT_REQUIRED" }
        val ref = stored.server.credentialConfigured.takeIf { it }?.let { credentialRef(serverId) }
        val client = connections.create(stored.server.endpoint, ref)
        client.initialize()
        return client.callTool(remoteName, arguments)
    }

    private fun validateTools(serverId: String, tools: List<McpToolDescriptor>) {
        require(tools.size <= MAX_TOOLS_PER_SERVER) { "MCP_TOOL_LIMIT" }
        val local = RuntimeToolCatalog.production().names()
        tools.forEach {
            require(it.name.length <= 128 && it.inputSchema.toString().toByteArray().size <= MAX_SCHEMA_BYTES) {
                "MCP_TOOL_SCHEMA_INVALID"
            }
            require("mcp.$serverId.${it.name}" !in local) { "MCP_TOOL_COLLISION" }
        }
    }

    private data class Stored(val server: McpRemoteServer, val tools: List<McpRemoteTool>)
    private fun stored(id: String): Stored? = prefs.getString(serverKey(id), null)?.let(::decode)

    private fun encodeDescriptors(server: McpRemoteServer, tools: List<McpToolDescriptor>): String = encode(
        server,
        tools.map { McpRemoteTool(server.id, it.name, it.description, it.inputSchema) },
    )

    private fun encode(server: McpRemoteServer, tools: List<McpRemoteTool>): String = buildJsonObject {
        put(
            "server",
            buildJsonObject {
                put("id", server.id)
                put("displayName", server.displayName)
                put("endpoint", server.endpoint)
                put("enabled", server.enabled)
                put("serverName", server.serverName)
                put("serverVersion", server.serverVersion)
                put("protocolVersion", server.protocolVersion)
                put("toolCount", server.toolCount)
                put("credentialConfigured", server.credentialConfigured)
                put("checkedAtEpochMs", server.checkedAtEpochMs)
            },
        )
        put(
            "tools",
            buildJsonArray {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("remoteName", tool.remoteName)
                            tool.description?.let { put("description", it) }
                            put("inputSchema", tool.inputSchema)
                        },
                    )
                }
            },
        )
    }.toString()

    private fun decode(raw: String): Stored {
        val root = Json.parseToJsonElement(raw).jsonObject
        val value = root.getValue("server").jsonObject
        val server = McpRemoteServer(
            value.getValue("id").jsonPrimitive.content,
            value.getValue("displayName").jsonPrimitive.content,
            value.getValue("endpoint").jsonPrimitive.content,
            value.getValue("enabled").jsonPrimitive.content.toBooleanStrict(),
            value.getValue("serverName").jsonPrimitive.content,
            value.getValue("serverVersion").jsonPrimitive.content,
            value.getValue("protocolVersion").jsonPrimitive.content,
            value.getValue("toolCount").jsonPrimitive.content.toInt(),
            value.getValue("credentialConfigured").jsonPrimitive.content.toBooleanStrict(),
            value.getValue("checkedAtEpochMs").jsonPrimitive.content.toLong(),
        )
        val tools = root.getValue("tools").jsonArray.map { item ->
            item.jsonObject.let {
                McpRemoteTool(
                    server.id,
                    it.getValue("remoteName").jsonPrimitive.content,
                    it["description"]?.jsonPrimitive?.content,
                    it.getValue("inputSchema").jsonObject,
                )
            }
        }
        return Stored(server, tools)
    }

    private fun safeCode(failure: Throwable): String = failure.message?.takeIf { it.matches(Regex("MCP_[A-Z0-9_]+")) }
        ?: "MCP_UNAVAILABLE"
    private fun serverKey(id: String) = "$SERVER_PREFIX$id"
    private fun credentialRef(id: String) = "mcp.$id.bearer"

    companion object {
        private const val PREFS = "runtime_mcp_servers"
        private const val SERVER_PREFIX = "server."
        private const val KEY_VERSION = 1
        private const val MAX_TOOLS_PER_SERVER = 128
        private const val MAX_SCHEMA_BYTES = 64 * 1024
        private val ID = Regex("[a-z][a-z0-9_-]{2,31}")
    }
}

internal class ProductionMcpConnectionFactory(private val client: OkHttpClient, private val credentials: CredentialResolver) : McpConnectionFactory {
    override fun create(endpoint: String, credentialRef: String?): McpClient = McpClient(
        StreamableHttpMcpTransport(endpoint, client, bearerCredential = {
            credentialRef?.let { ref -> credentials.withCredential(ref, 1) { it.copyOf() } }
        }),
    )
}
