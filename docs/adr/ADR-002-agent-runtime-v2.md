# ADR-002: 《知伴 Agent Runtime v2 架构决策书》

- 状态：Accepted（2026-07-18；Owner 方向确认，后端/前端/测试复核通过）
- 日期：2026-07-18
- 决策者：Owner、架构；后端/前端/设计/测试 reviewers
- 范围：Android 端单 Agent Runtime、云端模型接入及模块升级边界
- 取代范围：ADR-001 的本地 fixture Runtime 定义；ADR-001 的确认、事务、幂等、恢复与安全约束继续有效

## 1. 背景与目标

知伴需要一个长期稳定的端侧 Agent Runtime，承载感知、上下文、规划、工具、记忆、执行、反馈和恢复。旧版曾将 Qwen 0.5B、Moonshine、BGE 等模型推理放在手机，造成内存、CPU、耗电、发热与包体不可接受；新版本不再运行端侧 LLM。

本决策的目标不是“接一个聊天 SDK”，而是建立可独立演进的产品主干：

1. Android 本地掌握执行控制面和用户数据。
2. 云端 LLM 仅负责理解与推理，不能直接写数据、读取凭据或绕过策略。
3. Provider、Context、Tool、Policy、Memory、Input、UI 与外部能力均可替换或灰度，不改 Kernel。
4. Run 在进程死亡、网络中断、重试和用户取消后仍可确定性恢复。
5. 每个副作用均可授权、审计、幂等、验证和回滚。

## 2. 决策

采用 **Android 本地微内核 Runtime + 云端可替换 LLM Provider**：

```text
Input
  -> SessionActor
  -> Context / Prompt Assembly
  -> Provider
  -> Plan Validator
  -> Policy / Confirmation
  -> Tool Executor
  -> Observation
  -> EventStore
  -> UI Projection
```

不直接采用 OpenCode、Hermes、OpenClaw、Claude Code 或 OpenAlly Runtime。它们面向桌面/服务器或 Coding Agent，依赖 Shell、文件系统、容器、长驻进程、多渠道或广泛插件权限。知伴仅 clean-room 借鉴其公开机制：Provider 配置化、Session 串行、上下文预留、分层 Prompt、可追溯压缩、显式权限、暂存审批、运行事件和用量账本。

## 3. 微内核与模块边界

### 3.1 Runtime Kernel

Kernel 只负责：

- SessionActor 与串行 mailbox；
- Command 接收、Run/Attempt 状态迁移；
- Event append、projection 调度；
- Budget、timeout、cancel、retry 与恢复；
- 模块 SPI 编排；
- Feature Flag 与兼容性检查。

Kernel 不负责 Provider HTTP 细节、Prompt 内容、领域 DAO、Compose 状态、附件解析或外部 MCP transport。

### 3.2 可插拔模块

| 模块 | 所有权 | 禁止行为 |
|---|---|---|
| Provider | capability、stream、structured output、cancel、usage | 直调 Tool/DAO；接受模型返回的新 endpoint |
| Input | 文字/录音/图片/视频/文件归一化、预检 | 绕过权限/限额；整文件读入内存 |
| Context | 检索、Prompt 分层、token budget、compaction | 将不可信数据当指令；删除唯一原始事实 |
| Tool | 版本化 ToolSpec、执行、幂等与补偿 | 自行决定授权；访问 Provider Key |
| Policy | 权限、风险、确认、隐私、审计决策 | 依赖模型结论作为唯一安全边界 |
| Memory | Working/Fact/Long-term、候选暂存、检索与删除 | 自动持久化敏感正文；绕过审批 |
| UI Projection | Command client、AgentEvent 投影 | 直调 Provider、Tool、DAO 或拼装业务终态 |
| External Capability | Remote MCP、Accessibility、第三方服务 | 默认启用；导入 Kernel 内部实现 |

### 3.3 依赖规则

1. 模块只依赖 `runtime-spi` 的版本化接口和自己的领域接口。
2. 外部插件只依赖公开 SDK；禁止 import Kernel/internal 或跨模块直调 DAO。
3. Tool/Provider/Hook 不能直接发出“已成功”UI 状态；只能提交 Event，由 projection 生成界面。
4. Hook 可观察或提出决策，不可跳过 Policy、Confirmation、Budget 或事务。
5. Local Tool 直接实现 Kotlin Tool SPI；MCP 仅用于显式安装的外部能力，不作为内部调用协议。

## 4. Runtime 公共契约

