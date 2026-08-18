package com.zhiban.rebuild.data.contact

/** 闭环可向联系人索要补全的 7 个资料字段。[label] 供 UI 与起草提示使用。 */
enum class ContactProfileField(val label: String) {
    NAME("真实姓名"),
    PHONE("手机号"),
    WECHAT("微信号"),
    COMPANY("现公司"),
    TITLE("职位"),
    RESPONSIBILITIES("职责"),
    EMAIL("邮箱"),
}

/** 一个联系人的完整性：[missingFields] 为仍缺失的目标字段（按枚举声明顺序）。 */
data class ContactCompleteness(val contact: ContactEntity, val missingFields: List<ContactProfileField>) {
    val isComplete: Boolean get() = missingFields.isEmpty()
}

/**
 * 资料完整性评估：判断联系人在闭环 7 个目标字段（[ContactProfileField]）上还缺哪些。纯逻辑、可测。
 *
 * 与 [ContactMaintenanceModel] 的质量向量是两个概念——那是诊断评分，这里是逐字段"缺什么"，
 * 用来驱动"资料待补全"列表与后续的补全触达（contact_completion_requests）。
 */
object ContactProfileCompletenessEvaluator {

    /** 该联系人仍缺的目标字段。[contactIdentities] 须是这个联系人自身的平台身份（非全表）。 */
    fun missingFields(contact: ContactEntity, contactIdentities: List<ContactPlatformIdentityEntity>): List<ContactProfileField> =
        ContactProfileField.entries.filter { isMissing(it, contact, contactIdentities) }

    fun evaluate(contact: ContactEntity, contactIdentities: List<ContactPlatformIdentityEntity>): ContactCompleteness =
        ContactCompleteness(contact, missingFields(contact, contactIdentities))

    /** 所有仍有缺失字段的联系人，缺得少的排前（最接近完整、最容易补全的优先）。 */
    fun incomplete(contacts: List<ContactEntity>, platformIdentities: List<ContactPlatformIdentityEntity>): List<ContactCompleteness> {
        val identitiesByContact = platformIdentities.groupBy(ContactPlatformIdentityEntity::contactId)
        return contacts
            .map { evaluate(it, identitiesByContact[it.contactId].orEmpty()) }
            .filter { !it.isComplete }
            .sortedWith(compareBy({ it.missingFields.size }, { it.contact.normalizedName }))
    }

    private fun isMissing(field: ContactProfileField, contact: ContactEntity, contactIdentities: List<ContactPlatformIdentityEntity>): Boolean = when (field) {
        ContactProfileField.NAME -> contact.displayName.isBlank()

        ContactProfileField.PHONE -> contact.phone.isNullOrBlank()

        // 微信 stub 的身份是 handle（=显示名）而非 wechatId，只看 contacts.wechatId 会把 stub 误判为缺微信；
        // 因此只要存在一条 WECHAT 平台身份（handle 可达）就算"微信可达"、不算缺失。
        ContactProfileField.WECHAT ->
            contact.wechatId.isNullOrBlank() && contactIdentities.none { it.platform == PLATFORM_WECHAT }

        ContactProfileField.COMPANY -> contact.company.isNullOrBlank()

        ContactProfileField.TITLE -> contact.title.isNullOrBlank()

        ContactProfileField.RESPONSIBILITIES -> contact.responsibilities.isNullOrBlank()

        ContactProfileField.EMAIL -> contact.email.isNullOrBlank()
    }

    private const val PLATFORM_WECHAT = "WECHAT"
}
