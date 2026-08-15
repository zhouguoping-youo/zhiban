package com.zhiban.rebuild.runtime.store

internal suspend fun RoomRuntimeStore.containClaimedCommandFailure(commandId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): Boolean =
    commands.containClaimedCommandFailure(commandId, ownerId, fencingEpoch, nowEpochMs)
