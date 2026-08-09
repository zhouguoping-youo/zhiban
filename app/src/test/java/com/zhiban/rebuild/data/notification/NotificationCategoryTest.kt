package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCategoryTest {
    @Test
    fun thereAreExactlyFourCategories() {
        assertEquals(4, NotificationCategory.entries.size)
    }

    @Test
    fun everyCategoryHasTitleSubtitleAndDistinctKey() {
        val keys = NotificationCategory.entries.map { it.preferenceKey.name }
        assertEquals(keys.size, keys.distinct().size)
        NotificationCategory.entries.forEach { category ->
            assertTrue(category.title.isNotBlank())
            assertTrue(category.subtitle.isNotBlank())
        }
    }

    @Test
    fun storageKeysMatchSpec() {
        assertEquals("notification_schedule", NotificationCategory.SCHEDULE.preferenceKey.name)
        assertEquals("notification_crm", NotificationCategory.CRM.preferenceKey.name)
        assertEquals("notification_collection", NotificationCategory.COLLECTION.preferenceKey.name)
        assertEquals("notification_autowrite", NotificationCategory.AUTO_WRITE.preferenceKey.name)
    }
}
