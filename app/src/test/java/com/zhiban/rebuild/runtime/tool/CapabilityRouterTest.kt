package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRouterTest {
    private val spec = RuntimeToolSpec(
        name = "calendar.schedule.create",
        version = 1,
        risk = RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED,
        providerDefinitionJson = """{"type":"function","function":{"name":"calendar.schedule.create","parameters":{"type":"object"}}}""",
        maxCallsPerRun = 2,
    )

    @Test fun aliasRoutesThroughCanonicalBindingAndConfirmation() = runTest {
        val binding = FakeBinding(spec, aliases = setOf("calendar.create"))
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 })

        val accepted = router.requestApproval(
            RuntimeToolCallRequest("call-1", "calendar.create", "{}"),
            routeContext(),
        )

        assertTrue(accepted)
        assertEquals("calendar.schedule.create", binding.lastRequest?.name)
        assertEquals("calendar.schedule.create", router.canonicalName("calendar.create"))
    }

    @Test fun unknownToolFailsClosedBeforeBindingExecution() = runTest {
        val binding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 })

        val failure = runCatching {
            router.requestApproval(RuntimeToolCallRequest("call", "shell.exec", "{}"), routeContext())
        }.exceptionOrNull()

        assertTrue(failure is ToolPolicyRejectedException)
        assertFalse(binding.approvalCalled)
    }

    @Test fun perRunBudgetIsEnforcedCentrally() = runTest {
        val binding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, name ->
            assertEquals(spec.name, name)
            2
        })

        val failure = runCatching {
            router.requestApproval(RuntimeToolCallRequest("call", spec.name, "{}"), routeContext())
        }.exceptionOrNull()

        assertTrue(failure is ToolPolicyRejectedException)
        assertFalse(binding.approvalCalled)
    }

    @Test fun globalLoopBudgetStopsCrossToolCallChains() = runTest {
        val read = spec.copy(name = "calendar.search", risk = RuntimeToolRisk.READ_ONLY)
        val binding = FakeBinding(read)
        val router = CapabilityRouter(
            listOf(binding),
            proposalCount = { _, _ -> 0 },
            totalCallCount = { 12 },
        )

        val failure = runCatching {
            router.executeReadOnly(RuntimeToolCallRequest("call", read.name, "{}"), routeContext())
        }.exceptionOrNull()

        assertTrue(failure is ToolPolicyRejectedException)
        assertFalse(binding.readCalled)
    }

    @Test fun readOnlyToolCannotEnterApprovalPath() = runTest {
        val read = spec.copy(name = "calendar.search", risk = RuntimeToolRisk.READ_ONLY)
        val binding = FakeBinding(read)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 })

        val failure = runCatching {
            router.requestApproval(RuntimeToolCallRequest("call", read.name, "{}"), routeContext())
        }.exceptionOrNull()

        assertTrue(failure is ToolPolicyRejectedException)
        assertFalse(binding.approvalCalled)
    }

    @Test fun readOnlyToolExecutesWithoutApprovalAndWriteToolCannotUseThatPath() = runTest {
        val read = spec.copy(name = "contact.search", risk = RuntimeToolRisk.READ_ONLY)
        val readBinding = FakeBinding(read)
        val writeBinding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(readBinding, writeBinding), proposalCount = { _, _ -> 0 })

        val result = router.executeReadOnly(RuntimeToolCallRequest("read-1", read.name, "{}"), routeContext())
        val writeFailure = runCatching {
            router.executeReadOnly(RuntimeToolCallRequest("write-1", spec.name, "{}"), routeContext())
        }.exceptionOrNull()

        assertEquals(read.name, result.canonicalName)
        assertFalse(readBinding.approvalCalled)
        assertTrue(readBinding.readCalled)
        assertTrue(writeFailure is ToolPolicyRejectedException)
    }

    @Test fun reversibleWriteUsesDedicatedAutomaticPathOnlyWhenAllRegistriesAgree() = runTest {
        val auto = spec.copy(name = "contact.tag.add", risk = RuntimeToolRisk.REVERSIBLE_AUTO_WRITE)
        val binding = FakeAutoBinding(auto, ReversibleWriteReadiness(true, true, true))
        val router = CapabilityRouter(
            listOf(binding),
            proposalCount = { _, _ -> 0 },
            policy = CapabilityPolicy(
                autoUndoTools = setOf(auto.name),
                autoPresentationTools = setOf(auto.name),
            ),
        )

        val request = RuntimeToolCallRequest("auto-1", auto.name, "{}")
        assertEquals(ToolDisposition.ReversibleAutoWrite, router.disposition(request, routeContext()))
        assertEquals(auto.name, router.executeReversibleAutoWrite(request, routeContext()).canonicalName)
        assertTrue(binding.autoWriteCalled)
        assertFalse(binding.approvalCalled)
    }

    @Test fun missingAutoWriteGuaranteeDowngradesToConfirmation() = runTest {
        val auto = spec.copy(name = "contact.tag.add", risk = RuntimeToolRisk.REVERSIBLE_AUTO_WRITE)
        val binding = FakeAutoBinding(auto, ReversibleWriteReadiness(true, true, true))
        val router = CapabilityRouter(
            listOf(binding),
            proposalCount = { _, _ -> 0 },
            policy = CapabilityPolicy(
                autoUndoTools = setOf(auto.name),
                autoPresentationTools = emptySet(),
            ),
        )

        val disposition = router.disposition(RuntimeToolCallRequest("auto-1", auto.name, "{}"), routeContext())
        assertEquals(
            ToolDisposition.ConfirmationRequired(false, "auto_write:undo_surface_unavailable"),
            disposition,
        )
    }

    @Test fun approvedExecutionReturnsOnlySafeBindingResult() = runTest {
        val binding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 })

        val result = router.executeApproved(
            spec.name,
            "{\"toolName\":\"${spec.name}\"}",
            ConfirmedToolExecutionContext("run", "owner", 4, 100),
        )

        assertEquals(spec.name, result.canonicalName)
        assertEquals("call-safe", result.providerCallId)
        assertEquals("{\"status\":\"ok\"}", result.safeResultJson)
    }

    @Test
    fun oversizedToolResultsAreRejectedAtEveryExecutionExit() = runTest {
        val hugeResult = "\"" + "文".repeat(400_000) + "\""
        val readSpec = spec.copy(name = "contact.search", risk = RuntimeToolRisk.READ_ONLY)
        val autoSpec = spec.copy(name = "contact.tag.add", risk = RuntimeToolRisk.REVERSIBLE_AUTO_WRITE)
        val router = CapabilityRouter(
            listOf(
                FakeBinding(spec, safeResultJson = hugeResult),
                FakeBinding(readSpec, safeResultJson = hugeResult),
                FakeAutoBinding(autoSpec, ReversibleWriteReadiness(true, true, true), hugeResult),
            ),
            proposalCount = { _, _ -> 0 },
            policy = CapabilityPolicy(
                autoUndoTools = setOf(autoSpec.name),
                autoPresentationTools = setOf(autoSpec.name),
            ),
        )

        val failures = listOf(
            runCatching {
                router.executeReadOnly(RuntimeToolCallRequest("read", readSpec.name, "{}"), routeContext())
            }.exceptionOrNull(),
            runCatching {
                router.executeReversibleAutoWrite(RuntimeToolCallRequest("auto", autoSpec.name, "{}"), routeContext())
            }.exceptionOrNull(),
            runCatching {
                router.executeApproved(spec.name, "{}", ConfirmedToolExecutionContext("run", "owner", 4, 100))
            }.exceptionOrNull(),
        )

        failures.forEach { failure ->
            assertTrue(failure is ToolPolicyRejectedException)
            assertEquals("TOOL_RESULT_TOO_LARGE", failure?.message)
        }
    }

    @Test fun bindingExecutionHasHardTimeout() = runTest {
        val binding = FakeBinding(spec, delayMs = 50)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 }, timeoutMs = 1)

        val failure = runCatching {
            router.requestApproval(RuntimeToolCallRequest("call", spec.name, "{}"), routeContext())
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
    }

    @Test fun providerDefinitionsComeFromSameRegisteredBindings() {
        val binding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 })

        val json = router.providerToolsJson()

        assertFalse(json.contains(spec.name))
        assertTrue(json.contains("calendar_schedule_create"))
        assertFalse(json.contains("shell.exec"))
    }

    @Test fun canonicalNamesExposeCurrentBindingsForObservationLoopControl() {
        val read = spec.copy(name = "crm.opportunity.get", risk = RuntimeToolRisk.READ_ONLY)
        val router = CapabilityRouter(
            listOf(FakeBinding(spec), FakeBinding(read)),
            proposalCount = { _, _ -> 0 },
        )

        assertEquals(setOf(spec.name, read.name), router.canonicalNames())
    }

    @Test fun providerSafeNameRoutesBackToCanonicalBinding() = runTest {
        val binding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 })

        assertTrue(
            router.requestApproval(
                RuntimeToolCallRequest("safe-provider-call", "calendar_schedule_create", "{}"),
                routeContext(),
            ),
        )
        assertEquals(spec.name, binding.lastRequest?.name)
        assertEquals(spec.name, router.canonicalName("calendar_schedule_create"))
    }

    @Test fun userDisabledToolIsHiddenAndRejectedAtEveryEntryPoint() = runTest {
        val binding = FakeBinding(spec)
        val router = CapabilityRouter(listOf(binding), proposalCount = { _, _ -> 0 }, policy = CapabilityPolicy(isEnabled = { false }))

        assertFalse(router.providerToolsJson().contains(spec.name))
        assertTrue(
            runCatching {
                router.requestApproval(RuntimeToolCallRequest("call", spec.name, "{}"), routeContext())
            }.exceptionOrNull() is ToolPolicyRejectedException,
        )
        assertTrue(
            runCatching {
                router.executeApproved(spec.name, "{}", ConfirmedToolExecutionContext("run", "owner", 4, 100))
            }.exceptionOrNull() is ToolPolicyRejectedException,
        )
        assertFalse(binding.approvalCalled)
    }

    @Test fun remotelyDiscoveredBindingsAppearWithoutRecreatingRouterAndUseSamePolicyPath() = runTest {
        var remote = emptyList<RuntimeToolBinding>()
        val remoteSpec = spec.copy(
            name = "mcp.team.search",
            providerDefinitionJson = """{"type":"function","function":{"name":"mcp.team.search","parameters":{"type":"object"}}}""",
        )
        val remoteBinding = FakeBinding(remoteSpec)
        val router = CapabilityRouter(
            listOf(FakeBinding(spec)),
            proposalCount = { _, _ -> 0 },
            dynamicBindings = { remote },
        )

        assertFalse(router.providerToolsJson().contains("mcp_team_search"))
        remote = listOf(remoteBinding)
        assertTrue(router.providerToolsJson().contains("mcp_team_search"))
        assertTrue(
            router.requestApproval(
                RuntimeToolCallRequest("remote-1", remoteSpec.name, "{}"),
                routeContext(),
            ),
        )
        assertTrue(remoteBinding.approvalCalled)
    }

    @Test fun remoteToolCannotShadowLocalTool() {
        val router = CapabilityRouter(
            listOf(FakeBinding(spec)),
            proposalCount = { _, _ -> 0 },
            dynamicBindings = { listOf(FakeBinding(spec)) },
        )

        val failure = runCatching { router.providerToolsJson() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    private fun routeContext() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 4, 8, 100)

    private class FakeBinding(
        override val spec: RuntimeToolSpec,
        override val aliases: Set<String> = emptySet(),
        private val delayMs: Long = 0,
        private val safeResultJson: String = "{\"status\":\"ok\"}",
    ) :
        RuntimeToolBinding {
        var approvalCalled = false
        var readCalled = false
        var lastRequest: RuntimeToolCallRequest? = null

        override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
            approvalCalled = true
            lastRequest = request
            if (delayMs > 0) delay(delayMs)
            return true
        }

        override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext) =
            RoutedToolResult(spec.name, "call-safe", safeResultJson)

        override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
            readCalled = true
            return RoutedToolResult(spec.name, request.providerCallId, safeResultJson)
        }
    }

    private class FakeAutoBinding(
        override val spec: RuntimeToolSpec,
        private val readiness: ReversibleWriteReadiness,
        private val safeResultJson: String = "{\"status\":\"ok\"}",
    ) : ReversibleAutoWriteBinding {
        var approvalCalled = false
        var autoWriteCalled = false

        override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
            approvalCalled = true
            return true
        }

        override suspend fun reversibleWriteReadiness(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) = readiness

        override suspend fun executeReversibleAutoWrite(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
            autoWriteCalled = true
            return RoutedToolResult(spec.name, request.providerCallId, safeResultJson)
        }
    }
}
