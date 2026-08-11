package com.zhiban.rebuild.relationship

enum class RelationshipGroup(val displayName: String) {
    FAMILY("家人"),
    ROMANTIC("伴侣"),
    SOCIAL("朋友同学"),
    WORK("工作"),
    EDUCATION("教育"),
    SERVICE("生活服务"),
    COMMUNITY("组织社区"),
    OTHER("其他"),
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
) {
    val extensionType: String = "x-zhiban-${code.lowercase().replace('_', '-')}"
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
        family("SPOUSE", "配偶", "spouse", android = "TYPE_SPOUSE", ios = "CNLabelContactRelationSpouse"),
        type(
            code = "PARTNER",
            group = RelationshipGroup.ROMANTIC,
            label = "伴侣",
            vCard = "sweetheart",
            vCardQuality = RelationshipMappingQuality.LOSSY,
            android = "TYPE_PARTNER",
            ios = "CNLabelContactRelationPartner",
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
        type("FIANCE", RelationshipGroup.ROMANTIC, "未婚伴侣", vCard = "date"),
        type("DATE", RelationshipGroup.ROMANTIC, "约会对象", vCard = "date", vCardQuality = RelationshipMappingQuality.EXACT),
        type("CRUSH", RelationshipGroup.ROMANTIC, "喜欢的人", vCard = "crush", vCardQuality = RelationshipMappingQuality.EXACT),
        social("FRIEND", "朋友", "friend", android = "TYPE_FRIEND", ios = "CNLabelContactRelationFriend"),
        social("CLOSE_FRIEND", "密友", "friend"),
        social("CHILDHOOD_FRIEND", "发小", "friend"),
        social("CLASSMATE", "同学", "friend"),
        social("SCHOOLMATE", "校友", "acquaintance"),
        social("ROOMMATE", "室友", "co-resident", RelationshipMappingQuality.EXACT),
        social("FELLOW_TOWNSMAN", "老乡", "acquaintance"),
        social("NEIGHBOR", "邻居", "neighbor", RelationshipMappingQuality.EXACT),
        social("ONLINE_FRIEND", "网友", "acquaintance"),
        social("TEAMMATE", "队友", "friend"),
        social("HOBBY_FRIEND", "兴趣圈友", "acquaintance"),
        social("TRAVEL_COMPANION", "旅行伙伴", "acquaintance"),
        social("ACQUAINTANCE", "熟人", "acquaintance", RelationshipMappingQuality.EXACT),
        social("MET", "见过", "met", RelationshipMappingQuality.EXACT),
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
        directed("TEACHER", RelationshipGroup.EDUCATION, "老师", "STUDENT", "acquaintance"),
        directed("STUDENT", RelationshipGroup.EDUCATION, "学生", "TEACHER", "acquaintance"),
        directed("COACH", RelationshipGroup.EDUCATION, "教练", null, "acquaintance"),
        social("KIDS_PARENT", "孩子同学的家长", "acquaintance", group = RelationshipGroup.EDUCATION),
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
        directed("LANDLORD", RelationshipGroup.SERVICE, "房东", "TENANT", "contact"),
        directed("TENANT", RelationshipGroup.SERVICE, "租客", "LANDLORD", "contact"),
        service("VETERINARIAN", "宠物医生"),
        community("CLUB_MEMBER", "社团成员"),
        community("COMMUNITY_ORGANIZER", "社群组织者"),
        community("RELIGIOUS", "宗教社群联系人"),
        community("VOLUNTEER", "公益伙伴"),
        community("GROUP_MEMBER", "群友"),
        directed("EMERGENCY_CONTACT", RelationshipGroup.OTHER, "紧急联系人", null, "emergency"),
        directed("AUTHORIZED_AGENT", RelationshipGroup.OTHER, "授权代理人", null, "agent"),
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
        return when (code.uppercase()) {
            "COLLEAGUE" -> "前同事"
            "MANAGER" -> "前上级"
            "SUBORDINATE" -> "前下属"
            "CUSTOMER" -> "前客户"
            "SUPPLIER" -> "前供应商"
            "PROJECT_PARTNER" -> "前项目伙伴"
            "BUSINESS_PARTNER", "CHANNEL_PARTNER" -> "前合作伙伴"
            "PARTNER" -> "前任伴侣"
            "SPOUSE" -> "前配偶"
            "FRIEND", "CLOSE_FRIEND", "CHILDHOOD_FRIEND" -> "曾是朋友"
            "ROOMMATE" -> "前室友"
            "NEIGHBOR" -> "以前的邻居"
            "TEACHER" -> "曾任老师"
            "STUDENT" -> "曾是学生"
            "COACH" -> "曾任教练"
            "LANDLORD" -> "前房东"
            "TENANT" -> "前租客"
            else -> displayName(code)
        }
    }

    fun definitionsFor(group: RelationshipGroup): List<RelationshipTypeDefinition> = selectableDefinitions.filter { it.group == group }

    private fun family(code: String, label: String, vCard: String, android: String? = null, ios: String? = null) = type(
        code = code,
        group = RelationshipGroup.FAMILY,
        label = label,
        vCard = vCard,
        vCardQuality = if (code in EXACT_VCARD_FAMILY) RelationshipMappingQuality.EXACT else RelationshipMappingQuality.LOSSY,
        android = android,
        androidQuality = mappingQuality(android, code in EXACT_ANDROID_FAMILY),
        ios = ios,
        iosQuality = if (ios == null) RelationshipMappingQuality.CUSTOM else RelationshipMappingQuality.EXACT,
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
    )

    private fun work(code: String, label: String, vCard: String, quality: RelationshipMappingQuality = RelationshipMappingQuality.LOSSY) =
        type(code, RelationshipGroup.WORK, label, vCard = vCard, vCardQuality = quality)

    private fun directedWork(code: String, label: String, inverse: String?, vCard: String, android: String? = null, ios: String? = null) =
        directed(code, RelationshipGroup.WORK, label, inverse, vCard, android, ios)

    private fun service(code: String, label: String, vCard: String = "contact") = directed(code, RelationshipGroup.SERVICE, label, null, vCard)

    private fun community(code: String, label: String) = type(code, RelationshipGroup.COMMUNITY, label, vCard = "acquaintance")

    private fun directed(
        code: String,
        group: RelationshipGroup,
        label: String,
        inverse: String?,
        vCard: String,
        android: String? = null,
        ios: String? = null,
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
    )

    private fun mappingQuality(mapping: String?, isExact: Boolean): RelationshipMappingQuality = when {
        mapping == null -> RelationshipMappingQuality.CUSTOM
        isExact -> RelationshipMappingQuality.EXACT
        else -> RelationshipMappingQuality.LOSSY
    }
}
