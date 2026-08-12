package com.zhiban.rebuild.relationship

enum class RelationshipGroup(val displayName: String) {
    FAMILY("家人"),
    SOCIAL("朋友同学"),
    WORK("工作"),
    EDUCATION("教育"),
    SERVICE("生活服务"),
    COMMUNITY("组织社区"),
    OTHER("其他"),
}

/**
 * Describes how a relationship changes over time. This is deliberately separate from interaction
 * frequency: not speaking for years does not, by itself, end a friendship or classmate relation.
 */
enum class RelationshipContinuity {
    /** Created by kinship or a shared life stage and normally keeps its meaning over time. */
    ENDURING_ORIGIN,

    /** Remains until the user or reliable contrary evidence explicitly changes it. */
    EXPLICIT_END_ONLY,

    /** A role whose current/past state depends on a dated context such as employment or service. */
    CONTEXT_BOUND,
}

enum class HistoricalRelationshipVisibility {
    /** Keep the ordinary label even when the supporting episode is in the past. */
    PRESERVE_LABEL,

    /** Show a precise historical label, for example "前同事". */
    SHOW_AS_HISTORICAL,

    /** Retain the record for user control but do not surface it in the default graph. */
    HIDE_FROM_DEFAULT_GRAPH,
}

/** The kind of evidence required before the Agent may propose a relationship. */
enum class RelationshipEvidenceBasis {
    USER_CONFIRMATION,
    KINSHIP,
    PARTNERSHIP,
    SHARED_EDUCATION,
    SHARED_RESIDENCE,
    SHARED_EXPERIENCE,
    SOCIAL_INTERACTION,
    SHARED_EMPLOYMENT,
    REPORTING_LINE,
    BUSINESS_INTERACTION,
    PROFESSIONAL_ROLE,
    GUIDANCE,
    RECRUITING,
    INTRODUCTION,
    EDUCATION_ROLE,
    SERVICE_INTERACTION,
    COMMUNITY_MEMBERSHIP,
    EXPLICIT_DESIGNATION,
}

data class RelationshipEvidencePolicy(val basis: RelationshipEvidenceBasis, val guidance: String, val requiresOwnerEmployment: Boolean = false)

data class RelationshipTemporalOption(val value: String, val label: String)

data class RelationshipTemporalPolicy(val title: String?, val options: List<RelationshipTemporalOption>) {
    val defaultValue: String = options.first().value
    val allowedValues: Set<String> = options.mapTo(linkedSetOf(), RelationshipTemporalOption::value)
}

enum class RelationshipDirection {
    SYMMETRIC,
    DIRECTED,
}

enum class RelationshipMappingQuality {
    EXACT,
    LOSSY,
    CUSTOM,
    NONE,
}

data class RelationshipTypeDefinition(
    val code: String,
    val group: RelationshipGroup,
    val displayName: String,
    val direction: RelationshipDirection = RelationshipDirection.SYMMETRIC,
    val inverseCode: String? = null,
    val vCardType: String? = null,
    val vCardQuality: RelationshipMappingQuality = RelationshipMappingQuality.CUSTOM,
    val androidTypeName: String? = null,
    val androidQuality: RelationshipMappingQuality = RelationshipMappingQuality.CUSTOM,
    val iosLabelName: String? = null,
    val iosQuality: RelationshipMappingQuality = RelationshipMappingQuality.CUSTOM,
    val isSelectable: Boolean = true,
    val continuity: RelationshipContinuity = RelationshipContinuity.EXPLICIT_END_ONLY,
    val historicalVisibility: HistoricalRelationshipVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
) {
    val extensionType: String = "x-zhiban-${code.lowercase().replace('_', '-')}"
    val evidencePolicy: RelationshipEvidencePolicy get() = RelationshipTaxonomy.evidencePolicy(code)
    val temporalPolicy: RelationshipTemporalPolicy get() = RelationshipTaxonomy.temporalPolicy(code)
}

/**
 * Canonical relationship vocabulary used by storage validation, Agent tools and UI.
 *
 * vCard/iOS/Android mappings are projections only. A lossy projection never replaces the
 * canonical code or its provenance in the local relationship timeline.
 */
