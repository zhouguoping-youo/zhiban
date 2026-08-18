package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.facts.FactIndex

/** Stable infrastructure seam for the cross-domain repository facade. */
internal data class AgentRepositoryInfrastructure(
    val daos: AgentDataDaos,
    val transactions: AgentTransactionRunner,
    val factIndex: FactIndex,
    val autoWriteSink: AutoWriteSink,
)

/** Domain collaborators delegated to by [AgentDataRepository]. */
internal data class AgentRepositoryDomains(
    val calendar: CalendarAgentDataRepository,
    val crm: CrmAgentDataRepository,
    val contacts: ContactAgentDataRepository,
    val relationships: RelationshipAgentDataRepository,
)