以下为语义契约，Kotlin 代码由实现任务落地；字段删除或含义变化必须提升 `schemaVersion`。

### 4.1 标识与顺序

每条 Command/Event 至少包含：

```text
schemaVersion
sessionId
runId
attemptId
sequence
eventId | commandId
causationId
correlationId
producerVersion
createdAtEpochMs
```

- `sessionId`：对话稳定身份。
- `runId`：一次用户目标及其完整执行链。
- `attemptId`：同一 run 的 Provider/执行尝试；retry 创建新 attempt，但复用原 Tool idempotency key。
- `sequence`：session 内单调递增，由 EventStore 分配；UI 以它去重和排序。
- `causationId`：产生当前记录的直接 Command/Event；`correlationId`：贯穿同一用户目标，默认等于首个 commandId。
- `producerVersion`：写入端版本；`schemaVersion` 描述 payload 结构。读端必须按二者做兼容判断，未知必填字段 fail-closed。

### 4.2 Command

```text
Start(sessionId, inputRef)
Steer(sessionId, runId, text)
Approve(sessionId, runId, proposalId, optionalEditedPayload)
Reject(sessionId, runId, proposalId, reason?)
Cancel(sessionId, runId, reason)
Retry(sessionId, runId, failedStepId)
Resume(sessionId, runId)
```

每个 UI Command 还必须携带 `commandId/clientActionId/expectedRevision/surfaceId`。所有外部 Command 先写入持久化 `CommandInbox`，再由 SessionActor 领取；同一 `commandId` 重放返回已持久化 receipt/result，不产生新副作用。Inbox 与首个状态/Event 在同一 Room 事务内提交，避免“收到但丢失”。

`CommandReceipt` 返回 `status = ACCEPTED | DUPLICATE | CONFLICT | REJECTED`、`commandId`、`currentRevision` 与可选 error。revision 不匹配返回 CONFLICT，不静默覆盖：

- Steer：仅 ASSEMBLING_CONTEXT/INFERENCING/VALIDATING_PLAN 可接受；其他阶段排入下一 run 或拒绝。
- Cancel：所有非终态可接受；事务提交点后只产生 CANCEL_REQUESTED，按事实收敛。
- Retry：仅 FAILED_RETRYABLE；生成新 attemptId，保留 runId。
- Approve/Reject：仅 AWAITING_CONFIRMATION 且 proposalId/revision 匹配。

### 4.3 Event

事件族：

- Lifecycle：RunReceived、RunStarted、RunSuspended、RunCompleted、RunFailed、RunCancelled。
- Context：ContextAssemblyStarted、ContextChunkSelected、ContextCompacted。
- Provider：ProviderAttemptStarted、AssistantDelta、PlanProposed、ProviderUsageRecorded、ProviderAttemptFailed。
- Policy：ApprovalRequested、ApprovalGranted、ApprovalRejected、PolicyBlocked。
- Tool：ToolCallValidated、ToolExecutionStarted、ToolSucceeded、ToolFailed、ToolCancelRequested。
- Memory：MemoryCandidateStaged、MemoryCommitted、MemoryRejected、MemoryDeleted。
- Budget：BudgetWarning、BudgetExceeded。

事件 append 后不可原地修改。需要纠错时追加 superseding event；安全审计只保留脱敏摘要。

### 4.4 Run 状态机

```text
RECEIVED
  -> ASSEMBLING_CONTEXT
  -> INFERENCING
  -> VALIDATING_PLAN
  -> AWAITING_CONFIRMATION
  -> EXECUTING
  -> OBSERVING
  -> SUCCEEDED

任意可取消阶段 -> CANCEL_REQUESTED -> CANCELLED | SUCCEEDED
可恢复失败 -> FAILED_RETRYABLE -> INFERENCING | EXECUTING | CANCELLED
不可恢复失败 -> FAILED_FINAL
```

约束：

- 同一 session 同时最多一个 active foreground run；新输入用 `Steer` 或排队，不隐式并行。
- `AWAITING_CONFIRMATION` 前不得发生业务写入。
- `EXECUTING` 中事务不可被取消打断；取消只记录请求，事务完成后按事实收敛。
- 非终态 run 在冷启动时由 recovery scanner 恢复；不支持的 schema 必须投影为只读 `UnsupportedSchema`，禁止继续 Command/Tool，但保留导出与升级入口。
- CANCEL_REQUESTED 是持久状态：若 Tool 尚未进入提交点则取消；若已提交则等待 ToolSucceeded/ToolFailed 后收敛为 SUCCEEDED 或 FAILED，不能伪报 CANCELLED。

