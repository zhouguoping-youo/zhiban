package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.event.EventPlanEntity
import com.zhiban.rebuild.data.event.EventPlanParticipantEntity
import com.zhiban.rebuild.data.event.EventPlanStatus

data class EventParticipantUi(val contact: ContactEntity, val responseStatus: String)

data class EventPlanUi(val plan: EventPlanEntity, val participants: List<EventParticipantUi>) {
    val pendingReplies: Int = participants.count { it.responseStatus == "PENDING" }
}

data class EventPlanningState(
    val plans: List<EventPlanUi> = emptyList(),
    val contacts: List<ContactEntity> = emptyList(),
    val isLoading: Boolean = true,
    val actionMessage: String? = null,
) {
    val activePlans: List<EventPlanUi>
        get() = plans.filter { it.plan.status in setOf(EventPlanStatus.DRAFT, EventPlanStatus.COORDINATING) }

    val upcomingPlans: List<EventPlanUi>
        get() = plans.filter { it.plan.status == EventPlanStatus.CONFIRMED }
}

internal fun buildEventPlanUi(plans: List<EventPlanEntity>, participants: List<EventPlanParticipantEntity>, contacts: List<ContactEntity>): List<EventPlanUi> {
    val contactsById = contacts.associateBy(ContactEntity::contactId)
    val participantsByPlan = participants.groupBy(EventPlanParticipantEntity::planId)
    return plans.map { plan ->
        EventPlanUi(
            plan = plan,
            participants = participantsByPlan[plan.planId].orEmpty().mapNotNull { participant ->
                contactsById[participant.contactId]?.let { contact ->
                    EventParticipantUi(contact, participant.responseStatus)
                }
            },
        )
    }
}
