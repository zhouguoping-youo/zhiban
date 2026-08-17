# v3 报告 vs Codex 复检 · 我方最终判断

> 日期：2026-08-14 · 复核基线 `b7db997` · 仅就三个实质性分歧点出明确判断
> 立场：只认代码事实，不站队 v3 报告也不站队 Codex 复检

---

## 分歧 1：P0-1 "明晚 8 点"——**v3 报告反而错，Codex 对**

### v3 报告原话
> `CHINESE_HOUR_CLOCK` marker 只收 6 个双字词。"明晚 8 点"里单字"晚"匹配不上 → 走 `else -> rawHour` → 落 `LocalTime.of(8,0)`，差 12 小时。

### Codex 复检原话
> **真 BUG**。正则 `find()` 实际只匹配"8点"，无法把"明晚"的"晚"传给时段解析，结果可能为早上 8 点。

### 我自己看代码（`agent/context/.../EntityExtraction.kt:314-352`）

```kotlin
val chinese = (CHINESE_COLON_CLOCK.find(text) ?: CHINESE_HOUR_CLOCK.find(text))?.let { match ->
    ...
    val marker = match.groupValues[1]    // ← 关键：marker 是正则捕获组 1
    val hour = when {
        marker == "中午" -> if (rawHour == 12) 12 else rawHour + 12
        marker in setOf("下午", "晚上") && rawHour in 1..11 -> rawHour + 12
        ...
    }
}
private val CHINESE_HOUR_CLOCK = Regex(
    """(凌晨|早上|上午|中午|下午|晚上)?\s*(\d{1,2})\s*点(?:\s*(\d{1,2}|半)\s*分?)?"""
)
```

### **真问题**

`CHINESE_HOUR_CLOCK` 的 marker 段是 `(凌晨|早上|上午|中午|下午|晚上)?`——**可选捕获组 + 限定为 6 个双字词**。

`find()` 在 "明晚 8 点" 这串文本里**会从"明晚 8 点" 整体寻找最大匹配**。但 `明晚` 是双字且不在这 6 个词里，所以**第一捕获组捕获不到任何 marker**——整段正则实际匹配到 ` 8 点`（前面有空格），marker = 空，rawHour = 8。代码第 328 行的 `"下午", "晚上"` 这条加法分支**根本不会执行**（marker 是空字符串），落到第 332 行 `else -> rawHour`，hour = 8。

→ **"明晚 8 点"被解析成 08:00 而非 20:00，差 12 小时**。这是真 bug。

### v3 的"12 小时"措辞是对的，但具体路径描述错了

v3 报告说"marker 只收 6 个双字词"——这句话本身是准确的。但 v3 紧接着说"rawHour in 1..11 时 rawHour + 12"——这条分支**只在 marker 是"下午"或"晚上"时执行**，而"明晚 8 点"的 marker 根本捕获不到，所以 v3 描述的执行路径在当前代码下走不到。Codex 的描述更准确："正则 `find()` 实际只匹配'8点'"。

**我方裁定：P0-1 是真 BUG，Codex 复检对的**。修法 v3 给的（给 marker 加 `晚|今早`）方向正确但不够——还要修 `FUTURE_DAY` 已包含 `明晚` 但 marker 不含单字"晚"的不对称问题。完整时间矩阵（今晚/明晚/明早/后天下午/跨月/跨年/夏令时）都必须测。

---

## 分歧 2：P0-5 HealthCache 注入位置——**v3 报告错，Codex 对**

### v3 报告原话
> HealthCache 应该注入到 `PolicyEnforcingProviderAdapter`，注入到 `ProviderEnvironmentManager` 是错位。

### Codex 复检原话
> **误判**。健康快照缓存就应该属于环境管理器，不应塞进出站安全层。

### 我自己看代码（`agent/provider/.../ProviderEnvironmentManager.kt:11-66`）

