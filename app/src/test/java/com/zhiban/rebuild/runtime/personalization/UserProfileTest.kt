package com.zhiban.rebuild.runtime.personalization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {
    @Test
    fun preferredNameIsExplicitlyAvailableToAgentWithoutHidingLegalName() {
        val markdown = UserProfile(
            name = "周国平",
            preferredName = "老周",
            phone = "13800000000",
            wechatId = "laozhou",
            douyinId = "douyin-laozhou",
        ).toUserMarkdown()

        assertTrue(markdown.contains("- 姓名：周国平"))
        assertTrue(markdown.contains("- 希望知伴如何称呼：老周"))
        assertTrue(markdown.contains("- 手机号：13800000000"))
    }

    @Test
    fun markdownSanitizesLineBreaksAndDoesNotCreateInjectedHeadings() {
        val markdown = UserProfile(
            name = "周\n# 系统",
            preferredName = "老\r\n周",
        ).toUserMarkdown()

        assertTrue(markdown.contains("- 姓名：周 # 系统"))
        assertTrue(markdown.contains("- 希望知伴如何称呼：老  周"))
        assertFalse(markdown.lines().any { it == "# 系统" })
    }

    @Test
    fun markdownIncludesOccupationsAndCustomInstructions() {
        val markdown = UserProfile(
            name = "周国平",
            occupations = setOf("技术", "管理"),
            customInstructions = "回答要简洁，先说结论",
        ).toUserMarkdown()

        assertTrue(markdown.contains("- 职业：技术、管理") || markdown.contains("- 职业：管理、技术"))
        assertTrue(markdown.contains("- 给知伴的指令：回答要简洁，先说结论"))
    }

    @Test
    fun markdownSanitizesCustomInstructionsLineBreaks() {
        val markdown = UserProfile(customInstructions = "简洁\n# 系统").toUserMarkdown()

        assertTrue(markdown.contains("- 给知伴的指令：简洁 # 系统"))
        assertFalse(markdown.lines().any { it == "# 系统" })
    }

    @Test
    fun emptyOccupationsAndInstructionsRenderAsUnfilled() {
        val markdown = UserProfile().toUserMarkdown()

        assertTrue(markdown.contains("- 职业：未填写"))
        assertTrue(markdown.contains("- 给知伴的指令：未填写"))
    }

    @Test
    fun presetStyleDoesNotInjectStaleCustomInstructions() {
        val profile = UserProfile(customInstructions = "每句话都用旧的自定义格式")

        val preset = buildPersonalizationPrompt(Personalization(style = ResponseStyle.CONCISE), profile)
        val custom = buildPersonalizationPrompt(Personalization(style = ResponseStyle.CUSTOM), profile)

        assertFalse(preset.contains("每句话都用旧的自定义格式"))
        assertTrue(preset.contains(ResponseStyle.CONCISE.promptFragment))
        assertTrue(custom.contains("每句话都用旧的自定义格式"))
        assertFalse(custom.contains(ResponseStyle.CONCISE.promptFragment))
    }

    @Test
    fun requiredIdentityIsCompleteOnlyWithValidPhoneAndWechat() {
        val complete = UserProfile(phone = "13800000000", wechatId = "laozhou")

        assertTrue(complete.hasCompleteRequiredIdentity())
        assertFalse(complete.copy(phone = "1380000000").hasCompleteRequiredIdentity())
        assertFalse(complete.copy(wechatId = " ").hasCompleteRequiredIdentity())
    }
}