## 5. 核心 SPI

### 5.1 Provider SPI

```text
probe(profile): CapabilitySnapshot
stream(request, attemptContext): Flow<ModelEvent>
cancel(attemptId)
usage(attemptId): Usage
```

要求：

- credentialRef 强绑定 providerId、允许 endpoint/accountDomain、modelId 与 keyVersion。
- 每个 run 固化 capability/limits snapshot；过期或 probe 失败时对应模态 fail-closed，文字本地能力不受影响。
- ModelEvent 只允许 TextDelta、StructuredPlan、ToolCallSuggestion、Usage、Error；模型响应不得修改 endpoint、权限或 ToolSpec。
- Provider 明文 Key 仅在请求瞬间解封；不得进入 Event、Room、日志、崩溃 payload、截图或任务证据。

### 5.2 Tool SPI

```text
ToolSpec(name, version, inputSchema, outputSchema, risk, permissions, idempotency, timeout, reversible)
validate(call, context): ValidationResult
execute(validatedCall, idempotencyKey, cancellation): ToolResult
compensate(resultRef): CompensationResult?
```

- ToolCall 必须同时通过 Schema、Capability、Permission、Policy 与 Budget。
- 写 Tool 的授权来自 Runtime 持久化 Approval，不来自 Prompt 或 Tool 自己。
- `toolCallId` 在计划验证时稳定生成；`idempotencyKey = hash(runId, toolCallId, toolName, toolSpecVersion, canonicalInputDigest)`。未编辑 payload 的 retry 复用该 key；用户编辑批准 payload 后 canonical digest 改变，必须生成不同 key 并保留 supersedes 关系。Tool 开始前持久化 execution record，成功后在同一业务事务写入领域数据、ToolResult 引用和 ToolAudit；恢复时先查 execution record/resultRef，禁止盲重放。
- ToolResult 属于不可信 Observation；回灌模型前带 provenance/trust/sensitivity。

### 5.3 Context SPI

```text
assemble(query, budget, capability): ContextPack
shouldCompact(session, budget): Boolean
compact(sourceRange, protectedTail): CompactionResult
```

Prompt 顺序固定为：

1. stable：身份、安全规则、有效 Tool 摘要；
2. context：当前页面、结构化数据、检索事实、显式记忆、附件抽取；
3. volatile：当前输入、时间、budget/attempt 警告。

每个 ContextChunk 带 sourceRef、trust、sensitivity、tokenCost、expiresAt。外部文档、网页、附件、MCP/ToolResult 均作为 data，不可提升为 instruction。

### 5.4 Memory SPI

```text
stage(candidate): PendingMutation
approve(id, optionalEdit): MemoryRecord
reject(id)
retrieve(query, scope, budget): List<MemoryHit>
delete(id)
```

- Working：当前 run，终态后清理或汇总。
- Fact：有来源的客观记录，可过期/替代。
- Long-term：用户偏好、规则、长期项目，仅通过 staged approval 写入。
- Memory 与 Skill/外部能力变更均先 pending，可 diff、修改、拒绝、撤销并跨重启保存。

### 5.5 UI Projection SPI

```text
dispatch(command): CommandReceipt
getSessionProjection(sessionId): SessionProjection(lastAppliedSequence, revision)
observeSession(sessionId, afterSequenceExclusive): Flow<AgentEvent>
reduce(previousProjection, event): SessionProjection
getRun(runId): RunSnapshot
getPendingApprovals(sessionId): List<PendingApproval>
```

Compose、通知、Widget、语音入口都是带唯一 `surfaceId` 的 client。Event Journal 与 UI Projection 分离：集中式纯函数 reducer 按 sequence 生成 `SessionProjection`，Compose 不解释 Kernel 原始事件。读取 projection 与建立 `afterSequenceExclusive=lastAppliedSequence` 订阅必须由 store 提供无缝 catch-up 语义；订阅先注册再补读，或在同一 DB 快照边界读取，禁止 snapshot→subscribe 漏事件。

`AssistantDelta` 带 `ordinal/part/final/providerOffset`；reducer 按 attemptId+ordinal 幂等合并。最终 AssistantMessage 持久化且 final=true 后才能追加 RunCompleted。多 surface 可同时观察，但 Command 仍经 revision/commandId 仲裁。

冷启动恢复矩阵：

