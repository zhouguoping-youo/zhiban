package com.zhiban.rebuild.data.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactTemporalWriteTest {
    @Test
    fun ownerCurrentEmploymentChangePreservesPreviousCompanyAsHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val repository = ContactAgentDataRepository(database)

            repository.saveOwnerCurrentEmployment("甲公司", "销售", 100)
            repository.saveOwnerCurrentEmployment("乙公司", "负责人", 200)

            val employments = database.contactIntelligenceDao().listAllEmployments()
                .filter { it.personId == com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF }
            assertEquals(2, employments.size)
            assertEquals("乙公司", employments.single { it.currentState == "CURRENT" }.companyNameSnapshot)
            assertEquals("负责人", employments.single { it.currentState == "CURRENT" }.title)
            assertEquals(200L, employments.single { it.currentState == "PAST" }.validToEpochMs)
            val current = employments.single { it.currentState == "CURRENT" }
            assertNotNull(current.organizationId)
            assertEquals("乙公司", database.contactKnowledgeDao().findOrganization(current.organizationId!!)?.canonicalName)
        } finally {
            database.close()
        }
    }

    @Test
    fun ownerEmploymentEditorClosesCurrentWorkStoredOnLinkedOwnerContact() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val repository = ContactAgentDataRepository(database)
            val ownerContactId = repository.saveUserContact(
                contactId = null,
                displayName = "用户本人",
                phone = null,
                wechatId = null,
                company = "旧公司",
                title = "销售",
                tag = null,
                note = null,
                nowEpochMs = 100,
            )
            repository.confirmContactIsOwner(ownerContactId, 110)

            repository.saveOwnerCurrentEmployment("新公司", "负责人", 200)

            val employments = database.contactIntelligenceDao().listAllEmployments()
            assertEquals("PAST", employments.single { it.personId == ownerContactId }.currentState)
            assertEquals(200L, employments.single { it.personId == ownerContactId }.validToEpochMs)
            val current = employments.single { it.currentState == "CURRENT" }
            assertEquals(com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF, current.personId)
            assertEquals("新公司", current.companyNameSnapshot)
        } finally {
            database.close()
        }
    }

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

    @Test
    fun endingOneRelationshipTypeKeepsOtherTypeCurrent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val contacts = ContactAgentDataRepository(database)
            val relationships = RelationshipAgentDataRepository(database)
            val contactId = contacts.saveUserContact(
                contactId = null,
                displayName = "旧同事",
                phone = "13800138000",
                wechatId = null,
                company = null,
                title = null,
                tag = null,
                note = null,
                nowEpochMs = 10,
            )
            val friendEdge = relationships.saveConfirmedRelationship(
                com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
                contactId,
                "FRIEND",
                "CURRENT",
                100,
            )
            relationships.saveConfirmedRelationship(
                com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
                contactId,
                "COLLEAGUE",
                "CURRENT",
                200,
            )

            relationships.deleteConfirmedRelationship(friendEdge, 300)

            val episodes = database.contactIntelligenceDao().listRelationships(
                com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
                20,
            )
            assertEquals(2, episodes.size)
            assertEquals(300L, episodes.single { it.relationshipType == "FRIEND" }.validToEpochMs)
            assertNull(episodes.single { it.relationshipType == "COLLEAGUE" }.validToEpochMs)
        } finally {
            database.close()
        }
    }

    @Test
    fun directionalRelationshipPersistsDirectionAndInverseCompatibleType() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val contacts = ContactAgentDataRepository(database)
            val relationships = RelationshipAgentDataRepository(database)
            val contactId = contacts.saveUserContact(
                contactId = null,
                displayName = "直属下属",
                phone = null,
                wechatId = null,
                company = null,
                title = null,
                tag = null,
                note = null,
                nowEpochMs = 10,
            )

            relationships.saveConfirmedRelationship(
                com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
                contactId,
                "MANAGER",
                "CURRENT",
                100,
            )

            val episode = database.contactIntelligenceDao().listRelationships(
                com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
                20,
            ).single()
            assertEquals("MANAGER", episode.relationshipType)
            assertEquals("FROM_TO", episode.direction)
        } finally {
            database.close()
        }
    }

    @Test
    fun enduringRelationshipRejectsFalsePastState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            val contacts = ContactAgentDataRepository(database)
            val relationships = RelationshipAgentDataRepository(database)
            val contactId = contacts.saveUserContact(
                contactId = null,
                displayName = "大学同学",
                phone = null,
                wechatId = null,
                company = null,
                title = null,
                tag = null,
                note = null,
                nowEpochMs = 10,
            )

            val failure = runCatching {
                relationships.saveConfirmedRelationship(
                    com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
                    contactId,
                    "CLASSMATE",
                    "PAST",
                    100,
                )
            }.exceptionOrNull()

            assertEquals("关系时间状态与关系类型不匹配", failure?.message)
            assertEquals(0, database.relationshipEdgeDao().touching(listOf(contactId), 10).size)
        } finally {
            database.close()
        }
    }
}
