package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.spi.RuntimeContextInputGateway

internal class RoomContextInputGateway(database: AgentDatabase, private val clock: () -> Long = System::currentTimeMillis) : RuntimeContextInputGateway {
    private val store = RoomRuntimeStore(database, "runtime-v2")
    override suspend fun read(runId: String): String? = store.readRunInput(runId, clock())
    override suspend fun consume(runId: String): Boolean = store.consumeRunInput(runId)
}