object RelationshipTaxonomy {
    private val EXACT_VCARD_FAMILY = setOf("SPOUSE", "PARENT", "CHILD", "SIBLING", "KIN")
    private val EXACT_VCARD_DIRECTED = setOf("PARENT", "CHILD", "EMERGENCY_CONTACT", "AUTHORIZED_AGENT")
    private val EXACT_ANDROID_FAMILY = setOf("SPOUSE", "KIN")
    private val EXACT_ANDROID_DIRECTED = setOf("PARENT", "CHILD", "MANAGER", "ASSISTANT")

    val definitions: List<RelationshipTypeDefinition> = listOf(
        family("FAMILY", "家人", "kin"),
        family(
            "SPOUSE",
            "伴侣",
            "spouse",
            android = "TYPE_SPOUSE",
            ios = "CNLabelContactRelationSpouse",
            isSelectable = false,
            continuity = RelationshipContinuity.EXPLICIT_END_ONLY,
            historicalVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
        ),
        type(
            code = "PARTNER",
            group = RelationshipGroup.FAMILY,
            label = "伴侣",
            vCard = "sweetheart",
            vCardQuality = RelationshipMappingQuality.LOSSY,
            android = "TYPE_PARTNER",
            ios = "CNLabelContactRelationPartner",
            continuity = RelationshipContinuity.EXPLICIT_END_ONLY,
            historicalVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
        ),
        directedFamily("PARENT", "父母", "CHILD", "parent", "TYPE_PARENT", "CNLabelContactRelationParent"),
        directedFamily("CHILD", "子女", "PARENT", "child", "TYPE_CHILD", "CNLabelContactRelationChild"),
        family("SIBLING", "兄弟姐妹", "sibling", android = "TYPE_RELATIVE"),
        directedFamily("GRANDPARENT", "祖辈", "GRANDCHILD", "kin"),
        directedFamily("GRANDCHILD", "孙辈", "GRANDPARENT", "kin"),
        directedFamily("UNCLE_AUNT", "伯叔姑舅姨", "NEPHEW_NIECE", "kin"),
        directedFamily("NEPHEW_NIECE", "侄甥辈", "UNCLE_AUNT", "kin"),
        family("COUSIN", "堂表亲", "kin"),
        family("IN_LAW", "姻亲", "kin"),
        family("KIN", "其他亲属", "kin", android = "TYPE_RELATIVE"),
        directedFamily("STEP_PARENT", "继父母", "STEP_CHILD", "kin"),
        directedFamily("STEP_CHILD", "继子女", "STEP_PARENT", "kin"),
        family("STEP_SIBLING", "继兄弟姐妹", "kin"),
        directedFamily("ADOPTIVE_PARENT", "养父母", "ADOPTIVE_CHILD", "kin"),
        directedFamily("ADOPTIVE_CHILD", "养子女", "ADOPTIVE_PARENT", "kin"),
        directedFamily("GUARDIAN", "监护人", "WARD", "agent"),
        directedFamily("WARD", "被监护人", "GUARDIAN", "kin"),
        family("CO_PARENT", "共同抚养人", "kin"),
        type(
            "FIANCE",
            RelationshipGroup.FAMILY,
            "伴侣",
            vCard = "date",
            isSelectable = false,
            historicalVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
        ),
        type(
            "DATE",
            RelationshipGroup.FAMILY,
            "交往关系",
            vCard = "date",
            vCardQuality = RelationshipMappingQuality.EXACT,
            isSelectable = false,
            historicalVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
        ),
        type(
            "CRUSH",
            RelationshipGroup.FAMILY,
            "私人关系",
            vCard = "crush",
            vCardQuality = RelationshipMappingQuality.EXACT,
            isSelectable = false,
            historicalVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
        ),
        social("FRIEND", "朋友", "friend", android = "TYPE_FRIEND", ios = "CNLabelContactRelationFriend"),
        social("CLOSE_FRIEND", "密友", "friend"),
        social(
            "CHILDHOOD_FRIEND",
            "发小",
            "friend",
            continuity = RelationshipContinuity.ENDURING_ORIGIN,
            historicalVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
        ),
        social(
            "CLASSMATE",
            "同学",
            "friend",
            continuity = RelationshipContinuity.ENDURING_ORIGIN,
            historicalVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
        ),
        social(
            "SCHOOLMATE",
            "校友",
            "acquaintance",
            continuity = RelationshipContinuity.ENDURING_ORIGIN,
            historicalVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
        ),
        social(
            "ROOMMATE",
            "室友",
            "co-resident",
            RelationshipMappingQuality.EXACT,
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        social(
            "FELLOW_TOWNSMAN",
            "老乡",
            "acquaintance",
            continuity = RelationshipContinuity.ENDURING_ORIGIN,
            historicalVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
        ),
        social(
            "NEIGHBOR",
            "邻居",
            "neighbor",
            RelationshipMappingQuality.EXACT,
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        social("ONLINE_FRIEND", "网友", "acquaintance"),
        social(
            "TEAMMATE",
            "队友",
            "friend",
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        social(
            "HOBBY_FRIEND",
            "兴趣圈友",
            "acquaintance",
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        social(
            "TRAVEL_COMPANION",
            "旅行伙伴",
            "acquaintance",
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        social(
            "ACQUAINTANCE",
            "熟人",
            "acquaintance",
            RelationshipMappingQuality.EXACT,
            continuity = RelationshipContinuity.ENDURING_ORIGIN,
            historicalVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
        ),
        social(
            "MET",
            "见过",
            "met",
            RelationshipMappingQuality.EXACT,
            continuity = RelationshipContinuity.ENDURING_ORIGIN,
            historicalVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
        ),
        work("COLLEAGUE", "同事", "co-worker", RelationshipMappingQuality.EXACT),
        directedWork("MANAGER", "上级", "SUBORDINATE", "co-worker", android = "TYPE_MANAGER", ios = "CNLabelContactRelationManager"),
        directedWork("SUBORDINATE", "下属", "MANAGER", "co-worker"),
        directedWork("CUSTOMER", "客户", "SUPPLIER", "contact"),
        directedWork("SUPPLIER", "供应商", "CUSTOMER", "agent"),
        work("PROJECT_PARTNER", "项目伙伴", "colleague"),
        work("BUSINESS_PARTNER", "商业伙伴", "colleague"),
        work("PEER", "同行", "colleague", RelationshipMappingQuality.EXACT),
        directedWork("MENTOR", "导师", "MENTEE", "colleague"),
        directedWork("MENTEE", "被指导者", "MENTOR", "colleague"),
        directedWork("RECRUITER", "招聘方", "CANDIDATE", "agent"),
        directedWork("CANDIDATE", "候选人", "RECRUITER", "contact"),
        directedWork("ASSISTANT", "助理", null, "agent", android = "TYPE_ASSISTANT", ios = "CNLabelContactRelationAssistant"),
        directedWork("INVESTOR", "投资人", null, "contact"),
        directedWork("ADVISOR", "顾问", null, "agent"),
        directedWork("CONSULTANT", "咨询顾问", null, "agent"),
        directedWork("CONTRACTOR", "外包合作方", null, "contact"),
        directedWork("REFERRER", "介绍人", null, "contact", android = "TYPE_REFERRED_BY"),
        work("CHANNEL_PARTNER", "渠道伙伴", "colleague"),
        work("COMPETITOR", "竞争同行", "colleague"),
        directedContext("TEACHER", RelationshipGroup.EDUCATION, "老师", "STUDENT", "acquaintance"),
        directedContext("STUDENT", RelationshipGroup.EDUCATION, "学生", "TEACHER", "acquaintance"),
        directedContext("COACH", RelationshipGroup.EDUCATION, "教练", null, "acquaintance"),
        social(
            "KIDS_PARENT",
            "孩子同学的家长",
            "acquaintance",
            group = RelationshipGroup.EDUCATION,
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        service("DOCTOR", "医生"),
        service("LAWYER", "律师", "agent"),
        service("ESTATE_AGENT", "房产中介", "agent"),
        service("FINANCIAL_ADVISOR", "理财顾问", "agent"),
        service("HOUSEKEEPER", "家政服务"),
        service("REPAIR_PERSON", "维修师傅"),
        service("DELIVERY", "配送联系人"),
        service("PROPERTY_MANAGER", "物业联系人"),
        service("DRIVER", "司机"),
        service("MERCHANT", "商家"),
        service("CAREGIVER", "照护人", "agent"),
        service("THERAPIST", "心理咨询师"),
        service("ACCOUNTANT", "会计／税务顾问", "agent"),
        directed(
            "LANDLORD",
            RelationshipGroup.SERVICE,
            "房东",
            "TENANT",
            "contact",
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        directed(
            "TENANT",
            RelationshipGroup.SERVICE,
            "租客",
            "LANDLORD",
            "contact",
            continuity = RelationshipContinuity.CONTEXT_BOUND,
            historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
        ),
        service("VETERINARIAN", "宠物医生"),
        community("CLUB_MEMBER", "社团成员"),
        community("COMMUNITY_ORGANIZER", "社群组织者"),
        community("RELIGIOUS", "宗教社群联系人"),
        community("VOLUNTEER", "公益伙伴"),
        community("GROUP_MEMBER", "群友"),
        directed(
            "EMERGENCY_CONTACT",
            RelationshipGroup.OTHER,
            "紧急联系人",
            null,
            "emergency",
            continuity = RelationshipContinuity.EXPLICIT_END_ONLY,
            historicalVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
        ),
        directedContext("AUTHORIZED_AGENT", RelationshipGroup.OTHER, "授权代理人", null, "agent"),
        type("OTHER", RelationshipGroup.OTHER, "其他关系", vCard = "contact"),
        type("UNKNOWN", RelationshipGroup.OTHER, "未分类", isSelectable = false),
    )

    private val definitionsByCode = definitions.associateBy(RelationshipTypeDefinition::code)

    val selectableDefinitions: List<RelationshipTypeDefinition> = definitions.filter(RelationshipTypeDefinition::isSelectable)
    val selectableCodes: Set<String> = selectableDefinitions.mapTo(linkedSetOf(), RelationshipTypeDefinition::code)

    fun find(code: String): RelationshipTypeDefinition? = definitionsByCode[code.uppercase()]

    fun requireSupported(code: String): RelationshipTypeDefinition = requireNotNull(find(code)) {
        "Unsupported relationship type"
    }

    fun displayName(code: String): String = find(code)?.displayName ?: "其他关系"

    fun displayName(code: String, isHistorical: Boolean): String {
        if (!isHistorical) return displayName(code)
        val definition = find(code) ?: return "其他关系"
        if (definition.historicalVisibility != HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL) {
            return definition.displayName
        }
        return when (code.uppercase()) {
            "COLLEAGUE" -> "前同事"
            "MANAGER" -> "前上级"
            "SUBORDINATE" -> "前下属"
            "CUSTOMER" -> "前客户"
            "SUPPLIER" -> "前供应商"
            "PROJECT_PARTNER" -> "前项目伙伴"
            "BUSINESS_PARTNER", "CHANNEL_PARTNER" -> "前合作伙伴"
            "ROOMMATE" -> "前室友"
            "NEIGHBOR" -> "以前的邻居"
            "TEACHER" -> "曾任老师"
            "STUDENT" -> "曾是学生"
            "COACH" -> "曾任教练"
            "KIDS_PARENT" -> "以前的同学家长"
            "LANDLORD" -> "前房东"
            "TENANT" -> "前租客"
            "AUTHORIZED_AGENT" -> "曾授权代理人"
            else -> displayName(code)
        }
    }

    fun definitionsFor(group: RelationshipGroup): List<RelationshipTypeDefinition> = selectableDefinitions.filter { it.group == group }

    fun evidencePolicy(code: String): RelationshipEvidencePolicy {
        val normalized = requireSupported(code).code
        directEvidencePolicy(normalized)?.let { return it }
        return when (normalized) {
            "FAMILY", "PARENT", "CHILD", "SIBLING", "GRANDPARENT", "GRANDCHILD",
            "UNCLE_AUNT", "NEPHEW_NIECE", "COUSIN", "IN_LAW", "KIN", "STEP_PARENT",
            "STEP_CHILD", "STEP_SIBLING", "ADOPTIVE_PARENT", "ADOPTIVE_CHILD", "GUARDIAN",
            "WARD", "CO_PARENT", "SPOUSE", "FIANCE", "DATE", "CRUSH",
            -> policy(
                RelationshipEvidenceBasis.KINSHIP,
                "由你确认，或依据可核验的亲属与家庭资料",
            )

            "CHILDHOOD_FRIEND", "FELLOW_TOWNSMAN", "ACQUAINTANCE", "MET" -> policy(
                RelationshipEvidenceBasis.SHARED_EXPERIENCE,
                "共同地域、成长或认识经历；不能只凭同名判断",
            )

            "TEAMMATE", "HOBBY_FRIEND", "TRAVEL_COMPANION" -> policy(
                RelationshipEvidenceBasis.SHARED_EXPERIENCE,
                "共同队伍、兴趣活动或行程记录",
            )

            "FRIEND", "CLOSE_FRIEND", "ONLINE_FRIEND" -> policy(
                RelationshipEvidenceBasis.SOCIAL_INTERACTION,
                "你的明确确认优先；长期不联系不会自动取消关系",
            )

            "COLLEAGUE" -> policy(
                RelationshipEvidenceBasis.SHARED_EMPLOYMENT,
                "双方公司全称一致且任职时间重叠",
                requiresOwnerEmployment = true,
            )

            "MANAGER", "SUBORDINATE" -> policy(
                RelationshipEvidenceBasis.REPORTING_LINE,
                "同一公司与任职时间重叠，并有明确汇报关系",
                requiresOwnerEmployment = true,
            )

            "CUSTOMER", "SUPPLIER", "PROJECT_PARTNER", "BUSINESS_PARTNER", "CONTRACTOR",
            "INVESTOR", "ADVISOR", "CONSULTANT", "CHANNEL_PARTNER",
            -> policy(
                RelationshipEvidenceBasis.BUSINESS_INTERACTION,
                "真实项目、合同、订单或业务沟通；不能只凭公司名称判断",
            )

            "PEER", "COMPETITOR" -> policy(
                RelationshipEvidenceBasis.PROFESSIONAL_ROLE,
                "双方行业、职责或竞争领域证据",
            )

            "TEACHER", "STUDENT", "COACH", "KIDS_PARENT" -> policy(
                RelationshipEvidenceBasis.EDUCATION_ROLE,
                "学校、课程、班级或训练场景中的明确角色",
            )

            "DOCTOR", "LAWYER", "ESTATE_AGENT", "FINANCIAL_ADVISOR", "HOUSEKEEPER",
            "REPAIR_PERSON", "DELIVERY", "PROPERTY_MANAGER", "DRIVER", "MERCHANT", "CAREGIVER",
            "THERAPIST", "ACCOUNTANT", "LANDLORD", "TENANT", "VETERINARIAN",
            -> policy(
                RelationshipEvidenceBasis.SERVICE_INTERACTION,
                "真实预约、订单、租约或服务往来；不依赖你的工作信息",
            )

            "CLUB_MEMBER", "COMMUNITY_ORGANIZER", "RELIGIOUS", "VOLUNTEER", "GROUP_MEMBER" -> policy(
                RelationshipEvidenceBasis.COMMUNITY_MEMBERSHIP,
                "共同组织、社群或群组的成员记录",
            )

            "EMERGENCY_CONTACT", "AUTHORIZED_AGENT" -> policy(
                RelationshipEvidenceBasis.EXPLICIT_DESIGNATION,
                "必须由你明确指定，不能由 Agent 自动推断",
            )

            else -> error("Relationship evidence policy is missing")
        }
    }

    private fun directEvidencePolicy(code: String): RelationshipEvidencePolicy? = when (code) {
        "PARTNER" -> policy(RelationshipEvidenceBasis.PARTNERSHIP, "仅根据你的明确确认，不根据联系频率或聊天内容猜测")
        "CLASSMATE" -> policy(RelationshipEvidenceBasis.SHARED_EDUCATION, "共同学校或班级经历；毕业后仍保留“同学”")
        "SCHOOLMATE" -> policy(RelationshipEvidenceBasis.SHARED_EDUCATION, "共同学校经历；不要求在同一时间就读")
        "ROOMMATE" -> policy(RelationshipEvidenceBasis.SHARED_RESIDENCE, "共同居住地点与时间")
        "NEIGHBOR" -> policy(RelationshipEvidenceBasis.SHARED_RESIDENCE, "相邻住址或小区与时间")
        "MENTOR", "MENTEE" -> policy(RelationshipEvidenceBasis.GUIDANCE, "明确的指导或被指导经历")
        "RECRUITER", "CANDIDATE" -> policy(RelationshipEvidenceBasis.RECRUITING, "真实招聘流程或沟通记录")
        "ASSISTANT" -> policy(RelationshipEvidenceBasis.PROFESSIONAL_ROLE, "明确的助理职责与服务对象")
        "REFERRER" -> policy(RelationshipEvidenceBasis.INTRODUCTION, "可核验的介绍认识事件")
        "OTHER", "UNKNOWN" -> policy(RelationshipEvidenceBasis.USER_CONFIRMATION, "需要你说明或确认具体关系")
        else -> null
    }

    fun temporalPolicy(code: String): RelationshipTemporalPolicy {
        val definition = requireSupported(code)
        if (definition.code == "PARTNER" || definition.continuity == RelationshipContinuity.ENDURING_ORIGIN) {
            return RelationshipTemporalPolicy(null, listOf(RelationshipTemporalOption("CURRENT", "已确认")))
        }
        return when (definition.continuity) {
            RelationshipContinuity.ENDURING_ORIGIN -> error("handled above")

            RelationshipContinuity.EXPLICIT_END_ONLY -> RelationshipTemporalPolicy(
                "关系状态",
                listOf(
                    RelationshipTemporalOption("CURRENT", "仍然是"),
                    RelationshipTemporalOption("PAST", "已明确结束"),
                    RelationshipTemporalOption("UNKNOWN", "不确定"),
                ),
            )

            RelationshipContinuity.CONTEXT_BOUND -> RelationshipTemporalPolicy(
                "关系阶段",
                listOf(
                    RelationshipTemporalOption("CURRENT", "现在"),
                    RelationshipTemporalOption("PAST", "过去"),
                    RelationshipTemporalOption("UNKNOWN", "时间待核实"),
                ),
            )
        }
    }

    fun requireAllowedTemporalState(code: String, temporalState: String) {
        require(temporalState in temporalPolicy(code).allowedValues) { "关系时间状态与关系类型不匹配" }
    }

    fun groupGuidance(group: RelationshipGroup): String = when (group) {
        RelationshipGroup.FAMILY -> "家人关系由你确认，或依据可核验的亲属资料"
        RelationshipGroup.SOCIAL -> "依据共同经历或你的确认；不会因长期不联系自动失效"
        RelationshipGroup.WORK -> "同事看任职重叠；客户与合作方看真实业务往来"
        RelationshipGroup.EDUCATION -> "依据学校、课程、班级及师生角色"
        RelationshipGroup.SERVICE -> "依据预约、订单或服务往来，不依赖你的工作信息"
        RelationshipGroup.COMMUNITY -> "依据共同组织、社群或群组成员记录"
        RelationshipGroup.OTHER -> "紧急联系人和授权代理必须由你明确指定"
    }

    private fun policy(basis: RelationshipEvidenceBasis, guidance: String, requiresOwnerEmployment: Boolean = false) =
        RelationshipEvidencePolicy(basis, guidance, requiresOwnerEmployment)

    private fun family(
        code: String,
        label: String,
        vCard: String,
        android: String? = null,
        ios: String? = null,
        isSelectable: Boolean = true,
        continuity: RelationshipContinuity = RelationshipContinuity.ENDURING_ORIGIN,
        historicalVisibility: HistoricalRelationshipVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
    ) = type(
        code = code,
        group = RelationshipGroup.FAMILY,
        label = label,
        vCard = vCard,
        vCardQuality = if (code in EXACT_VCARD_FAMILY) RelationshipMappingQuality.EXACT else RelationshipMappingQuality.LOSSY,
        android = android,
        androidQuality = mappingQuality(android, code in EXACT_ANDROID_FAMILY),
        ios = ios,
        iosQuality = if (ios == null) RelationshipMappingQuality.CUSTOM else RelationshipMappingQuality.EXACT,
        isSelectable = isSelectable,
        continuity = continuity,
        historicalVisibility = historicalVisibility,
    )

    private fun directedFamily(code: String, label: String, inverse: String, vCard: String, android: String? = null, ios: String? = null) =
        directed(code, RelationshipGroup.FAMILY, label, inverse, vCard, android, ios)

    private fun social(
        code: String,
        label: String,
        vCard: String,
        quality: RelationshipMappingQuality = RelationshipMappingQuality.LOSSY,
        android: String? = null,
        ios: String? = null,
        group: RelationshipGroup = RelationshipGroup.SOCIAL,
        continuity: RelationshipContinuity = RelationshipContinuity.EXPLICIT_END_ONLY,
        historicalVisibility: HistoricalRelationshipVisibility = HistoricalRelationshipVisibility.HIDE_FROM_DEFAULT_GRAPH,
    ) = type(
        code = code,
        group = group,
        label = label,
        vCard = vCard,
        vCardQuality = quality,
        android = android,
        androidQuality = mappingQuality(android, isExact = true),
        ios = ios,
        iosQuality = if (ios == null) RelationshipMappingQuality.CUSTOM else RelationshipMappingQuality.EXACT,
        continuity = continuity,
        historicalVisibility = historicalVisibility,
    )

    private fun work(code: String, label: String, vCard: String, quality: RelationshipMappingQuality = RelationshipMappingQuality.LOSSY) = type(
        code,
        RelationshipGroup.WORK,
        label,
        vCard = vCard,
        vCardQuality = quality,
        continuity = RelationshipContinuity.CONTEXT_BOUND,
        historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
    )

    private fun directedWork(code: String, label: String, inverse: String?, vCard: String, android: String? = null, ios: String? = null) = directed(
        code,
        RelationshipGroup.WORK,
        label,
        inverse,
        vCard,
        android,
        ios,
        continuity = RelationshipContinuity.CONTEXT_BOUND,
        historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
    )

    private fun directedContext(code: String, group: RelationshipGroup, label: String, inverse: String?, vCard: String) = directed(
        code,
        group,
        label,
        inverse,
        vCard,
        continuity = RelationshipContinuity.CONTEXT_BOUND,
        historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
    )

    private fun service(code: String, label: String, vCard: String = "contact") = directed(
        code,
        RelationshipGroup.SERVICE,
        label,
        null,
        vCard,
        continuity = RelationshipContinuity.CONTEXT_BOUND,
        historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
    )

    private fun community(code: String, label: String) = type(
        code,
        RelationshipGroup.COMMUNITY,
        label,
        vCard = "acquaintance",
        continuity = RelationshipContinuity.CONTEXT_BOUND,
        historicalVisibility = HistoricalRelationshipVisibility.SHOW_AS_HISTORICAL,
    )

    private fun directed(
        code: String,
        group: RelationshipGroup,
        label: String,
        inverse: String?,
        vCard: String,
        android: String? = null,
        ios: String? = null,
        continuity: RelationshipContinuity = RelationshipContinuity.ENDURING_ORIGIN,
        historicalVisibility: HistoricalRelationshipVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
    ) = type(
        code = code,
        group = group,
        label = label,
        direction = RelationshipDirection.DIRECTED,
        inverseCode = inverse,
        vCard = vCard,
        vCardQuality = if (code in EXACT_VCARD_DIRECTED) RelationshipMappingQuality.EXACT else RelationshipMappingQuality.LOSSY,
        android = android,
        androidQuality = mappingQuality(android, code in EXACT_ANDROID_DIRECTED),
        ios = ios,
        iosQuality = if (ios == null) RelationshipMappingQuality.CUSTOM else RelationshipMappingQuality.EXACT,
        continuity = continuity,
        historicalVisibility = historicalVisibility,
    )

    @Suppress("LongParameterList")
    private fun type(
        code: String,
        group: RelationshipGroup,
        label: String,
        direction: RelationshipDirection = RelationshipDirection.SYMMETRIC,
        inverseCode: String? = null,
        vCard: String? = null,
        vCardQuality: RelationshipMappingQuality = RelationshipMappingQuality.LOSSY,
        android: String? = null,
        androidQuality: RelationshipMappingQuality = RelationshipMappingQuality.CUSTOM,
        ios: String? = null,
        iosQuality: RelationshipMappingQuality = RelationshipMappingQuality.CUSTOM,
        isSelectable: Boolean = true,
        continuity: RelationshipContinuity = RelationshipContinuity.EXPLICIT_END_ONLY,
        historicalVisibility: HistoricalRelationshipVisibility = HistoricalRelationshipVisibility.PRESERVE_LABEL,
    ) = RelationshipTypeDefinition(
        code = code,
        group = group,
        displayName = label,
        direction = direction,
        inverseCode = inverseCode,
        vCardType = vCard,
        vCardQuality = vCardQuality,
        androidTypeName = android,
        androidQuality = androidQuality,
        iosLabelName = ios,
        iosQuality = iosQuality,
        isSelectable = isSelectable,
        continuity = continuity,
        historicalVisibility = historicalVisibility,
    )

    private fun mappingQuality(mapping: String?, isExact: Boolean): RelationshipMappingQuality = when {
        mapping == null -> RelationshipMappingQuality.CUSTOM
        isExact -> RelationshipMappingQuality.EXACT
        else -> RelationshipMappingQuality.LOSSY
    }
}
