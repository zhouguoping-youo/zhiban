package com.zhiban.rebuild.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Test

class RelationshipAttentionTest {
    @Test
    fun `attention workbench stays quiet when there is nothing actionable`() {
        assertEquals(emptyList<RelationshipAttentionItem>(), buildRelationshipAttentionItems(emptyList(), emptyList(), 0))
    }

    @Test
    fun `attention workbench prioritizes concrete actions and stays within three rows`() {
        val items = buildRelationshipAttentionItems(
            pendingCallContactNames = listOf("丁波", "王能能"),
            replySuggestions = listOf(
                ReplySuggestionCardModel("reply-1", "huang", "WECHAT", "黄勇", "报价收到了", emptyList(), 1L),
            ),
            maintenanceCount = 4,
        )

        assertEquals(3, items.size)
        assertEquals(RelationshipAttentionKind.CALL_NOTE, items[0].kind)
        assertEquals("丁波 · 补充刚才的通话要点", items[0].title)
        assertEquals(RelationshipAttentionKind.REPLY, items[1].kind)
        assertEquals(RelationshipAttentionKind.MAINTENANCE, items[2].kind)
    }
}
