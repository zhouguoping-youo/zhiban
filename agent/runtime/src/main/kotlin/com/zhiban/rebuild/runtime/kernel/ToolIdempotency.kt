package com.zhiban.rebuild.runtime.kernel

import java.security.MessageDigest

object ToolIdempotency {
    fun key(runId: String, logicalStepId: String, toolName: String, toolSpecVersion: Int, canonicalInputDigest: String): String {
        val material = listOf(runId, logicalStepId, toolName, toolSpecVersion.toString(), canonicalInputDigest)
            .joinToString(separator = "\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
