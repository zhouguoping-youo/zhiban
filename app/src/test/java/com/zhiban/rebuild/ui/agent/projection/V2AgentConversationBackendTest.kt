package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.RuntimeUiEvent
import com.zhiban.rebuild.runtime.spi.SessionProjection
import com.zhiban.rebuild.runtime.spi.StagedTextInput
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import com.zhiban.rebuild.ui.agent.AgentConversationStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V2AgentConversationBackendTest {
    @Test
    fun `staging timeout re-enables input and allows a later send`() = runTest {
        val runtime = BackendFakeRuntimeUiClient(SessionProjection("s1"))
        val input = HangingOnceTextInputGateway()
        val backend = V2AgentConversationBackend(runtime, input, "s1", this) { "id" }
        backend.initialize()
        runCurrent()

        backend.plan("第一次")
        advanceTimeBy(15_001)
        runCurrent()

        assertTrue(backend.uiState.value.isInputEnabled)
        assertEquals("提交超时，请检查网络后重试。", backend.uiState.value.safeMessage)
        backend.plan("第二次")
        runCurrent()
        assertEquals(1, runtime.commands.size)
        backend.close()
    }

    @Test
    fun `plan stages text once then sends only input reference`() = runTest {
        val input = FakeTextInputGateway()
        val runtime = BackendFakeRuntimeUiClient(SessionProjection("s1"))
        val backend = V2AgentConversationBackend(runtime, input, "s1", this) { "id-${runtime.commands.size}" }
        backend.initialize()
        runCurrent()

        backend.plan("  明天开会  ")
        runCurrent()

        val envelope = Json.parseToJsonElement(input.rawTexts.single()).jsonObject
        assertEquals("明天开会", envelope.getValue("text").jsonPrimitive.content)
        assertEquals("Work", envelope.getValue("mode").jsonPrimitive.content)
        val command = runtime.commands.single() as RuntimeUiCommand.Start
        assertEquals("input-ref-1", command.inputRef)
        assertTrue(command.inputRef != "明天开会")
        backend.close()
    }

    @Test
    fun `runtime status updates do not erase current user message`() = runTest {
        val runtime = BackendFakeRuntimeUiClient(SessionProjection("s1"))
        val backend = V2AgentConversationBackend(runtime, FakeTextInputGateway(), "s1", this) { "id" }
        backend.initialize()
        runCurrent()
        backend.plan("明天开会")
        runCurrent()

        runtime.events.emit(
            RuntimeUiEvent.RunStatusChanged(
                sessionId = "s1",
                runId = "r1",
                sequence = 1,
                revision = 1,
                status = com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.RECEIVED,
            ),
        )
        runCurrent()

        assertEquals("明天开会", backend.uiState.value.userMessage)
        backend.close()
    }

    @Test
    fun `unknown snapshot renders read only after cold start`() = runTest {
        val runtime = BackendFakeRuntimeUiClient(
            SessionProjection(sessionId = "s1", readOnly = true, allowedActions = emptySet()),
        )
        val backend = V2AgentConversationBackend(runtime, FakeTextInputGateway(), "s1", this) { "id" }

        backend.initialize()
        runCurrent()

        assertEquals(AgentConversationStage.FAILED_FINAL, backend.uiState.value.stage)
        assertTrue(!backend.uiState.value.isInputEnabled)
        backend.close()
    }

    @Test
    fun `double start stages once and rejected receipt discards staged input`() = runTest {
        val input = FakeTextInputGateway()
        val runtime = BackendFakeRuntimeUiClient(SessionProjection("s1")).apply {
            receiptStatus = CommandReceiptStatus.REJECTED
        }
        val backend = V2AgentConversationBackend(runtime, input, "s1", this) { "id" }
        backend.initialize()
        runCurrent()

        backend.plan("同一条")
        backend.plan("同一条")
        runCurrent()

        assertEquals(1, input.rawTexts.size)
        assertEquals(
            "同一条",
            Json.parseToJsonElement(input.rawTexts.single()).jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals(listOf("input-ref-1"), input.discardedRefs)
        assertEquals(AgentConversationStage.EMPTY, backend.uiState.value.stage)
        assertEquals("当前操作未被允许。", backend.uiState.value.safeMessage)
        backend.close()
    }

    @Test
    fun `conflict receipt refreshes projection for every action`() = runTest {
        val runtime = BackendFakeRuntimeUiClient(
            SessionProjection(
                "s1",
                runId = "r1",
                revision = 1,
                runStatus = com.zhiban.rebuild.runtime.spi.RuntimeRunStatus.EXECUTING,
                allowedActions = setOf(com.zhiban.rebuild.runtime.spi.RuntimeAction.CANCEL),
            ),
        ).apply { receiptStatus = CommandReceiptStatus.CONFLICT }
        val backend = V2AgentConversationBackend(runtime, FakeTextInputGateway(), "s1", this) { "id" }
        backend.initialize()
        runCurrent()

        backend.cancel()
        runCurrent()

        assertEquals(2, runtime.snapshotReads)
        assertEquals(AgentConversationStage.EXECUTING, backend.uiState.value.stage)
        assertEquals("会话状态已更新，请重试。", backend.uiState.value.safeMessage)
        backend.close()
    }
}

private class FakeTextInputGateway : TextInputGateway {
    val rawTexts = mutableListOf<String>()
    val discardedRefs = mutableListOf<String>()
    override suspend fun stage(rawText: String): StagedTextInput {
        rawTexts += rawText
        return StagedTextInput("input-ref-${rawTexts.size}", rawText.length, "digest", Long.MAX_VALUE)
    }
    override suspend fun discard(inputRef: String) {
        discardedRefs += inputRef
    }
}

private class HangingOnceTextInputGateway : TextInputGateway {
    private var first = true

    override suspend fun stage(rawText: String): StagedTextInput {
        if (first) {
            first = false
            awaitCancellation()
        }
        return StagedTextInput("input-ref-2", rawText.length, "digest", Long.MAX_VALUE)
    }

    override suspend fun discard(inputRef: String) = Unit
}

private class BackendFakeRuntimeUiClient(initial: SessionProjection) : RuntimeUiClient {
    val commands = mutableListOf<RuntimeUiCommand>()
    val events = MutableSharedFlow<RuntimeUiEvent>(extraBufferCapacity = 4)
    private val snapshot = initial
    var receiptStatus = CommandReceiptStatus.ACCEPTED
    var snapshotReads = 0
    override suspend fun dispatch(command: RuntimeUiCommand): CommandReceipt {
        commands += command
        return CommandReceipt(receiptStatus, command.commandId, snapshot.revision + 1)
    }
    override suspend fun getSessionProjection(sessionId: String) = snapshot.also { snapshotReads++ }
    override fun observeSession(sessionId: String, afterSequenceExclusive: Long): Flow<RuntimeUiEvent> = events
}
