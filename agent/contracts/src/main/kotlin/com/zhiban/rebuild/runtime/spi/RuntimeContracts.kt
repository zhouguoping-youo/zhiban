package com.zhiban.rebuild.runtime.spi

const val RUNTIME_SCHEMA_VERSION = 1

enum class RuntimeRunStatus {
    RECEIVED,
    ASSEMBLING_CONTEXT,
    INFERENCING,
    VALIDATING_PLAN,
    AWAITING_CONFIRMATION,
    EXECUTING,
    OBSERVING,
    SUCCEEDED,
    CANCEL_REQUESTED,
    CANCELLED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
}

enum class RuntimeCommandStatus { PENDING, CLAIMED, COMPLETED, FAILED }
enum class RuntimeAttemptStatus { ACTIVE, SUCCEEDED, FAILED, CANCELLED, SUPERSEDED }
enum class RuntimeToolExecutionStatus { PREPARED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
