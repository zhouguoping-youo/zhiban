package com.zhiban.rebuild.navigation

data class MainTabDefinition(val key: String, val label: String, val pageTitle: String, val emptyTitle: String, val emptyDescription: String)

object MainTabContract {
    val tabs = listOf(
        MainTabDefinition("calendar", "日历", "日历", "今天还没有安排", "创建日程后，知伴会在这里帮你整理一天。"),
        MainTabDefinition("relation", "关系", "关系", "还没有联系人", "添加重要的人，慢慢建立你的关系网络。"),
        MainTabDefinition("home", "问问", "问问知伴", "还没有对话", "写下此刻的问题，知伴会陪你一起梳理。"),
        MainTabDefinition("skill", "能力", "能力", "还没有启用能力", "选择适合你的场景能力，让知伴更懂你的需要。"),
        MainTabDefinition("profile", "我的", "我的", "个人资料尚未完善", "完善资料后，知伴可以提供更贴合你的建议。"),
    )

    fun requireTab(key: String): MainTabDefinition = tabs.first { it.key == key }
}
