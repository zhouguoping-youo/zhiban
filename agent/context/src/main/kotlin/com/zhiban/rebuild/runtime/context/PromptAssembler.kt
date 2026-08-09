package com.zhiban.rebuild.runtime.context

class PromptAssembler {
    fun assemble(blocks: List<ContextBlock>, budget: PromptBudget): PromptAssembly {
        require(blocks.map { it.id }.distinct().size == blocks.size) { "duplicate context block id" }
        blocks.filter { it.trust == TrustLevel.SYSTEM }.forEach {
            require(it.layer == ContextLayer.STABLE && it.provenance.sourceType in TRUSTED_SYSTEM_SOURCE_TYPES) {
                "SYSTEM role requires a stable trusted system source"
            }
        }
        val ordered = blocks.withIndex().sortedWith(
            compareBy<IndexedValue<ContextBlock>>({
                it.value.layer.ordinal
            }, { it.index }),
        ).map { it.value }
        val groups = ordered.groupBy { it.atomicGroupId ?: "block:${it.id}" }
        val stableCost = ordered.filter { it.layer == ContextLayer.STABLE }.sumOf { it.tokenCost }
        require(stableCost <= budget.availableInputTokens) { "stable context exceeds prompt budget" }
        val included = mutableListOf<ContextBlock>()
        val omitted = mutableListOf<String>()
        var used = 0
        groups.values.forEach { group ->
            validateAtomicGroup(group)
            val cost = group.sumOf { it.tokenCost }
            if (used + cost <= budget.availableInputTokens) {
                included += group
                used += cost
            } else {
                omitted += group.map { it.id }
            }
        }
        return PromptAssembly(included, omitted, used, included.map { message(it) })
    }

    private fun validateAtomicGroup(group: List<ContextBlock>) {
        if (group.any { it.kind == ContextKind.TOOL_CALL || it.kind == ContextKind.TOOL_RESULT }) {
            require(
                group.count { it.kind == ContextKind.TOOL_CALL } == 1 &&
                    group.count { it.kind == ContextKind.TOOL_RESULT } == 1,
            ) {
                "ToolCall and ToolResult must be paired"
            }
        }
    }

    private fun message(block: ContextBlock) = PromptMessage(
        role = if (block.trust == TrustLevel.SYSTEM) PromptMessageRole.SYSTEM else PromptMessageRole.DATA,
        content = block.content,
        trust = block.trust,
        sensitivity = block.sensitivity,
        provenance = block.provenance,
    )

    private companion object {
        val TRUSTED_SYSTEM_SOURCE_TYPES = setOf("system_policy", "agent_personalization", "agent_skill")
    }
}
