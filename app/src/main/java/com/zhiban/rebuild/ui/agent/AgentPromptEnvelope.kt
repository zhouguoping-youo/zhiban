package com.zhiban.rebuild.ui.agent

/**
 * Keeps product copy separate from runtime-only task constraints.
 *
 * The complete value is sent to the agent. Conversation surfaces render only [displayText],
 * including when an older session is reopened from history.
 */
object AgentPromptEnvelope {
    private const val INTERNAL_CONTEXT_MARKER = "\n\n<!-- zhiban-internal-task-context -->\n"

    fun wrap(displayText: String, internalContext: String): String = displayText.trim() + INTERNAL_CONTEXT_MARKER + internalContext.trim()

    fun displayText(value: String): String = value.substringBefore(INTERNAL_CONTEXT_MARKER).trim()
}
