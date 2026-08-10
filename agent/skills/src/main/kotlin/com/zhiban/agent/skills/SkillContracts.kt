package com.zhiban.agent.skills

enum class SkillOrigin { BUILT_IN, SIGNED_PACKAGE }
enum class SkillLevel { SYSTEM, SCENE }

data class SkillSpec(
    val id: String,
    val version: Int,
    val displayName: String,
    val triggerIntents: Set<String>,
    val requiredTools: Set<String>,
    val planningInstruction: String,
    val origin: SkillOrigin = SkillOrigin.BUILT_IN,
    val publisherId: String? = null,
    val level: SkillLevel = SkillLevel.SYSTEM,
)

data class SkillActivation(
    val skillId: String,
    val version: Int,
    val planningInstruction: String,
    val requiredTools: Set<String>,
    val origin: SkillOrigin,
    val publisherId: String?,
)

class SkillActivator(private val specs: List<SkillSpec> = BuiltInSkills.all) {
    init {
        require(specs.map { it.id }.distinct().size == specs.size) { "duplicate skill id" }
    }

    fun activate(intent: String, mode: String, availableTools: Set<String>): List<SkillActivation> {
        if (mode != "Work") return emptyList()
        return specs.asSequence()
            .filter { intent in it.triggerIntents && availableTools.containsAll(it.requiredTools) }
            .take(2)
            .map {
                SkillActivation(
                    it.id,
                    it.version,
                    it.planningInstruction,
                    it.requiredTools,
                    it.origin,
                    it.publisherId,
                )
            }
            .toList()
    }
}

object BuiltInSkills {
    val all = listOf(
        SkillSpec(
            "calendar_coordination",
            1,
            "日程协调",
            setOf("CALENDAR_QUERY", "CALENDAR_CREATE"),
            setOf("calendar.schedule.search"),
            "先核对时间范围和已有日程；创建或修改前复述关键时间并等待用户确认。",
        ),
        SkillSpec(
            "contact_relationship",
            3,
            "联系人智能完善",
            setOf("GENERAL_WORK", "CONTACT_QUERY", "CONTACT_CREATE", "RELATIONSHIP_QUERY", "RELATIONSHIP_WRITE"),
            setOf(
                "contact.search",
                "contact.getDetail",
                "contact.profile.proposeUpdate",
                "contact.tag.add",
                "relationship.search",
                "relationship.createCandidate",
                "relationship.getEvidence",
            ),
            "主动识别当前任务中出现的人物，先消歧身份，再检查档案空缺和已有关系。" +
                "可从用户授权的联系人、已确认聊天摘要、日程和共同经历中提取公司、职位、联系方式、重要日期、沟通偏好与当前事项。" +
                "同一对人允许存在多层关系；同公司只能作为可追溯的组织关联，不能直接冒充已确认同事。" +
                "只对空缺档案提出补全，不覆盖已有字段；高置信、可追溯的本地标签可自动整理且必须可见可撤，档案写入和主观关系落库仍需用户确认。",
        ),
        SkillSpec(
            "sales_crm",
            3,
            "个人 CRM",
            setOf("SALES_CRM"),
            setOf(
                "contact.search",
                "contact.getDetail",
                "contact.profile.proposeUpdate",
                "contact.tag.add",
                "relationship.search",
                "relationship.getEvidence",
                "crm.opportunity.list",
                "crm.opportunity.get",
                "crm.lead.createCandidate",
                "crm.opportunity.create",
                "crm.opportunity.update",
                "crm.opportunity.changeStage",
                "crm.activity.append",
                "crm.nextAction.create",
                "crm.nextAction.update",
                "crm.nextAction.complete",
                "calendar.schedule.conflicts",
                "calendar.schedule.create",
                "communication.message.compose",
            ),
            "以 CRM 机会为销售推进主线，以关系库联系人为客户底座，以日历承载已确认的下一步动作。" +
                "回答前先读取机会事实、联系人角色、沟通活动、下一步动作和阶段历史，再基于可追溯证据提出推进建议。" +
                "高置信线索只能自动进入候选池；有稳定证据的内部活动和新动作可自动整理且必须可见可撤。候选转正、机会、阶段、已有动作修改/完成仍需逐次确认；创建日程必须再走一次日历确认。" +
                "不得把推断当作事实，也不得静默读取社交软件私有数据库；档案补全、关系写入、日程创建和对外发送都必须等待用户确认。",
            level = SkillLevel.SCENE,
        ),
        SkillSpec(
            "personal_life",
            1,
            "生活助理",
            setOf("PERSONAL_LIFE"),
            setOf(
                "contact.search",
                "contact.getDetail",
                "relationship.search",
                "relationship.getEvidence",
                "calendar.schedule.search",
                "calendar.schedule.conflicts",
                "calendar.schedule.create",
                "communication.message.compose",
            ),
            "围绕重要的人、重要日期和已经答应的事情安排生活。先核对联系人资料、关系证据与已有日程，再给出少而明确的下一步。" +
                "消息中的约定只能作为候选，写入日历前必须让用户确认；联系人资料和关系不明确时不得猜测。" +
                "可以准备祝福、探望、聚会或提醒方案，但任何对外发送都必须由用户最后确认。",
            level = SkillLevel.SCENE,
        ),
        SkillSpec(
            "social_planning",
            1,
            "一起安排",
            setOf("SOCIAL_PLANNING"),
            setOf(
                "contact.search",
                "contact.getDetail",
                "relationship.search",
                "relationship.getEvidence",
                "calendar.schedule.search",
                "calendar.schedule.conflicts",
                "calendar.schedule.create",
                "communication.message.compose",
            ),
            "围绕一次多人聚会、探望或出行推进安排。先确认参与人身份和用户自己的日程冲突，再准备候选时间与邀请文案。" +
                "不得声称知道其他人的空闲时间；回复只能依据用户确认或可追溯消息整理。写入日历前由用户确认，任何邀请或变更消息由用户最后发送。",
            level = SkillLevel.SCENE,
        ),
        SkillSpec(
            "memory_preference",
            1,
            "偏好记忆",
            setOf("MEMORY_QUERY", "MEMORY_WRITE"),
            setOf("memory.search"),
            "区分当前会话信息和长期稳定偏好；长期保存必须说明内容并等待用户确认。",
        ),
    )
}
