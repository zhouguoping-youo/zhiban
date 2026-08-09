package com.zhiban.rebuild.ui.tabs

internal fun newCrmOpportunityPrompt(): String = "请使用个人 CRM 工具创建机会。主要联系人可选：我明确提到联系人时先调用 contact.search 确认；" +
    "没有提到联系人时不要追问，直接用 title 和 accountName 调用 crm.opportunity.create 发起一次写入确认。" +
    "不要自动创建联系人，也不要只给文字建议。"

internal fun crmSuggestionPrompt(opportunityId: String?, suggestionTitle: String, isDemo: Boolean): String = if (isDemo) {
    "这是演示数据中的机会“$opportunityId”，请基于演示内容分析建议“$suggestionTitle”并准备一份只读演练方案。" +
        "不要调用真实联系人、个人 CRM 或日历的写入工具，也不要写入任何真实数据。"
} else if (opportunityId == null) {
    "这是针对一位联系人的建议“$suggestionTitle”，与具体机会无关。请基于该联系人的真实信息分析这条建议的依据、风险和下一步；" +
        "涉及写入时仍逐项发起确认，不要自动写入，也不要只给文字建议。"
} else {
    "请先调用 crm.opportunity.get 查询个人 CRM 机会 ID“$opportunityId”，再分析建议“$suggestionTitle”的依据、风险和下一步。" +
        "如果我决定执行，请调用对应的 crm.opportunity.update、crm.opportunity.changeStage、" +
        "crm.activity.append 或 crm.nextAction.create 逐项发起写入确认；" +
        "完成下一步动作时使用 crm.nextAction.complete，日程仍走独立确认，不要只给文字建议。"
}
