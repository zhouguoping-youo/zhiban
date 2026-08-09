# ADR-001: 知伴 Agent MVP 纵向闭环

- 状态：Proposed（待后端与测试独立复核）
- 日期：2026-07-17
- 决策者：架构；后端可实施性 reviewer；测试门 reviewer
- 范围：今日可验证的 Android 本地 Agent 闭环

## 1. 决策

“知伴 Agent 完全落地”在本阶段定义为一个可运行、可持久化、可审计、可回滚的纵向闭环，而不是把静态页面或 Debug Mock 称为 Agent：

1. 用户在 AssistantChat 输入创建本地日程的自然语言请求。
2. Agent 生成可解释计划与 `Schedule.Create` 工具调用草案。
3. 任何写入必须先展示确认卡；用户可确认、拒绝或取消。
4. 确认后在单一事务中写入 Schedule、AgentRun 与 ToolAudit。
5. 最终回复引用 ToolResult；Calendar 立即读取同一 Repository 并显示日程。
6. 进程重启后 Schedule、Memory、AgentRun、ToolAudit 可恢复。
7. 整个验收使用本地确定性 fixture，不依赖 Gateway、外网或 API Key。

现有 MiniMax 普通聊天链路保留，但不是本地 Agent 工具闭环的必要依赖。

## 2. 状态机

`AgentRunStatus`：

```text
RECEIVED -> PLANNING -> AWAITING_CONFIRMATION
AWAITING_CONFIRMATION -> EXECUTING | REJECTED | CANCELLED
EXECUTING -> SUCCEEDED | FAILED_RETRYABLE | FAILED_FINAL
FAILED_RETRYABLE -> EXECUTING | CANCELLED
```

约束：

- 每次迁移写入时间戳和原因；非法迁移拒绝并记审计。
- `AWAITING_CONFIRMATION` 之前不得产生业务写入。
- 恢复时 `EXECUTING` 视为未完成：以幂等键查询 ToolAudit，已成功则收敛为 `SUCCEEDED`，否则回到 `FAILED_RETRYABLE`。
- 同一 run 同一 tool call 只能有一个终态结果。
- `EXECUTING` 内的 Room 事务不可中断；此时收到取消请求仅记录 `CANCEL_REQUESTED` 审计。事务成功则收敛为 `SUCCEEDED` 并提示“已完成，无法取消”，事务失败则收敛为 `CANCELLED`，不得制造“UI 显示取消但数据已写入”的分裂状态。
- 拒绝、确认、取消都必须产生不含业务参数正文的决策审计；拒绝/取消不得产生 Schedule 写入。

## 3. 跨模块契约

### 3.1 AgentMessage

```kotlin
data class AgentMessage(
    val id: String,
    val runId: String,
    val role: Role, // USER | ASSISTANT | SYSTEM | TOOL
    val content: String,
    val createdAtEpochMs: Long,
)
```

### 3.2 ToolCall / ToolResult

```kotlin
data class ToolCall(
    val id: String,
    val runId: String,
    val toolName: String,
    val argumentsJson: String,
    val idempotencyKey: String,
    val requiresConfirmation: Boolean,
)

sealed interface ToolResult {
    data class Success(val outputJson: String) : ToolResult
    data class Failure(val code: String, val retryable: Boolean, val safeMessage: String) : ToolResult
}
```

首个工具固定为 `schedule.create.v1`。参数：标题、开始时间、时长、备注；不得包含系统日历 ID、联系人 ID 或凭据。

### 3.3 Memory

MVP 仅允许显式、可见、可删除的本地记忆：

- `USER_PREFERENCE`：用户明确表达的偏好。
- `RUN_SUMMARY`：成功 run 的短摘要。

禁止自动保存 API Key、附件正文、系统权限数据或失败请求原文。记忆写入需审计；清除必须同时删除索引与内容。

