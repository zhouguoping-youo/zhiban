package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.data.contact.ContactEntity

/**
 * 本地规则关系推断（零 LLM 兜底）：
 *
 * 背景：关系图谱的「有联系 · 来自消息互动」只是只读投影边，真正的关系类型（家人/客户/朋友…）
 * 依赖 LLM 推断；未配置 provider 时 inferFromInteractions 直接返回，图谱永远只有「有联系」。
 * 本启发式从互动文本 + 联系人资料用确定性规则给出关系类型候选，让没有 LLM 也能落边/出建议卡。
 *
 * 口径对齐 RelationshipInferenceCoordinator：
 * - 强信号（称谓/商务高频词）→ 高置信（0.9+），达到 AUTO_APPLY_CONFIDENCE 可自动写可撤销边；
 * - 弱信号 → 中置信（0.8 左右），落「智能完善」建议卡由用户拍板；
 * - 未命中任何规则 → null，仍走 LLM 推断。
 *
 * 只做「最保守的语义归类」，绝不用单条消息猜具体人物关系（如「王经理」→ 客户 而非 上下级）。
 */
internal object LocalRelationshipHeuristics {

    /** 强亲属称谓：明确指向血缘/婚姻关系。注意 corpus 已去空白，词条不得含空格。 */
    private val KINSHIP_TERMS = listOf(
        "爸爸", "妈妈", "老爸", "老妈", "爸", "妈", "爹", "娘",
        "哥哥", "弟弟", "姐姐", "妹妹", "大哥", "二哥", "小弟",
        "老公", "老婆", "媳妇", "丈夫", "妻子", "爱人",
        "儿子", "女儿", "闺女", "孙子", "孙女",
        "爷爷", "奶奶", "外公", "外婆", "姥姥", "姥爷",
        "叔叔", "阿姨", "舅舅", "舅妈", "姑姑", "姑妈", "姨妈", "伯父", "伯母",
    )

    /** 客户强信号：商务往来高频词。 */
    private val CUSTOMER_TERMS = listOf(
        "报价", "合同", "回款", "付款", "采购", "订单", "招标", "投标",
        "方案", "项目", "签约", "发票", "对公", "商务", "合作", "客户",
        "预算", "签约仪式", "回访", "拜访", "对接",
    )

    /** 供应商/服务方信号：供货、物流、到货。 */
    private val SUPPLIER_TERMS = listOf(
        "供货", "发货", "到货", "物流", "快递单号", "进货", "库存", "厂家",
        "供应商", "发货单", "送货",
    )

    /** 朋友/社交信号。 */
    private val FRIEND_TERMS = listOf(
        "吃饭", "聚会", "聚餐", "打球", "好久不见", "兄弟", "姐妹", "哥们",
        "约饭", "撸串", "烧烤", "K歌", "唱歌", "逛街", "旅游", "一起玩",
        "有空吗", "出来坐坐", "聚聚",
    )

    /** 同学/校友信号。 */
    private val CLASSMATE_TERMS = listOf(
        "同学",
        "校友",
        "学校",
        "毕业",
        "班级",
        "班主任",
        "同桌",
        "宿舍",
    )

    /** 师生角色不是同学证据；当前关系枚举未覆盖时交给 LLM 保守判断。 */
    private val EDUCATION_ROLE_TERMS = listOf("老师", "教授", "导师", "教练", "学生")

    /** 同事信号：公司/单位/部门/开会等共事语义。 */
    private val COLLEAGUE_TERMS = listOf(
        "公司", "单位", "部门", "开会", "例会", "工位", "同事", "上班",
        "项目组", "周报", "日报", "KPI", "绩效",
    )

    /**
     * 综合互动文本 + 联系人资料推断关系类型。
     *
     * @return 命中规则返回 InferredRelationship；未命中返回 null（交给 LLM）。
     */
    internal fun infer(contact: ContactEntity, evidence: String): InferredRelationship? {
        val text = evidence.orEmpty().replace(Regex("\\s+"), "")
        val contactName = contact.displayName.orEmpty()
        // 联系人备注/公司/职位拼进语料：如备注「客户王总」直接命中客户。
        val meta = listOfNotNull(
            contact.company?.takeIf(String::isNotBlank),
            contact.title?.takeIf(String::isNotBlank),
            contact.tagsJson?.takeIf(String::isNotBlank),
            contact.note?.takeIf(String::isNotBlank),
        ).joinToString(" ")
        val corpus = "$text $contactName $meta"

        // 亲属称谓是最高置信（0.95）——「我爸」「我姐」几乎不可能是别的关系。
        if (KINSHIP_TERMS.any { corpus.contains(it) }) {
            return InferredRelationship(
                relationType = "FAMILY",
                confidence = 0.95,
                evidence = "互动中出现亲属称谓",
            )
        }
        // 客户与供应商二选一：客户词更常见，两者都有时偏向客户（销售场景默认）。
        val customerHit = CUSTOMER_TERMS.count { corpus.contains(it) }
        val supplierHit = SUPPLIER_TERMS.count { corpus.contains(it) }
        if (customerHit > 0 || supplierHit > 0) {
            val isCustomer = customerHit >= supplierHit
            return InferredRelationship(
                relationType = if (isCustomer) "CUSTOMER" else "SUPPLIER",
                confidence = if (customerHit + supplierHit >= 2) 0.92 else 0.85,
                evidence = if (isCustomer) "互动出现商务往来词（报价/合同/对接等）" else "互动出现供货/物流词",
            )
        }
        // 师生角色不能降级成同学；缺少明确师生关系枚举时不做本地猜测。
        if (EDUCATION_ROLE_TERMS.any { corpus.contains(it) }) return null
        // 同学/校友信号 0.88。
        if (CLASSMATE_TERMS.any { corpus.contains(it) }) {
            return InferredRelationship(
                relationType = "CLASSMATE",
                confidence = 0.88,
                evidence = "互动出现同学/校友/班级词",
            )
        }
        // 同事信号：联系人公司字段与本人公司一致的强同事边由 applyCompanyColleagueEdges 处理；
        // 这里只覆盖文本里的共事语义（开会/部门/周报），置信 0.82——低于自动写阈值，落建议卡。
        if (COLLEAGUE_TERMS.any { corpus.contains(it) }) {
            return InferredRelationship(
                relationType = "COLLEAGUE",
                confidence = 0.82,
                evidence = "互动出现共事语义（公司/部门/开会等）",
            )
        }
        // 朋友/社交信号 0.8——吃饭聚会不代表长期关系，保守落建议卡。
        if (FRIEND_TERMS.any { corpus.contains(it) }) {
            return InferredRelationship(
                relationType = "FRIEND",
                confidence = 0.8,
                evidence = "互动出现社交词（吃饭/聚会/约玩等）",
            )
        }
        return null
    }
}
