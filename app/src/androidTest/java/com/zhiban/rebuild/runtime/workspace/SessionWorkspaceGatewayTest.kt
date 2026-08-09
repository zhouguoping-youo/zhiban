package com.zhiban.rebuild.runtime.workspace

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionWorkspaceGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: AgentDatabase
    private lateinit var gateway: AppPrivateSessionWorkspaceGateway
    private lateinit var sessionId: String

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = AppPrivateSessionWorkspaceGateway(context, database)
        sessionId = "test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        database.close()
        val sessionHash = sha256(sessionId.toByteArray()).take(32)
        File(context.filesDir, "agent-session-workspaces/session-$sessionHash").deleteRecursively()
    }

    @Test
    fun saveCreatesDurableMetadataAndPrivateFile() = runBlocking {
        val bytes = "calendar export".toByteArray()

        val saved = gateway.save(
            sessionId = sessionId,
            runId = null,
            kind = ArtifactKind.GENERATED_FILE,
            displayName = "../calendar.txt",
            mimeType = "text/plain",
            bytes = bytes,
            provenance = "agent_generated",
        )

        assertEquals("_calendar.txt", saved.displayName)
        assertEquals(bytes.size.toLong(), saved.byteLength)
        assertEquals(listOf(saved), gateway.artifacts(sessionId))
        val entity = requireNotNull(database.runtimeArtifactDao().find(saved.artifactId))
        val stored = File(context.filesDir, "agent-session-workspaces/${entity.relativePath}").canonicalFile
        assertTrue(
            stored.toPath().startsWith(File(context.filesDir, "agent-session-workspaces").canonicalFile.toPath()),
        )
        assertArrayEquals(bytes, stored.readBytes())
        assertEquals(bytes.size.toLong(), database.runtimeSessionWorkspaceDao().find(sessionId)?.totalArtifactBytes)
    }

    @Test
    fun summaryIsNormalizedAndPersisted() = runBlocking {
        gateway.updateSummary(sessionId, " 用户计划\n 明天拜访客户 ", 1234L)

        val workspace = requireNotNull(database.runtimeSessionWorkspaceDao().find(sessionId))
        assertEquals("用户计划 明天拜访客户", workspace.summaryText)
        assertEquals(1234L, workspace.summaryThroughTurnAtEpochMs)
    }

    @Test
    fun deleteRemovesDurableSessionFiles() = runBlocking {
        val saved = gateway.save(
            sessionId = sessionId,
            runId = null,
            kind = ArtifactKind.GENERATED_FILE,
            displayName = "private.txt",
            mimeType = "text/plain",
            bytes = "private result".toByteArray(),
            provenance = "agent_generated",
        )
        val entity = requireNotNull(database.runtimeArtifactDao().find(saved.artifactId))
        val stored = File(context.filesDir, "agent-session-workspaces/${entity.relativePath}").canonicalFile
        assertTrue(stored.isFile)

        gateway.delete(sessionId)

        assertFalse(stored.exists())
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
