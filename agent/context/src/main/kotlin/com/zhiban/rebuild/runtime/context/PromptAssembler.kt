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
        groups.values.forEach(::validateAtomicGroup)
        val requiredGroups = groups.filterValues { group ->
            group.any { it.layer == ContextLayer.STABLE || it.isRequired }
        }
        val requiredCost = requiredGroups.values.flatten().sumOf { it.tokenCost }
        require(requiredCost <= budget.availableInputTokens) { "required context exceeds prompt budget" }

        val includedGroupIds = requiredGroups.keys.toMutableSet()
        var used = requiredCost
        groups.forEach { (groupId, group) ->
            if (groupId in includedGroupIds) return@forEach
            val cost = group.sumOf { it.tokenCost }
            if (used + cost <= budget.availableInputTokens) {
                includedGroupIds += groupId
                used += cost
            }
        }
        val included = ordered.filter { (it.atomicGroupId ?: "block:${it.id}") in includedGroupIds }
        val omitted = ordered.filterNot { (it.atomicGroupId ?: "block:${it.id}") in includedGroupIds }.map { it.id }
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
