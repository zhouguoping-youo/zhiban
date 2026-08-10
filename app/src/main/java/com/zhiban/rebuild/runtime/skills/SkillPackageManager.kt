package com.zhiban.rebuild.runtime.skills

import android.content.Context
import android.net.Uri
import com.zhiban.agent.skills.SkillOrigin
import com.zhiban.agent.skills.SkillSpec
import com.zhiban.rebuild.runtime.tool.RuntimeToolCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class SkillPermission(val label: String) {
    READ_CALENDAR("查看日程"),
    WRITE_CALENDAR("新增或修改日程"),
    READ_CONTACTS("查看联系人与关系"),
    WRITE_CONTACTS("新增或修改联系人与关系"),
    READ_MEMORY("查看你允许保存的记忆"),
    WRITE_MEMORY("新增或删除长期记忆"),
    USE_EXTERNAL_SERVICE("使用已连接的外部服务"),
}

data class TrustedSkillPublisher(val publisherId: String, val publisherName: String, val publicKeyDerBase64: String)

data class InstalledSkillPackage(
    val spec: SkillSpec,
    val summary: String,
    val publisherId: String,
    val publisherName: String,
    val keyId: String,
    val permissions: Set<SkillPermission>,
    val packageDigest: String,
    val enabled: Boolean,
    val installedAtEpochMs: Long,
    val sourceFile: File,
)

enum class SkillInstallStatus { INSTALLED, UPDATED, ALREADY_INSTALLED }

internal object SkillVersionPolicy {
    fun decide(installedVersion: Int?, installedDigest: String?, candidateVersion: Int, candidateDigest: String): SkillInstallStatus {
        if (installedVersion == null) return SkillInstallStatus.INSTALLED
        if (candidateVersion < installedVersion) throw SkillPackageException("SKILL_DOWNGRADE_BLOCKED")
        if (candidateVersion == installedVersion) {
            if (candidateDigest != installedDigest) throw SkillPackageException("SKILL_VERSION_CONFLICT")
            return SkillInstallStatus.ALREADY_INSTALLED
        }
        return SkillInstallStatus.UPDATED
    }
}

data class SkillInstallResult(val status: SkillInstallStatus, val value: InstalledSkillPackage)

class SkillPackageException(val code: String) : IllegalArgumentException(code)

data class VerifiedSkillPackage(
    val spec: SkillSpec,
    val summary: String,
    val publisherId: String,
    val publisherName: String,
    val keyId: String,
    val permissions: Set<SkillPermission>,
    val packageDigest: String,
)

