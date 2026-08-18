package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.foundation.MemoryCandidateState
import com.zhiban.rebuild.foundation.MemoryScope
import com.zhiban.rebuild.foundation.Sensitivity

data class StagedMemoryInput(
    val id: String,
    val scope: MemoryScope,
    val scopeId: String?,
    val content: String,
    val sourceIds: List<String>,
    val sensitivity: Sensitivity,
)

class MemoryCandidate private constructor(
    val id: String,
    val scope: MemoryScope,
    val scopeId: String?,
    private val content: String?,
    val sourceIds: List<String>,
    val sensitivity: Sensitivity,
    val state: MemoryCandidateState,
    val approvalRef: String?,
) {
    companion object {
        fun stage(input: StagedMemoryInput): MemoryCandidate {
            require(input.id.isNotBlank() && input.content.isNotBlank() && input.sourceIds.isNotEmpty())
            require((input.scope == MemoryScope.GLOBAL) == (input.scopeId == null)) { "scope identity mismatch" }
            return MemoryCandidate(
                input.id,
                input.scope,
                input.scopeId,
                input.content,
                input.sourceIds.distinct(),
                input.sensitivity,
                MemoryCandidateState.PENDING,
                null,
            )
        }
    }

    fun approve(approvalRef: String): MemoryCandidate {
        check(state == MemoryCandidateState.PENDING && approvalRef.isNotBlank())
        return changed(state = MemoryCandidateState.APPROVED, approvalRef = approvalRef)
    }

    fun reject(): MemoryCandidate {
        check(state == MemoryCandidateState.PENDING)
        return changed(content = null, state = MemoryCandidateState.REJECTED)
    }

    fun delete(): MemoryCandidate {
        check(state == MemoryCandidateState.APPROVED)
        return changed(content = null, state = MemoryCandidateState.DELETED)
    }

    private fun changed(content: String? = this.content, state: MemoryCandidateState = this.state, approvalRef: String? = this.approvalRef) =
        MemoryCandidate(id, scope, scopeId, content, sourceIds, sensitivity, state, approvalRef)

    fun readFor(requestedScope: MemoryScope, requestedScopeId: String?): String {
        check(state == MemoryCandidateState.APPROVED) { "memory is not approved" }
        check(scope == requestedScope && scopeId == requestedScopeId) { "memory scope mismatch" }
        return requireNotNull(content)
    }
}
