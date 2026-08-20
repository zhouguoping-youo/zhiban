package com.zhiban.rebuild.data.contact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommonGroupRelationshipScannerTest {
    private lateinit var database: AgentDatabase

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() = database.close()

    @Test fun twoResolvedMembersCreateOneReversibleWeakGroupEdge() = runBlocking {
        insertResolvedMember("contact-a", "张三", "identity-a", "group-1")
        insertResolvedMember("contact-b", "李四", "identity-b", "group-1")
        val scanner = CommonGroupRelationshipScanner(database)

        assertEquals(1, scanner.scan(10_000L))
        assertEquals(0, scanner.scan(20_000L))

        val edge = database.relationshipEdgeDao().touching(listOf("contact-a"), 10).single()
        assertEquals("GROUP_MEMBER", edge.relationType)
        assertEquals(0.35, edge.confidence, 0.0)
        assertEquals(false, edge.userConfirmed)
        assertNotNull(database.changeLogDao().findAvailableAutoChangeForTarget("RELATIONSHIP", edge.edgeId))
    }

    private suspend fun insertResolvedMember(contactId: String, name: String, identityId: String, groupId: String) {
        database.contactDao().insert(
            ContactEntity(
                contactId, name, name, null, null, null, null, null,
                "[]", "[]", null, null, "SYSTEM", null, 1L, 1L,
            ),
        )
        database.contactIntelligenceDao().upsertPerson(
            PersonEntity(contactId, contactId, name, name, "PERSON", "ACTIVE", 1L, 1L),
        )
        database.contactIntelligenceDao().upsertSourceIdentity(
            SourceIdentityEntity(
                identityId,
                contactId,
                "WECHAT",
                "DEVICE_OBSERVED",
                null,
                null,
                name,
                name,
                groupId,
                "RESOLVED",
                0.9,
                "candidate-$identityId",
                1L,
                1L,
            ),
        )
        database.contactIntelligenceDao().upsertGroup(
            GroupConversationEntity(groupId, "WECHAT", "DEVICE_OBSERVED", null, "测试群", null, 1L, 1L),
        )
        database.contactIntelligenceDao().upsertGroupMembership(
            GroupMembershipEpisodeEntity(
                "membership-$identityId", groupId, identityId, name, null, null, "ACTIVE", 0.9, null, 1L,
            ),
        )
    }
}