class SkillPackageVerifier(
    private val trustedPublishers: Map<String, TrustedSkillPublisher>,
    private val localTools: Set<String> = RuntimeToolCatalog.production().names(),
) {
    fun verify(packageBytes: ByteArray): VerifiedSkillPackage {
        if (packageBytes.isEmpty() || packageBytes.size > MAX_PACKAGE_BYTES) {
            throw SkillPackageException("SKILL_PACKAGE_SIZE_INVALID")
        }
        val entries = readEntries(packageBytes)
        if (entries.keys != REQUIRED_ENTRIES) throw SkillPackageException("SKILL_PACKAGE_CONTENT_INVALID")
        val manifestBytes = entries.getValue(MANIFEST_ENTRY)
        val signatureBytes = entries.getValue(SIGNATURE_ENTRY)
        val manifest = parseObject(manifestBytes, "SKILL_MANIFEST_INVALID")
        requireExactKeys(
            manifest,
            setOf(
                "schemaVersion", "id", "version", "displayName", "summary",
                "publisherId", "publisherName", "triggerIntents", "requiredTools",
                "permissions", "planningInstruction",
            ),
            "SKILL_MANIFEST_INVALID",
        )
        if (manifest.requiredInt("schemaVersion") !=
            SCHEMA_VERSION
        ) {
            throw SkillPackageException("SKILL_SCHEMA_UNSUPPORTED")
        }
        val id = manifest.requiredString("id")
        if (!SKILL_ID.matches(id)) throw SkillPackageException("SKILL_ID_INVALID")
        val version = manifest.requiredInt("version")
        if (version !in 1..1_000_000) throw SkillPackageException("SKILL_VERSION_INVALID")
        val displayName = manifest.requiredString("displayName").validatedLength(1, 40, "SKILL_NAME_INVALID")
        val summary = manifest.requiredString("summary").validatedLength(1, 200, "SKILL_SUMMARY_INVALID")
        val publisherId = manifest.requiredString("publisherId").validatedId("SKILL_PUBLISHER_INVALID")
        val publisherName = manifest.requiredString("publisherName").validatedLength(1, 80, "SKILL_PUBLISHER_INVALID")
        val triggers = manifest.requiredStrings("triggerIntents", 1, 10)
        if (!SUPPORTED_INTENTS.containsAll(triggers)) throw SkillPackageException("SKILL_TRIGGER_INVALID")
        val tools = manifest.requiredStrings("requiredTools", 1, 16)
        if (tools.any { !TOOL_ID.matches(it) }) throw SkillPackageException("SKILL_TOOL_INVALID")
        val permissions = manifest.requiredStrings("permissions", 1, SkillPermission.entries.size).map {
            runCatching {
                SkillPermission.valueOf(it)
            }.getOrElse { throw SkillPackageException("SKILL_PERMISSION_INVALID") }
        }.toSet()
        val expectedPermissions = tools.map(::permissionForTool).toSet()
        if (permissions != expectedPermissions) throw SkillPackageException("SKILL_PERMISSION_MISMATCH")
        val instruction = manifest.requiredString("planningInstruction")
            .validatedLength(1, 1_500, "SKILL_INSTRUCTION_INVALID")

        val keyId = verifyPublisherSignature(signatureBytes, manifestBytes, publisherId, publisherName)

        return VerifiedSkillPackage(
            spec = SkillSpec(
                id = id,
                version = version,
                displayName = displayName,
                triggerIntents = triggers,
                requiredTools = tools,
                planningInstruction = instruction,
                origin = SkillOrigin.SIGNED_PACKAGE,
                publisherId = publisherId,
            ),
            summary = summary,
            publisherId = publisherId,
            publisherName = publisherName,
            keyId = keyId,
            permissions = permissions,
            packageDigest = sha256(packageBytes),
        )
    }

    private fun verifyPublisherSignature(signatureBytes: ByteArray, manifestBytes: ByteArray, publisherId: String, publisherName: String): String {
        val signature = parseObject(signatureBytes, "SKILL_SIGNATURE_INVALID")
        requireExactKeys(signature, setOf("algorithm", "keyId", "value"), "SKILL_SIGNATURE_INVALID")
        if (signature.requiredString("algorithm") != SIGNATURE_ALGORITHM) {
            throw SkillPackageException("SKILL_SIGNATURE_ALGORITHM_UNSUPPORTED")
        }
        val keyId = signature.requiredString("keyId").validatedId("SKILL_SIGNATURE_INVALID")
        val publisher = trustedPublishers[keyId] ?: throw SkillPackageException("SKILL_PUBLISHER_UNTRUSTED")
        if (publisher.publisherId != publisherId || publisher.publisherName != publisherName) {
            throw SkillPackageException("SKILL_PUBLISHER_MISMATCH")
        }
        val signatureValue = runCatching { Base64.getDecoder().decode(signature.requiredString("value")) }
            .getOrElse { throw SkillPackageException("SKILL_SIGNATURE_INVALID") }
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publisher.publicKeyDerBase64)))
        }.getOrElse { throw SkillPackageException("SKILL_TRUST_KEY_INVALID") }
        val valid = runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(manifestBytes)
                verify(signatureValue)
            }
        }.getOrDefault(false)
        if (!valid) throw SkillPackageException("SKILL_SIGNATURE_INVALID")
        return keyId
    }

    private fun readEntries(packageBytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.contains('/') || entry.name.contains('\\') ||
                    entry.name.contains("..")
                ) {
                    throw SkillPackageException("SKILL_PACKAGE_CONTENT_INVALID")
                }
                if (entry.name in result) throw SkillPackageException("SKILL_PACKAGE_DUPLICATE_ENTRY")
                val limit = when (entry.name) {
                    MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                    SIGNATURE_ENTRY -> MAX_SIGNATURE_BYTES
                    else -> throw SkillPackageException("SKILL_PACKAGE_CONTENT_INVALID")
                }
                result[entry.name] = zip.readBounded(limit)
                zip.closeEntry()
            }
        }
        return result
    }

    private fun permissionForTool(tool: String): SkillPermission = when {
        tool in setOf("calendar.schedule.search", "calendar.schedule.conflicts") -> SkillPermission.READ_CALENDAR

        tool.startsWith("calendar.schedule.") -> SkillPermission.WRITE_CALENDAR

        tool in setOf(
            "contact.search",
            "contact.getDetail",
            "relationship.search",
            "relationship.getEvidence",
        ) -> SkillPermission.READ_CONTACTS

        tool.startsWith("contact.") || tool.startsWith("relationship.") -> SkillPermission.WRITE_CONTACTS

        tool == "memory.search" -> SkillPermission.READ_MEMORY

        tool.startsWith("memory.") -> SkillPermission.WRITE_MEMORY

        tool.startsWith("mcp.") -> SkillPermission.USE_EXTERNAL_SERVICE

        tool in localTools -> throw SkillPackageException("SKILL_TOOL_PERMISSION_UNKNOWN")

        else -> throw SkillPackageException("SKILL_TOOL_NOT_REGISTERED")
    }

    private fun parseObject(bytes: ByteArray, code: String): JsonObject = runCatching {
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }.getOrElse { throw SkillPackageException(code) }

    private fun requireExactKeys(value: JsonObject, keys: Set<String>, code: String) {
        if (value.keys != keys) throw SkillPackageException(code)
    }

    private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive?.content ?: throw SkillPackageException("SKILL_MANIFEST_INVALID")

    private fun JsonObject.requiredInt(key: String): Int = requiredString(key).toIntOrNull() ?: throw SkillPackageException("SKILL_MANIFEST_INVALID")

    private fun JsonObject.requiredStrings(key: String, min: Int, max: Int): Set<String> {
        val values = runCatching {
            getValue(key).jsonArray
        }.getOrElse { throw SkillPackageException("SKILL_MANIFEST_INVALID") }
        if (values.size !in min..max) throw SkillPackageException("SKILL_MANIFEST_INVALID")
        val result = values.map { it.jsonPrimitive.content }.toSet()
        if (result.size != values.size) throw SkillPackageException("SKILL_MANIFEST_INVALID")
        return result
    }

    private fun String.validatedLength(min: Int, max: Int, code: String): String = trim().takeIf { it.length in min..max } ?: throw SkillPackageException(code)

    private fun String.validatedId(code: String): String = trim().takeIf { PUBLISHER_ID.matches(it) } ?: throw SkillPackageException(code)

    private fun ZipInputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw SkillPackageException("SKILL_PACKAGE_ENTRY_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val SIGNATURE_ENTRY = "signature.json"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val SCHEMA_VERSION = 1
        private const val MAX_PACKAGE_BYTES = 512 * 1024
        private const val MAX_MANIFEST_BYTES = 32 * 1024
        private const val MAX_SIGNATURE_BYTES = 8 * 1024
        private val REQUIRED_ENTRIES = setOf(MANIFEST_ENTRY, SIGNATURE_ENTRY)
        private val SKILL_ID = Regex("[a-z][a-z0-9._-]{2,63}")
        private val PUBLISHER_ID = Regex("[a-z][a-z0-9._-]{2,63}")
        private val TOOL_ID = Regex("[a-zA-Z0-9._/-]{3,160}")
        private val SUPPORTED_INTENTS = setOf(
            "GENERAL_WORK", "CALENDAR_QUERY", "CALENDAR_CREATE", "CONTACT_QUERY", "CONTACT_CREATE",
            "MEMORY_QUERY", "MEMORY_WRITE", "RELATIONSHIP_QUERY", "RELATIONSHIP_WRITE", "SALES_CRM", "PERSONAL_LIFE",
        )
    }
}

