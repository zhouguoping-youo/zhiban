package com.zhiban.rebuild.runtime.store

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.agent.mcp.McpClient
import com.zhiban.agent.mcp.McpTransport
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.agent.TemporalRelationshipWriter
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import com.zhiban.rebuild.runtime.config.FeedbackPolicy
import com.zhiban.rebuild.runtime.config.MemoryPolicy
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.context.LocalEntityExtractor
import com.zhiban.rebuild.runtime.context.PerceptionGateway
import com.zhiban.rebuild.runtime.context.QueryContext
import com.zhiban.rebuild.runtime.kernel.KernelCommandProcessor
import com.zhiban.rebuild.runtime.kernel.RuntimeCommandRunner
import com.zhiban.rebuild.runtime.mcp.McpConnectionFactory
import com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.CredentialProvisioner
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundExportGate
import com.zhiban.rebuild.runtime.provider.OutboundPolicySettings
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class RuntimeInputProcessorTest {
    private lateinit var database: AgentDatabase
    private var now = 1_000L

    @Before fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AgentDatabase::class.java,
            )
                // Match production (AgentDataModule): the CALLBACK creates the FTS triggers that
                // populate contact_search_fts on insert. Without it the retrieval/search path under
                // test silently queries an empty index.
                .addCallback(AgentDatabase.CALLBACK)
                .allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun assistantTurnTextRoundTripsThroughRoomGateways() = runBlocking {
        // End-to-end for the reconnect backfill: a streamed reply is persisted by the engine, then
        // RoomRuntimeGateways.assistantTurnText must return it (and null for an unknown run).
        val input = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"随便聊聊","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateways = RoomRuntimeGateways(database, "test") { now++ }
        gateways.accept(RuntimeUiCommand.Start("s-backfill", input.inputRef, "c-backfill", "a-backfill", 0, "chat", "r-backfill"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(
                ModelEvent.Delta(0, "第一"),
                ModelEvent.Delta(1, "第二"),
                ModelEvent.Final("stop"),
            )
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(database, "processor", { true }, { now++ }, provider = provider, profiles = fixedProfileStore())
        processor.processNext()
        awaitRunStatus("r-backfill", "SUCCEEDED")

        assertEquals("第一第二", gateways.assistantTurnText("s-backfill", "r-backfill"))
        assertNull(gateways.assistantTurnText("s-backfill", "r-nonexistent"))
    }

    @Test fun memoryApprovalSnapshotCarriesPreviewButEventJournalStaysRedacted() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"记住我喜欢简洁回答","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateways = RoomRuntimeGateways(database, "test") { now++ }
        gateways.accept(RuntimeUiCommand.Start("s-prev", input.inputRef, "c-prev", "a-prev", 0, "chat", "r-prev"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(
                ModelEvent.ToolCall(
                    0,
                    "call-prev",
                    "memory.remember",
                    """{"content":"用户喜欢简洁回答","memoryType":"PREFERENCE","subjectKey":"user","predicateKey":"response_style"}""",
                ),
                ModelEvent.Final("tool_calls"),
            )
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(database, "processor", { true }, { now++ }, provider = provider, profiles = fixedProfileStore())
        processor.processNext()
        awaitRunStatus("r-prev", "AWAITING_CONFIRMATION")

        // Neither the durable event journal nor the persisted projection snapshot carries the body.
        val snapshotPayload = gateways.snapshotAndObserve("s-prev", "ui", 0).snapshot.snapshotJson!!
        assertFalse(snapshotPayload.contains("用户喜欢简洁回答"))
        assertTrue(
            database.runtimeEventDao().listByRunId("r-prev").none { it.payloadJson.contains("用户喜欢简洁回答") },
        )
        val approval = approvalPlan("r-prev")
        assertEquals(
            "用户喜欢简洁回答",
            gateways.stagedCandidateContent(requireNotNull(approval["candidateId"]).jsonPrimitive.content),
        )
    }

    @Test fun stageUsesUnguessableRefsEnforcesUtf8LimitAndOffWritesNothing() = runBlocking {
        val on = RoomTextInputGateway(database, { true }, { now })
        val first = on.stage("你好")
        val second = on.stage("你好")
        assertNotEquals(first.inputRef, second.inputRef)
        assertTrue(first.inputRef.matches(Regex("[0-9a-f]{32}")))
        assertEquals("你好".toByteArray(StandardCharsets.UTF_8).size, first.utf8Length)
        assertEquals(2, database.runtimeInputStagingDao().count())
        assertTrue(runCatching { on.stage("a".repeat(65_537)) }.isFailure)

        val off = RoomTextInputGateway(database, { false }, { now })
        assertTrue(runCatching { off.stage("must-not-write") }.isFailure)
        assertEquals(2, database.runtimeInputStagingDao().count())
    }

    @Test fun processorConsumesRawInOneTransactionAndNeverCopiesItToRuntimeRecords() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { now })
        val staged = input.stage("private raw text")
        val gateways = RoomRuntimeGateways(database, "test") { now++ }
        gateways.accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        val processor = KernelCommandProcessor(database, "processor", { true }, { now++ })

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        assertNull(database.runtimeInputStagingDao().find(staged.inputRef))
        assertEquals("private raw text", RoomContextInputGateway(database) { now }.read("r1"))
        val events = database.runtimeEventDao().listAfter("s1", 0)
        assertTrue(events.any { it.eventType == "InputCommitted" })
        assertTrue(events.any { it.eventType == "ContextAssemblyStarted" })
        assertTrue(events.none { it.payloadJson.contains("private raw text") })
        assertEquals("ASSEMBLING_CONTEXT", database.runtimeRunDao().find("r1")?.status)
        assertTrue(database.runtimeCommandInboxDao().find("c1")?.resultJson?.contains("private raw text") == false)
        assertTrue(RoomContextInputGateway(database) { now }.consume("r1"))
        assertNull(RoomContextInputGateway(database) { now }.read("r1"))
        assertEquals(KernelCommandProcessor.Outcome.IDLE, processor.processNext())
    }

    @Test fun providerExecutionWritesDeltaFinalUsageAndCompletesWithoutSecretMaterial() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("帮我整理今天")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(
                RuntimeUiCommand.Start(
                    "s-provider",
                    staged.inputRef,
                    "c-provider",
                    "a-provider",
                    0,
                    "chat",
                    "r-provider",
                ),
            )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(
                ModelEvent.Delta(0, "今天"),
                ModelEvent.Delta(1, "已整理"),
                ModelEvent.Usage(12, 4),
                ModelEvent.Final("stop"),
            )
            override fun cancel(requestId: String) = false
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-provider", "SUCCEEDED")
        val events = database.runtimeEventDao().listByRunId("r-provider")
        assertEquals(
            listOf("今天", "已整理", ""),
            events.filter { it.eventType == "AssistantDelta" }
                .map {
                    kotlinx.serialization.json.Json.parseToJsonElement(
                        it.payloadJson,
                    ).jsonObject.getValue("part").jsonPrimitive.content
                },
        )
        assertTrue(events.any { it.eventType == "ProviderUsageRecorded" })
        assertTrue(events.any { it.eventType == "RunCompleted" })
        assertEquals("SUCCEEDED", database.runtimeRunDao().find("r-provider")?.status)
        assertNull(database.runtimeRunInputDao().findByRunId("r-provider"))
        assertTrue(events.none { it.payloadJson.contains("credential") || it.payloadJson.contains("api-key") })
    }

    @Test fun verifiedImageMetadataFlowsFromRuntimeEnvelopeIntoProviderRequest() = runBlocking {
        val input = """{"schemaVersion":1,"text":"描述图片","mode":"Chat","attachments":[{"attachmentId":"image-1","kind":"IMAGE","mimeType":"image/png","byteLength":8,"sha256Digest":"${"a".repeat(
            64,
        )}","contentRef":"cache://zbi_999999_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.bin","expiresAtEpochMs":999999}]}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start("s-vision", staged.inputRef, "c-vision", "a-vision", 0, "chat", "r-vision"),
        )
        var captured: ModelRequest? = null
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile).copy(modalities = setOf("text", "image"))
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                captured = request
                return flowOf(ModelEvent.Delta(0, "图片描述"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())
        processor.processNext()
        awaitRunStatus("r-vision", "SUCCEEDED")
        val attachment = requireNotNull(captured).attachments.single()
        assertEquals("image-1", attachment.attachmentId)
        assertEquals(
            "cache://zbi_999999_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.bin",
            attachment.contentRef,
        )
        assertTrue(database.runtimeEventDao().listByRunId("r-vision").none { it.payloadJson.contains("cache://") })
    }

    @Test fun providerAuthenticationFailureIsFinalAndPersistsOnlySafeCode() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("auth case")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s-auth", staged.inputRef, "c-auth", "a-auth", 0, "chat", "r-auth"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flow<ModelEvent> {
                throw ProviderFailure("AUTHENTICATION_FAILED", retryable = false, safeRequestId = "safe-request")
            }
            override fun cancel(requestId: String) = false
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-auth", "FAILED_FINAL")
        val failure = database.runtimeEventDao().listByRunId("r-auth").single { it.eventType == "RunFailedFinal" }
        assertTrue(failure.payloadJson.contains("AUTHENTICATION_FAILED"))
        assertTrue(failure.payloadJson.contains("safe-request"))
        assertFalse(failure.payloadJson.contains("auth case"))
        assertEquals("FAILED_FINAL", database.runtimeRunDao().find("r-auth")?.status)
    }

    @Test fun invalidToolCallFailsClosedWithoutSuccess() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("tool")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s-tool", staged.inputRef, "c-tool", "a-tool", 0, "chat", "r-tool"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(
                ModelEvent.ToolCall(0, "call-1", "calendar.create", "{}"),
                ModelEvent.Final("tool_calls"),
            )
            override fun cancel(requestId: String) = false
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-tool", "FAILED_FINAL")
        val events = database.runtimeEventDao().listByRunId("r-tool")
        assertTrue(events.single { it.eventType == "RunFailedFinal" }.payloadJson.contains("INVALID_TOOL_CALL"))
        assertFalse(events.any { it.eventType == "RunCompleted" || it.eventType == "ProviderToolCallReceived" })
    }

    @Test fun invalidToolArgumentsAreReturnedToModelForOneCorrection() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "contact-correction", "张三", "张三", "13800138000", null, null,
                "知伴科技", "项目经理", "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        val input = """{"schemaVersion":1,"text":"查一下张三的联系方式","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start(
                "s-tool-correction", staged.inputRef, "c-tool-correction", "a-tool-correction",
                0, "chat", "r-tool-correction",
            ),
        )
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return when (requests.size) {
                    1 -> flowOf(
                        ModelEvent.ToolCall(0, "call-invalid", "contact.search", "{}"),
                        ModelEvent.Final("tool_calls"),
                    )
                    2 -> {
                        assertTrue(request.messages.any { it.content.contains("INVALID_TOOL_ARGUMENTS") })
                        assertTrue(requireNotNull(request.toolsJson).contains("contact_search"))
                        flowOf(
                            ModelEvent.ToolCall(
                                0, "call-corrected", "contact.search", """{"query":"张三"}""",
                            ),
                            ModelEvent.Final("tool_calls"),
                        )
                    }
                    else -> {
                        assertTrue(request.messages.any { it.content.contains("contact-correction") })
                        flowOf(ModelEvent.Delta(0, "找到了张三。"), ModelEvent.Final("stop"))
                    }
                }
            }
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database, "processor", { true }, { now++ }, provider = provider, profiles = fixedProfileStore(),
        )

        processor.processNext()
        awaitRunStatus("r-tool-correction", "SUCCEEDED")

        assertEquals(3, requests.size)
        val executions = database.runtimeToolExecutionDao().listByRunId("r-tool-correction")
        assertEquals(listOf("FAILED", "SUCCEEDED"), executions.map { it.status })
        assertEquals(setOf("contact.search"), executions.map { it.toolName }.toSet())
        assertEquals(
            1,
            database.runtimeEventDao().listByRunId("r-tool-correction").count { it.eventType == "ToolFailed" },
        )
    }

    @Test fun repeatedInvalidToolArgumentsStopAfterOneCorrection() = runBlocking {
        val input = """{"schemaVersion":1,"text":"查联系人","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start(
                "s-tool-correction-limit", staged.inputRef, "c-tool-correction-limit",
                "a-tool-correction-limit", 0, "chat", "r-tool-correction-limit",
            ),
        )
        var requestCount = 0
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requestCount += 1
                return flowOf(
                    ModelEvent.ToolCall(0, "call-invalid-$requestCount", "contact.search", "{}"),
                    ModelEvent.Final("tool_calls"),
                )
            }
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database, "processor", { true }, { now++ }, provider = provider, profiles = fixedProfileStore(),
        )

        processor.processNext()
        awaitRunStatus("r-tool-correction-limit", "FAILED_FINAL")

        assertEquals(2, requestCount)
        assertEquals(
            listOf("FAILED", "FAILED"),
            database.runtimeToolExecutionDao().listByRunId("r-tool-correction-limit").map { it.status },
        )
        val events = database.runtimeEventDao().listByRunId("r-tool-correction-limit")
        assertEquals(2, events.count { it.eventType == "ToolFailed" })
        assertEquals(1, events.count { it.eventType == "RunFailedFinal" })
        assertFalse(events.any { it.eventType == "RunCompleted" })
    }

    @Test fun workToolApprovalSurvivesProcessorRestartThenCreatesScheduleExactlyOnce() = runBlocking {
        val input = """{"schemaVersion":1,"text":"明天下午 3 点开会，提前 10 分钟提醒我","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(RuntimeUiCommand.Start("s-work", staged.inputRef, "c-work", "a-work", 0, "chat", "r-work"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = if (request.messages.any { it.content.contains("不可信数据") }) {
                flowOf(ModelEvent.Delta(0, "日程已经创建。"), ModelEvent.Final("stop"))
            } else {
                assertEquals("calendar_schedule_create", request.forcedToolName)
                assertTrue(requireNotNull(request.toolsJson).contains("calendar_schedule_create"))
                assertFalse(requireNotNull(request.toolsJson).contains("calendar_schedule_search"))
                flowOf(
                    ModelEvent.ToolCall(
                        0,
                        "call-work",
                        "calendar.schedule.create",
                        """{"title":"项目会","startAtEpochMs":2000000,"durationMinutes":45,"reminderMinutesBefore":10}""",
                    ),
                    ModelEvent.Final("tool_calls"),
                )
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-work", "AWAITING_CONFIRMATION")
        assertNull(database.scheduleDao().findById("schedule-placeholder"))
        val payload = approvalPlan("r-work")
        assertEquals(10, payload["reminderMinutesBefore"]!!.jsonPrimitive.content.toInt())
        val normalizedStart = payload["startAtEpochMs"]!!.jsonPrimitive.content.toLong()
        assertNotEquals(2_000_000L, normalizedStart)
        assertEquals(
            15,
            java.time.Instant.ofEpochMilli(normalizedStart)
                .atZone(java.time.ZoneId.systemDefault()).hour,
        )
        val revision = database.runtimeSessionDao().find("s-work")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-work", "r-work", "approve-work", "approve-action", revision, "chat",
                payload["proposalId"]!!.jsonPrimitive.content, payload["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        val restartedProcessor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, restartedProcessor.processNext())
        awaitRunStatus("r-work", "SUCCEEDED")
        val scheduleId = payload["scheduleId"]!!.jsonPrimitive.content
        assertEquals("开会", database.scheduleDao().findById(scheduleId)?.title)
        assertEquals(10, database.scheduleDao().findById(scheduleId)?.reminderMinutesBefore)
        assertEquals(1, database.runtimeToolExecutionDao().listByRunId("r-work").size)
        assertEquals(listOf("schedule:$scheduleId"), FactIndex(database).search("开会", now, 10).map { it.factId })
        val change = database.changeLogDao().listByRun("r-work").single()
        assertEquals("AVAILABLE", change.undoState)
        val undoRevision = database.runtimeSessionDao().find("s-work")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.UNDO,
                "s-work",
                "r-work",
                "undo-work",
                "undo-work-action",
                undoRevision,
                "chat",
                payloadRef = change.changeId,
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, restartedProcessor.processNext())
        assertNull(database.scheduleDao().findById(scheduleId))
        assertNull(database.factDao().find("schedule:$scheduleId"))
        assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
    }

    @Test fun approvalWaitsForPreviousRunJobToExitBeforeLaunchingExecution() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"记住我喜欢简洁回答","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-approval-race", staged.inputRef, "c-approval-race", "a-approval-race",
                0, "chat", "r-approval-race",
            ),
        )
        val cancelEntered = CompletableDeferred<Unit>()
        val releaseOldJob = CountDownLatch(1)
        val holdFirstCancel = AtomicBoolean(true)
        val requestCount = AtomicInteger()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> =
                if (requestCount.incrementAndGet() > 1) {
                    flowOf(ModelEvent.Delta(0, "日程已经创建。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-approval-race",
                            "memory.remember",
                            """{"content":"用户喜欢简洁回答","memoryType":"PREFERENCE","subjectKey":"user","predicateKey":"response_style"}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }

            override fun cancel(requestId: String): Boolean {
                if (holdFirstCancel.compareAndSet(true, false)) {
                    cancelEntered.complete(Unit)
                    check(releaseOldJob.await(5, TimeUnit.SECONDS))
                }
                return true
            }
        }
        val processor = KernelCommandProcessor(
            database, "processor", { true }, { now++ }, provider = provider, profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(
                dynamicConfig = {
                    com.zhiban.rebuild.runtime.config.AgentDynamicConfig(enableLlmRerank = false)
                },
            ),
        )

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        withTimeout(5_000) { cancelEntered.await() }
        awaitRunStatus("r-approval-race", "AWAITING_CONFIRMATION")
        val approval = approvalPlan("r-approval-race")
        val revision = database.runtimeSessionDao().find("s-approval-race")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE,
                "s-approval-race",
                "r-approval-race",
                "approve-race",
                "approve-race-action",
                revision,
                "chat",
                approval.getValue("proposalId").jsonPrimitive.content,
                approval.getValue("payloadRef").jsonPrimitive.content,
            ),
        )

        val approvalProcessing = async { processor.processNext() }
        awaitRunStatus("r-approval-race", "EXECUTING")
        assertFalse(approvalProcessing.isCompleted)
        releaseOldJob.countDown()

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, approvalProcessing.await())
        awaitRunStatus("r-approval-race", "SUCCEEDED")
        assertEquals(1, database.runtimeToolExecutionDao().listByRunId("r-approval-race").size)
    }

    @Test fun explicitCalendarIntentFallsBackToLocalConfirmedPlanWhenProviderReturnsEmpty() = runBlocking {
        val input = """{"schemaVersion":1,"text":"Create a calendar event tomorrow at 9 PM called Local fallback, remind me 10 minutes before.","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-local-fallback",
                staged.inputRef,
                "c-local-fallback",
                "a-local-fallback",
                0,
                "chat",
                "r-local-fallback",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        processor.processNext()
        awaitRunStatus("r-local-fallback", "AWAITING_CONFIRMATION")
        val approval = approvalPlan("r-local-fallback")
        assertEquals("Local fallback", approval["title"]!!.jsonPrimitive.content)
        assertEquals(10, approval["reminderMinutesBefore"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            21,
            java.time.Instant.ofEpochMilli(approval["startAtEpochMs"]!!.jsonPrimitive.content.toLong())
                .atZone(java.time.ZoneId.systemDefault()).hour,
        )
        assertNull(database.scheduleDao().findById(approval["scheduleId"]!!.jsonPrimitive.content))
    }

    @Test fun explicitCalendarIntentCreatesExactLocalPlanWithoutProviderOrConfiguredProfile() = runBlocking {
        val fixedNow = java.time.LocalDateTime.of(2026, 8, 14, 8, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val text = "让agent创建一个 明晚10点与委内瑞拉客户会议的日程提醒"
        val input =
            """{"schemaVersion":1,"text":"$text","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { fixedNow }).stage(input)
        RoomRuntimeGateways(database, "test") { fixedNow }.accept(
            RuntimeUiCommand.Start(
                "s-calendar-local-first",
                staged.inputRef,
                "c-calendar-local-first",
                "a-calendar-local-first",
                0,
                "chat",
                "r-calendar-local-first",
            ),
        )
        KernelCommandProcessor(database, "processor", { true }, { fixedNow }).processNext()
        val lease = requireNotNull(database.runtimeSessionDao().find("s-calendar-local-first"))
        val providerCalls = AtomicInteger()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot {
                providerCalls.incrementAndGet()
                error("provider must not be called for an exact local calendar command")
            }

            override fun stream(request: ModelRequest) = flow<ModelEvent> {
                providerCalls.incrementAndGet()
                error("provider must not be called for an exact local calendar command")
            }

            override fun cancel(requestId: String): Boolean {
                providerCalls.incrementAndGet()
                return false
            }
        }
        val noProfile = object : ProviderProfileStore {
            override suspend fun load(): ProviderProfile? = null
            override suspend fun save(profile: ProviderProfile) = Unit
            override suspend fun clear() = Unit
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            noProfile,
            "processor",
            { fixedNow },
        )

        assertTrue(engine.execute("r-calendar-local-first", "s-calendar-local-first", lease.leaseEpoch))
        assertEquals("AWAITING_CONFIRMATION", database.runtimeRunDao().find("r-calendar-local-first")?.status)
        assertEquals(0, providerCalls.get())
        val approval = approvalPlan("r-calendar-local-first")
        assertEquals("与委内瑞拉客户会议", approval.getValue("title").jsonPrimitive.content)
        assertEquals(
            java.time.LocalDateTime.of(2026, 8, 15, 22, 0)
                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
            approval.getValue("startAtEpochMs").jsonPrimitive.content.toLong(),
        )
        assertEquals(60, approval.getValue("durationMinutes").jsonPrimitive.content.toInt())
        assertEquals(10, approval.getValue("reminderMinutesBefore").jsonPrimitive.content.toInt())

        val revision = requireNotNull(database.runtimeSessionDao().find("s-calendar-local-first")).nextSequence - 1
        RoomRuntimeGateways(database, "test") { fixedNow }.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE,
                "s-calendar-local-first",
                "r-calendar-local-first",
                "c-calendar-local-first-approve",
                "a-calendar-local-first-approve",
                revision,
                "chat",
                approval.getValue("proposalId").jsonPrimitive.content,
                approval.getValue("payloadRef").jsonPrimitive.content,
            ),
        )
        KernelCommandProcessor(database, "processor", { true }, { fixedNow }).processNext()
        assertTrue(engine.executeApprovedTool("r-calendar-local-first", "s-calendar-local-first", lease.leaseEpoch))
        assertEquals("SUCCEEDED", database.runtimeRunDao().find("r-calendar-local-first")?.status)
        assertEquals(0, providerCalls.get())
        assertEquals(
            "与委内瑞拉客户会议",
            database.scheduleDao().findById(approval.getValue("scheduleId").jsonPrimitive.content)?.title,
        )
    }

    @Test fun approvingAnExpiredPlanFailsTerminallyInsteadOfStayingExecuting() = runBlocking {
        var currentNow = java.time.LocalDateTime.of(2026, 8, 14, 8, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val text = "让agent创建一个 明晚10点与委内瑞拉客户会议的日程提醒"
        val staged = RoomTextInputGateway(database, { true }, { currentNow }).stage(
            """{"schemaVersion":1,"text":"$text","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { currentNow }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-expired-approval",
                staged.inputRef,
                "c-expired-approval-start",
                "a-expired-approval-start",
                0,
                "chat",
                "r-expired-approval",
            ),
        )
        val processor = KernelCommandProcessor(database, "processor", { true }, { currentNow })
        processor.processNext()
        val initialLease = requireNotNull(database.runtimeSessionDao().find("s-expired-approval"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf<ModelEvent>()
            override fun cancel(requestId: String) = false
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { currentNow },
        )
        assertTrue(engine.execute("r-expired-approval", "s-expired-approval", initialLease.leaseEpoch))
        assertEquals("AWAITING_CONFIRMATION", database.runtimeRunDao().find("r-expired-approval")?.status)
        val approval = approvalPlan("r-expired-approval")

        currentNow += 24L * 60 * 60 * 1_000 + 1
        val revision = requireNotNull(database.runtimeSessionDao().find("s-expired-approval")).nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE,
                "s-expired-approval",
                "r-expired-approval",
                "c-expired-approval-approve",
                "a-expired-approval-approve",
                revision,
                "chat",
                approval.getValue("proposalId").jsonPrimitive.content,
                approval.getValue("payloadRef").jsonPrimitive.content,
            ),
        )
        processor.processNext()
        val approvalLease = requireNotNull(database.runtimeSessionDao().find("s-expired-approval"))
        assertEquals("EXECUTING", database.runtimeRunDao().find("r-expired-approval")?.status)

        assertFalse(engine.executeApprovedTool("r-expired-approval", "s-expired-approval", approvalLease.leaseEpoch))
        assertEquals("FAILED_FINAL", database.runtimeRunDao().find("r-expired-approval")?.status)
        assertTrue(
            database.runtimeEventDao().listByRunId("r-expired-approval").any {
                it.eventType == "RunFailedFinal" && it.payloadJson.contains("APPROVAL_EXPIRED_OR_MISSING")
            },
        )
    }

    @Test fun approvedToolTimeoutIsRetryableInsteadOfRuntimeInterrupted() = runBlocking {
        val fixedNow = java.time.LocalDateTime.of(2026, 8, 14, 8, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val staged = RoomTextInputGateway(database, { true }, { fixedNow }).stage(
            """{"schemaVersion":1,"text":"明晚10点开项目会","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { fixedNow }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-approved-timeout", staged.inputRef, "c-approved-timeout", "a-approved-timeout",
                0, "chat", "r-approved-timeout",
            ),
        )
        KernelCommandProcessor(database, "processor", { true }, { fixedNow }).processNext()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf<ModelEvent>()
            override fun cancel(requestId: String) = false
        }
        val preparingEngine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database, provider, fixedProfileStore(), "processor", { fixedNow },
        )
        val initialLease = requireNotNull(database.runtimeSessionDao().find("s-approved-timeout"))
        assertTrue(preparingEngine.execute("r-approved-timeout", "s-approved-timeout", initialLease.leaseEpoch))
        val approval = approvalPlan("r-approved-timeout")
        val revision = requireNotNull(database.runtimeSessionDao().find("s-approved-timeout")).nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-approved-timeout", "r-approved-timeout", "c-approved-timeout-approve",
                "a-approved-timeout-approve", revision, "chat",
                approval.getValue("proposalId").jsonPrimitive.content,
                approval.getValue("payloadRef").jsonPrimitive.content,
            ),
        )
        KernelCommandProcessor(database, "processor", { true }, { fixedNow }).processNext()
        val executionLease = requireNotNull(database.runtimeSessionDao().find("s-approved-timeout"))
        val timeoutEngine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { fixedNow },
            com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(toolExecutionTimeoutMs = 0),
        )

        assertFalse(timeoutEngine.executeApprovedTool("r-approved-timeout", "s-approved-timeout", executionLease.leaseEpoch))
        assertEquals("FAILED_RETRYABLE", database.runtimeRunDao().find("r-approved-timeout")?.status)
        assertNull(database.scheduleDao().findById(approval.getValue("scheduleId").jsonPrimitive.content))
        assertTrue(
            database.runtimeEventDao().listByRunId("r-approved-timeout").any {
                it.eventType == "RunFailedRetryable" && it.payloadJson.contains("TIMEOUT")
            },
        )
    }

    @Test fun exactCalendarConflictQuestionReadsLocalAndDeviceCalendarsWithoutProvider() = runBlocking {
        val fixedNow = java.time.LocalDateTime.of(2026, 8, 14, 8, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val target = java.time.LocalDateTime.of(2026, 8, 15, 22, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        database.scheduleDao().insert(
            ScheduleEntity(
                id = "local-meeting",
                title = "委内瑞拉客户视频会议",
                startAtEpochMs = target,
                durationMinutes = 30,
                note = null,
                createdByRunId = null,
                createdAtEpochMs = fixedNow,
                updatedAtEpochMs = fixedNow,
            ),
        )
        val staged = RoomTextInputGateway(database, { true }, { fixedNow }).stage(
            """{"schemaVersion":1,"text":"明天有晚上10点的会议冲突吗","mode":"Work","model":"M2.7"}""",
        )
        val gateways = RoomRuntimeGateways(database, "test") { fixedNow }
        gateways.accept(
            RuntimeUiCommand.Start(
                "s-calendar-conflict",
                staged.inputRef,
                "c-calendar-conflict",
                "a-calendar-conflict",
                0,
                "chat",
                "r-calendar-conflict",
            ),
        )
        KernelCommandProcessor(database, "processor", { true }, { fixedNow }).processNext()
        val lease = requireNotNull(database.runtimeSessionDao().find("s-calendar-conflict"))
        val providerCalls = AtomicInteger()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = error("provider must not be called")
            override fun stream(request: ModelRequest) = flow<ModelEvent> {
                providerCalls.incrementAndGet()
                error("provider must not be called")
            }
            override fun cancel(requestId: String) = false
        }
        val external = com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource { _, _, _, _ ->
            listOf(
                com.zhiban.rebuild.data.calendar.ExternalCalendarConflict(
                    "device-1",
                    "机场接人",
                    target,
                    target + 30 * 60_000L,
                ),
            )
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { fixedNow },
            infrastructure = com.zhiban.rebuild.runtime.kernel.ProviderEngineInfrastructure(
                externalCalendarConflicts = external,
            ),
        )

        assertTrue(engine.execute("r-calendar-conflict", "s-calendar-conflict", lease.leaseEpoch))
        assertEquals("SUCCEEDED", database.runtimeRunDao().find("r-calendar-conflict")?.status)
        assertEquals(0, providerCalls.get())
        val answer = gateways.assistantTurnText("s-calendar-conflict", "r-calendar-conflict").orEmpty()
        assertTrue(answer.contains("委内瑞拉客户视频会议"))
        assertTrue(answer.contains("机场接人"))
        assertTrue(answer.contains("会发生冲突"))
    }

    @Test fun exactCalendarCreateStopsBeforeApprovalWhenTheTimeIsAlreadyOccupied() = runBlocking {
        val fixedNow = java.time.LocalDateTime.of(2026, 8, 14, 8, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val target = java.time.LocalDateTime.of(2026, 8, 15, 22, 0)
            .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val staged = RoomTextInputGateway(database, { true }, { fixedNow }).stage(
            """{"schemaVersion":1,"text":"帮我安排明天晚上10点客户会议，时间30分钟","mode":"Work","model":"M2.7"}""",
        )
        val gateways = RoomRuntimeGateways(database, "test") { fixedNow }
        gateways.accept(
            RuntimeUiCommand.Start(
                "s-create-conflict",
                staged.inputRef,
                "c-create-conflict",
                "a-create-conflict",
                0,
                "chat",
                "r-create-conflict",
            ),
        )
        KernelCommandProcessor(database, "processor", { true }, { fixedNow }).processNext()
        val lease = requireNotNull(database.runtimeSessionDao().find("s-create-conflict"))
        val external = com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource { _, _, _, _ ->
            listOf(com.zhiban.rebuild.data.calendar.ExternalCalendarConflict("device-existing", "机场接人", target, target + 60 * 60_000L))
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = error("provider must not be called")
                override fun stream(request: ModelRequest) = flow<ModelEvent> { error("provider must not be called") }
                override fun cancel(requestId: String) = false
            },
            fixedProfileStore(),
            "processor",
            { fixedNow },
            infrastructure = com.zhiban.rebuild.runtime.kernel.ProviderEngineInfrastructure(
                externalCalendarConflicts = external,
            ),
        )

        assertTrue(engine.execute("r-create-conflict", "s-create-conflict", lease.leaseEpoch))
        assertEquals("SUCCEEDED", database.runtimeRunDao().find("r-create-conflict")?.status)
        assertNull(database.runtimeEventDao().latestByType("r-create-conflict", "ApprovalRequested"))
        assertEquals(0, database.scheduleDao().count())
        assertTrue(gateways.assistantTurnText("s-create-conflict", "r-create-conflict").orEmpty().contains("机场接人"))
    }

    @Test fun contactSearchAutoExecutesWithoutApprovalAndFeedsFinalAnswer() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "contact-zhang", "张三", "张三", "13800138000", "zhang@example.com", "zhangsan",
                "知伴科技", "项目经理", "[]", "[\"客户\"]", "负责星河项目", null, "USER", null, now, now,
            ),
        )
        database.contactDao().upsertRole(
            ContactRoleEntity("contact-zhang", "crm", "CUSTOMER", .95, true, "{}", now, now),
        )
        database.contactDao().insert(
            ContactEntity(
                "contact-li", "李四", "李四", null, null, null,
                "星河供应链", "采购主管", "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "edge-zhang-li", "contact-zhang", "contact-li", "PROJECT_PARTNER",
                "evidence-digest", "[\"meeting:42\"]", .9, true, "crm", "ACTIVE", now, now,
            ),
        )
        val input = """{"schemaVersion":1,"text":"查一下张三的联系方式","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(
                RuntimeUiCommand.Start("s-contact", staged.inputRef, "c-contact", "a-contact", 0, "chat", "r-contact"),
            )
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return if (request.messages.any {
                        it.content.contains("不可信数据") && it.content.contains("contact-zhang")
                    }
                ) {
                    flowOf(ModelEvent.Delta(0, "张三的电话是 13800138000。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(0, "call-contact", "contact.search", """{"query":"张三"}"""),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-contact", "SUCCEEDED")

        assertNull(database.runtimeEventDao().latestByType("r-contact", "ApprovalRequested"))
        val execution = database.runtimeToolExecutionDao().listByRunId("r-contact").single()
        assertEquals("contact.search", execution.toolName)
        assertTrue(execution.safeResultJson!!.contains("13800138000"))
        assertEquals(2, requests.size)
        val perception = database.runtimeEventDao().latestByType("r-contact", "PerceptionCompleted")
        assertTrue(perception != null)
        assertTrue(perception!!.payloadJson.contains("CONTACT_QUERY"))
        assertTrue(perception.payloadJson.contains("contact-zhang"))
        assertFalse(perception.payloadJson.contains("13800138000"))
        assertTrue(
            requests.first().messages.any {
                it.content.contains("source=local_perception") &&
                    it.content.contains("contact-zhang") && it.content.contains("role=CUSTOMER")
            },
        )
        val retrieval = database.runtimeEventDao().latestByType("r-contact", "ContextRetrievalCompleted")!!
        assertTrue(retrieval.payloadJson.contains("contact-zhang"))
        assertTrue(retrieval.payloadJson.contains("edge-zhang-li"))
        assertTrue(retrieval.payloadJson.contains("vector_skipped:not_configured"))
        assertFalse(retrieval.payloadJson.contains("13800138000"))
        assertTrue(
            database.runtimeEventDao().latestByType("r-contact", "ContextRerankCompleted")!!
                .payloadJson.contains("rerank_skipped:capability_unavailable"),
        )
        assertTrue(
            requests.first().messages.any {
                it.content.contains("source=context_retrieval") && it.content.contains("张三") &&
                    it.content.contains("13800138000")
            },
        )
        assertTrue(
            requests.first().messages.any {
                it.content.contains("张三 与 李四 的关系=PROJECT_PARTNER")
            },
        )
        assertTrue(
            requests.last().messages.any {
                it.content.contains("不可信数据") && it.content.contains("contact-zhang")
            },
        )
        assertTrue(database.runtimeEventDao().listByRunId("r-contact").any { it.eventType == "RunCompleted" })
    }

    @Test fun observationCanExecuteADifferentReadToolWithoutLeavingRunStuck() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "contact-observation", "张三", "张三", null, null, null,
                "知伴科技", null, "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"查张三的资料，再告诉我联系人总数","mode":"Work","model":"M2.7","level":"高"}""",
        )
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start(
                "s-observation-read",
                staged.inputRef,
                "c-observation-read",
                "a-observation-read",
                0,
                "chat",
                "r-observation-read",
            ),
        )
        var requestNumber = 0
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = when (requestNumber++) {
                0 -> flowOf(
                    ModelEvent.ToolCall(0, "call-contact-search", "contact.search", """{"query":"张三"}"""),
                    ModelEvent.Final("tool_calls"),
                )

                1 -> flowOf(
                    ModelEvent.ToolCall(0, "call-contact-count", "contact.maintenance.list", """{"limit":1}"""),
                    ModelEvent.Final("tool_calls"),
                )

                else -> flowOf(ModelEvent.Delta(0, "已完成两项查询。"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        ).processNext()

        awaitRunStatus("r-observation-read", "SUCCEEDED")
        assertEquals(
            setOf("contact.search", "contact.maintenance.list"),
            database.runtimeToolExecutionDao().listByRunId("r-observation-read").map { it.toolName }.toSet(),
        )
    }

    @Test fun calendarUpdateAndDeleteRequireApprovalPersistAuditAndUndo() = runBlocking {
        database.scheduleDao().insert(
            com.zhiban.rebuild.data.agent.ScheduleEntity(
                "schedule-mutate", "原始会议", 5_000_000, 30, "原备注", null, null, null, now, now,
            ),
        )

        suspend fun runMutation(suffix: String, toolName: String, arguments: String) {
            val sessionId = "s-cal-$suffix"
            val runId = "r-cal-$suffix"
            val staged = RoomTextInputGateway(database, { true }, { now }).stage(
                """{"schemaVersion":1,"text":"修改日程","mode":"Work","model":"M2.7","level":"高"}""",
            )
            val gateway = RoomRuntimeGateways(database, "test") { now++ }
            gateway.accept(
                RuntimeUiCommand.Start(sessionId, staged.inputRef, "c-$suffix", "a-$suffix", 0, "chat", runId),
            )
            val provider = object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile) = capability(profile)
                override fun stream(request: ModelRequest) = if (request.messages.any { it.content.contains("不可信数据") }) {
                    flowOf(ModelEvent.Delta(0, "日程操作完成。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(0, "call-$suffix", toolName, arguments),
                        ModelEvent.Final("tool_calls"),
                    )
                }
                override fun cancel(requestId: String) = true
            }
            val processor =
                KernelCommandProcessor(database, "processor", {
                    true
                }, { now++ }, provider = provider, profiles = fixedProfileStore())
            processor.processNext()
            awaitRunStatus(runId, "AWAITING_CONFIRMATION")
            val approval = database.runtimeEventDao().latestByType(runId, "ApprovalRequested")!!
            val payload = Json.parseToJsonElement(approval.payloadJson).jsonObject
            val revision = database.runtimeSessionDao().find(sessionId)!!.nextSequence - 1
            gateway.accept(
                RuntimeUiCommand.RunAction(
                    RuntimeAction.APPROVE, sessionId, runId, "approve-$suffix", "approve-action-$suffix", revision, "chat",
                    payload["proposalId"]!!.jsonPrimitive.content, payload["payloadRef"]!!.jsonPrimitive.content,
                ),
            )
            processor.processNext()
            awaitRunStatus(runId, "SUCCEEDED")
            assertEquals(
                "SUCCEEDED",
                database.toolAuditDao().findByIdempotencyKey(payload["idempotencyKey"]!!.jsonPrimitive.content)?.status,
            )
            val change = database.changeLogDao().listByRun(runId).single()
            val undoRevision = database.runtimeSessionDao().find(sessionId)!!.nextSequence - 1
            gateway.accept(
                RuntimeUiCommand.RunAction(
                    RuntimeAction.UNDO,
                    sessionId,
                    runId,
                    "undo-$suffix",
                    "undo-action-$suffix",
                    undoRevision,
                    "chat",
                    payloadRef = change.changeId,
                ),
            )
            processor.processNext()
            assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
        }

        runMutation(
            "update",
            "calendar.schedule.update",
            """{"scheduleId":"schedule-mutate","title":"修改后会议","startAtEpochMs":7000000,"durationMinutes":45,"note":"新备注"}""",
        )
        assertEquals("原始会议", database.scheduleDao().findById("schedule-mutate")?.title)

        runMutation("delete", "calendar.schedule.delete", """{"scheduleId":"schedule-mutate"}""")
        assertEquals("原始会议", database.scheduleDao().findById("schedule-mutate")?.title)
        assertTrue(FactIndex(database).search("原始会议", now, 10).any { it.factId == "schedule:schedule-mutate" })
    }

    @Test fun providerRerankReordersRrfContextWithoutPersistingCandidateText() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "rerank-zhang", "张三", "张三", null, null, null, "甲公司", null,
                "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.contactDao().insert(
            ContactEntity(
                "rerank-li", "李四", "李四", null, null, null, "乙公司", null,
                "[]", "[]", "张三的合作伙伴", null, "USER", null, now, now,
            ),
        )
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "rerank-edge", "rerank-zhang", "rerank-li", "PARTNER", "digest", "[]", .8, true, null, "ACTIVE", now, now,
            ),
        )
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"张三 李四 公司","mode":"Chat","model":"M2.7"}""",
        )
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start("s-rerank", staged.inputRef, "c-rerank", "a-rerank", 0, "chat", "r-rerank"),
        )
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile).copy(features = setOf("stream", "tools", "rerank"))
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return if (request.requestId.startsWith("rerank-")) {
                    flowOf(
                        ModelEvent.Delta(0, "[\"contact:rerank-li\",\"contact:rerank-zhang\"]"),
                        ModelEvent.Final("stop"),
                    )
                } else {
                    flowOf(ModelEvent.Delta(0, "李四。"), ModelEvent.Final("stop"))
                }
            }
            override fun cancel(requestId: String) = true
        }
        KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        ).processNext()
        awaitRunStatus("r-rerank", "SUCCEEDED")
        assertEquals(2, requests.size)
        val context = requests.single { !it.requestId.startsWith("rerank-") }.messages.joinToString("\n") { it.content }
        assertTrue(context.indexOf("contact:rerank-li") < context.indexOf("contact:rerank-zhang"))
        val event = database.runtimeEventDao().latestByType("r-rerank", "ContextRerankCompleted")!!
        assertTrue(event.payloadJson.contains("contact:rerank-li"))
        // The provider-issued order must be reflected (li before zhang).
        assertTrue(event.payloadJson.indexOf("contact:rerank-li") < event.payloadJson.indexOf("contact:rerank-zhang"))
        assertFalse(event.payloadJson.contains("李四。"))
        assertFalse(event.payloadJson.contains("甲公司"))
    }

    @Test fun rerankTimeoutCancelsOnlyRerankAndFallsBackToRrf() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "timeout-zhang", "张三", "张三", null, null, null, "甲公司", null,
                "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.contactDao().insert(
            ContactEntity(
                "timeout-li", "李四", "李四", null, null, null, "乙公司", null,
                "[]", "[]", "张三的合作伙伴", null, "USER", null, now, now,
            ),
        )
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "timeout-edge", "timeout-zhang", "timeout-li", "PARTNER", "digest", "[]", .8, true, null, "ACTIVE", now, now,
            ),
        )
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"张三 李四 公司","mode":"Chat","model":"M2.7"}""",
        )
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start(
                "s-rerank-timeout",
                staged.inputRef,
                "c-rerank-timeout",
                "a-rerank-timeout",
                0,
                "chat",
                "r-rerank-timeout",
            ),
        )
        val rerankCancelled = AtomicBoolean(false)
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile).copy(features = setOf("stream", "rerank"))
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> = if (request.requestId.startsWith("rerank-")) {
                flow {
                    delay(500)
                    emit(ModelEvent.Delta(0, "[]"))
                    emit(ModelEvent.Final("stop"))
                }
            } else {
                flowOf(ModelEvent.Delta(0, "仍可回答。"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String): Boolean {
                if (requestId.startsWith("rerank-")) rerankCancelled.set(true)
                return true
            }
        }
        KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(rerankTimeoutMs = 50),
        ).processNext()
        awaitRunStatus("r-rerank-timeout", "SUCCEEDED")
        assertTrue(rerankCancelled.get())
        assertTrue(
            database.runtimeEventDao().latestByType("r-rerank-timeout", "ContextRerankCompleted")!!
                .payloadJson.contains("rerank_skipped:timeout"),
        )
    }

    @Test fun relationshipSearchRunsThroughRouterLedgerAndObservation() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "rel-a", "张三", "张三", null, null, null, null, null, "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.contactDao().insert(
            ContactEntity(
                "rel-b", "李四", "李四", null, null, null, null, null, "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "rel-edge", "rel-a", "rel-b", "FRIEND", "rel-digest", "[\"private-evidence\"]", .95, true, null, "ACTIVE", now, now,
            ),
        )
        val historicalEpisode = TemporalRelationshipWriter(database).replaceEpisode(
            episodeKey = "rel-past-colleague",
            fromPersonId = "rel-a",
            toPersonId = "rel-b",
            relationshipType = "COLLEAGUE",
            temporalState = "PAST",
            evidenceRefsJson = "[\"private-history-evidence\"]",
            confidence = 0.9,
            verificationState = "USER_CONFIRMED",
            nowEpochMs = now,
        )
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"查询张三的关系","mode":"Work","model":"M2.7"}""",
        )
        RoomRuntimeGateways(database, "test") { now++ }.accept(
            RuntimeUiCommand.Start("s-rel", staged.inputRef, "c-rel", "a-rel", 0, "chat", "r-rel"),
        )
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return if (request.messages.any {
                        it.content.contains("rel-edge") && it.content.contains("rel-digest")
                    }
                ) {
                    flowOf(ModelEvent.Delta(0, "李四是张三的朋友。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-rel",
                            "relationship.search",
                            """{"contactId":"rel-a","maxDepth":2}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
        }
        KernelCommandProcessor(database, "processor", {
            true
        }, { now++ }, provider = provider, profiles = fixedProfileStore()).processNext()
        awaitRunStatus("r-rel", "SUCCEEDED")
        assertNull(database.runtimeEventDao().latestByType("r-rel", "ApprovalRequested"))
        val execution = database.runtimeToolExecutionDao().listByRunId("r-rel").single()
        assertEquals("relationship.search", execution.toolName)
        assertTrue(execution.safeResultJson!!.contains("rel-digest"))
        assertTrue(execution.safeResultJson!!.contains(historicalEpisode.episodeId))
        assertTrue(execution.safeResultJson!!.contains("\"temporalState\":\"PAST\""))
        assertFalse(execution.safeResultJson!!.contains("private-evidence"))
        assertFalse(execution.safeResultJson!!.contains("private-history-evidence"))
        assertEquals(2, requests.size)
        val trace = com.zhiban.rebuild.runtime.observability.AgentTraceService(database).recent().first {
            it.runId ==
                "r-rel"
        }
        assertEquals(listOf("relationship.search"), trace.toolNames)
        assertTrue(trace.eventCount > 0)
        assertTrue(trace.auditSteps.any { it.phase == "PERCEPTION" })
        assertTrue(trace.auditSteps.any { it.phase == "PLANNING" })
        assertTrue(trace.auditSteps.any { it.phase == "EXECUTION" && it.toolName == "relationship.search" })
        assertTrue(trace.auditSteps.any { it.phase == "FEEDBACK" })
        assertNotNull(trace.firstTokenLatencyMs)
        assertNotNull(trace.retrievalDurationMs)
        val metrics = com.zhiban.rebuild.runtime.observability.AgentTraceService(database).metrics()
        assertNotNull(metrics.firstTokenP95Ms)
        assertNotNull(metrics.retrievalP95Ms)
        assertFalse(trace.degradationPaths.any { it.contains("private-evidence") })
        assertFalse(trace.toString().contains("private-evidence"))
    }

    @Test fun calendarTimeEntityStructuredFilterFeedsRealScheduleIntoPlanning() = runBlocking {
        database.scheduleDao().insert(
            ScheduleEntity(
                id = "schedule-today",
                title = "项目晨会",
                startAtEpochMs = 3_600_000L,
                durationMinutes = 30,
                note = "讨论里程碑",
                createdByRunId = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        val input = """{"schemaVersion":1,"text":"今天有什么日程？","mode":"Work","model":"M2.7","level":"中"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(
                RuntimeUiCommand.Start(
                    "s-calendar-context",
                    staged.inputRef,
                    "c-calendar-context",
                    "a-calendar-context",
                    0,
                    "chat",
                    "r-calendar-context",
                ),
            )
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return flowOf(ModelEvent.Delta(0, "今天有项目晨会。"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-calendar-context", "SUCCEEDED")
        val prompt = requests.single().messages.joinToString("\n") { it.content }
        assertTrue(prompt.contains("source=local_perception") && prompt.contains("CALENDAR_QUERY"))
        assertTrue(prompt.contains("calendar_coordination") && prompt.contains("先核对时间范围"))
        assertTrue(prompt.contains("source=context_retrieval") && prompt.contains("项目晨会") && prompt.contains("讨论里程碑"))
        val retrieval = database.runtimeEventDao().latestByType("r-calendar-context", "ContextRetrievalCompleted")!!
        assertTrue(retrieval.payloadJson.contains("schedule-today"))
        assertTrue(retrieval.payloadJson.contains("\"structuredCandidateCount\":1"))
        assertFalse(retrieval.payloadJson.contains("项目晨会"))
    }

    @Test fun contactCandidateRequiresConfirmationWritesAuditAndCanUndo() = runBlocking {
        val input = """{"schemaVersion":1,"text":"把陈晨加为客户，电话 13900139000","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-contact-write",
                staged.inputRef,
                "c-contact-write",
                "a-contact-write",
                0,
                "chat",
                "r-contact-write",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> =
                if (request.messages.any { it.content.contains("candidate_created") }) {
                    flowOf(ModelEvent.Delta(0, "联系人候选已创建，可撤销。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-contact-create",
                            "contact.createCandidate",
                            """{"displayName":"陈晨","phone":"13900139000","company":"星河公司","roleType":"CUSTOMER"}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-contact-write", "AWAITING_CONFIRMATION")
        assertEquals(0, database.contactDao().countActive())
        val approval = database.runtimeEventDao().latestByType("r-contact-write", "ApprovalRequested")!!
        assertFalse(approval.payloadJson.contains("13900139000"))
        assertFalse(approval.payloadJson.contains("星河公司"))
        val payload = approvalPlan("r-contact-write")
        val revision = database.runtimeSessionDao().find("s-contact-write")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-contact-write", "r-contact-write", "approve-contact", "approve-contact-action", revision, "chat",
                payload["proposalId"]!!.jsonPrimitive.content, payload["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-contact-write", "SUCCEEDED")

        val contactId = payload["contactId"]!!.jsonPrimitive.content
        assertEquals("13900139000", database.contactDao().findById(contactId)?.phone)
        assertEquals(listOf("contact:$contactId"), FactIndex(database).search("星河", now, 10).map { it.factId })
        assertEquals("CUSTOMER", database.contactDao().roles(contactId).single().roleType)
        assertEquals(1, database.toolAuditDao().count())
        val change = database.changeLogDao().listByRun("r-contact-write").single()
        assertEquals("AVAILABLE", change.undoState)
        val undoRevision = database.runtimeSessionDao().find("s-contact-write")!!.nextSequence - 1
        val undoReceipt = gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.UNDO,
                "s-contact-write",
                "r-contact-write",
                "undo-contact",
                "undo-contact-action",
                undoRevision,
                "chat",
                payloadRef = change.changeId,
            ),
        )
        assertEquals(CommandReceiptStatus.ACCEPTED, undoReceipt.status)
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        assertNull(database.contactDao().findById(contactId))
        assertNull(database.factDao().find("contact:$contactId"))
        assertTrue(FactIndex(database).search("星河", now, 10).none { it.factId == "contact:$contactId" })
        assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
        assertEquals(
            change.changeId,
            Json.parseToJsonElement(
                database.runtimeEventDao().latestByType("r-contact-write", "ChangeUndone")!!.payloadJson,
            ).jsonObject["changeId"]!!.jsonPrimitive.content,
        )
    }

    @Test fun ownerCurrentEmploymentRequiresConfirmationPersistsAndCanUndo() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"我目前在平凯星辰（北京）科技有限公司工作","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-owner-employment",
                staged.inputRef,
                "c-owner-employment",
                "a-owner-employment",
                0,
                "chat",
                "r-owner-employment",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> =
                if (request.messages.any { it.content.contains("employmentAdded") }) {
                    flowOf(ModelEvent.Delta(0, "本人任职已保存。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-owner-employment",
                            "contact.profile.proposeUpdate",
                            """{"contactId":"user:self","company":"平凯星辰（北京）科技有限公司","evidenceSummary":"用户在当前会话明确提供","confidence":1.0}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        processor.processNext()
        awaitRunStatus("r-owner-employment", "AWAITING_CONFIRMATION")
        assertNull(database.contactIntelligenceDao().findCurrentUserEmployment(RelationshipPersonIds.SELF))
        val approval = requireNotNull(database.runtimeEventDao().latestByType("r-owner-employment", "ApprovalRequested"))
        val payload = approvalPlan("r-owner-employment")
        assertEquals("确认本人当前任职", payload["title"]?.jsonPrimitive?.content)
        assertTrue(payload["details"]?.jsonPrimitive?.content?.contains("公司全称：") == true)
        assertNull(payload["message"])
        val revision = requireNotNull(database.runtimeSessionDao().find("s-owner-employment")).nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE,
                "s-owner-employment",
                "r-owner-employment",
                "approve-owner-employment",
                "approve-owner-employment-action",
                revision,
                "chat",
                requireNotNull(payload["proposalId"]).jsonPrimitive.content,
                requireNotNull(payload["payloadRef"]).jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-owner-employment", "SUCCEEDED")

        val employment = requireNotNull(database.contactIntelligenceDao().findCurrentUserEmployment(RelationshipPersonIds.SELF))
        assertEquals("平凯星辰（北京）科技有限公司", employment.companyNameSnapshot)
        assertEquals("USER_CONFIRMED", employment.verificationState)
        assertNull(employment.validFromEpochMs)
        val change = database.changeLogDao().listByRun("r-owner-employment").single()
        val undoRevision = requireNotNull(database.runtimeSessionDao().find("s-owner-employment")).nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.UNDO,
                "s-owner-employment",
                "r-owner-employment",
                "undo-owner-employment",
                "undo-owner-employment-action",
                undoRevision,
                "chat",
                payloadRef = change.changeId,
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        assertNull(database.contactIntelligenceDao().findCurrentUserEmployment(RelationshipPersonIds.SELF))
        assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
    }

    @Test fun contactCompanyProfileWritesCanonicalEmploymentAndUndoRemovesBothProjections() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "profile-company-contact", "丁波", "丁波", null, null, null, null, "销售", "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"丁波在平凯星辰（北京）科技有限公司工作","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-profile-company",
                staged.inputRef,
                "c-profile-company",
                "a-profile-company",
                0,
                "chat",
                "r-profile-company",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> =
                if (request.messages.any { it.content.contains("profile_enriched") }) {
                    flowOf(ModelEvent.Delta(0, "联系人任职已保存。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-profile-company",
                            "contact.profile.proposeUpdate",
                            """{"contactId":"profile-company-contact","company":"  平凯星辰（北京）科技有限公司  ","evidenceSummary":"用户在当前会话明确提供","confidence":1.0}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }

            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        processor.processNext()
        awaitRunStatus("r-profile-company", "AWAITING_CONFIRMATION")
        val approval = requireNotNull(database.runtimeEventDao().latestByType("r-profile-company", "ApprovalRequested"))
        val approvalPayload = approvalPlan("r-profile-company")
        val revision = requireNotNull(database.runtimeSessionDao().find("s-profile-company")).nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE,
                "s-profile-company",
                "r-profile-company",
                "approve-profile-company",
                "approve-profile-company-action",
                revision,
                "chat",
                requireNotNull(approvalPayload["proposalId"]).jsonPrimitive.content,
                requireNotNull(approvalPayload["payloadRef"]).jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-profile-company", "SUCCEEDED")

        val contact = requireNotNull(database.contactDao().findById("profile-company-contact"))
        assertEquals("平凯星辰（北京）科技有限公司", contact.company)
        val temporalEmployment = requireNotNull(
            database.contactIntelligenceDao().listEmploymentEpisodes(contact.contactId).single(),
        )
        assertEquals("平凯星辰（北京）科技有限公司", temporalEmployment.companyNameSnapshot)
        assertEquals("UNKNOWN", temporalEmployment.currentState)
        val contactEmployment = database.contactKnowledgeDao().observeEmployments(contact.contactId).first().single()
        assertEquals(temporalEmployment.organizationId, contactEmployment.organizationId)
        assertEquals(
            "平凯星辰（北京）科技有限公司",
            database.contactKnowledgeDao().findOrganization(requireNotNull(contactEmployment.organizationId))?.canonicalName,
        )

        val change = database.changeLogDao().listByRun("r-profile-company").single()
        val undoRevision = requireNotNull(database.runtimeSessionDao().find("s-profile-company")).nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.UNDO,
                "s-profile-company",
                "r-profile-company",
                "undo-profile-company",
                "undo-profile-company-action",
                undoRevision,
                "chat",
                payloadRef = change.changeId,
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        assertNull(database.contactDao().findById(contact.contactId)?.company)
        assertNull(database.contactIntelligenceDao().findEmploymentEpisode(temporalEmployment.episodeId))
        assertNull(database.contactKnowledgeDao().findEmployment(contactEmployment.employmentId))
        assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
    }

    @Test fun relationshipCandidateRequiresConfirmationWritesEvidenceDigestAndCanUndo() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "rel-write-a", "张三", "张三", null, null, null, null, null, "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.contactDao().insert(
            ContactEntity(
                "rel-write-b", "李四", "李四", null, null, null, null, null, "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"记下张三和李四是朋友","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-rel-write",
                staged.inputRef,
                "c-rel-write",
                "a-rel-write",
                0,
                "chat",
                "r-rel-write",
            ),
        )
        val privateEvidence = "用户明确说张三和李四从小学就是朋友"
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> = if (request.messages.any {
                    it.content.contains("userConfirmed") && it.content.contains("rel-write-a")
                }
            ) {
                flowOf(ModelEvent.Delta(0, "关系已确认保存，可撤销。"), ModelEvent.Final("stop"))
            } else {
                flowOf(
                    ModelEvent.ToolCall(
                        0,
                        "call-rel-create",
                        "relationship.createCandidate",
                        """{"fromContactId":"rel-write-a","toContactId":"rel-write-b","relationType":"FRIEND","evidenceSummary":"$privateEvidence","confidence":0.9}""",
                    ),
                    ModelEvent.Final("tool_calls"),
                )
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-rel-write", "AWAITING_CONFIRMATION")
        val approval = database.runtimeEventDao().latestByType("r-rel-write", "ApprovalRequested")!!
        assertFalse(approval.payloadJson.contains(privateEvidence))
        val payload = approvalPlan("r-rel-write")
        assertTrue(payload["evidenceDigest"]!!.jsonPrimitive.content.isNotBlank())
        val revision = database.runtimeSessionDao().find("s-rel-write")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-rel-write", "r-rel-write", "approve-rel", "approve-rel-action", revision, "chat",
                payload["proposalId"]!!.jsonPrimitive.content, payload["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-rel-write", "SUCCEEDED")

        val edgeId = payload["edgeId"]!!.jsonPrimitive.content
        val edge = database.relationshipEdgeDao().find(edgeId)!!
        assertTrue(edge.userConfirmed)
        assertEquals("FRIEND", edge.relationType)
        assertFalse(edge.evidenceRefsJson.contains(privateEvidence))
        assertNull(database.contactIntelligenceDao().listRelationships("rel-write-a", 10).single().validToEpochMs)
        val execution = database.runtimeToolExecutionDao().listByRunId("r-rel-write").single()
        assertFalse(execution.safeResultJson!!.contains(privateEvidence))
        val change = database.changeLogDao().listByRun("r-rel-write").single()
        val undoRevision = database.runtimeSessionDao().find("s-rel-write")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.UNDO,
                "s-rel-write",
                "r-rel-write",
                "undo-rel",
                "undo-rel-action",
                undoRevision,
                "chat",
                payloadRef = change.changeId,
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        assertNull(database.relationshipEdgeDao().find(edgeId))
        assertNotNull(database.contactIntelligenceDao().listRelationships("rel-write-a", 10).single().validToEpochMs)
        assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
    }

    @Test fun unresolvedSocialIdentityRequiresConfirmationThenCanBeUndone() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "identity-contact", "张三", "张三", null, null, null, null, null,
                "[]", "[]", null, null, "USER", null, now, now,
            ),
        )
        database.contactIntelligenceDao().upsertSourceIdentity(
            SourceIdentityEntity(
                sourceIdentityId = "wechat-project-old-zhang",
                personId = null,
                sourceType = "WECHAT",
                accountScope = "DEVICE_OBSERVED",
                tenantId = null,
                stableExternalId = null,
                visibleHandle = "项目群里的老张",
                normalizedHandle = "项目群里的老张",
                conversationScopeId = "项目群",
                resolutionStatus = "UNRESOLVED",
                confidence = 0.55,
                sourceRef = "notification-identity",
                firstObservedAtEpochMs = now,
                lastObservedAtEpochMs = now,
            ),
        )
        val input = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"项目群里的老张就是张三","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-identity-resolve",
                input.inputRef,
                "c-identity-resolve",
                "a-identity-resolve",
                0,
                "chat",
                "r-identity-resolve",
            ),
        )
        val privateEvidence = "用户明确确认项目群里的老张就是张三"
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = if (request.messages.any { it.content.contains("identity_resolved") }) {
                flowOf(ModelEvent.Delta(0, "身份已关联，可撤销。"), ModelEvent.Final("stop"))
            } else {
                flowOf(
                    ModelEvent.ToolCall(
                        0,
                        "call-identity-resolve",
                        "contact.identity.resolve",
                        """{"sourceIdentityId":"wechat-project-old-zhang","contactId":"identity-contact","evidenceSummary":"$privateEvidence","confidence":0.95}""",
                    ),
                    ModelEvent.Final("tool_calls"),
                )
            }
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(database, "processor", { true }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-identity-resolve", "AWAITING_CONFIRMATION")
        val approval = database.runtimeEventDao().latestByType("r-identity-resolve", "ApprovalRequested")!!
        assertFalse(approval.payloadJson.contains(privateEvidence))
        val payload = Json.parseToJsonElement(approval.payloadJson).jsonObject
        val revision = database.runtimeSessionDao().find("s-identity-resolve")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-identity-resolve", "r-identity-resolve", "approve-identity",
                "approve-identity-action", revision, "chat",
                payload["proposalId"]!!.jsonPrimitive.content,
                payload["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-identity-resolve", "SUCCEEDED")

        val resolved = database.contactIntelligenceDao().findSourceIdentity("wechat-project-old-zhang")!!
        assertEquals("identity-contact", resolved.personId)
        assertEquals("RESOLVED", resolved.resolutionStatus)
        assertTrue(database.contactIdentityDao().listPlatformIdentities().any { it.contactId == "identity-contact" })
        val change = database.changeLogDao().listByRun("r-identity-resolve").single()
        val undoRevision = database.runtimeSessionDao().find("s-identity-resolve")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.UNDO,
                "s-identity-resolve",
                "r-identity-resolve",
                "undo-identity",
                "undo-identity-action",
                undoRevision,
                "chat",
                payloadRef = change.changeId,
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        val restored = database.contactIntelligenceDao().findSourceIdentity("wechat-project-old-zhang")!!
        assertNull(restored.personId)
        assertEquals("UNRESOLVED", restored.resolutionStatus)
        assertFalse(database.contactIdentityDao().listPlatformIdentities().any { it.contactId == "identity-contact" })
        assertEquals("UNDONE", database.changeLogDao().find(change.changeId)?.undoState)
    }

    @Test fun memoryToolRequiresApprovalThenBecomesRetrievableContext() = runBlocking {
        val input = """{"schemaVersion":1,"text":"记住我喜欢简洁回答","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start("s-memory", staged.inputRef, "c-memory", "a-memory", 0, "chat", "r-memory"),
        )
        val requests = mutableListOf<ModelRequest>()
        var deleteLogicalMemoryId = ""
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                val all = request.messages.joinToString("\n") { it.content }
                return when {
                    all.contains("\"tool\":\"memory.delete\"") ->
                        flowOf(ModelEvent.Delta(0, "这条长期记忆已删除。"), ModelEvent.Final("stop"))

                    all.contains("忘掉这个偏好") -> flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-memory-delete",
                            "memory.delete",
                            """{"logicalMemoryId":"$deleteLogicalMemoryId"}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )

                    all.contains("\"tool\":\"memory.search\"") ->
                        flowOf(ModelEvent.Delta(0, "你喜欢简洁回答。"), ModelEvent.Final("stop"))

                    all.contains("我喜欢什么") -> flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-memory-search",
                            "memory.search",
                            """{"query":"用户喜欢简洁回答","limit":10}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )

                    request.messages.any { it.content.contains("不可信数据") } ->
                        flowOf(ModelEvent.Delta(0, "我会按你的偏好回答。"), ModelEvent.Final("stop"))

                    else -> flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-memory",
                            "memory.remember",
                            """{"content":"用户喜欢简洁回答","memoryType":"PREFERENCE","subjectKey":"user","predicateKey":"response_style"}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())

        processor.processNext()
        awaitRunStatus("r-memory", "AWAITING_CONFIRMATION")
        assertTrue(
            runCatching {
                com.zhiban.rebuild.runtime.memory.MemoryAtomicStore(database).recall("runtime-global")
            }.isFailure,
        )
        val approval = database.runtimeEventDao().latestByType("r-memory", "ApprovalRequested")!!
        assertFalse(approval.payloadJson.contains("用户喜欢简洁回答"))
        val payload = Json.parseToJsonElement(approval.payloadJson).jsonObject
        val revision = database.runtimeSessionDao().find("s-memory")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-memory", "r-memory", "approve-memory", "approve-memory-action", revision, "chat",
                payload["proposalId"]!!.jsonPrimitive.content, payload["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-memory", "SUCCEEDED")
        val recalled = com.zhiban.rebuild.runtime.memory.MemoryAtomicStore(database).recall("runtime-global")
        assertEquals(listOf("用户喜欢简洁回答"), recalled.records.map { it.canonicalText })
        deleteLogicalMemoryId = recalled.records.single().logicalMemoryId
        assertTrue(database.runtimeEventDao().listByRunId("r-memory").any { it.eventType == "MemoryCommitted" })

        val followup = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"我喜欢什么？","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val currentRevision = database.runtimeSessionDao().find("s-memory")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-memory",
                followup.inputRef,
                "c-memory-search",
                "a-memory-search",
                currentRevision,
                "chat",
                "r-memory-search",
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-memory-search", "SUCCEEDED")
        assertNull(database.runtimeEventDao().latestByType("r-memory-search", "ApprovalRequested"))
        val searchExecution = database.runtimeToolExecutionDao().listByRunId("r-memory-search").single()
        assertEquals("memory.search", searchExecution.toolName)
        assertTrue(searchExecution.safeResultJson!!.contains("用户喜欢简洁回答"))
        assertTrue(
            requests.last().messages.any {
                it.content.contains("memory.search") && it.content.contains("不可信数据")
            },
        )
        assertTrue(
            requests.any { request ->
                request.messages.any {
                    it.content.contains("source=context_retrieval") && it.content.contains("用户喜欢简洁回答")
                }
            },
        )

        val deleteInput = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"忘掉这个偏好","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val deleteRevision = database.runtimeSessionDao().find("s-memory")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-memory",
                deleteInput.inputRef,
                "c-memory-delete",
                "a-memory-delete",
                deleteRevision,
                "chat",
                "r-memory-delete",
            ),
        )
        processor.processNext()
        awaitRunStatus("r-memory-delete", "AWAITING_CONFIRMATION")
        val deleteApproval = database.runtimeEventDao().latestByType("r-memory-delete", "ApprovalRequested")!!
        val deletePayload = Json.parseToJsonElement(deleteApproval.payloadJson).jsonObject
        assertEquals("memory.delete", deletePayload["toolName"]!!.jsonPrimitive.content)
        val approveDeleteRevision = database.runtimeSessionDao().find("s-memory")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-memory", "r-memory-delete", "approve-memory-delete", "approve-memory-delete-action",
                approveDeleteRevision, "chat", deletePayload["proposalId"]!!.jsonPrimitive.content,
                deletePayload["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-memory-delete", "SUCCEEDED")
        assertTrue(
            com.zhiban.rebuild.runtime.memory.MemoryAtomicStore(database).recall("runtime-global").records.isEmpty(),
        )
        assertEquals(
            "memory.delete",
            database.runtimeToolExecutionDao().listByRunId("r-memory-delete").single().toolName,
        )
        assertEquals(
            "SUCCEEDED",
            database.toolAuditDao().findByIdempotencyKey(
                deletePayload["idempotencyKey"]!!.jsonPrimitive.content,
            )?.status,
        )
        val deleteTrace = com.zhiban.rebuild.runtime.observability.AgentTraceService(database).recent().first {
            it.runId ==
                "r-memory-delete"
        }
        assertTrue(deleteTrace.auditSteps.any { it.phase == "APPROVAL" && it.status == "REQUIRED" })
        assertTrue(
            deleteTrace.auditSteps.any {
                it.phase == "EXECUTION" && it.toolName == "memory.delete" &&
                    it.status == "SUCCEEDED"
            },
        )
        assertFalse(deleteTrace.toString().contains(deletePayload["payloadRef"]!!.jsonPrimitive.content))
        assertFalse(deleteTrace.toString().contains(deletePayload["idempotencyKey"]!!.jsonPrimitive.content))
    }

    @Test fun memoryCommitRollsBackWhenRuntimeFinalizationFails() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"记住我喜欢简洁回答","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-memory-rollback",
                staged.inputRef,
                "c-memory-rollback",
                "a-memory-rollback",
                0,
                "chat",
                "r-memory-rollback",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(
                ModelEvent.ToolCall(
                    0,
                    "call-memory-rollback",
                    "memory.remember",
                    """{"content":"用户喜欢简洁回答","memoryType":"PREFERENCE","subjectKey":"user","predicateKey":"response_style"}""",
                ),
                ModelEvent.Final("tool_calls"),
            )
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        processor.processNext()
        awaitRunStatus("r-memory-rollback", "AWAITING_CONFIRMATION")
        val approval = approvalPlan("r-memory-rollback")
        val candidateId = approval.getValue("candidateId").jsonPrimitive.content
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_memory_runtime_finalization
            BEFORE INSERT ON runtime_tool_executions
            WHEN NEW.toolName = 'memory.remember'
            BEGIN
                SELECT RAISE(ABORT, 'forced runtime finalization failure');
            END
            """.trimIndent(),
        )
        val revision = database.runtimeSessionDao().find("s-memory-rollback")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE,
                "s-memory-rollback",
                "r-memory-rollback",
                "approve-memory-rollback",
                "approve-memory-rollback-action",
                revision,
                "chat",
                approval.getValue("proposalId").jsonPrimitive.content,
                approval.getValue("payloadRef").jsonPrimitive.content,
            ),
        )

        processor.processNext()
        awaitRunStatus("r-memory-rollback", "FAILED_FINAL")
        assertNull(database.memoryPersistenceDao().namespace("runtime-global"))
        assertEquals("PENDING", database.stagedMemoryCandidateDao().find(candidateId)?.state)
        assertTrue(database.runtimeToolExecutionDao().listByRunId("r-memory-rollback").isEmpty())
        assertFalse(
            database.runtimeEventDao().listByRunId("r-memory-rollback").any { it.eventType == "MemoryCommitted" },
        )
    }

    @Test fun remoteMcpToolIsDiscoveredByRouterRequiresConfirmationAndExecutesAtSameLayer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("runtime_mcp_servers", Context.MODE_PRIVATE).edit().clear().commit()
        val factory = RuntimeMcpFactory()
        val environment = McpRemoteEnvironment(
            context,
            NoopCredentialProvisioner(),
            factory,
            OutboundExportGate({ OutboundPolicySettings(allowRemoteMcp = true) }),
        ) { now++ }
        environment.configure("e2e", "E2E MCP", "https://mcp.example.test/rpc", null)
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"查询团队任务","mode":"Work","model":"M2.7","level":"高"}""",
        )
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(RuntimeUiCommand.Start("s-mcp", staged.inputRef, "c-mcp", "a-mcp", 0, "chat", "r-mcp"))
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return if (request.messages.any {
                        it.content.contains("mcp.e2e.tasks.search") &&
                            it.content.contains("isError")
                    }
                ) {
                    flowOf(ModelEvent.Delta(0, "找到了团队任务。"), ModelEvent.Final("stop"))
                } else {
                    flowOf(
                        ModelEvent.ToolCall(0, "call-mcp", "mcp_e2e_tasks_search", """{"query":"quarterly"}"""),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            infrastructure = com.zhiban.rebuild.runtime.kernel.ProviderEngineInfrastructure(mcpEnvironment = environment),
        )

        processor.processNext()
        awaitRunStatus("r-mcp", "AWAITING_CONFIRMATION")
        assertEquals(0, factory.callCount)
        val approval = Json.parseToJsonElement(
            database.runtimeEventDao().latestByType("r-mcp", "ApprovalRequested")!!.payloadJson,
        ).jsonObject
        assertEquals("mcp.e2e.tasks.search", approval["toolName"]!!.jsonPrimitive.content)
        val revision = database.runtimeSessionDao().find("s-mcp")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.APPROVE, "s-mcp", "r-mcp", "approve-mcp", "approve-mcp-action", revision, "chat",
                approval["proposalId"]!!.jsonPrimitive.content, approval["payloadRef"]!!.jsonPrimitive.content,
            ),
        )
        processor.processNext()
        awaitRunStatus("r-mcp", "SUCCEEDED")

        assertEquals(1, factory.callCount)
        assertEquals("quarterly", factory.lastArguments?.get("query")?.jsonPrimitive?.content)
        val execution = database.runtimeToolExecutionDao().listByRunId("r-mcp").single()
        assertEquals("mcp.e2e.tasks.search", execution.toolName)
        assertTrue(execution.safeResultJson!!.contains("task-42"))
        assertTrue(requests.first().toolsJson?.contains("mcp_e2e_tasks_search") == true)
        assertFalse(requests.first().toolsJson?.contains("mcp.e2e.tasks.search") == true)
        environment.remove("e2e")
    }

    @Test fun explicitFeedbackIsPersistedAndAvailableToFuturePlanning() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("hello")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start("s-feedback", staged.inputRef, "c-feedback", "a-feedback", 0, "chat", "r-feedback"),
        )
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return flowOf(ModelEvent.Delta(0, "你好"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())
        processor.processNext()
        awaitRunStatus("r-feedback", "SUCCEEDED")
        val revision = database.runtimeSessionDao().find("s-feedback")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.FEEDBACK_NEGATIVE,
                "s-feedback",
                "r-feedback",
                "feedback-command",
                "feedback-action",
                revision,
                "chat",
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        assertEquals(listOf("NEGATIVE"), RoomRuntimeStore(database, "test").recentFeedback("s-feedback"))
        assertTrue(database.runtimeEventDao().listByRunId("r-feedback").any { it.eventType == "UserFeedbackRecorded" })

        val followup = RoomTextInputGateway(database, { true }, { now }).stage("再回答一次")
        val currentRevision = database.runtimeSessionDao().find("s-feedback")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-feedback",
                followup.inputRef,
                "c-feedback-2",
                "a-feedback-2",
                currentRevision,
                "chat",
                "r-feedback-2",
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-feedback-2", "SUCCEEDED")
        val feedbackContext = requests.last().messages.joinToString("\n") { it.content }
        assertTrue(feedbackContext.contains("需改进=1"))
        assertTrue(feedbackContext.contains("不授予任何权限"))
        assertTrue(feedbackContext.contains("user: hello"))
        assertTrue(feedbackContext.contains("assistant: 你好"))
        val turns = database.runtimeConversationTurnDao().recent("s-feedback", "r-feedback-2", 12)
        assertEquals(listOf("user", "assistant"), turns.map { it.role })
    }

    @Test fun disabledMemoryAndFeedbackPoliciesExcludeTheirContextFromProviderRequests() = runBlocking {
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return flowOf(ModelEvent.Delta(0, "答复"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        val enabledProcessor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )
        val first = RoomTextInputGateway(database, { true }, { now }).stage("第一轮私有上下文")
        gateway.accept(
            RuntimeUiCommand.Start("s-policy", first.inputRef, "c-policy-1", "a-policy-1", 0, "chat", "r-policy-1"),
        )
        enabledProcessor.processNext()
        awaitRunStatus("r-policy-1", "SUCCEEDED")
        val revision = database.runtimeSessionDao().find("s-policy")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.FEEDBACK_NEGATIVE,
                "s-policy",
                "r-policy-1",
                "c-policy-feedback",
                "a-policy-feedback",
                revision,
                "chat",
            ),
        )
        enabledProcessor.processNext()

        val disabledProcessor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(
                memoryPolicy = { MemoryPolicy(sessionMemoryEnabled = false, longTermMemoryEnabled = false) },
                feedbackPolicy = { FeedbackPolicy(useHumanFeedback = false, allowPreferenceImprovement = false) },
            ),
        )
        val followup = RoomTextInputGateway(database, { true }, { now }).stage("第二轮问题")
        val followupRevision = database.runtimeSessionDao().find("s-policy")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-policy",
                followup.inputRef,
                "c-policy-2",
                "a-policy-2",
                followupRevision,
                "chat",
                "r-policy-2",
            ),
        )
        disabledProcessor.processNext()
        awaitRunStatus("r-policy-2", "SUCCEEDED")

        val context = requests.last().messages.joinToString("\n") { it.content }
        assertFalse(context.contains("第一轮私有上下文"))
        assertFalse(context.contains("需改进=1"))
        assertTrue(context.contains("第二轮问题"))
    }

    @Test fun observationCanReplanIntoSecondApprovedToolAndPersistsDag() = runBlocking {
        val input = """{"schemaVersion":1,"text":"安排复盘会并记住我喜欢短会","mode":"Work","model":"M2.7","level":"高"}"""
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(RuntimeUiCommand.Start("s-dag", staged.inputRef, "c-dag", "a-dag", 0, "chat", "r-dag"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                val all = request.messages.joinToString("\n") { it.content }
                return when {
                    all.contains(
                        "\"tool\":\"memory.remember\"",
                    ) -> flowOf(ModelEvent.Delta(0, "都处理好了。"), ModelEvent.Final("stop"))

                    all.contains("\"tool\":\"calendar.schedule.create\"") -> flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-dag-memory",
                            "memory.remember",
                            """{"content":"用户喜欢短会","memoryType":"PREFERENCE","subjectKey":"user","predicateKey":"meeting_duration"}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )

                    else -> flowOf(
                        ModelEvent.ToolCall(
                            0,
                            "call-dag-schedule",
                            "calendar.schedule.create",
                            """{"title":"复盘会","startAtEpochMs":3000000,"durationMinutes":30}""",
                        ),
                        ModelEvent.Final("tool_calls"),
                    )
                }
            }
            override fun cancel(requestId: String) = true
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())
        processor.processNext()
        awaitRunStatus("r-dag", "AWAITING_CONFIRMATION")

        suspend fun approve(commandId: String) {
            val approval = database.runtimeEventDao().latestByType("r-dag", "ApprovalRequested")!!
            val payload = Json.parseToJsonElement(approval.payloadJson).jsonObject
            val revision = database.runtimeSessionDao().find("s-dag")!!.nextSequence - 1
            gateway.accept(
                RuntimeUiCommand.RunAction(RuntimeAction.APPROVE, "s-dag", "r-dag", commandId, "$commandId-action", revision, "chat", payload["proposalId"]!!.jsonPrimitive.content, payload["payloadRef"]!!.jsonPrimitive.content),
            )
            processor.processNext()
        }
        approve("approve-dag-1")
        awaitRunStatus("r-dag", "AWAITING_CONFIRMATION")
        approve("approve-dag-2")
        awaitRunStatus("r-dag", "SUCCEEDED")

        val definitionId = "runtime-plan-r-dag"
        assertEquals(2, database.planDao().nodesForDefinition(definitionId).size)
        assertEquals(1, database.planDao().edgesForDefinition(definitionId).size)
        assertEquals("TERMINAL", database.planDao().runById("r-dag")?.runStatus)
        assertEquals(2, database.runtimeToolExecutionDao().listByRunId("r-dag").size)
        assertTrue(
            com.zhiban.rebuild.runtime.memory.MemoryAtomicStore(database).recall("runtime-global").records.any {
                it.canonicalText ==
                    "用户喜欢短会"
            },
        )
    }

    @Test fun hangingProbeIsCancelledByCancelCommand() = runBlocking {
        val probeCancelled = AtomicBoolean(false)
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("probe cancel")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(RuntimeUiCommand.Start("s-probe", staged.inputRef, "c-probe", "a-probe", 0, "chat", "r-probe"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override suspend fun probe(profile: ProviderProfile, requestId: String): CapabilitySnapshot = try {
                awaitCancellation()
            } finally {
                probeCancelled.set(true)
            }
            override fun stream(request: ModelRequest) = flowOf<ModelEvent>()
            override fun cancel(requestId: String): Boolean {
                probeCancelled.set(true)
                return true
            }
        }
        val processor =
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }, provider = provider, profiles = fixedProfileStore())
        processor.processNext()
        awaitRunStatus("r-probe", "INFERENCING")
        val revision = database.runtimeSessionDao().find("s-probe")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.CANCEL,
                "s-probe",
                "r-probe",
                "cancel-probe",
                "cancel-action",
                revision,
                "chat",
            ),
        )
        processor.processNext()
        awaitRunStatus("r-probe", "CANCELLED")
        assertTrue(probeCancelled.get())
    }

    @Test fun stalledProviderTimesOutWhileLeaseHeartbeatKeepsSafeFailureWritable() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("stall")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s-stall", staged.inputRef, "c-stall", "a-stall", 0, "chat", "r-stall"))
        KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
        val lease = database.runtimeSessionDao().find("s-stall")!!
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flow<ModelEvent> { awaitCancellation() }
            override fun cancel(requestId: String) = true
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { now++ },
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(
                totalTimeoutMs = 500,
                idleTimeoutMs = 40,
                heartbeatIntervalMs = 10,
                leaseDurationMs = 100,
            ),
        )

        assertFalse(engine.execute("r-stall", "s-stall", lease.leaseEpoch))
        assertEquals("FAILED_RETRYABLE", database.runtimeRunDao().find("r-stall")?.status)
        assertTrue(
            database.runtimeEventDao().listByRunId("r-stall").single { it.eventType == "RunFailedRetryable" }
                .payloadJson.contains("TIMEOUT"),
        )
    }

    @Test fun stalledToolObservationFallsBackAndUnlocksConversation() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage(
            """{"schemaVersion":1,"text":"查找联系人张三","mode":"Work","model":"M2.7"}""",
        )
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s-observe-timeout", staged.inputRef, "c-observe", "a-observe", 0, "chat", "r-observe-timeout"))
        KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
        val lease = database.runtimeSessionDao().find("s-observe-timeout")!!
        val streams = AtomicInteger()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = if (streams.getAndIncrement() == 0) {
                flowOf(
                    ModelEvent.ToolCall(
                        0,
                        "call-contact",
                        "contact.search",
                        """{"query":"张三"}""",
                    ),
                    ModelEvent.Final("tool_calls"),
                )
            } else {
                flow<ModelEvent> {
                    emit(ModelEvent.Delta(0, "没有检测到日程冲突。"))
                    awaitCancellation()
                }
            }
            override fun cancel(requestId: String) = true
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { now++ },
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(totalTimeoutMs = 1_000),
        )

        val completed = engine.execute("r-observe-timeout", "s-observe-timeout", lease.leaseEpoch)
        val run = database.runtimeRunDao().find("r-observe-timeout")
        val events = database.runtimeEventDao().listByRunId("r-observe-timeout")
        assertTrue(
            "completed=$completed status=${run?.status} events=${events.map { it.eventType to it.payloadJson }}",
            completed,
        )
        assertEquals("SUCCEEDED", run?.status)
        assertTrue(
            events.any {
                it.eventType == "RunCompleted" && it.payloadJson.contains("tool_observation_fallback")
            },
        )
    }

    @Test fun weakNetworkSkipsVectorAndRerankWhileExtremeNetworkFailsBeforeProvider() = runBlocking {
        suspend fun stage(session: String, run: String) {
            val input = RoomTextInputGateway(database, { true }, { now }).stage("你好")
            RoomRuntimeGateways(database, "test") { now++ }
                .accept(RuntimeUiCommand.Start(session, input.inputRef, "c-$run", "a-$run", 0, "chat", run))
        }
        val calls = AtomicInteger()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile).copy(features = setOf("rerank"))
            override fun stream(request: ModelRequest) =
                flowOf<ModelEvent>(ModelEvent.Delta(0, "你好"), ModelEvent.Final("stop")).also { calls.incrementAndGet() }
            override fun cancel(requestId: String) = true
        }

        stage("s-weak", "r-weak")
        val weak = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(networkQuality = { com.zhiban.rebuild.runtime.network.NetworkQuality.WEAK }),
        )
        weak.processNext()
        awaitRunStatus("r-weak", "SUCCEEDED")
        val retrieval = database.runtimeEventDao().latestByType("r-weak", "ContextRetrievalCompleted")!!
        assertTrue(retrieval.payloadJson.contains("vector_skipped:weak_network"))
        assertNull(database.runtimeEventDao().latestByType("r-weak", "ContextRerankCompleted"))

        stage("s-extreme", "r-extreme")
        val extreme = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(networkQuality = { com.zhiban.rebuild.runtime.network.NetworkQuality.EXTREME }),
        )
        extreme.processNext()
        awaitRunStatus("r-extreme", "FAILED_RETRYABLE")
        assertTrue(
            database.runtimeEventDao().latestByType(
                "r-extreme",
                "RunFailedRetryable",
            )!!.payloadJson.contains("NETWORK_TOO_SLOW"),
        )
        assertEquals(1, calls.get())

        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        val revision = database.runtimeSessionDao().find("s-extreme")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.RETRY,
                "s-extreme",
                "r-extreme",
                "retry-extreme",
                "retry-extreme-action",
                revision,
                "chat",
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, extreme.processNext())
        awaitRunStatus("r-extreme", "FAILED_RETRYABLE")
        val attempts = database.runtimeAttemptDao().listByRunId("r-extreme")
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it.status == "FAILED" })
        assertEquals(1, calls.get())

        stage("s-forced-fts", "r-forced-fts")
        val forcedFts = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(dynamicConfig = {
                com.zhiban.rebuild.runtime.config.AgentDynamicConfig(forceFtsOnly = true)
            }),
        )
        forcedFts.processNext()
        awaitRunStatus("r-forced-fts", "SUCCEEDED")
        assertTrue(
            database.runtimeEventDao().latestByType(
                "r-forced-fts",
                "ContextRetrievalCompleted",
            )!!.payloadJson.contains("vector_skipped:remote_force_fts_only"),
        )
        assertNull(database.runtimeEventDao().latestByType("r-forced-fts", "ContextRerankCompleted"))

        stage("s-blacklisted", "r-blacklisted")
        val blacklisted = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(
                dynamicConfig = {
                    com.zhiban.rebuild.runtime.config.AgentDynamicConfig(providerBlacklist = setOf("stepfun"))
                },
            ),
        )
        blacklisted.processNext()
        awaitRunStatus("r-blacklisted", "FAILED_FINAL")
        assertTrue(
            database.runtimeEventDao().latestByType(
                "r-blacklisted",
                "RunFailedFinal",
            )!!.payloadJson.contains("PROVIDER_DISABLED"),
        )
    }

    @Test fun perceptionTimeoutDegradesLocallyWithoutBlockingProviderExecution() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("你好")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(
                RuntimeUiCommand.Start(
                    "s-perception-timeout",
                    staged.inputRef,
                    "c-perception-timeout",
                    "a-perception-timeout",
                    0,
                    "chat",
                    "r-perception-timeout",
                ),
            )
        KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
        val lease = database.runtimeSessionDao().find("s-perception-timeout")!!
        val requests = mutableListOf<ModelRequest>()
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<ModelEvent> {
                requests += request
                return flowOf(ModelEvent.Delta(0, "你好"), ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        val fallbackExtractor = LocalEntityExtractor()
        val stalledPerception = object : PerceptionGateway {
            override suspend fun perceive(text: String, mode: String): QueryContext {
                delay(500)
                return fallback(text, mode)
            }
            override fun fallback(text: String, mode: String) = fallbackExtractor.extract(text, mode, now)
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { now++ },
            config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(totalTimeoutMs = 2_000),
            infrastructure = com.zhiban.rebuild.runtime.kernel.ProviderEngineInfrastructure(
                perception = stalledPerception,
            ),
        )

        assertTrue(engine.execute("r-perception-timeout", "s-perception-timeout", lease.leaseEpoch))
        assertEquals("SUCCEEDED", database.runtimeRunDao().find("r-perception-timeout")?.status)
        val event = database.runtimeEventDao().latestByType("r-perception-timeout", "PerceptionCompleted")!!
        assertTrue(event.payloadJson.contains("\"degraded\":true"))
        assertTrue(event.payloadJson.contains("GENERAL_CHAT"))
        assertTrue(requests.single().messages.any { it.content.contains("source=local_perception") })
    }

    @Test fun cancelCommandRemainsProcessableWhileProviderStreamIsActive() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("cancel")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(RuntimeUiCommand.Start("s-cancel", staged.inputRef, "c-start", "a-start", 0, "chat", "r-cancel"))
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flow<ModelEvent> {
                emit(ModelEvent.Delta(0, "partial"))
                awaitCancellation()
            }
            override fun cancel(requestId: String): Boolean {
                cancelled.set(true)
                return true
            }
        }
        val processor = KernelCommandProcessor(
            database,
            "processor",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-cancel", "INFERENCING")
        val revision = database.runtimeSessionDao().find("s-cancel")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.CANCEL,
                "s-cancel",
                "r-cancel",
                "c-cancel",
                "a-cancel",
                revision,
                "chat",
            ),
        )
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-cancel", "CANCELLED")
        assertTrue(cancelled.get())
        assertTrue(database.runtimeEventDao().listByRunId("r-cancel").any { it.eventType == "RunCancelled" })
    }

    @Test fun cancelFallbackFinalizesRequestedRunWhenProviderJobAlreadyExited() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("cancel race")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-cancel-race",
                staged.inputRef,
                "c-start-race",
                "a-start-race",
                0,
                "chat",
                "r-cancel-race",
            ),
        )
        val commandOnlyProcessor = KernelCommandProcessor(database, "processor", { true }, { now++ })
        commandOnlyProcessor.processNext()
        val lease = database.runtimeSessionDao().find("s-cancel-race")!!
        RoomRuntimeStore(database, "test").startAttempt(
            AttemptStartRequest(
                "attempt-cancel-race",
                "r-cancel-race",
                1,
                "processor",
                lease.leaseEpoch,
                now++,
            ),
        )
        val revision = database.runtimeSessionDao().find("s-cancel-race")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.CANCEL,
                "s-cancel-race",
                "r-cancel-race",
                "c-cancel-race",
                "a-cancel-race",
                revision,
                "chat",
            ),
        )
        commandOnlyProcessor.processNext()
        assertEquals("CANCEL_REQUESTED", database.runtimeRunDao().find("r-cancel-race")?.status)
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf<ModelEvent>()
            override fun cancel(requestId: String) = false
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { now++ },
        )

        assertTrue(engine.cancel("r-cancel-race", "s-cancel-race", lease.leaseEpoch))
        assertEquals("CANCELLED", database.runtimeRunDao().find("r-cancel-race")?.status)
        assertTrue(database.runtimeEventDao().listByRunId("r-cancel-race").any { it.eventType == "RunCancelled" })
    }

    @Test fun immediateCancelBeforeAttemptExistsStillReachesCancelled() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("cancel before attempt")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-cancel-before-attempt",
                staged.inputRef,
                "c-start-before-attempt",
                "a-start-before-attempt",
                0,
                "chat",
                "r-cancel-before-attempt",
            ),
        )
        val commandOnlyProcessor = KernelCommandProcessor(database, "processor", { true }, { now++ })
        commandOnlyProcessor.processNext()
        val lease = database.runtimeSessionDao().find("s-cancel-before-attempt")!!
        val revision = lease.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.CANCEL,
                "s-cancel-before-attempt",
                "r-cancel-before-attempt",
                "c-cancel-before-attempt",
                "a-cancel-before-attempt",
                revision,
                "chat",
            ),
        )
        commandOnlyProcessor.processNext()
        assertEquals("CANCEL_REQUESTED", database.runtimeRunDao().find("r-cancel-before-attempt")?.status)
        assertNull(database.runtimeRunDao().find("r-cancel-before-attempt")?.activeAttemptId)
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf<ModelEvent>()
            override fun cancel(requestId: String) = false
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { now++ },
        )

        assertTrue(engine.cancel("r-cancel-before-attempt", "s-cancel-before-attempt", lease.leaseEpoch))
        assertEquals("CANCELLED", database.runtimeRunDao().find("r-cancel-before-attempt")?.status)
        assertTrue(
            database.runtimeEventDao().listByRunId("r-cancel-before-attempt")
                .any { it.eventType == "RunCancelled" },
        )
    }

    @Test fun crashAfterPartialSupersedesAttemptInsteadOfAssumingIdenticalReplay() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("recover")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(
                RuntimeUiCommand.Start("s-recover", staged.inputRef, "c-recover", "a-recover", 0, "chat", "r-recover"),
            )
        KernelCommandProcessor(database, "owner-a", { true }, { now++ }).processNext()
        val firstLease = database.runtimeSessionDao().find("s-recover")!!
        val store = RoomRuntimeStore(database, "test")
        store.startAttempt(AttemptStartRequest("attempt-old", "r-recover", 1, "owner-a", firstLease.leaseEpoch, now++))
        store.appendProviderEventOnce(
            RuntimeEventDraft("event-provider-attempt-old-delta-0", "AssistantDelta", "s-recover", "r-recover", "attempt-old", "attempt-old", "r-recover", "{\"ordinal\":0,\"part\":\"old-partial\",\"final\":false}", now++),
            "owner-a",
            firstLease.leaseEpoch,
            now++,
        )
        now += 200_000
        val secondLease = RoomRuntimeStore(database, "test").claimSession("s-recover", "owner-b", now, 120_000)
        val succeeding = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Delta(0, "new-answer"), ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = false
        }
        val second = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(database, succeeding, fixedProfileStore(), "owner-b", {
            now++
        })

        assertTrue(second.execute("r-recover", "s-recover", secondLease.leaseEpoch))
        val attempts = database.runtimeAttemptDao().listByRunId("r-recover")
        assertEquals(listOf("SUPERSEDED", "SUCCEEDED"), attempts.map { it.status })
        assertTrue(
            database.runtimeEventDao().listByRunId("r-recover").any {
                it.eventType == "ProviderAttemptSuperseded"
            },
        )
    }

    @Test fun recoveryEngineDrainsEveryHandleClaimedInOneScan() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { now })
        val gateways = RoomRuntimeGateways(database, "test") { now++ }
        listOf("a", "b").forEach { suffix ->
            val staged = input.stage("recover-$suffix")
            gateways.accept(
                RuntimeUiCommand.Start(
                    "session-$suffix",
                    staged.inputRef,
                    "command-$suffix",
                    "action-$suffix",
                    0,
                    "chat",
                    "run-$suffix",
                ),
            )
            assertEquals(
                KernelCommandProcessor.Outcome.PROCESSED,
                KernelCommandProcessor(database, "dead-owner", { true }, { now++ }).processNext(),
            )
        }
        now += 31_000
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Delta(0, "recovered"), ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = true
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "recovery-owner",
            { now++ },
        )

        assertTrue(engine.recoverNext())
        assertTrue(engine.recoverNext())
        awaitRunStatus("run-a", "SUCCEEDED")
        awaitRunStatus("run-b", "SUCCEEDED")
        assertFalse(engine.recoverNext())
    }

    @Test fun expiredInputFailsClosedAndIsClearedWithoutDomainSuccess() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { now }, ttlMs = 10)
        val staged = input.stage("expired")
        val gateways = RoomRuntimeGateways(database, "test") { now++ }
        gateways.accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        now += 11
        val processor = KernelCommandProcessor(database, "processor", { true }, { now++ })

        assertEquals(KernelCommandProcessor.Outcome.FAILED, processor.processNext())
        assertNull(database.runtimeInputStagingDao().find(staged.inputRef))
        assertEquals("FAILED", database.runtimeCommandInboxDao().find("c1")?.status)
        assertEquals("FAILED_FINAL", database.runtimeRunDao().find("r1")?.status)
        assertFalse(database.runtimeEventDao().listAfter("s1", 0).any { it.eventType == "InputCommitted" })
    }

    @Test fun activeClaimedSessionDoesNotStarveAnotherPendingSession() = runBlocking {
        val gateway = RoomTextInputGateway(database, { true }, { now })
        val a = gateway.stage("a")
        val b = gateway.stage("b")
        val commands = RoomRuntimeGateways(database, "test") { now++ }
        commands.accept(RuntimeUiCommand.Start("session-a", a.inputRef, "command-a", "action-a", 0, "chat", "run-a"))
        commands.accept(RuntimeUiCommand.Start("session-b", b.inputRef, "command-b", "action-b", 0, "chat", "run-b"))
        val store = RoomRuntimeStore(database, "test")
        store.claimSession("session-a", "other", now, 30_000)

        assertEquals(
            KernelCommandProcessor.Outcome.PROCESSED,
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }).processNext(),
        )
        assertEquals("COMPLETED", database.runtimeCommandInboxDao().find("command-b")?.status)
        assertEquals("PENDING", database.runtimeCommandInboxDao().find("command-a")?.status)
    }

    @Test fun featureFlagOffRejectsCommandAndCleansStagingWithoutRuntimeWrites() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("turn off")
        val gateways =
            RoomRuntimeGateways(
                database,
                "test",
                com.zhiban.rebuild.runtime.spi.RuntimeV2FeatureFlag {
                    false
                },
            ) { now++ }
        val receipt = gateways.accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        assertEquals(com.zhiban.rebuild.runtime.spi.CommandReceiptStatus.REJECTED, receipt.status)
        assertEquals(0, database.runtimeInputStagingDao().count())
        assertNull(database.runtimeRunDao().find("r1"))
        assertNull(database.runtimeCommandInboxDao().find("c1"))
    }

    @Test fun appScopeRunnerDrainsPendingStartWithoutUiPolling() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("cold start")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        val runner = RuntimeCommandRunner(KernelCommandProcessor(database, "app-process", { true }, { now++ }))
        runner.start()
        repeat(40) {
            if (database.runtimeCommandInboxDao().find("c1")?.status == "COMPLETED") return@repeat
            delay(25)
        }
        runner.stopForTest()
        assertEquals("COMPLETED", database.runtimeCommandInboxDao().find("c1")?.status)
    }

    @Test fun idleRunnerWaitsForAWorkSignalInsteadOfHotSpinning() = runBlocking {
        val clockReads = AtomicInteger(0)
        val runner = RuntimeCommandRunner(
            KernelCommandProcessor(
                database,
                "idle-runner",
                { true },
                {
                    clockReads.incrementAndGet()
                    now
                },
            ),
        )

        runner.start()
        delay(2_000)
        runner.stopForTest()

        assertTrue("idle runner read the clock ${clockReads.get()} times", clockReads.get() <= 8)
    }

    @Test fun runnerWakesForCompletedCommandWhoseRunLeaseExpiresAfterProcessDeath() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }).stage("recover completed command")
        RoomRuntimeGateways(database, "test")
            .accept(RuntimeUiCommand.Start("s-recover-wake", staged.inputRef, "c-recover-wake", "a-recover-wake", 0, "chat", "r-recover-wake"))
        assertEquals(
            KernelCommandProcessor.Outcome.PROCESSED,
            KernelCommandProcessor(database, "dead-process", { true }).processNext(),
        )
        val leaseExpiresAt = System.currentTimeMillis() + 250
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_sessions SET leaseExpiresAtEpochMs=? WHERE sessionId=?",
            arrayOf<Any>(leaseExpiresAt, "s-recover-wake"),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Delta(0, "已恢复"), ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = true
        }
        val runner = RuntimeCommandRunner(
            KernelCommandProcessor(database, "new-process", { true }, provider = provider, profiles = fixedProfileStore()),
        )

        runner.start()
        repeat(80) {
            if (database.runtimeRunDao().find("r-recover-wake")?.status == "SUCCEEDED") return@repeat
            delay(25)
        }
        runner.stopForTest()

        assertEquals("COMPLETED", database.runtimeCommandInboxDao().find("c-recover-wake")?.status)
        assertEquals("SUCCEEDED", database.runtimeRunDao().find("r-recover-wake")?.status)
    }

    @Test fun escapedProviderFailureIsContainedAndMakesSessionRetryable() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("trigger unexpected provider failure")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s-contained", staged.inputRef, "c-contained", "a-contained", 0, "chat", "r-contained"))
        KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
        val lease = database.runtimeSessionDao().find("s-contained")!!
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = true
        }
        val engine = com.zhiban.rebuild.runtime.kernel.ProviderExecutionEngine(
            database,
            provider,
            fixedProfileStore(),
            "processor",
            { now++ },
            com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(
                networkQuality = { error("unexpected runtime defect") },
            ),
        )

        assertTrue(engine.launch("r-contained", "s-contained", lease.leaseEpoch))
        awaitRunStatus("r-contained", "FAILED_RETRYABLE")

        assertTrue(
            database.runtimeEventDao().listByRunId("r-contained").any {
                it.eventType == "RunFailedRetryable" && it.payloadJson.contains("RUNTIME_INTERRUPTED")
            },
        )
    }

    @Test fun resumeCommandImmediatelyRelaunchesInterruptedInference() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("resume interrupted inference")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-resume-now",
                staged.inputRef,
                "c-resume-start",
                "a-resume-start",
                0,
                "chat",
                "r-resume-now",
            ),
        )
        KernelCommandProcessor(database, "app-process", { true }, { now++ }).processNext()
        val revision = database.runtimeSessionDao().find("s-resume-now")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.RESUME,
                "s-resume-now",
                "r-resume-now",
                "c-resume-now",
                "a-resume-now",
                revision,
                "chat",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Delta(0, "已恢复"), ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = true
        }
        val processor = KernelCommandProcessor(
            database,
            "app-process",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
        awaitRunStatus("r-resume-now", "SUCCEEDED")
        assertEquals("已恢复", database.runtimeConversationTurnDao().listBySession("s-resume-now", 10).last().content)
    }

    @Test fun resumeObservationWithoutDurableExecutionFailsRecoverablyInsteadOfSticking() = runBlocking {
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("resume broken observation")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(
            RuntimeUiCommand.Start(
                "s-resume-observation",
                staged.inputRef,
                "c-resume-observation-start",
                "a-resume-observation-start",
                0,
                "chat",
                "r-resume-observation",
            ),
        )
        KernelCommandProcessor(database, "app-process", { true }, { now++ }).processNext()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET status='OBSERVING' WHERE runId='r-resume-observation'",
        )
        val revision = database.runtimeSessionDao().find("s-resume-observation")!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(
                RuntimeAction.RESUME,
                "s-resume-observation",
                "r-resume-observation",
                "c-resume-observation",
                "a-resume-observation",
                revision,
                "chat",
            ),
        )
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flowOf(ModelEvent.Final("stop"))
            override fun cancel(requestId: String) = true
        }

        KernelCommandProcessor(
            database,
            "app-process",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        ).processNext()

        assertEquals("FAILED_RETRYABLE", database.runtimeRunDao().find("r-resume-observation")?.status)
        assertTrue(
            database.runtimeEventDao().listByRunId("r-resume-observation").any {
                it.eventType == "RunFailedRetryable" && it.payloadJson.contains("RUNTIME_INTERRUPTED")
            },
        )
    }

    @Test fun twentyFiveSequentialLongStreamsLeaveSessionWritableAndEveryRunTerminal() = runBlocking {
        val prompts = listOf(
            "Summarize today without tools",
            "Find a contact without writing",
            "Inspect calendar conflicts without writing",
            "Explain CRM next actions without writing",
            "Use the relationship skill without writing",
        )
        val providerCalls = AtomicInteger(0)
        val provider = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = capability(profile)
            override fun stream(request: ModelRequest) = flow {
                val call = providerCalls.incrementAndGet()
                repeat(25) { part -> emit(ModelEvent.Delta(part.toLong(), "[$call:$part]")) }
                emit(ModelEvent.Final("stop"))
            }
            override fun cancel(requestId: String) = true
        }
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        val processor = KernelCommandProcessor(
            database,
            "app-process",
            { true },
            { now++ },
            provider = provider,
            profiles = fixedProfileStore(),
        )

        repeat(25) { index ->
            val sessionId = "s-sequential"
            val runId = "r-sequential-$index"
            val input = "${prompts[index % prompts.size]} #$index " + "detail ".repeat(80)
            val staged = RoomTextInputGateway(database, { true }, { now }).stage(input)
            val revision = database.runtimeSessionDao().find(sessionId)?.let { it.nextSequence - 1 } ?: 0
            gateway.accept(
                RuntimeUiCommand.Start(
                    sessionId,
                    staged.inputRef,
                    "c-sequential-$index",
                    "a-sequential-$index",
                    revision,
                    "chat",
                    runId,
                ),
            )
            assertEquals(KernelCommandProcessor.Outcome.PROCESSED, processor.processNext())
            awaitRunStatus(runId, "SUCCEEDED")
        }

        assertEquals(25, providerCalls.get())
        assertEquals(25, database.runtimeRunDao().idsBySession("s-sequential").size)
        assertTrue(
            database.runtimeRunDao().idsBySession("s-sequential").all { runId ->
                database.runtimeRunDao().find(runId)?.status == "SUCCEEDED"
            },
        )
        assertEquals(0, database.runtimeInputStagingDao().count())
        val turns = database.runtimeConversationTurnDao().listBySession("s-sequential", 100)
        assertEquals(25, turns.count { it.role == "user" })
        assertEquals(25, turns.count { it.role == "assistant" })
    }

    @Test fun typedRunActionsUseStateMachineAndIllegalActionFailsClosed() = runBlocking {
        val cases = listOf(
            Triple(RuntimeAction.APPROVE, "AWAITING_CONFIRMATION", "EXECUTING"),
            Triple(RuntimeAction.REJECT, "AWAITING_CONFIRMATION", "CANCELLED"),
            Triple(RuntimeAction.CANCEL, "ASSEMBLING_CONTEXT", "CANCEL_REQUESTED"),
            Triple(RuntimeAction.RETRY, "FAILED_RETRYABLE", "INFERENCING"),
            Triple(RuntimeAction.RESUME, "FAILED_RETRYABLE", "FAILED_RETRYABLE"),
        )
        cases.forEachIndexed { index, (action, initial, expected) ->
            val session = "s$index"
            val run = "r$index"
            val staged = RoomTextInputGateway(database, { true }, { now }).stage("input-$index")
            val gateway = RoomRuntimeGateways(database, "test") { now++ }
            gateway.accept(
                RuntimeUiCommand.Start(session, staged.inputRef, "start-$index", "start-action-$index", 0, "chat", run),
            )
            KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE runtime_runs SET status=? WHERE runId=?",
                arrayOf(initial, run),
            )
            if (action == RuntimeAction.APPROVE || action == RuntimeAction.REJECT) {
                val leaseEpoch = database.runtimeSessionDao().find(session)!!.leaseEpoch
                RoomRuntimeStore(database, "test").appendEvent(
                    RuntimeEventDraft("approval-$index", "ApprovalRequested", session, run, null, "plan-$index", run, "{\"proposalId\":\"p$index\",\"payloadRef\":\"payload-$index\"}", now),
                    "processor",
                    leaseEpoch,
                    now,
                )
            }
            val revision = database.runtimeSessionDao().find(session)!!.nextSequence - 1
            gateway.accept(
                RuntimeUiCommand.RunAction(action, session, run, "action-$index", "client-$index", revision, "chat", proposalId = "p$index", payloadRef = "payload-$index"),
            )
            assertEquals(
                KernelCommandProcessor.Outcome.PROCESSED,
                KernelCommandProcessor(database, "processor", {
                    true
                }, { now++ }).processNext(),
            )
            assertEquals(expected, database.runtimeRunDao().find(run)?.status)
            if (action == RuntimeAction.RESUME) {
                val catchUp = gateway.snapshotAndObserve(session, "ui", revision).events.first()
                assertTrue(catchUp.any { it.eventType == "RunResumed" })
            }
        }

        val session = "illegal-session"
        val run = "illegal-run"
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("illegal")
        val gateway = RoomRuntimeGateways(database, "test") { now++ }
        gateway.accept(RuntimeUiCommand.Start(session, staged.inputRef, "illegal-start", "a", 0, "chat", run))
        KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
        val leaseEpoch = database.runtimeSessionDao().find(session)!!.leaseEpoch
        RoomRuntimeStore(database, "test").appendEvent(
            RuntimeEventDraft("illegal-approval", "ApprovalRequested", session, run, null, "plan", run, "{\"proposalId\":\"expected\",\"payloadRef\":\"expected-ref\"}", now),
            "processor",
            leaseEpoch,
            now,
        )
        val revision = database.runtimeSessionDao().find(session)!!.nextSequence - 1
        gateway.accept(
            RuntimeUiCommand.RunAction(RuntimeAction.APPROVE, session, run, "illegal-approve", "b", revision, "chat", proposalId = "wrong", payloadRef = "wrong-ref"),
        )
        assertEquals(
            KernelCommandProcessor.Outcome.FAILED,
            KernelCommandProcessor(database, "processor", {
                true
            }, { now++ }).processNext(),
        )
        assertEquals("ASSEMBLING_CONTEXT", database.runtimeRunDao().find(run)?.status)
        assertFalse(database.runtimeEventDao().listByRunId(run).any { it.eventType == "ApproveApplied" })
    }

    @Test fun runnerWakesOnceAtForeignLeaseExpiryAfterFileDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-lease-wakeup.db"
        database.close()
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        val staged = RoomTextInputGateway(database, { true }).stage("lease recovery")
        RoomRuntimeGateways(
            database,
            "test",
        ).accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        val store = RoomRuntimeStore(database, "test")
        val now = System.currentTimeMillis()
        val lease = store.claimSession("s1", "dead-process", now, 200)
        assertTrue(store.claimCommand("c1", "dead-process", lease.leaseEpoch, now))
        database.close()

        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        val runner = RuntimeCommandRunner(KernelCommandProcessor(database, "app-process", { true }))
        runner.start()
        repeat(60) {
            if (database.runtimeCommandInboxDao().find("c1")?.status == "COMPLETED") return@repeat
            delay(25)
        }
        runner.stopForTest()
        assertEquals("COMPLETED", database.runtimeCommandInboxDao().find("c1")?.status)
        database.close()
        context.deleteDatabase(name)
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
        Unit
    }

    @Test fun transactionFailurePreservesRawForRecovery() = runBlocking {
        val input = RoomTextInputGateway(database, { true }, { now })
        val staged = input.stage("recover me")
        val gateways = RoomRuntimeGateways(database, "test") { now++ }
        gateways.accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        database.openHelper.writableDatabase.execSQL("UPDATE runtime_runs SET status='SUCCEEDED' WHERE runId='r1'")

        val processor = KernelCommandProcessor(database, "processor", { true }, { now++ })
        assertTrue(runCatching { processor.processNext() }.isFailure)
        assertEquals("recover me", database.runtimeInputStagingDao().find(staged.inputRef)?.rawText)
        assertFalse(database.runtimeEventDao().listAfter("s1", 0).any { it.eventType == "InputCommitted" })
    }

    @Test fun fileDatabaseReopenContinuesPendingStart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "runtime-input-reopen.db"
        database.close()
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        val staged = RoomTextInputGateway(database, { true }, { now }).stage("after restart")
        RoomRuntimeGateways(database, "test") { now++ }
            .accept(RuntimeUiCommand.Start("s1", staged.inputRef, "c1", "a1", 0, "chat", "r1"))
        database.close()

        database = Room.databaseBuilder(context, AgentDatabase::class.java, name).allowMainThreadQueries().build()
        val outcome = KernelCommandProcessor(database, "processor", { true }, { now++ }).processNext()
        assertEquals(KernelCommandProcessor.Outcome.PROCESSED, outcome)
        assertNull(database.runtimeInputStagingDao().find(staged.inputRef))
        database.close()
        context.deleteDatabase(name)
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries().build()
        Unit
    }

    @Test fun backupIsDisabledAtRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(hasDatabaseRootExclusion(context, R.xml.backup_rules))
        assertTrue(hasDatabaseRootExclusion(context, R.xml.data_extraction_rules))
    }

    private fun hasDatabaseRootExclusion(context: Context, resourceId: Int): Boolean {
        val parser = context.resources.getXml(resourceId)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" &&
                parser.getAttributeValue(null, "domain") == "database" &&
                parser.getAttributeValue(null, "path") == "."
            ) {
                return true
            }
            parser.next()
        }
        return false
    }

    private fun fixedProfileStore() = object : ProviderProfileStore {
        private val profile = ProviderProfile("stepfun", "stepfun-cn-openai-v1", "step-3.5-flash", "stepfun.primary", 1)
        override suspend fun load() = profile
        override suspend fun save(profile: ProviderProfile) = Unit
        override suspend fun clear() = Unit
    }

    private fun capability(profile: ProviderProfile) = CapabilitySnapshot(
        profileDigest = com.zhiban.rebuild.runtime.provider.TrustedProviderRegistry().digest(profile),
        modalities = setOf("text"),
        features = setOf("stream", "tools"),
        maxContextTokens = 32_768,
        maxOutputTokens = 4_096,
        observedAtEpochMs = now,
        expiresAtEpochMs = now + 60_000,
    )

    private class NoopCredentialProvisioner : CredentialProvisioner {
        override suspend fun provision(credentialRef: String, keyVersion: Int, credential: ByteArray) = Unit
        override suspend fun delete(credentialRef: String, keyVersion: Int) = Unit
        override suspend fun contains(credentialRef: String, keyVersion: Int) = false
    }

    private class RuntimeMcpFactory : McpConnectionFactory {
        var callCount = 0
        var lastArguments: JsonObject? = null
        override fun create(endpoint: String, credentialRef: String?) = McpClient(
            McpTransport { request ->
                val id = request["id"]?.jsonPrimitive?.content
                when (request["method"]?.jsonPrimitive?.content) {
                    "initialize" -> rpc(
                        id,
                        buildJsonObject {
                            put("protocolVersion", McpClient.PROTOCOL_VERSION)
                            put(
                                "serverInfo",
                                buildJsonObject {
                                    put("name", "runtime-e2e")
                                    put("version", "1")
                                },
                            )
                        },
                    )

                    "notifications/initialized" -> null

                    "tools/list" -> rpc(
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

                    "tools/call" -> {
                        callCount++
                        lastArguments = request["params"]!!.jsonObject["arguments"]!!.jsonObject
                        rpc(
                            id,
                            buildJsonObject {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", "task-42")
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

        private fun rpc(id: String?, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requireNotNull(id))
            put("result", result)
        }
    }

    private suspend fun awaitRunStatus(runId: String, expected: String) {
        repeat(100) {
            if (database.runtimeRunDao().find(runId)?.status == expected) return
            delay(10)
        }
        val events = database.runtimeEventDao().listByRunId(runId).joinToString { "${it.eventType}:${it.payloadJson}" }
        assertEquals(events, expected, database.runtimeRunDao().find(runId)?.status)
    }

    private suspend fun approvalPlan(runId: String): JsonObject = Json.parseToJsonElement(
        requireNotNull(RoomRuntimeStore(database, "test").pendingToolPlan(runId, now)),
    ).jsonObject
}
