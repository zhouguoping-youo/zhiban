package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeCommandInboxEntity

private const val SESSION_LEASE_MS = 30_000L

internal class KernelCommandProcessor(
    database: AgentDatabase,
    private val ownerId: String,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    provider: ProviderAdapter? = null,
    profiles: ProviderProfileStore? = null,
    config: ProviderEngineConfig = ProviderEngineConfig(),
    infrastructure: ProviderEngineInfrastructure = ProviderEngineInfrastructure(),
) {
    enum class Outcome { IDLE, PROCESSED, FAILED }
    private val store = RoomRuntimeStore(database, producerVersion = "runtime-v2")
    private val providerEngine = if (provider != null && profiles != null) {
        ProviderExecutionEngine(
            database = database,
            provider = provider,
            profiles = profiles,
            ownerId = ownerId,
            clock = clock,
            config = config,
            infrastructure = infrastructure,
        )
    } else {
        null
    }

    suspend fun processNext(): Outcome {
        val now = clock()
        if (!enabled()) {
            store.clearStagedInputs(now)
            return Outcome.IDLE
        }
        val command = store.nextProcessableCommand(now, ownerId)
            ?: return if (providerEngine?.recoverNext() == true) Outcome.PROCESSED else Outcome.IDLE
        val lease = store.claimSession(command.sessionId, ownerId, now, SESSION_LEASE_MS)
        if (!store.claimCommand(command.commandId, ownerId, lease.leaseEpoch, now)) return Outcome.IDLE
        if (!store.processClaimedCommand(command.commandId, ownerId, lease.leaseEpoch, clock())) return Outcome.FAILED
        return if (dispatchRuntimeWork(command, lease.leaseEpoch)) Outcome.PROCESSED else Outcome.FAILED
    }

    private suspend fun dispatchRuntimeWork(command: RuntimeCommandInboxEntity, fencingEpoch: Long): Boolean {
        val runId = command.runId ?: return true
        val engine = providerEngine ?: return true
        return when (command.commandType) {
            "Start", "Retry" -> engine.launchAfterCurrent(runId, command.sessionId, fencingEpoch)
            "Approve" -> engine.launchApprovedToolAfterCurrent(runId, command.sessionId, fencingEpoch)
            "Cancel" -> engine.cancel(runId, command.sessionId, fencingEpoch)
            "Resume" -> engine.resumeAfterCurrent(runId, command.sessionId, fencingEpoch)
            else -> true
        }
    }

    fun observeWorkCount() = store.observeWorkCount()
    suspend fun millisUntilNextLeaseExpiry(): Long? {
        val now = clock()
        return listOfNotNull(
            store.nextForeignLeaseExpiry(ownerId, now),
            store.nextRecoverableLeaseExpiry(now),
        ).minOrNull()?.let { (it - now).coerceAtLeast(1) }
    }
}
