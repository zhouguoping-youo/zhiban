package com.zhiban.rebuild.runtime.tool

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactMaintenanceToolBindingTest {
    @Test
    fun readOnlyToolReturnsRealIssuesWithoutContactChannels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            database.contactDao().insert(contact())
            val binding = ContactMaintenanceToolBinding(
                RuntimeToolCatalog.production().requireRegistered("contact.maintenance.list"),
                database.contactDao(),
                database.contactIdentityDao(),
                database.contactIntelligenceDao(),
            )

            val result = binding.executeReadOnly(
                RuntimeToolCallRequest("call-1", "contact.maintenance.list", "{\"issue\":\"NO_REACHABLE_METHOD\"}"),
                RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, NOW),
            )
            val json = Json.parseToJsonElement(result.safeResultJson).jsonObject
            val item = json.getValue("items").jsonArray.single().jsonObject

            assertEquals("待核实联系人", item.getValue("displayName").jsonPrimitive.content)
            assertEquals("1", json.getValue("count").jsonPrimitive.content)
            assertEquals(null, item["phone"])
        } finally {
            database.close()
        }
    }

    private fun contact() = ContactEntity(
        contactId = "contact-maintenance-test",
        displayName = "待核实联系人",
        normalizedName = "待核实联系人",
        phone = null,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT:test",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
