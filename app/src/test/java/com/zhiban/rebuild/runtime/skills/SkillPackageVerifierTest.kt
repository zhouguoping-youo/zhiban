package com.zhiban.rebuild.runtime.skills

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SkillPackageVerifierTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val publisher = TrustedSkillPublisher(
        "test.publisher",
        "测试发布者",
        Base64.getEncoder().encodeToString(keys.public.encoded),
    )
    private val verifier = SkillPackageVerifier(mapOf("test-key" to publisher))

    @Test
    fun acceptsValidSignedPackage() {
        val value = verifier.verify(packageBytes())

        assertEquals("meeting_brief", value.spec.id)
        assertEquals(1, value.spec.version)
        assertEquals(setOf(SkillPermission.READ_CALENDAR, SkillPermission.READ_CONTACTS), value.permissions)
    }

    @Test
    fun rejectsManifestChangedAfterSigning() {
        val original = manifest()
        val signature = signature(original)
        val changed = original.replace("\"version\":1", "\"version\":2")

        assertCode("SKILL_SIGNATURE_INVALID") { verifier.verify(zip(changed, signature)) }
    }

    @Test
    fun rejectsUnknownPublisherKey() {
        val bytes = packageBytes(keyId = "unknown-key")

        assertCode("SKILL_PUBLISHER_UNTRUSTED") { verifier.verify(bytes) }
    }

    @Test
    fun rejectsPermissionThatDoesNotMatchTools() {
        val invalid = manifest().replace(
            "\"READ_CALENDAR\",\"READ_CONTACTS\"",
            "\"WRITE_CALENDAR\",\"READ_CONTACTS\"",
        )

        assertCode("SKILL_PERMISSION_MISMATCH") { verifier.verify(zip(invalid, signature(invalid))) }
    }

    @Test
    fun rejectsExtraOrNestedArchiveEntries() {
        val manifest = manifest()
        val archive = zip(manifest, signature(manifest), "nested/payload.txt" to "bad")

        assertCode("SKILL_PACKAGE_CONTENT_INVALID") { verifier.verify(archive) }
    }

    @Test
    fun versionPolicyAcceptsInstallUpdateAndIdempotentReinstall() {
        assertEquals(SkillInstallStatus.INSTALLED, SkillVersionPolicy.decide(null, null, 1, "a"))
        assertEquals(SkillInstallStatus.UPDATED, SkillVersionPolicy.decide(1, "a", 2, "b"))
        assertEquals(SkillInstallStatus.ALREADY_INSTALLED, SkillVersionPolicy.decide(2, "b", 2, "b"))
    }

    @Test
    fun versionPolicyRejectsDowngradeAndSameVersionMutation() {
        assertCode("SKILL_DOWNGRADE_BLOCKED") { SkillVersionPolicy.decide(2, "b", 1, "a") }
        assertCode("SKILL_VERSION_CONFLICT") { SkillVersionPolicy.decide(2, "b", 2, "changed") }
    }

    private fun packageBytes(keyId: String = "test-key"): ByteArray {
        val manifest = manifest()
        return zip(manifest, signature(manifest, keyId))
    }

    private fun manifest(): String = """
        {"schemaVersion":1,"id":"meeting_brief","version":1,"displayName":"会前准备","summary":"查找日程和联系人并整理准备清单","publisherId":"test.publisher","publisherName":"测试发布者","triggerIntents":["GENERAL_WORK","CALENDAR_QUERY","CONTACT_QUERY"],"requiredTools":["calendar.schedule.search","contact.search"],"permissions":["READ_CALENDAR","READ_CONTACTS"],"planningInstruction":"只读取已授权的信息，整理简洁清单。"}
    """.trimIndent()

    private fun signature(manifest: String, keyId: String = "test-key"): String {
        val value = Signature.getInstance(SkillPackageVerifier.SIGNATURE_ALGORITHM).run {
            initSign(keys.private)
            update(manifest.toByteArray())
            sign()
        }
        return """{"algorithm":"${SkillPackageVerifier.SIGNATURE_ALGORITHM}","keyId":"$keyId","value":"${Base64.getEncoder().encodeToString(
            value,
        )}"}"""
    }

    private fun zip(manifest: String, signature: String, vararg extras: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf(
                SkillPackageVerifier.MANIFEST_ENTRY to manifest,
                SkillPackageVerifier.SIGNATURE_ENTRY to signature,
            ).plus(extras).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun assertCode(expected: String, block: () -> Unit) {
        try {
            block()
            fail("Expected SkillPackageException")
        } catch (error: SkillPackageException) {
            assertEquals(expected, error.code)
        }
    }
}
