package com.zhiban.rebuild.runtime.store

/**
 * WeChat iLink send approval delegate, kept as an extension (like `RoomRuntimeExternalToolStore`) so
 * the `RoomRuntimeStore` class body stays under the 1000-effective-line cap. Stages the
 * `communication.wechat.send` confirmation plan via the shared approval pipeline.
 */
internal suspend fun RoomRuntimeStore.requestWechatSendApproval(
    payloadJson: String,
    providerCallId: String,
    sessionId: String,
    runId: String,
    attemptId: String,
    ownerId: String,
    fencingEpoch: Long,
    nowEpochMs: Long,
): Boolean = approvals.requestWechatSendApproval(
    payloadJson,
    providerCallId,
    sessionId,
    runId,
    attemptId,
    ownerId,
    fencingEpoch,
    nowEpochMs,
)
