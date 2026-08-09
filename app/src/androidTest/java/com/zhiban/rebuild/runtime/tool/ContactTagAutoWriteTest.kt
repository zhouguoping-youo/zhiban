package com.zhiban.rebuild.runtime.tool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.runtime.governance.AutoWriteRepository
import com.zhiban.rebuild.runtime.kernel.PersistentRuntimeKernel
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeAttemptEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactTagAutoWriteTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var store: RoomRuntimeStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "contact-tag-test")
        database.contactDao().insert(
            ContactEntity(
                "contact-tag", "李雷", "李雷", null, null, null, "星河科技", null,
                "[]", "[]", null, null, "MANUAL", null, 1, 1,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun automaticContactTagIsVisibleAndCanBeUndone() = runBlocking {
        val route = routeContext()
        val dataDigest = sha256("contact-tag-payload")
        val plan = buildJsonObject {
            put("toolName", ContactTagToolBinding.TOOL_NAME)
            put("providerCallId", "call-tag")
            put("logicalStepId", "step-tag")
            put("proposalId", "proposal-tag")
            put("payloadRef", "contact-tag-$dataDigest")
            put("revision", 1)
            put("canonicalInputDigest", dataDigest)
            put("idempotencyKey", sha256("contact-tag-idempotency"))
            put("runId", route.runId)
            put("attemptId", route.attemptId)
            put("contactId", "contact-tag")
            put("tag", "客户")
            put("evidenceSummary", "对方连续讨论采购需求")
            put("sourceRef", "notification-tag")
            put("confidence", 0.99)
        }

        val result = ContactTagDomainWriter(database, store).executeAuto(plan, route)

        assertTrue(requireNotNull(database.contactDao().findRawById("contact-tag")).tagsJson.contains("客户"))
        val change = database.changeLogDao().listByRun(route.runId).single()
        assertNotNull(database.changeLogDao().findAutoWriteReceipt(change.changeId))
        assertTrue(result.safeResultJson.contains("undoAvailable"))
        assertTrue(AutoWriteRepository(database, context).undo(change.changeId, 50))
        assertEquals("[]", database.contactDao().findRawById("contact-tag")?.tagsJson)
    }

    private suspend fun routeContext(): RuntimeToolRouteContext {
        store.acceptStart("start-tag", "session-tag", "run-tag", "{}", 1)
        val lease = store.claimSession("session-tag", "owner", 2, 1_000)
        val kernel = PersistentRuntimeKernel(store)
        kernel.transition("run-tag", RuntimeSignal.BeginContext, "owner", lease.leaseEpoch, 3)
        kernel.transition("run-tag", RuntimeSignal.ContextReady, "owner", lease.leaseEpoch, 4)
        database.runtimeAttemptDao().insert(RuntimeAttemptEntity("attempt-tag", "run-tag", 1, "ACTIVE", 4, 4))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET activeAttemptId = ? WHERE runId = ?",
            arrayOf("attempt-tag", "run-tag"),
        )
        return RuntimeToolRouteContext("run-tag", "session-tag", "attempt-tag", "owner", lease.leaseEpoch, 1, 30)
    }
}
