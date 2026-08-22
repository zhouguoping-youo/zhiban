package com.zhiban.rebuild.ui.acceptance

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.MainActivity
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsights
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.di.AgentDataModule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验收项 4:用受控测试数据在真机真实界面上触发三处仅靠空库无法看到的 UI,
 * 验证后清理,不污染用户数据。
 *
 * 种子通过第二连接直写真实加密库(与 App 实例同一文件),scenario.recreate()
 * 让 App 侧 ViewModel 重建并重新查询,绕过跨实例无 InvalidationTracker 通知的限制。
 */
@RunWith(AndroidJUnit4::class)
class ControlledSeedAcceptanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun <T> withSeedDatabase(block: suspend (AgentDatabase) -> T): T = runBlocking {
        val db = AgentDataModule.provideAgentDatabase(context)
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun relaunchApp() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) {
        val found = runCatching {
            compose.waitUntil(timeoutMs) {
                compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!found) {
            // 诊断:打印整棵语义树,区分"种子没生效"和"找错页面/文案"。
            compose.onNode(androidx.compose.ui.test.isRoot()).printToLog("SEED-DIAG")
            throw AssertionError("text not found within ${timeoutMs}ms: $text")
        }
    }

    @After
    fun cleanupSeeds() {
        withSeedDatabase { db ->
            db.scheduleDao().deleteById(SCHEDULE_ID)
            db.notificationCandidateDao().deleteById(CANDIDATE_ID)
        }
        relaunchApp()
    }

    @Test
    fun runtimeCreatedScheduleShowsZhiBanSourceMark() {
        val startAt = System.currentTimeMillis() + SCHEDULE_LEAD_MS
        withSeedDatabase { db ->
            db.scheduleDao().insert(
                ScheduleEntity(
                    id = SCHEDULE_ID,
                    title = SCHEDULE_TITLE,
                    startAtEpochMs = startAt,
                    durationMinutes = 30,
                    note = null,
                    createdByRunId = null,
                    createdByRuntimeRunId = "seed-runtime-run",
                    createdAtEpochMs = System.currentTimeMillis(),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            // 读回确认种子落库,把"种子没写上"和"UI 没显示"分开。
            val persisted = db.scheduleDao().listRange(0L, Long.MAX_VALUE, 1_000).any { it.id == SCHEDULE_ID }
            assertTrue("schedule seed must persist", persisted)
        }
        relaunchApp()

        // 保险:日历是首屏,其 ViewModel 可能早于种子创建;切日再切回强制重新查询。
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()
        compose.onNodeWithContentDescription("选择 ${today.minusDays(1)}").performClick()
        if (startDate != today) {
            compose.onNodeWithContentDescription("选择 $startDate").performClick()
        } else {
            compose.onNodeWithContentDescription("选择 $today").performClick()
        }
        waitForText(SCHEDULE_TITLE, substring = true)
        compose.onNodeWithText(SCHEDULE_TITLE, substring = true).assertIsDisplayed()
        compose.onNodeWithText("知伴记录", substring = true).assertIsDisplayed()
    }

    @Test
    fun lifeAssistantDetailEvidenceExpands() {
        seedCandidate()
        relaunchApp()

        compose.onNodeWithContentDescription("能力").performClick()
        compose.onNodeWithText("生活助理").performClick()
        compose.waitForIdle()
        // 种子卡是唯一事项时在工作台大图卡上,多张时收进「全部」列表;两条路都走到详情。
        val onWorkbench = compose.onAllNodesWithText(CANDIDATE_SCHEDULE_TITLE)
            .fetchSemanticsNodes().isNotEmpty()
        if (!onWorkbench) {
            compose.onNodeWithText("全部").performClick()
        }
        waitForText(CANDIDATE_SCHEDULE_TITLE)
        compose.onAllNodesWithText(CANDIDATE_SCHEDULE_TITLE).onFirst().performClick()
        compose.onNodeWithText("查看依据").performClick()

        compose.onNodeWithText("来源").assertIsDisplayed()
        compose.onNodeWithText("微信").assertIsDisplayed()
        compose.onNodeWithText("原消息").assertIsDisplayed()
        compose.onNodeWithText(CANDIDATE_BODY, substring = true).assertIsDisplayed()
        compose.onNodeWithText("90% 可信").assertIsDisplayed()
    }

    @Test
    fun perceptionCandidateCardShowsAndDismissesThroughRealUi() {
        seedCandidate()
        relaunchApp()

        compose.onNodeWithContentDescription("关系").performClick()
        compose.onNodeWithContentDescription("待确认内容").performClick()
        waitForText(CANDIDATE_SENDER, substring = true)
        compose.onAllNodesWithText(CANDIDATE_SENDER, substring = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText(CANDIDATE_BODY, substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认加入日历").assertIsDisplayed()

        // 通过卡片自身的「忽略」走真实清理路径,而不是绕过 UI 直接改库。
        compose.onAllNodesWithText(CANDIDATE_SENDER, substring = true).onFirst().performScrollTo()
        val seededIgnore = hasText("忽略") and
            hasAnyAncestor(hasAnyDescendant(hasText(CANDIDATE_SENDER, substring = true)))
        compose.onNode(seededIgnore).performScrollTo().performClick()
        compose.waitForIdle()
        val cardGone = compose.onAllNodesWithText(CANDIDATE_SENDER, substring = true)
            .fetchSemanticsNodes().isEmpty()
        if (!cardGone) {
            compose.onAllNodes(androidx.compose.ui.test.isRoot()).onLast().printToLog("SEED-DIAG")
        }
        assertTrue("seeded card should be gone after 忽略", cardGone)

        val remaining = withSeedDatabase { db ->
            db.notificationCandidateDao().find(CANDIDATE_ID)
        }
        assertTrue(remaining == null || remaining.status != "PENDING")
    }

    private fun seedCandidate() {
        val zone = ZoneId.systemDefault()
        val reviewAt = LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(15, 0))
            .atZone(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        withSeedDatabase { db ->
            db.notificationCandidateDao().upsert(
                NotificationCandidateEntity(
                    candidateId = CANDIDATE_ID,
                    sourceKey = "seed-source-$CANDIDATE_ID",
                    packageName = "com.tencent.mm",
                    appLabel = "微信",
                    title = CANDIDATE_SENDER,
                    body = CANDIDATE_BODY,
                    postedAtEpochMs = now,
                    createdAtEpochMs = now,
                    platform = "WECHAT",
                    conversationTitle = CANDIDATE_SENDER,
                    senderName = CANDIDATE_SENDER,
                    insightJson = NotificationInsights(
                        ScheduleInsight(
                            title = CANDIDATE_SCHEDULE_TITLE,
                            startAtEpochMs = reviewAt,
                            durationMinutes = 60,
                            reminderMinutesBefore = 10,
                            confidence = 0.9,
                        ),
                    ).toJsonOrNull(),
                    normalizedSender = CANDIDATE_SENDER,
                ),
            )
        }
    }

    private companion object {
        const val SCHEDULE_ID = "seed-accept-schedule"
        const val SCHEDULE_TITLE = "验收·知伴来源日程"
        const val SCHEDULE_LEAD_MS = 2 * 3_600_000L
        const val CANDIDATE_ID = "seed-accept-candidate"
        const val CANDIDATE_SENDER = "验收同事"
        const val CANDIDATE_BODY = "明天下午3点产品评审会，老地方见"
        const val CANDIDATE_SCHEDULE_TITLE = "产品评审会"
    }
}