@Singleton
class SkillPackageManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val root = File(context.filesDir, "skill_packages").apply {
        if ((!exists() && !mkdirs()) || !isDirectory) throw SkillPackageException("SKILL_STORAGE_UNAVAILABLE")
    }
    private val preferences = context.getSharedPreferences("installed_skill_controls", Context.MODE_PRIVATE)
    private val verifier = SkillPackageVerifier(PRODUCTION_TRUSTED_PUBLISHERS)
    private val mutex = Mutex()
    private val _installed = MutableStateFlow(loadInstalled())
    val installed = _installed.asStateFlow()

    suspend fun install(uri: Uri): SkillInstallResult = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_IMPORT_BYTES) }
            ?: throw SkillPackageException("SKILL_FILE_UNREADABLE")
        install(bytes)
    }

    suspend fun install(bytes: ByteArray): SkillInstallResult = mutex.withLock {
        val verified = verifier.verify(bytes)
        val current = loadInstalled().firstOrNull { it.spec.id == verified.spec.id }
        val decision = SkillVersionPolicy.decide(
            current?.spec?.version,
            current?.packageDigest,
            verified.spec.version,
            verified.packageDigest,
        )
        if (decision == SkillInstallStatus.ALREADY_INSTALLED) {
            return@withLock SkillInstallResult(decision, current!!)
        }
        val destination = File(root, "${verified.spec.id}-${verified.spec.version}.zskill")
        val staging = File.createTempFile("install-", ".tmp", root)
        staging.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!staging.renameTo(destination)) {
            staging.delete()
            throw SkillPackageException("SKILL_INSTALL_WRITE_FAILED")
        }
        val stateSaved = preferences.edit()
            .putLong("installed_at.${verified.spec.id}", System.currentTimeMillis())
            .putBoolean("enabled.${verified.spec.id}", current?.enabled ?: true)
            .commit()
        if (!stateSaved) {
            destination.delete()
            throw SkillPackageException("SKILL_INSTALL_STATE_FAILED")
        }
        current?.sourceFile?.takeIf { it != destination }?.delete()
        val refreshed = loadInstalled()
        _installed.value = refreshed
        val installed = refreshed.first { it.spec.id == verified.spec.id }
        SkillInstallResult(decision, installed)
    }

    suspend fun setEnabled(skillId: String, enabled: Boolean) = mutex.withLock {
        if (loadInstalled().none { it.spec.id == skillId }) throw SkillPackageException("SKILL_NOT_INSTALLED")
        if (!preferences.edit().putBoolean("enabled.$skillId", enabled).commit()) {
            throw SkillPackageException("SKILL_INSTALL_STATE_FAILED")
        }
        _installed.value = loadInstalled()
    }

    suspend fun uninstall(skillId: String) = mutex.withLock {
        val current = loadInstalled().firstOrNull { it.spec.id == skillId }
            ?: throw SkillPackageException("SKILL_NOT_INSTALLED")
        if (!current.sourceFile.delete()) throw SkillPackageException("SKILL_UNINSTALL_FAILED")
        preferences.edit().remove("enabled.$skillId").remove("installed_at.$skillId").apply()
        _installed.value = loadInstalled()
    }

    fun activeSpecs(): List<SkillSpec> = _installed.value.filter { it.enabled }.map { it.spec }

    private fun loadInstalled(): List<InstalledSkillPackage> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == "zskill" }
        .mapNotNull { file ->
            runCatching {
                val verified = verifier.verify(file.readBytes())
                InstalledSkillPackage(
                    verified.spec, verified.summary, verified.publisherId, verified.publisherName,
                    verified.keyId, verified.permissions, verified.packageDigest,
                    preferences.getBoolean("enabled.${verified.spec.id}", true),
                    preferences.getLong("installed_at.${verified.spec.id}", file.lastModified()),
                    file,
                )
            }.getOrNull()
        }
        .groupBy { it.spec.id }
        .values
        .mapNotNull { versions -> versions.maxByOrNull { it.spec.version } }
        .sortedBy { it.spec.displayName }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw SkillPackageException("SKILL_PACKAGE_SIZE_INVALID")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 512 * 1024
        private val PRODUCTION_TRUSTED_PUBLISHERS = mapOf(
            "zhiban-official-2026" to TrustedSkillPublisher(
                publisherId = "zhiban.official",
                publisherName = "知伴",
                publicKeyDerBase64 =
                    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERwAigoqzcJi964nzdQMToEfWIf6WbVNwjEVqD8KJeY9M+" +
                        "yRrHHpmwow8RVt3wivYXu17MKbFt6fHor49pODjRQ==",
            ),
        )
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