删除采用**事务内硬删除**：先删除记忆索引/关联，再删除 `MemoryEntity` 行；MVP 不使用仅设置 `deletedAt` 且保留 `content` 的软删。删除审计只保留 memoryId、kind、时间和结果，不保留正文。

## 4. Room 数据边界

数据库初始版本为 v1（当前仓库尚无 Room Database）：

- `ScheduleEntity(id, title, startAt, durationMinutes, note, createdByRunId, createdAt, updatedAt)`
- `MemoryEntity(id, kind, content, sourceRunId NULL, schemaVersion, createdAt)`
- `AgentRunEntity(id, userInput, status, pendingToolCallJson, schemaVersion, expiresAt, errorCode, createdAt, updatedAt)`
- `ToolAuditEntity(id, runId NULL, subjectRunDigest, toolCallId, toolName, idempotencyKey UNIQUE, argumentsDigest, schemaVersion, status, resultJson, expiresAt, createdAt, updatedAt)`

规则：

- DAO 不暴露 UI 可直接任意写入的 public generic update。
- `confirmAndExecuteScheduleCreate()` 是唯一写事务入口，同时写 Schedule、ToolAudit、AgentRun。
- 业务失败整体回滚；审计不得记录 API Key 或用户附件原文。
- v1 创建失败可安全删除未发布数据库重建；后续版本必须提供显式 Migration 和回滚说明，禁止 destructive migration 进入 release。
- 用户输入、pending tool call、记忆正文和 ToolResult 均属于用户数据：仅保存本地日程闭环所需字段，禁止 API Key、认证头、附件正文和系统权限数据。`RUN_SUMMARY` 不保存完整原始输入。
- 保留期：未完成/失败的 AgentRun 与其 ToolAudit 为 7 天；成功的 AgentRun、ToolAudit 与 RUN_SUMMARY 为 30 天；Schedule 与 USER_PREFERENCE 由用户显式删除。每次启动执行过期清理事务。
- FK/删除策略：`RUN_SUMMARY.sourceRunId -> AgentRun.id ON DELETE CASCADE`；`USER_PREFERENCE.sourceRunId -> AgentRun.id ON DELETE SET NULL`，因为显式长期偏好可独立于来源对话；`Schedule.createdByRunId -> AgentRun.id ON DELETE SET NULL`；`ToolAudit.runId -> AgentRun.id ON DELETE SET NULL`，并以不可逆 `subjectRunDigest` 保持安全审计关联。不得由数据库级联删除 Schedule 或安全审计。
- 用户“清除当前对话/记忆”时，在单一清理事务内先擦除/删除该 run 的非安全审计 `resultJson`，硬删关联 RUN_SUMMARY，再硬删 AgentRun；ToolAudit 仅保留无正文的 id、subjectRunDigest、argumentsDigest、toolName、status、时间，`runId` 由 FK 置空。USER_PREFERENCE 默认保留并解除来源引用，只有用户明确选择“同时清除偏好”才硬删。Schedule 仅在用户明确选择“同时删除已创建日程”时删除。
- 所有持久化 JSON 都带 `schemaVersion`；读取不支持版本时返回 `SCHEMA_UNSUPPORTED` 并阻断写入，不得猜测解析。升级必须先迁移后切换代码。

### 4.1 幂等键

- 客户端在首次生成工具草案时生成并持久化稳定 key：`SHA-256(runId + toolCallId + toolName)`；参数摘要单独存为 `argumentsDigest`。重试、进程恢复必须复用原 key 与原 digest。
- `canonicalArgumentsJson` 采用字段名排序、统一时区/数字格式后的 UTF-8 JSON；`argumentsDigest` 为其 SHA-256，不保存明文日志。
- 同 key + 同 digest：执行中返回原状态，成功后返回原结果；同 key + 不同 digest：返回 `IDEMPOTENCY_CONFLICT`，禁止写入。

## 5. 错误模型

