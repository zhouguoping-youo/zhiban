package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Rule
import org.junit.Test

class RelationshipEditorPolicyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun serviceRelationshipDoesNotAskForOwnerEmploymentAndUsesServiceEvidence() {
        setEditor()

        compose.onNodeWithText("生活服务").performScrollTo().performClick()
        compose.onNodeWithText("医生").performScrollTo().performClick()

        compose.onNodeWithText("真实预约、订单、租约或服务往来；不依赖你的工作信息")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("关系阶段").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("还不知道你现在在哪工作").assertDoesNotExist()
    }

    @Test
    fun classmateUsesSharedEducationAndDoesNotOfferEndedState() {
        setEditor()

        compose.onNodeWithText("朋友同学").performScrollTo().performClick()
        compose.onNodeWithText("同学").performScrollTo().performClick()

        compose.onNodeWithText("共同学校或班级经历；毕业后仍保留“同学”")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("关系状态").assertDoesNotExist()
        compose.onNodeWithText("已结束").assertDoesNotExist()
        compose.onNodeWithText("过去").assertDoesNotExist()
    }

    @Test
    fun customerUsesBusinessEvidenceInsteadOfSameCompanyRule() {
        setEditor()

        compose.onNodeWithText("工作").performScrollTo().performClick()
        compose.onNodeWithText("客户").performScrollTo().performClick()

        compose.onNodeWithText("真实项目、合同、订单或业务沟通；不能只凭公司名称判断")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun switchingGroupClearsPreviouslySelectedRelationshipType() {
        setEditor()

        compose.onNodeWithText("测试联系人").performClick()
        compose.onNodeWithText("生活服务").performScrollTo().performClick()
        compose.onNodeWithText("医生").performScrollTo().performClick()
        compose.onNodeWithText("确认添加").assertIsDisplayed()

        compose.onNodeWithText("工作").performScrollTo().performClick()

        compose.onNodeWithText("选择关系类型").assertIsDisplayed()
        compose.onNodeWithText("医生").assertDoesNotExist()
    }

    @Test
    fun selectedPeopleArePresentedAsAQuestionRatherThanAnIncompleteSentence() {
        setEditor()

        compose.onNodeWithText("测试联系人").performClick()
        compose.onNodeWithText("生活服务").performScrollTo().performClick()

        compose.onNodeWithText("我和测试联系人是什么关系").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("我是测试联系人的……").assertDoesNotExist()
    }

    private fun setEditor() {
        compose.setContent {
            ZhiBanTheme {
                RelationshipEditorDialog(
                    owner = UserProfile(name = "周国平"),
                    contacts = listOf(contact()),
                    onDismiss = {},
                    onSave = { _, _, _, _, result -> result(null) },
                )
            }
        }
    }

    private fun contact() = ContactEntity(
        contactId = "contact-1",
        displayName = "测试联系人",
        normalizedName = "测试联系人",
        phone = null,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "TEST",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