| 持久状态 | UI Projection | 允许动作 |
|---|---|---|
| INFERENCING/ASSEMBLING_CONTEXT | 显示恢复中，接续 lastAppliedSequence | Cancel；Steer 按状态矩阵 |
| AWAITING_CONFIRMATION | 恢复原 proposal 与 revision | Approve/Reject/Cancel |
| EXECUTING/CANCEL_REQUESTED | 显示执行/取消处理中，不伪造终态 | 仅 Cancel 去重；等待 reconcile |
| FAILED_RETRYABLE | 恢复失败原因及安全输入 | Retry/Cancel |
| 终态 | 重建最终消息、ToolResult 与审计摘要 | Start 新 run |
| 未知 schema | 只读 UnsupportedSchema | 导出/升级；禁止执行 |

### 5.6 PendingUserOperation

权限、Photo Picker、SAF、相机、录音等系统交互由 Input/Policy 产生持久化 `PendingUserOperation(requestId, sessionId, runId, type, payloadRef, expiresAt, status)`。UI 只执行系统交互并以 Command 回传 COMPLETED/CANCELLED/EXPIRED 及受控 resultRef；不得直接推进 Runtime 状态或写业务 DAO。冷启动从 projection 恢复待办；过期操作 fail-closed，并释放临时 URI/文件授权。

### 5.7 EventStore 与事务边界

一期采用现有 Room **同库分表**，不新建独立数据库；这样 CommandInbox、Run/Event、Approval、Tool execution/audit 与日程等领域写入可以使用同一数据库事务。Kernel 只能通过 `runtime-store` 接口访问这些表，禁止跨模块直调 DAO。

- append 时由数据库事务分配 session sequence，并建立 eventId 唯一键、`(sessionId, sequence)` 唯一键、commandId 唯一键。
- CommandInbox 状态为 PENDING/CLAIMED/COMPLETED/FAILED；receipt 与终态结果可重读。CLAIMED 必须绑定 leaseEpoch；lease 过期后允许新 owner 原子重领，旧 owner 受 fencing 拒写。
- Provider `AssistantDelta` 先在内存聚合，按 **100 ms 或 32 个 delta 或 4 KiB** 任一先到批量追加；Plan、ToolCall、Usage、终态和取消立即落盘。批次保留首尾 provider offset，恢复时去重而非重复展示。
- Event append-only；可重建的流式临时片段允许按 retention 清理，但其完成快照与审计事件不可删。

## 6. Session、Attempt 与并发

1. 每个 session 一个 actor/mailbox，保证 Command 和 Event 有序。
2. Provider 读取可跨 session 并发；全局并发由 BudgetGuard 限制。
3. 写同一领域对象的 Tool 由领域互斥键串行化。
4. retry 创建 attemptId，并清除上一 attempt 的临时 delta/tool summary；已提交 Event、Approval 与 ToolAudit 不删除。
5. 大附件/索引任务交给 Durable Job Executor；Android 14+ 评估 user-initiated data transfer，低版本使用可取消的 foreground Worker。Kernel 不依赖常驻进程。
6. Actor 恢复使用持久化 lease：`leaseOwnerId/leaseEpoch/leaseExpiresAt`。领取或续租以 compare-and-set 提升 `leaseEpoch`；每次 Event/Tool 提交必须携带 fencing epoch，旧 owner 的写入被数据库拒绝。
7. 进程启动只扫描未终态且 lease 已过期的 run；同一 session 同时只允许一个有效 lease owner。系统时钟只用于过期判定，正确性依赖单调递增 epoch。

## 7. BudgetGuard

每个 run 固化版本化预算：

- maxModelTurns
- maxToolCalls / maxParallelTools
- maxInputTokens / reservedOutputTokens
- maxEstimatedCost
- maxWallClockMs
- maxAttachmentItems / perItemBytes / aggregateBytes / duration/dimensions
- maxRetryAttempts

达到软阈值追加 BudgetWarning；达到硬阈值追加 BudgetExceeded 并停止新 Provider/Tool 调用。预算不能由模型、Plugin 或 Hook 提高。

## 8. Context 压缩与谱系

1. 压缩前先持久化 Event 和 pending MemoryCandidate。
2. 原 Event 不删除；摘要引用 source sequence range、algorithmVersion 与 digest。
3. ToolCall/ToolResult、ApprovalRequest/Decision 必须成对保留。
4. 最近受保护窗口保持原文；旧 Tool 大输出可被脱敏摘要替换用于 Prompt，但审计事实保留。
5. 摘要是 projection，不是唯一事实；版本变化或 digest 不匹配时可重建。

