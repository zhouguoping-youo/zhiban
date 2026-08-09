package com.zhiban.rebuild.ui.agent

/**
 * Keeps product copy separate from runtime-only task constraints.
 *
 * The complete value is sent to the agent. Conversation surfaces render only [displayText],
 * including when an older session is reopened from history.
 */
object AgentPromptEnvelope {
    private const val INTERNAL_CONTEXT_MARKER = "\n\n<!-- zhiban-internal-task-context -->\n"
    private val legacySuggestionTitle = Regex("建议[‘'“\\\"]([^’'”\\\"]+)[’'”\\\"]")

    fun wrap(displayText: String, internalContext: String): String = displayText.trim() + INTERNAL_CONTEXT_MARKER + internalContext.trim()

    fun displayText(value: String): String {
        val trimmed = value.substringBefore(INTERNAL_CONTEXT_MARKER).trim()
        return when {
            trimmed.startsWith("请使用个人 CRM 工具创建机会") -> "帮我新建一个个人 CRM 机会"

            isLegacyCrmSuggestion(trimmed) -> {
                legacySuggestionTitle.find(trimmed)?.groupValues?.get(1)?.let { "帮我分析：$it" }
                    ?: "帮我分析这条 CRM 建议"
            }

            else -> trimmed
        }
    }

    private fun isLegacyCrmSuggestion(value: String): Boolean = value.startsWith("这是演示数据中的机会") ||
        value.startsWith("这是针对一位联系人的建议") ||
        value.startsWith("请先调用 crm.opportunity.get")
}