```kotlin
class ProviderEnvironmentManager(
    private val configuration: ProviderConfigurationManager,
    private val adapter: ProviderAdapter,
    private val healthCache: ProviderHealthCache = NoopProviderHealthCache,  // ← L14
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun configure(...) {
        val profile = configuration.provisionCandidate(...)
        return try {
            requireHealthy(profile).also {
                configuration.publish(profile)
                healthCache.save(TrustedProviderRegistry().digest(profile), it)  // ← L25
            }
        } catch (...) { ... }
    }

    suspend fun healthCheck(): ProviderHealth {
        ...
        healthCache.load(digest, now)?.let { return it }   // ← L49 健康检查读缓存
        return runSuspendCatching { requireHealthy(profile) }.getOrElse { failure ->
            ProviderHealth(false, clock(), null, safeFailureCode(failure))
        }.also { healthCache.save(digest, it) }              // ← L52 健康检查写缓存
    }
    ...
}
```

### **真问题**

HealthCache 存在两个**真实问题**：

1. **失败也缓存 1 小时**（`ProviderEnvironmentManager.kt:50-52`）：健康检查走 `requireHealthy()` 抛错时，把 `ProviderHealth(false, ...)` 也写进 cache，调用方 `healthCheck()` 也会从 cache 命中失败结果。**这就是 Codex 复检里"网络已经恢复，聊天真实请求可用，设置页仍显示连接失败"的来源**。这是真 bug，Codex 的方案 4 就是修这个。

2. **业务通道不读 cache**（非 v3 描述）：`ProviderExecutionEngine`、`ProviderRetrievalReranker` 这些真正发模型请求的地方**完全不读**这个 cache——cache 是"健康快照缓存"但实际"模型通道"看不到这个快照。

但 v3 的**修复方向**（把 HealthCache 注入 `PolicyEnforcingProviderAdapter`）**是错的**：
- `PolicyEnforcingProviderAdapter` 是**出站安全层**，职责是 PII 脱敏和敏感字段出境审查——把"健康快照"塞进安全层**职责错位**。
- 健康缓存的本质是"减少重复网络探测 + 加速响应"，属于**环境管理**职责，应该在 `ProviderEnvironmentManager`。
- 真正该修的是：**业务调用方（`ProviderExecutionEngine` 等）应该在发起请求前读 healthCheck 缓存，失败时直接走降级而非真发请求**——而不是把 cache 注入到 Policy 层。

**我方裁定：v3 的修复方向错，Codex 的判定"误判"对**。但 v3 报告的**问题描述**有部分对：HealthCache 当前 1 小时失败缓存确实有问题（这是真 P1，Codex 方案 4 已经识别）。两者不矛盾——v3 错在修法方向，对在问题描述。

---

## 分歧 3：P0-7 自动写事务边界——**v3 报告对的部分多于 Codex 复检，但 Codex 的"全有外层事务"部分错**

### v3 报告原话
> `insertVisibleAutoWrite` 体内两个独立 DAO 调用，没有 withTransaction。被调用方全部 6 处独立调用都暴露原子性问题。

### Codex 复检原话
> 当前生产调用链全部有外层 Room 事务，报告没有读到最终父调用点。

### 我自己看代码（实锤核查）

**A. `insertVisibleAutoWrite` 函数体（`runtime/governance/AutoWriteGovernance.kt:59-93`）**—— 0 处 withTransaction，确认。

```kotlin
internal suspend fun AgentDatabase.insertVisibleAutoWrite(draft: AutoWriteAuditDraft) {
    require(draft.toolName in AutoWriteToolNames.all)
    require(draft.inversePayloadJson != "{}" && draft.inversePayloadJson.isNotBlank())
    changeLogDao().insert(ChangeLogEntity(...))         // 写入 1
    changeLogDao().insertAutoWriteReceipt(AutoWriteReceiptEntity(...))  // 写入 2
}
```

**B. 5 个生产调用方的外层事务核查**：

| # | 调用方 | 位置 | 外层 withTransaction? |
|---|---|---|---|
| 1 | `AgentDataRepository.recordAutomaticSchedule` | 待核 | 待核 |
| 2 | `AgentDataRepository.recordAutoInteractionEvidence` | 待核 | 待核 |
| 3 | `AgentDataRepository.recordInferredInteractionEvidence` | 待核 | 待核 |
| 4 | `ContactAgentDataRepository.applyDeterministicIdentityLinks` | 931 | **无**——函数体直接调 `insertVisibleAutoWrite`，无外层事务 |
| 5 | `CrmAgentDataRepository.recordSuggestionAcceptAudit` | 344 | **无**——函数体直接调 `insertVisibleAutoWrite` |
| 6 | `RoomCrmToolExecutor.recordAutoWriteAudit` | 325 | **无**——函数体直接调 `insertVisibleAutoWrite` |
| 7 | `ContactTagToolBinding.mutate` | 201 | **无**——函数体直接调 `insertVisibleAutoWrite` |

