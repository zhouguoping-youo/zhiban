package com.zhiban.rebuild.data.suggestion

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 事件意图本地结构化提取器：唤醒决策器判定"未落日程的时间意图"后，
 * 用确定性规则从消息正文解析出可执行的日程要素——时间、双地点（接人/拜访）、
 * 人物、客户公司（含全称补全）、对接人候选、行程估算（出发时间）、待确认项，
 * 并生成语义化标题。
 *
 * 为什么本地而非依赖 LLM 输出 JSON：后台唤醒的 LLM 输出格式不稳定，
 * 而日程要素（尤其时间）必须精确到分钟，本地规则可测试、可兜底。
 * LLM 仍负责"判断是否值得跟进 + 给出建议正文"，结构化要素由本提取器保证。
 */
object EventIntentExtractor {

    /** 对接人候选：来自联系人库，按公司名匹配出同一家公司的联系人。 */
    data class ContactCandidate(val contactId: String, val name: String, val title: String?, val company: String?)

    /** 公司已知地址（来自联系人库 contact_addresses 或公司注册地址注册表）。 */
    data class CompanyAddress(
        val company: String,
        val address: String,
        val latitude: Double?,
        val longitude: Double?,
        /** CONTACT=联系人库地址 / REGISTRY=公司注册地址。 */
        val source: String,
    )

    /** 出发位置上下文（通常是知伴主人自己的住址/定位）。 */
    data class DepartureContext(val locationName: String?, val latitude: Double?, val longitude: Double?)

    /** 接人点坐标（协调器从接送对象联系人的地址检索）。 */
    data class PickupContext(val locationName: String?, val latitude: Double?, val longitude: Double?)

    /** 解析结果；hasScheduleIntent=false 表示正文里没有可落日程的意图。 */
    data class EventIntent(
        val hasScheduleIntent: Boolean,
        val startAtEpochMs: Long? = null,
        /** 自然语言时间描述，如「明天 09:30」。 */
        val timeDescription: String? = null,
        val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
        /** 日程主地点（拜访地点优先，其次接人地点），用于日历事件的地点字段。 */
        val location: String? = null,
        /** 接人地点，如「万科云城」。 */
        val pickupLocation: String? = null,
        /** 拜访地点（客户公司地址），可能来自消息文本或联系人库/注册地址检索。 */
        val visitLocation: String? = null,
        /** 拜访地点来源：CONTACT=联系人库地址 / REGISTRY=公司注册地址 / null=未知。 */
        val visitLocationSource: String? = null,
        /** 对接人候选（按公司从联系人库过滤，含职位）。 */
        val contactCandidates: List<ContactCandidate> = emptyList(),
        /** 建议出发时间（行程估算：出发位置→接人点→拜访点）。 */
        val departAtEpochMs: Long? = null,
        /** 行程说明，如「建议 08:45 出发（直线距离 8.2km × 1.4 路况系数…）」。 */
        val travelNote: String? = null,
        /** 接人/赴约对象（通常是消息发送者本人）。 */
        val pickupPerson: String? = null,
        /** 客户公司简称，如「九州通」。 */
        val company: String? = null,
        /** 公司全称（从联系人库/CRM 检索补全；查不到时等于简称）。 */
        val companyFull: String? = null,
        val needsConfirmation: List<String> = emptyList(),
        /** 语义化日程标题，如「明天 09:30 接周国平（万科云城）→ 拜访九州通」。 */
        val title: String? = null,
        /** 完整日程备注（时间/接人/拜访/行程/客户/候选/来源/待确认）。 */
        val note: String? = null,
    ) {
        /** 能否直接创建日历事件：有时间锚点即可（其余要素可缺省，缺失项进待确认）。 */
        val canCreateSchedule: Boolean get() = hasScheduleIntent && startAtEpochMs != null
    }

    private val PLACE_PREFIXES = listOf("我家", "家里", "我们", "公司", "单位", "这边", "那儿", "那里")
    private val PLACE_SUFFIXES = listOf(
        "云城", "家园", "小区", "大厦", "广场", "中心", "路", "街", "苑", "城", "湾", "园", "府",
        "酒店", "饭店", "医院", "学校", "大学", "公园", "车站", "机场", "码头", "桥",
    )
    private val COMPANY_SUFFIXES = listOf(
        "有限公司", "股份有限公司", "集团", "科技", "医药", "医疗", "信息", "电子", "软件", "网络",
        "生物", "能源", "实业", "控股", "研究院", "医院",
    )