## 9. Policy 与安全

所有 Provider、Tool、EventStore、telemetry、日志和错误边界必须统一经过 `SecretRedactor`。它是 `runtime-spi` 的安全契约而非某个 Provider 私有实现：至少识别 API Key/Bearer、credentialRef 解封值、Authorization/header、URL query secret、用户自定义 canary；任何 redactor 初始化失败或 payload 无法安全结构化时 fail-closed，不写 Event/日志/崩溃附件。实现与规则变化属于安全红线，须 Owner 批准并由独立测试复核。

- 确定性规则优先；辅助模型只能给风险信号，不能批准动作。
- 日历、联系人、关系、长期记忆及任何外发/删除默认确认；高风险动作强确认。
- Remote MCP、Accessibility、第三方能力默认关闭，独立授权、最小 scope、可撤销、可审计、Feature Flag 回滚。
- 外部 MCP 安装需固定来源/版本/工具快照；工具列表或描述变化需重新批准。
- SecretRedactor 覆盖 Authorization、Key、query/body、日志、Room、DataStore、崩溃 payload 与证据。
- 禁止 Shell/任意代码执行、自修改 Skill、多 Agent 和 host-first Plugin 进入一期。

## 10. 错误模型

统一错误至少包含：

```text
domain
code
retryable
retryAfterMs?
safeMessage
redactedProviderRequestId?
stepId
attemptId
```

错误域：INPUT、CONTEXT、PROVIDER、PLAN、POLICY、PERMISSION、TOOL、MEMORY、STORAGE、BUDGET、CANCELLED、INTERNAL。

未知错误不得回显堆栈、SQL、路径、Prompt、附件正文或凭据；默认不可重试，需显式分类后才允许自动重试。

## 11. 生命周期 Hook

允许：beforeModel、afterModel、beforeTool、afterTool、beforeCompact、afterTurn。

Hook 可：追加脱敏 telemetry、提出 context/风险建议、统计 usage。Hook 不可：改写已验证 ToolCall、跳过 Policy、扩大 Budget、读取 Key、直接写 DAO、发 UI 终态。Hook 失败默认不阻断主链；安全 Hook fail-closed 必须在注册元数据中声明。

## 12. 实施阶段与依赖

1. 冻结本 ADR 与公共 SPI。
2. 按 `runtime-spi → runtime-store(Room) → runtime-kernel` 落地 Kernel、SessionActor、CommandInbox 与 EventStore；沿用项目 minSdk 26。
3. Provider 与 Context/Memory 可在 SPI 冻结后并行。
4. Tool/Policy 日程 vertical slice 依赖 Kernel + Provider。
5. UI Projection 对接稳定 Command/Event。
6. 多模态 Input/附件/转写最后接入。
7. 独立安全、E2E、迁移与回滚关门。

阶段任务以 `#runtime-v2` 任务板为唯一执行台账：

| 阶段任务 | 交付物 | 前置门 |
|---|---|---|
| #t2 决策与公共 SPI | 本 ADR Accepted、版本化公共契约 | Owner + 后端 + 前端 + 测试复核 |
| #t3 Kernel/SessionActor/EventStore | runtime-store、CommandInbox、lease/fencing、恢复 | #t2 |
| #t4 Provider Module | capability probe、stream/cancel、usage、凭据边界 | #t2 |
| #t6 Context/Prompt/Memory | budget、compaction lineage、staged memory | #t2 |
| #t5 Tool/Policy 日程切片 | 校验、确认、单事务、审计、幂等恢复 | #t3 + #t4 |
| #t7 UI Projection | reducer、Command/Receipt、PendingUserOperation | 稳定 Event/Projection 契约；集成依赖 #t3 |
| #t8 多模态 Input | 附件、上传、转写、取消清理 | #t3 + #t4 + #t7 |
| #t9 独立验收 | 安全、E2E、迁移、回滚、泄露失败门 | #t3–#t8 |

依赖解除只以父任务 checkpoint 与 AC 为准；子任务通过不自动代表父任务完成。

首个发布门：

```text
问问文本输入
-> 云端模型结构化计划
-> 本地校验与确认
-> Room 单事务创建日程
-> ToolResult Observation
-> 云端模型文字反馈
-> force-stop 后恢复同一结果且不重复写入
```

## 13. 迁移

