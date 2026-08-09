package com.zhiban.rebuild.runtime.workspace

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkspaceAtomicWriteTest {
    @Test
    fun failedAtomicRenameDeletesPartialArtifact() {
        val directory = Files.createTempDirectory("zhiban-workspace-test").toFile()
        try {
            val temporary = directory.resolve("artifact.part")
            val blockedDestination = directory.resolve("missing/artifact.bin")

            val failure = runCatching {
                writeArtifactAtomically(temporary, blockedDestination, "payload".encodeToByteArray())
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