    /** 与时间词共现的日程动作词（「现在下午3点了」无动作词 → 不建日程）。 */
    private val TIME_ACTION_WORD_PATTERN = Regex("(?:见|来|去|到|走|出发|开会|拜访|接|等|安排|约|碰|见?面)")

    private val PICKUP_PATTERN = Regex(
        "(?:到|去|来)([^，。,.！!？?；;的]{2,16}?)(?:接|接我|找我|接一下|来接)",
    )
    private val PICKUP_TAIL_PATTERN = Regex("([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}?)(?:接我|来接|接一下)")
    private val PLACE_ANCHOR_PATTERN = Regex(
        "(?:到|去|来|在|前往|去往)([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}?(?:${PLACE_SUFFIXES.joinToString("|")}))",
    )
    private val COMPANY_ANCHOR_PATTERN = Regex("(?:拜访|去拜访|去见|见见?|见一下|对接)")
    private val JOB_TITLE_PATTERN = Regex("(?:总经理|老总|领导|负责人|经理|客户|团队|公司|采购|院长|主任|局长|处长|部长|总)")
    private val COMPANY_NAMED_PATTERN = Regex(
        "([\\u4e00-\\u9fa5A-Za-z0-9]{2,12}?(?:${COMPANY_SUFFIXES.joinToString("|")}))",
    )
    private val BOSS_HINT_PATTERN = Regex("(?:(找|见|对接|联系)\\s*([\\u4e00-\\u9fa5]{1,3})(?:总|经理|老师|哥|姐|总|董))")

    /** 常见姓氏（用于从"公司名+人名+职位"结构里剥掉人名尾巴）。 */
    private val SURNAMES =
        "张王李赵刘陈杨黄周吴徐孙胡朱高林何郭马罗梁宋郑谢韩唐冯于董萧程曹袁邓许傅沈曾彭吕苏卢蒋蔡贾丁魏薛叶阎余潘杜戴夏钟汪田任姜范方石姚谭廖邹熊金陆郝孔白崔康毛邱秦江史顾侯邵孟龙万段雷钱汤尹黎易常武乔贺赖龚文"

