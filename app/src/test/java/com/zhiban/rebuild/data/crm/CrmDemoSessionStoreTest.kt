package com.zhiban.rebuild.data.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmDemoSessionStoreTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `demo is opt in and exiting removes the entire memory dataset`() {
        val store = CrmDemoSessionStore()

        assertNull(store.dataset.value)
        store.enter(now)
        assertEquals(3, store.dataset.value?.opportunities?.size)
        assertEquals(3, store.dataset.value?.contacts?.size)

        store.exit()
        assertNull(store.dataset.value)
    }

    @Test
    fun `all demo records carry explicit stable markers`() {
        val dataset = createCrmDemoDataset(now)

        assertTrue(dataset.contacts.all { it.source == "CRM_DEMO_MEMORY" && it.contactId.startsWith("crm-demo-") })
        assertTrue(dataset.leads.all { it.sourceType == CrmDemoSessionStore.DEMO_SOURCE })
        assertTrue(dataset.opportunities.all { it.sourceType == CrmDemoSessionStore.DEMO_SOURCE })
        assertTrue(dataset.activities.all { it.sourceType == CrmDemoSessionStore.DEMO_SOURCE })
        assertTrue(dataset.actions.all { it.sourceType == CrmDemoSessionStore.DEMO_SOURCE && it.scheduleId == null })
    }

    @Test
    fun `enter is idempotent and does not regenerate the active session`() {
        val store = CrmDemoSessionStore()
        store.enter(now)
        val first = store.dataset.value

        store.enter(now + 86_400_000L)

        assertSame(first, store.dataset.value)
    }

    @Test
    fun `demo mutations remain inside the memory dataset`() {
        val store = CrmDemoSessionStore()
        store.enter(now)

        assertTrue(store.setActionCompleted("crm-demo-action-private", true, now + 1))
        assertTrue(store.setSuggestionStatus("crm-demo-suggestion-data", false, now + 2))
        assertTrue(store.changeStage("crm-demo-opp-private", CrmOpportunityStage.NEGOTIATION, now + 3))

        val dataset = store.dataset.value!!
        assertEquals(
            CrmActionStatus.COMPLETED,
            dataset.actions.single {
                it.actionId == "crm-demo-action-private"
            }.status,
        )
        assertEquals(
            CrmSuggestionStatus.DISMISSED,
            dataset.suggestions.single {
                it.suggestionId ==
                    "crm-demo-suggestion-data"
            }.status,
        )
        assertEquals(
            CrmOpportunityStage.NEGOTIATION,
            dataset.opportunities.single {
                it.opportunityId ==
                    "crm-demo-opp-private"
            }.stage,
        )
    }
}