| code | 含义 | UI | retryable |
|---|---|---|---|
| `INVALID_INTENT` | 无法形成日程参数 | 请求用户补充 | false |
| `CONFIRMATION_REQUIRED` | 未确认写入 | 展示确认卡 | false |
| `USER_REJECTED` | 用户拒绝 | 保留对话，不写数据 | false |
| `DUPLICATE_CALL` | 幂等键已完成 | 返回原结果 | false |
| `IDEMPOTENCY_CONFLICT` | 同一幂等键参数不一致 | 阻断并要求重新规划 | false |
| `SCHEMA_UNSUPPORTED` | 持久化版本无法读取 | 阻断写入并提示升级 | false |
| `DB_BUSY` | 暂时写入失败 | 显示重试 | true |
| `DB_CORRUPT` | 数据库不可用 | 阻断写入并提示恢复 | false |
| `CANCELLED` | 用户/生命周期取消 | 不写数据 | false |

错误消息不得回显 SQL、文件路径、凭据或原始异常堆栈。

## 6. UI 状态契约

AssistantChat 必须可见：

- 计划生成中
- 工具确认卡（确认 / 拒绝 / 取消）
- 执行中（禁用重复确认）
- 成功及 Calendar 跳转
- 可重试失败 / 最终失败
- 记忆命中和清除入口

Calendar 必须订阅 Repository 暴露的 `Flow<List<ScheduleProjection>>`，按日期范围查询；UI 不得持有 DAO、拼 SQL 或读取测试静态列表冒充真实数据。

## 7. 安全边界

- API Key 持久化不属于本 ADR 的批准范围；现有实现继续标记安全债务，不新增能力。
- Agent fixture 不读取真实或 fixture key，不访问外网。
- 不新增 AccessibilityService、NotificationListener、证书 pin、系统日历写入或联系人写入。
- Debug fixture/入口不得进入 release 可达路由。
- 日志不得包含用户输入全文、Tool arguments 明文或记忆正文；仅允许 runId、toolName、状态和摘要哈希。
- 测试需用已知探针扫描 logcat（用户输入片段、`schedule.create.v1` 参数 JSON、`Authorization`、`Bearer`、`MiniMax`）；命中任何正文/凭据即失败。

## 8. 验收与证据

必须在模拟器完成：

1. 创建日程 -> 确认 -> 成功 -> Calendar 可见。
2. 拒绝/取消 -> Calendar 无新增记录。
3. 双击确认 -> 仅一条日程和一条成功审计。
4. 注入可重试失败 -> 重试成功。
5. `adb shell am force-stop com.zhiban.rebuild.debug` 后重新启动，30 秒内日程、run、记忆和审计可恢复。
6. 清除记忆 -> 内容和索引均不可查询。
7. Debug/Release 构建通过；Release 不含 Debug 控制入口。

证据：全屏截图、完整录屏、Room 查询摘要（不含敏感正文）、SHA256、单元测试、迁移/事务测试、红线扫描。

## 9. 回滚

- 各实现任务独立 commit，数据层、runtime、UI 不混提。
- 代码回滚顺序：UI -> Runtime -> Data。
- v1 未发布数据库允许卸载 Debug App 重建；Release 一旦携带数据库，禁止无迁移降级。
- 数据库 schema 版本与最低兼容代码版本必须随 release 记录。旧代码无法读取新 schema 时必须拒绝启动数据写入，而不是 destructive downgrade；需要代码降级时先发布兼容迁移版本或恢复同版本 APK。
- 回滚 runtime/UI 时数据库保持只读；只有 schema 与代码兼容矩阵验证通过后才恢复写入。
- Runtime 通过 DI 可切回现有普通 Chat；切回时数据只读保留，不自动删除。

## 10. 非目标

- 真实系统日历/联系人写入。
- Accessibility/Notification 自动化。
- 云端同步、多设备、后台自治执行。
- 未经安全评审的 API Key 生命周期扩展。
- 用 LLM 自由生成任意工具名或 SQL。