    /**
     * @param body                    消息正文（已剥堆叠前缀的干净文本）
     * @param contactName             已归因联系人的显示名（通常是消息发送者）
     * @param knownCompanies          联系人库/CRM 中已知的公司全称集合（用于客户公司全称补全）
     * @param knownCompanyAddresses   联系人库/注册表中已知的公司地址（用于拜访地点检索）
     * @param contacts                联系人库精简列表（用于按公司匹配对接人候选）
     * @param departure               出发位置上下文（知伴主人自己，通常为 null 表示未知）
     * @param pickupCoordinate        接人点坐标（从接送对象联系人地址检索，可能为 null）
     * @param nowEpochMs              当前时刻
     * @param zoneId                  系统时区
     */
    fun extract(
        body: String,
        contactName: String?,
        knownCompanies: List<String>,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        knownCompanyAddresses: List<CompanyAddress> = emptyList(),
        contacts: List<ContactCandidate> = emptyList(),
        departure: DepartureContext? = null,
        pickupCoordinate: PickupContext? = null,
        authoritativeStartAtEpochMs: Long? = null,
        authoritativeDurationMinutes: Int? = null,
        authoritativeTitle: String? = null,
        fallbackTimeExpression: String? = null,
        forceScheduleIntent: Boolean = false,
    ): EventIntent {
        val text = body.trim()
        if (text.isBlank()) return EventIntent(hasScheduleIntent = false)

        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), zoneId)
        val timeResolution = authoritativeStartAtEpochMs?.let { startAt ->
            ScheduleTimeParser.Resolution(
                dateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startAt), zoneId),
                explicitDate = true,
                explicitTime = true,
            )
        } ?: ScheduleTimeParser.resolve(
            text = text,
            now = now,
            allowTimeOnly = TIME_ACTION_WORD_PATTERN.containsMatchIn(text),
            defaultTimeForDate = true,
        ) ?: fallbackTimeExpression?.let {
            ScheduleTimeParser.resolve(
                text = it,
                now = now,
                allowTimeOnly = true,
                defaultTimeForDate = false,
            )
        }
        val start = timeResolution?.dateTime
        val pickupLocation = extractPickupLocation(text)
        val company = extractCompany(text)
        val companyFull = resolveCompanyFull(company, knownCompanies)
        val pickup = extractPickupPerson(text, contactName, pickupLocation)

        // 拜访地点检索链：联系人库/注册地址 → 未知进待确认
        val visitAddress = company?.let { resolveVisitAddress(it, knownCompanyAddresses) }
        val visitLocation = visitAddress?.address
        val visitLocationSource = visitAddress?.source

        // 对接人候选：按公司简称匹配联系人库
        val candidates = company?.let { c ->
            contacts.filter { cand ->
                cand.company?.isNotBlank() == true && cand.company!!.contains(c)
            }.take(5)
        } ?: emptyList()

        // 行程估算：出发位置→接人点→拜访点
        val (departAt, travelNote) = estimateDeparture(start, company != null, departure, pickupCoordinate, visitAddress, zoneId)

        val confirmations = buildList {
            if (forceScheduleIntent && start == null) add("时间未识别，请手动补充")
            if (locationIsBlank(pickupLocation)) add("接人地点不明确，建议与${contactName ?: "对方"}确认")
            if (company != null && visitLocation == null) {
                add("拜访地点未知（$company 的地址），建议确认")
            }
            if (company != null && !looksLikeNamedPersonMentioned(text)) {
                if (candidates.isNotEmpty()) {
                    add("拜访对象待确认（候选：${candidates.joinToString("、") { it.name }}），建议确认后再定")
                } else {
                    add("拜访对象未提及（$company 的对接人），建议与${contactName ?: "发起人"}确认后再定")
                }
            }
            if (company != null && companyFull == company) {
                add("「$company」未在联系人库中检索到公司全称，建议确认")
            }
            if (company != null && departure == null) {
                add("出发位置未知，建议确认")
            }
        }

        if (start == null && pickupLocation == null && company == null && pickup == null && !forceScheduleIntent) {
            return EventIntent(hasScheduleIntent = false)
        }

        val timeDescription = start?.let { describeTime(it, now) }
        val hasScheduleAnchor = start != null
        val pickupAction = pickup?.let { base ->
            if (pickupLocation != null) "接$base（$pickupLocation）" else "接$base"
        }
        val visitAction = company?.let { "拜访$it" }
        val actions = listOfNotNull(pickupAction, visitAction)
            .joinToString(if (pickupAction != null && visitAction != null) " → " else " · ")
        val title = if (actions.isBlank() && !authoritativeTitle.isNullOrBlank()) {
            authoritativeTitle.trim().take(60)
        } else {
            buildTitle(timeDescription, actions)
        }
        val note = buildNote(
            timeDescription = timeDescription,
            pickupLocation = pickupLocation,
            visitLocation = visitLocation,
            pickup = pickup,
            companyFull = companyFull,
            candidates = candidates,
            travelNote = travelNote,
            contactName = contactName,
            confirmations = confirmations,
        )
        return EventIntent(
            hasScheduleIntent = forceScheduleIntent || hasScheduleAnchor,
            startAtEpochMs = start?.atZone(zoneId)?.toEpochSecond()?.times(1_000),
            timeDescription = timeDescription,
            durationMinutes = authoritativeDurationMinutes
                ?: if (company != null) VISIT_DURATION_MINUTES else DEFAULT_DURATION_MINUTES,
            location = visitLocation ?: pickupLocation,
            pickupLocation = pickupLocation,
            visitLocation = visitLocation,
            visitLocationSource = visitLocationSource,
            contactCandidates = candidates,
            departAtEpochMs = departAt,
            travelNote = travelNote,
            pickupPerson = pickup,
            company = company,
            companyFull = companyFull,
            needsConfirmation = confirmations,
            title = title,
            note = note,
        )
    }

    private fun describeTime(dt: LocalDateTime, now: ZonedDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), dt.toLocalDate())
        val dayPart = when {
            days == 0L -> "今天"
            days == 1L -> "明天"
            days == 2L -> "后天"
            days == 3L -> "大后天"
            else -> dt.format(DateTimeFormatter.ofPattern("M月d日"))
        }
        return "$dayPart ${dt.format(TIME_FORMATTER)}"
    }

    // ---- 地点 / 人物 / 公司 ----

    private fun extractPickupLocation(text: String): String? {
        PICKUP_PATTERN.find(text)?.let { m ->
            cleanPlace(m.groupValues[1])?.let { return it }
        }
        PICKUP_TAIL_PATTERN.find(text)?.let { m ->
            cleanPlace(m.groupValues[1])?.let { return it }
        }
        // 兜底：从"到/去/来"等动词锚点后找含常见后缀的地名词，避免从全文开头误扫
        PLACE_ANCHOR_PATTERN.find(text)?.let { m ->
            cleanPlace(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun cleanPlace(raw: String): String? {
        var s = raw.trim()
        PLACE_PREFIXES.forEach { prefix ->
            if (s.startsWith(prefix)) s = s.removePrefix(prefix)
        }
        s = s.trim { it in "到去来的" }
        return s.takeIf { it.length in 2..16 }
    }

    private fun locationIsBlank(location: String?): Boolean = location == null || location.isBlank()

    private fun extractCompany(text: String): String? {
        // 1) 语义锚点（拜访/见/对接）+ 职位词定位：职位词之前即为公司名（可能带"的<人名>"尾巴）
        COMPANY_ANCHOR_PATTERN.find(text)?.let { anchor ->
            val tail = text.substring(anchor.range.last + 1)
            val job = JOB_TITLE_PATTERN.find(tail)
            if (job != null && job.range.first > 0) {
                val candidate = stripPersonSuffix(tail.substring(0, job.range.first))
                if (candidate.length in 2..20) return candidate
            }
        }
        // 2) 兜底：公司后缀词（有限公司/集团/科技…）
        COMPANY_NAMED_PATTERN.find(text)?.let { m ->
            val raw = stripPersonSuffix(m.groupValues[1])
            if (raw.length in 2..16) return raw
        }
        return null
    }

    /** 剥"公司名+职位"结构里的人名尾巴：如「九州通张」→「九州通」、「天马科技的陈」→「天马科技」。 */
    private fun stripPersonSuffix(seg: String): String {
        var s = seg.trim { it in "的" }
        // 「天马科技的陈」：剥"的<人名>"
        val withDe = Regex("^(.*?)的[\\u4e00-\\u9fa5]{1,3}$").find(s)
        if (withDe != null && withDe.groupValues[1].length >= 2) {
            s = withDe.groupValues[1]
        }
        // 「九州通张」：结尾 1 字是常见姓氏 → 剥（公司名至少剩 2 字）
        if (s.length >= 3 && SURNAMES.contains(s.last().toString())) {
            s = s.dropLast(1)
        }
        return s
    }

    /** 用联系人库/CRM 已知公司做全称补全：包含简称 → 取最长的全称。 */
    private fun resolveCompanyFull(company: String?, knownCompanies: List<String>): String? {
        if (company == null) return null
        val matches = knownCompanies
            .filter { it.isNotBlank() && it.contains(company) }
            .sortedByDescending(String::length)
        return matches.firstOrNull() ?: company
    }

    /** 拜访地点检索：已知公司地址里按「含简称」匹配，取地址（来源区分联系人库/注册表）。 */
    private fun resolveVisitAddress(company: String, knownCompanyAddresses: List<CompanyAddress>): CompanyAddress? = knownCompanyAddresses
        .filter { it.company.isNotBlank() && it.company.contains(company) }
        .sortedByDescending { it.address.length }
        .firstOrNull()

    private fun extractPickupPerson(text: String, contactName: String?, location: String?): String? {
        // 有"接"的语义且已归因联系人：接送对象就是消息发送者
        if (location != null && (text.contains("接") || text.contains("接我"))) return contactName
        return null
    }

    private fun looksLikeNamedPersonMentioned(text: String): Boolean = BOSS_HINT_PATTERN.containsMatchIn(text) ||
        Regex("([\\u4e00-\\u9fa5]{2,3})(?:总|经理|老师|哥|姐|董|院长|主任|局长|处长|部长)").containsMatchIn(text)

    // ---- 行程估算 ----

    /**
     * 估算出发时间与行程说明。优先用坐标链（出发→接人→拜访）按直线距离×路况系数；
     * 无坐标时按「市区单程 30 分钟 + 接人等待 15 分钟」经验值。
     * @return (建议出发 epochMs, 行程说明)；start 为 null 时返回 (null, null)
     */
    private fun estimateDeparture(
        start: LocalDateTime?,
        hasVisit: Boolean,
        departure: DepartureContext?,
        pickup: PickupContext?,
        visit: CompanyAddress?,
        zoneId: ZoneId,
    ): Pair<Long?, String?> {
        if (start == null) return null to null

        // 收集带坐标的停靠点（保持 出发→接人→拜访 顺序）
        data class Stop(val label: String, val lat: Double, val lon: Double)
        val stops = buildList {
            departure?.takeIf { it.latitude != null && it.longitude != null }
                ?.let { add(Stop("出发地", it.latitude!!, it.longitude!!)) }
            pickup?.takeIf { it.latitude != null && it.longitude != null }
                ?.let { add(Stop("接人点", it.latitude!!, it.longitude!!)) }
            visit?.takeIf { it.latitude != null && it.longitude != null }
                ?.let { add(Stop("拜访点", it.latitude!!, it.longitude!!)) }
        }

        val driveMinutes: Int
        val basis: String
        if (stops.size >= 2) {
            var totalKm = 0.0
            for (i in 0 until stops.size - 1) {
                totalKm += haversineKm(stops[i].lat, stops[i].lon, stops[i + 1].lat, stops[i + 1].lon)
            }
            driveMinutes = (totalKm / 25.0 * 60.0 * ROAD_FACTOR).toInt().coerceAtLeast(5)
            basis = "坐标 ${"%.1f".format(totalKm)}km 直线 × ${ROAD_FACTOR} 路况系数，车程约 $driveMinutes 分钟"
        } else {
            driveMinutes = DEFAULT_LEG_MINUTES * (if (hasVisit) 2 else 1) + WAIT_BUFFER_MINUTES
            basis = if (hasVisit) {
                "无坐标，按市区单程 30 分钟 + 接人等待 15 分钟经验估算"
            } else {
                "无坐标，按市区 30 分钟车程经验估算"
            }
        }

        val depart = start.minusMinutes(driveMinutes.toLong())
        val note = "建议 ${depart.format(TIME_FORMATTER)} 出发（$basis）"
        return depart.atZone(zoneId).toEpochSecond() * 1_000 to note
    }

    /** Haversine 球面距离（公里）。 */
    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    // ---- 标题 / 备注 ----

    private fun buildTitle(timeDescription: String?, actions: String): String? = listOfNotNull(timeDescription, actions.takeIf(String::isNotBlank))
        .joinToString(" ")
        .replace("  ", " ")
        .take(60)
        .takeIf(String::isNotBlank)

    private fun buildNote(
        timeDescription: String?,
        pickupLocation: String?,
        visitLocation: String?,
        pickup: String?,
        companyFull: String?,
        candidates: List<ContactCandidate>,
        travelNote: String?,
        contactName: String?,
        confirmations: List<String>,
    ): String? = buildList {
        timeDescription?.let { add("时间：$it") }
        pickupLocation?.let { add("接人：$it") }
        if (visitLocation != null) add("拜访：$visitLocation")
        if (pickup != null) {
            val target = pickupLocation ?: "约定地点"
            add("行程：先到$target 接$pickup，再一同前往${companyFull ?: "客户"}")
        }
        travelNote?.let { add(it) }
        if (candidates.isNotEmpty()) {
            add("对接人候选：${candidates.joinToString("、") { "${it.name}${it.title?.let { t -> "（$t）" } ?: ""}" }}")
        }
        companyFull?.let { add("客户：$it") }
        contactName?.let { add("来源：$it 的微信消息") }
        confirmations.takeIf(List<String>::isNotEmpty)?.let { add("待确认：${it.joinToString("；")}") }
    }.joinToString("\n").takeIf(String::isNotBlank)

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

    private const val DEFAULT_DURATION_MINUTES = 90
    private const val VISIT_DURATION_MINUTES = 120
    private const val ROAD_FACTOR = 1.4
    private const val WAIT_BUFFER_MINUTES = 15
    private const val DEFAULT_LEG_MINUTES = 30
}