- 旧 Runtime 代码不是兼容目标；仅迁移用户数据和明确配置。
- 保留 Schedule、Contact、Relationship、用户确认的 Memory 及安全审计所需摘要。
- Provider/profile/model 配置可迁移；API Key 必须重新以新 credentialRef 安全封装，不能复制旧明文或不明密文。
- 旧会话转为只读历史或经过版本化 importer 转换；无法证明语义的 pending tool call 不恢复执行。
- 本地模型、权重、下载 Worker、推理依赖及残留 asset 全部从 release 清除。

## 14. 回滚

1. 每个模块独立 Feature Flag；关闭 Provider/Context/Memory/External 后，Kernel 与本地文字/数据只读仍可启动。
2. 回滚顺序：UI Projection -> Input/External -> Context/Memory -> Provider/Tool -> Kernel；EventStore schema 不做 destructive downgrade。
3. 新 Event schema 上线必须支持旧事件读取或提供前向 migration；旧代码不兼容时保持只读并发布 forward-fix。
4. Provider 失败不得偷偷切换到未授权供应商；只允许同 Provider 能力降级或退回本地/文字路径。
5. External Capability 回滚需取消在途任务、撤销 token/URI 授权并清理临时对象。

## 15. 验证门

- 状态机非法迁移、Command 重放与 sequence 去重。
- 同 session 串行、跨 session Budget 并发。
- Provider stream/cancel/schema/usage/capability expiry。
- 确认前零写入、重复确认单写、Tool 失败事务回滚。
- Provider/进程在每个非终态被杀后的恢复。
- retry 不重复 delta、ToolCall、Calendar 或 ToolAudit。
- kill/restart 与双 owner 竞争时 lease/fencing 只允许一个 writer；CommandInbox 不丢 Command。
- delta 批量边界、provider offset 去重与终态立即持久化。
- projection snapshot→subscribe 无丢失窗口；多 surface 重放只产生一个 Command 结果。
- PendingUserOperation 在授权、取消、过期、进程死亡及 URI 回收时安全收敛。
- Prompt injection、MCP tool poisoning、Memory poisoning、超预算与泄露 canary。
- Context compaction lineage、摘要失效重建、Tool/Approval 对完整。
- Feature Flag 回滚、Room migration、旧数据保留与 release 无本地模型资产。

必须形成可执行的发布失败门：

1. 向 Provider input、Tool input/result、Memory、异常、header/query 注入唯一 secret canary；扫描 Room、logcat、测试报告、崩溃 payload 与产物，任一命中即失败。
2. 使用 `apkanalyzer files list <release.apk>`（不可用时 `unzip -l`）扫描 release APK/AAB；命中 `.gguf/.onnx/.tflite` 或 `qwen|bge|moonshine|model` 候选时必须逐项白名单审查，未知模型/权重资产即失败。
3. 以旧 schema fixture 执行 Room forward migration，校验 Schedule/Contact/Relationship/Memory/Audit 保数；降级不 destructive，旧客户端不兼容时只读并 forward-fix。
4. 对每个 Feature Flag 组合验证 Kernel 可启动、未授权 Provider/Tool 不加载、在途任务安全取消并释放 URI/临时文件。

## 16. 被否决方案

| 方案 | 否决理由 |
|---|---|
| 端侧 LLM Runtime | 手机资源不可接受；模型升级与兼容成本高 |
| 直接嵌入 OpenCode/Hermes/OpenClaw/OpenAlly | 平台、权限和产品边界不匹配；扩大供应链与执行面 |
| 单一“上帝 Agent”类 | Provider/Prompt/Tool/Memory/Persistence 耦合，无法独立升级或验证 |
| 内部 Tool 全部 MCP 化 | Android 内部调用无收益，增加 transport/session/安全复杂度 |
| 默认自动写 + Undo | Undo 不能替代授权；模型误判仍会产生副作用 |
| 一期即上向量/RRF/图谱/rerank | 缺乏数据证明；增加成本、延迟与迁移面 |
| 隐式跨 Provider fallback | 无第二凭据且会产生未授权数据出境和费用 |

## 17. 待 reviewer 决定

1. 首期 Provider 的准确 API 模式、模型与 capability probe 方式。
2. Long-term Memory 默认审批粒度：逐条、批次或只允许显式用户命令。
3. 多模态阶段 Android 14+ UIDT 与 foreground Worker 的兼容矩阵。

已冻结：一期 EventStore 与现有 Agent Room 同库分表；模块顺序为 `runtime-spi → runtime-store(Room) → runtime-kernel`；最低 Android API 沿用项目 minSdk 26。
