package com.zhiban.rebuild.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutLegalEntriesTest {
    @Test
    fun thereAreExactlyThreeEntriesInOrder() {
        assertEquals(
            listOf("隐私政策", "使用条款", "开源许可"),
            ABOUT_LEGAL_ENTRIES.map { it.title },
        )
    }

    @Test
    fun everyEntryHasNonBlankBody() {
        ABOUT_LEGAL_ENTRIES.forEach { entry ->
            assertTrue("${entry.title} body blank", entry.body.isNotBlank())
        }
    }

    @Test
    fun licensesEntryNamesTheOpenSourceLibraries() {
        val licenses = ABOUT_LEGAL_ENTRIES.single { it.title == "开源许可" }.body
        listOf("Jetpack Compose", "Room", "Hilt", "OkHttp", "Retrofit")
            .forEach { library -> assertTrue("missing $library", licenses.contains(library)) }
    }
}
