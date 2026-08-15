package com.zhiban.rebuild.runtime.store

internal suspend fun RoomRuntimeStore.reserveApprovedExternalTool(request: ApprovedExternalToolReservationRequest): ApprovedExternalToolReservation =
    tools.reserveApprovedExternalTool(request)

internal suspend fun RoomRuntimeStore.abandonApprovedExternalToolReservation(executionId: String, fencingEpoch: Long) {
    tools.abandonApprovedExternalToolReservation(executionId, fencingEpoch)
}
