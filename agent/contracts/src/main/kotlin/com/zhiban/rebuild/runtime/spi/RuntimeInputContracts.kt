package com.zhiban.rebuild.runtime.spi

data class StagedTextInput(val inputRef: String, val utf8Length: Int, val sha256Digest: String, val expiresAtEpochMs: Long)

interface TextInputGateway {
    suspend fun stage(rawText: String): StagedTextInput
    suspend fun discard(inputRef: String)
}

/** Restricted hand-off used only by Context assembly; callers must consume after durable context capture. */
interface RuntimeContextInputGateway {
    suspend fun read(runId: String): String?
    suspend fun consume(runId: String): Boolean
}

fun interface RuntimeV2FeatureFlag {
    fun isEnabled(): Boolean
}

class RuntimeV2DisabledException : IllegalStateException("runtime v2 is disabled")
