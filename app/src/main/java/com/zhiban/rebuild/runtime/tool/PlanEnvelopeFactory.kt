package com.zhiban.rebuild.runtime.tool

internal data class PlanEnvelope(
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
)

internal object PlanEnvelopeFactory {
    fun canonicalInputDigest(vararg inputs: String): String = sha256(inputs.joinToString("|") { "${it.toByteArray().size}:$it" })

    fun create(
        request: RuntimeToolCallRequest,
        context: RuntimeToolRouteContext,
        toolName: String,
        canonicalInputDigest: String,
        payloadPrefix: String = "plan",
    ): PlanEnvelope {
        val identity = listOf(
            context.runId,
            context.attemptId,
            request.providerCallId,
            toolName,
            context.revision.toString(),
            canonicalInputDigest,
        ).joinToString("|")
        val idempotencyKey = sha256(identity)
        return PlanEnvelope(
            logicalStepId = "step-${request.providerCallId}",
            proposalId = "proposal-${sha256("${context.runId}|${request.providerCallId}").take(24)}",
            payloadRef = "$payloadPrefix-${canonicalInputDigest.take(32)}",
            canonicalInputDigest = canonicalInputDigest,
            idempotencyKey = idempotencyKey,
        )
    }
}
