package com.zhiban.rebuild.data.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactTemporalWriteTest {
    @Test
    fun userContactCreateAndCompanyChangeStayInTemporalIdentityModel() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val repository = ContactAgentDataRepository(database)
            val contactId = repository.saveUserContact(
                contactId = null,
                displayName = "丁波",
                phone = "138-0013-8000",
                wechatId = "dingbo",
                company = "甲公司",
                title = "销售",
                tag = null,
                note = null,
                nowEpochMs = 100,
            )

            assertNotNull(database.contactIntelligenceDao().findPerson(contactId))
            assertEquals("甲公司", database.contactIntelligenceDao().findCurrentUserEmployment(contactId)?.companyNameSnapshot)
            assertEquals(1, database.contactIntelligenceDao().matchingClaims("PHONE", "13800138000").size)

            repository.saveUserContact(
                contactId = contactId,
                displayName = "丁波",
                phone = "13800138000",
                wechatId = "dingbo",
                company = "乙公司",
                title = "负责人",
                tag = null,
                note = null,
                nowEpochMs = 200,
            )

            val employments = database.contactIntelligenceDao().listAllEmployments().filter { it.personId == contactId }
            assertEquals(2, employments.size)
            assertEquals("乙公司", employments.single { it.currentState == "CURRENT" }.companyNameSnapshot)
            assertEquals(200L, employments.single { it.currentState == "PAST" }.validToEpochMs)
            assertEquals(1, database.contactIntelligenceDao().matchingClaims("COMPANY", "乙公司").size)
            assertEquals(0, database.contactIntelligenceDao().matchingClaims("COMPANY", "甲公司").size)
        } finally {
            database.close()
        }
    }
}