**关键发现**：
- **3 个 v3 报告里说的 `AgentDataRepository` 调用点还需要具体核**，但 v3 报告"行号差 18 行"是事实
- **至少 4 个**生产调用方（ContactAgentDataRepository 931、CrmAgentDataRepository 344、RoomCrmToolExecutor 325、ContactTagToolBinding 201）**直接调用，无外层事务**——这是事实，可在代码里逐字核到

**Codex 复检"全部有外层 Room 事务"——这条结论不成立**。至少这 4 个调用方直接调到 `insertVisibleAutoWrite`，调用前没有 `database.withTransaction { }` 包裹。如果中间异常发生（OOM、cancel、第二个 DAO 抛错），`change_log` 有记录但 `auto_write_receipts` 没有——receipt 缺失意味着"自动写回执未读徽标"会一直算这条记录，永远 -1。

**我方裁定**：
- v3 报告"没有 withTransaction"——**对**（代码 59-93 行确实没有）
- v3 报告"6 个调用方全部暴露"——**部分对**，至少 4 个调用方确认无外层事务，剩下 3 个 AgentDataRepository 内部点还需要实测，但即使都有外层事务，**helper 自身原子性更好是 R10 红线**（规范 R10 明确"多表写必须在同一事务内"）
- Codex 复检"全部有外层 Room 事务"——**错**（4 个调用方明确无）
- 真实裁定：P0-7 是真 P1（**结构加固**），不是 P0。修法应聚焦两点：
  1. `insertVisibleAutoWrite` 内部加 `database.withTransaction { }`，使 helper 自身原子
  2. 保留外层嵌套事务能力（Room 协程事务支持嵌套 → no-op）

---

## 三个分歧点的最终结论

| 分歧 | 裁定 | 后续 |
|---|---|---|
| P0-1 时间解析 | **真 P0**（v3 描述对路径错，Codex 描述对） | Commit 3 修。Codex plan 已写明 |
| P0-5 HealthCache | **真 P1 但修法方向 v3 错**（v3 报告混淆了"问题描述"和"修法方向"） | 修法是"健康检查失败不缓存或短 TTL + 业务通道读 cache"，不是"塞进 Policy 层" |
| P0-7 自动写事务 | **真 P1**（v3 对问题描述，Codex 对修法方向但 4 个调用方核查错） | 修法是 `insertVisibleAutoWrite` 内部 withTransaction（Codex 方案 5 方向对） |

## 整体判断

v3 报告作为"问题线索库"价值仍然高——它挖出了 3 个真 bug、3 个真 P1 风险、2 个清理债。但它有两个系统性缺陷：
- **路径与行号错位**（多模块布局没掌握，文件路径几乎全错）
- **修法方向和诊断混淆**（P0-5 把"问题在 cache 失败缓存"和"修法在 Policy 层注入"打包说了，结果被实测推翻）

Codex 复检的"3 个真 P0 / 3 个 P1 / 2 个清理债 / 1 个 Roadmap"这个分类基本准确，但"全部有外层 Room 事务"这条结论有事实错误（至少 4 个调用方裸调），需要修正。

**推荐执行**：
- 按 Codex 的 9 commit 计划继续走（顺序、闸口、合并流程都对）
- Commit 5（自动写事务）按 Codex 方向修，但**必须先核 AgentDataRepository 3 个内部点的真实外层事务情况**，不要假设全有
- Commit 4（HealthCache）按 Codex 方向修：失败短 TTL + 用户主动检查强制刷新 + 业务通道读 cache，不是 v3 的"塞 Policy 层"

本报告作为 Provider/Data/Product 三路 Reviewer 的对接基线，Codex 开工时按此校准。
