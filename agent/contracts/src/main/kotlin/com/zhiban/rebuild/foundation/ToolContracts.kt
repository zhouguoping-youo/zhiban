package com.zhiban.rebuild.foundation

/** Tool risk tiers used by the policy authority and the tool catalog. */
enum class RuntimeToolRisk { READ_ONLY, REVERSIBLE_AUTO_WRITE, WRITE_CONFIRMATION_REQUIRED, HIGH_RISK }

/** Pure tool contract shared by the catalog, policy and bindings. */
data class RuntimeToolSpec(
    val name: String,
    val version: Int,
    val risk: RuntimeToolRisk,
    val providerDefinitionJson: String,
    val maxCallsPerRun: Int,
    // True when the tool's result content originates outside the user's own data (web search,
    // remote MCP). The auto-write provenance gate requires confirmation when a run that consumed
    // external content attempts a silent reversible write.
    val returnsExternalContent: Boolean = false,
    // True when the Agent may call this tool autonomously during a proactive run without the user
    // explicitly requesting it in the current turn. Only READ_ONLY tools should set this to true.
    // Write tools must always remain false — writes require explicit user confirmation.
    val autonomousSafe: Boolean = false,
)
