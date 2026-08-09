package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.governance.ActionDecision
import com.zhiban.rebuild.runtime.governance.ActionPolicy
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import kotlinx.coroutines.withTimeout

/** Provider-neutral request. The runtime never dispatches on provider-specific event classes. */
data class RuntimeToolCallRequest(val providerCallId: String, val name: String, val argumentsJson: String)

data class RuntimeToolRouteContext(
    val runId: String,
    val sessionId: String,
    val attemptId: String,
    val ownerId: String,
    val fencingEpoch: Long,
    val revision: Long,
    val nowEpochMs: Long,
)

data class RoutedToolResult(val canonicalName: String, val providerCallId: String, val safeResultJson: String)

/** One binding owns validation, approval persistence and execution for one canonical tool. */
internal interface RuntimeToolBinding {
    val spec: RuntimeToolSpec
    val aliases: Set<String> get() = emptySet()

    suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean
    suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult =
        throw ToolPolicyRejectedException("tool does not implement read-only execution")
    suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult =
        throw ToolPolicyRejectedException("tool does not implement approved execution")
}

internal interface ReversibleAutoWriteBinding : RuntimeToolBinding {
    suspend fun reversibleWriteReadiness(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): ReversibleWriteReadiness

    suspend fun executeReversibleAutoWrite(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult
}

internal sealed interface ToolDisposition {
    data object ReadOnly : ToolDisposition
    data object ReversibleAutoWrite : ToolDisposition
    data class ConfirmationRequired(val strong: Boolean, val reasonCode: String) : ToolDisposition
}

internal data class CapabilityPolicy(
    val actionPolicy: ActionPolicy = ActionPolicy(),
    val isEnabled: (String) -> Boolean = { true },
    val autoUndoTools: Set<String> = emptySet(),
    val autoPresentationTools: Set<String> = emptySet(),
)

/**
 * The sole authority for production tool discovery and dispatch.
 *
 * It enforces allowlisting, per-run call budgets, confirmation policy and a hard timeout before
 * delegating to a domain binding. ProviderExecutionEngine must not branch on tool names.
 */
internal class CapabilityRouter(
    bindings: List<RuntimeToolBinding>,
    private val proposalCount: suspend (runId: String, canonicalName: String) -> Int,
    private val totalCallCount: suspend (runId: String) -> Int = { 0 },
    private val timeoutMs: Long = 30_000L,
    private val policy: CapabilityPolicy = CapabilityPolicy(),
    private val dynamicBindings: () -> List<RuntimeToolBinding> = { emptyList() },
) {
    private val localBindings = bindings.toList()

    private data class Registry(
        val canonical: Map<String, RuntimeToolBinding>,
        val byName: Map<String, RuntimeToolBinding>,
        val providerNames: Map<String, String>,
    )

    private fun registry(): Registry {
        val all = localBindings + dynamicBindings()
        val canonical = buildMap {
            all.forEach { binding ->
                check(put(binding.spec.name, binding) == null) { "duplicate tool: ${binding.spec.name}" }
            }
        }
        val usedProviderNames = mutableSetOf<String>()
        val providerNames = canonical.keys.sorted().associateWith { canonicalName ->
            val sanitized = canonicalName
                .map { character ->
                    if (character.isLetterOrDigit() || character == '_' ||
                        character == '-'
                    ) {
                        character
                    } else {
                        '_'
                    }
                }
                .joinToString("")
                .let {
                    if (it.firstOrNull()?.let { first -> first.isLetter() || first == '_' } ==
                        true
                    ) {
                        it
                    } else {
                        "tool_$it"
                    }
                }
            val base = sanitized.take(MAX_PROVIDER_TOOL_NAME_LENGTH)
            if (usedProviderNames.add(base)) {
                base
            } else {
                val suffixed = "${base.take(MAX_PROVIDER_TOOL_NAME_LENGTH - 9)}_${sha256(canonicalName).take(8)}"
                check(usedProviderNames.add(suffixed)) { "provider tool name collision: $canonicalName" }
                suffixed
            }
        }
        val byName = buildMap {
            all.forEach { binding ->
                check(put(binding.spec.name, binding) == null) { "duplicate tool: ${binding.spec.name}" }
                binding.aliases.forEach { alias ->
                    check(put(alias, binding) == null) { "duplicate tool alias: $alias" }
                }
                val providerName = providerNames.getValue(binding.spec.name)
                if (providerName != binding.spec.name) {
                    check(put(providerName, binding) == null) { "duplicate provider tool alias: $providerName" }
                }
            }
        }
        return Registry(canonical, byName, providerNames)
    }

    fun providerToolsJson(allowedNames: Set<String>? = null): String {
        val registry = registry()
        val current = registry.canonical
        return RuntimeToolCatalog(
            current
                .filterKeys { policy.isEnabled(it) && (allowedNames == null || it in allowedNames) }
                .mapValues { it.value.spec },
        ).providerToolsJson(registry.providerNames)
    }

    fun canonicalNames(): Set<String> = registry().canonical.keys

    fun canonicalName(name: String): String = binding(name).spec.name
    fun providerName(canonicalName: String): String = registry().providerNames[canonicalName] ?: throw ToolPolicyRejectedException("tool is not registered")
    fun risk(name: String): RuntimeToolRisk = binding(name).spec.risk

    suspend fun disposition(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): ToolDisposition {
        val binding = binding(request.name)
        val spec = binding.spec
        requireEnabled(spec.name)
        val canonicalRequest = request.copy(name = spec.name)
        val readiness = reversibleReadiness(binding, canonicalRequest, context)
        return when (val decision = policy.actionPolicy.evaluate(spec, reversibleWriteReadiness = readiness)) {
            ActionDecision.AutoExecute -> ToolDisposition.ReadOnly

            ActionDecision.AutoExecuteReversibleWrite -> ToolDisposition.ReversibleAutoWrite

            is ActionDecision.RequireConfirmation -> ToolDisposition.ConfirmationRequired(
                decision.strong,
                decision.reason,
            )

            is ActionDecision.AllowedAfterConfirmation,
            is ActionDecision.Blocked,
            -> throw ToolPolicyRejectedException("tool disposition is not executable")
        }
    }

    suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val binding = binding(request.name)
        val spec = binding.spec
        requireEnabled(spec.name)
        if (policy.actionPolicy.evaluate(spec) !is ActionDecision.RequireConfirmation) {
            throw ToolPolicyRejectedException("read-only tools must use the automatic execution path")
        }
        requireGlobalBudget(context.runId)
        if (proposalCount(context.runId, spec.name) >= spec.maxCallsPerRun) {
            throw ToolPolicyRejectedException("tool call budget exceeded")
        }
        return withTimeout(timeoutMs) { binding.requestApproval(request.copy(name = spec.name), context) }
    }

    suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val binding = binding(request.name)
        val spec = binding.spec
        requireEnabled(spec.name)
        if (policy.actionPolicy.evaluate(spec) != ActionDecision.AutoExecute) {
            throw ToolPolicyRejectedException("write tools must use the approval path")
        }
        requireGlobalBudget(context.runId)
        if (proposalCount(context.runId, spec.name) >= spec.maxCallsPerRun) {
            throw ToolPolicyRejectedException("tool call budget exceeded")
        }
        return withTimeout(timeoutMs) { binding.executeReadOnly(request.copy(name = spec.name), context) }
    }

    suspend fun executeReversibleAutoWrite(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val binding = binding(request.name)
        val spec = binding.spec
        requireEnabled(spec.name)
        val canonicalRequest = request.copy(name = spec.name)
        val readiness = reversibleReadiness(binding, canonicalRequest, context)
        if (policy.actionPolicy.evaluate(spec, reversibleWriteReadiness = readiness) !=
            ActionDecision.AutoExecuteReversibleWrite
        ) {
            throw ToolPolicyRejectedException("reversible write must use the confirmation path")
        }
        requireGlobalBudget(context.runId)
        if (proposalCount(context.runId, spec.name) >= spec.maxCallsPerRun) {
            throw ToolPolicyRejectedException("tool call budget exceeded")
        }
        val reversible = binding as? ReversibleAutoWriteBinding
            ?: throw ToolPolicyRejectedException("auto write binding is not reversible")
        return withTimeout(timeoutMs) { reversible.executeReversibleAutoWrite(canonicalRequest, context) }
    }

    suspend fun executeApproved(canonicalName: String, planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val binding = registry().canonical[canonicalName] ?: throw ToolPolicyRejectedException("tool is not registered")
        requireEnabled(binding.spec.name)
        check(
            policy.actionPolicy.evaluate(binding.spec, confirmationGranted = true) is ActionDecision.AllowedAfterConfirmation,
        ) {
            "tool is not authorized for confirmed execution"
        }
        return withTimeout(timeoutMs) { binding.executeApproved(planJson, context) }
    }

    private fun binding(name: String): RuntimeToolBinding = registry().byName[name] ?: throw ToolPolicyRejectedException("tool is not registered")

    private fun requireEnabled(name: String) {
        if (!policy.isEnabled(name)) throw ToolPolicyRejectedException("tool is disabled by user")
    }

    private suspend fun reversibleReadiness(
        binding: RuntimeToolBinding,
        request: RuntimeToolCallRequest,
        context: RuntimeToolRouteContext,
    ): ReversibleWriteReadiness {
        if (binding.spec.risk != RuntimeToolRisk.REVERSIBLE_AUTO_WRITE) return ReversibleWriteReadiness.Unavailable
        val reversible = binding as? ReversibleAutoWriteBinding ?: return ReversibleWriteReadiness.Unavailable
        val declared = reversible.reversibleWriteReadiness(request, context)
        return declared.copy(
            inverseSupported = declared.inverseSupported && binding.spec.name in policy.autoUndoTools,
            visibleUndoSupported = declared.visibleUndoSupported && binding.spec.name in policy.autoPresentationTools,
        )
    }

    private suspend fun requireGlobalBudget(runId: String) {
        if (totalCallCount(runId) >= MAX_TOOL_CALLS_PER_RUN) {
            throw ToolPolicyRejectedException("global tool loop budget exceeded")
        }
    }

    private companion object {
        const val MAX_TOOL_CALLS_PER_RUN = 12
        const val MAX_PROVIDER_TOOL_NAME_LENGTH = 64
    }
}
