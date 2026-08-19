package com.zhiban.rebuild.data.notification

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * observePending 去重语义回归(窗口函数重写后):同会话+同正文的多次捕获只保留最新一条
 * (postedAt 优先、同刻 rowid 大者优先);正文/会话/方向任一不同则不合并。
 */
@RunWith(AndroidJUnit4::class)
class NotificationInboxDedupTest {
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    private suspend fun insert(key: String, conversationTitle: String?, body: String?, postedAt: Long, direction: String = "INCOMING") {
        database.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = "c-$key",
                sourceKey = "sk-$key",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三",
                body = body,
                postedAtEpochMs = postedAt,
                platform = "WECHAT",
                conversationTitle = conversationTitle,
                senderName = "张三",
                direction = direction,
            ),
        )
    }

    @Test fun duplicateCapturesCollapseToNewest() = runBlocking {
        insert("1", "张三", "明天见", postedAt = 1_000)
        insert("2", "张三", "明天见", postedAt = 2_000) // 同会话同正文,更新
        insert("3", "张三", "明天见", postedAt = 1_500)

        val pending = database.notificationCandidateDao().observePending().first()

        assertEquals(listOf("c-2"), pending.map { it.candidateId })
    }

    @Test fun sameTimestampKeepsLargerRowid() = runBlocking {
        insert("1", "张三", "明天见", postedAt = 1_000)
        insert("2", "张三", "明天见", postedAt = 1_000) // 同刻,rowid 大者保留

        val pending = database.notificationCandidateDao().observePending().first()

        assertEquals(listOf("c-2"), pending.map { it.candidateId })
    }

    @Test fun differentBodyOrConversationNeverMerges() = runBlocking {
        insert("1", "张三", "明天见", postedAt = 1_000)
        insert("2", "张三", "明天见！", postedAt = 2_000) // 正文不同
        insert("3", "李四", "明天见", postedAt = 3_000) // 会话不同

        val pending = database.notificationCandidateDao().observePending().first()

        assertEquals(setOf("c-1", "c-2", "c-3"), pending.map { it.candidateId }.toSet())
    }

    @Test fun incomingAndOutgoingNeverMerge() = runBlocking {
        insert("1", "张三", "明天见", postedAt = 1_000, direction = "INCOMING")
        insert("2", "张三", "明天见", postedAt = 2_000, direction = "OUTGOING")

        val pending = database.notificationCandidateDao().observePending().first()

        assertEquals(setOf("c-1", "c-2"), pending.map { it.candidateId }.toSet())
    }
}
