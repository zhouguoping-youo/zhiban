# 知伴 Agent 第一阶段完成审计（2026-07-21）

审计基准：`zhiban_agent_architecture_20260705.html`、`PHASE1_AGENT_MASTERPLAN.md`。本文件只认生产入口、可重复测试和 Android 15 实机态证据，不以页面存在或空回调作为完成。

## 第一阶段闭环证据

| 要求 | 生产证据 | 自动化证据 | 结论 |
|---|---|---|---|
| 对话：发送、流式、停止、重试、恢复 | `ProviderExecutionEngine`、`RuntimeStateMachine`、`V2AgentConversationBackend` | `RuntimeInputProcessorTest` 的 provider delta/final、cancel、timeout、crash/reopen、retry；`AgentConversationScreenE2ETest` | 已证明 |
| Chat / Work 与规划 | `PlanningStrategySelector`、`SkillActivator`、`CapabilityRouter` | `PlanningStrategyTest`、二次工具重规划 DAG 集成测试 | 已证明 |
| 相机、照片、文件、语音 | 私有附件暂存、Activity Result、阶跃星辰 ASR/Realtime 与旧系统回退 | 私有暂存/篡改/过期/PDF/图片测试、ASR 恢复、实时语音持久化、UI E2E | 已证明；模拟器已完成录音转写和双向实时语音，结果以幂等 Runtime run 写入会话历史 |
| 工具总线 | 所有生产工具仅由 `CapabilityRouter` 暴露 | Router、MCP、动态工具及 Runtime Observation 测试 | 已证明 |
| 日程 CRUD/查询 | Calendar bindings + 事务 Domain Writer | 查询、冲突、create/update/delete、确认、审计、幂等、Undo、数据库重开测试 | 已证明 |
| 联系人查询 | Contact bindings + 独立 Contact 数据域 | search/detail 自动执行并回灌最终回答 | 已证明 |
| 记忆查询/候选/确认/删除 | `MemoryGate`、`RoomMemoryGate`、`MemoryAtomicStore` | remember/search/delete、租约、索引失效、上下文回灌测试 | 已证明 |
| 写入治理 | ActionProposal → ActionPolicy → Domain Writer → Audit/ChangeLog | 未确认零写入、篡改拒绝、重复点击、事务回滚、进程重开、Undo | 已证明 |
| Context | Entity、Structured/FTS/Vector/Graph、RRF/Rerank、预算 Prompt | Entity/RRF/结构过滤/超时降级/Provider 切换 backfill | 已证明 |
| Provider | 阶跃星辰唯一 Preset、Keystore、探测后发布、文字/图片/ASR/实时语音、流式、熔断 | 唯一服务商约束、配置/Key 隔离/401/限流/重试/熔断测试 | 代码闭环已证明；Android 15 已用真实 Key 验证文字流式、ASR SSE、Realtime WebSocket 与音频播放 |
| 安全 | SQLCipher、Keystore、附件隔离、脱敏、HTTPS、阶跃星辰 SPKI pin | 明文库迁移/重开/原文不可见、污染诊断、Android TLS 测试 | 已证明 |
| 反馈与可观测性 | 人类反馈写入后续规划、运行 Trace、指标、诊断导出 | feedback context、指标、诊断污染测试 | 已证明 |
| 智能体设置 | 个性化、记忆、模型、工具、行为安全、反馈、运行记录 | 七页导航和关键交互 E2E | 已证明 |

模块化额外证据：`:agent:runtime` 已成为独立 JVM Gradle 模块，拥有状态机、Session Actor、规划策略、幂等核心及其单测；`:agent:feature-ask` 独立拥有对话/权限/附件/转写状态合同及多模态状态单测。`:app` 排除这些源码后仅通过模块依赖使用，避免“同一源码被 App 顺带编译”的伪模块化。

## 模拟器逐功能证据

1. 初始聊天、聚焦键盘、多行与发送/停止：Compose E2E + Android 15 页面实测。
2. Chat/Work、模型与智能等级：E2E + Android 15 抽屉视觉实测；抽屉内容按实际高度紧凑布局。
3. 相机/照片/文件/插件：Android 15 分别进入权限控制器、系统图片选择器、系统文件选择器；插件进入真实工具设置。
4. 麦克风：Android 15 首次权限请求、一次性允许、录音状态、系统识别失败恢复均实测。
5. 普通/长回复与操作：富文本/长列表 E2E；复制、反馈、朗读、分享连接真实 Android 系统能力。
6. 日程/联系人/记忆/关系：Runtime + Room 端到端覆盖确认、拒绝、执行、Observation、审计和 Undo。
7. 无 Key、错误 Key、断网、超时、限流、杀进程恢复：配置、Provider 韧性、Room 重开和 UI 恢复测试覆盖。
8. Android 返回：聊天返回主页、系统选择器返回聊天、设置返回均实测。
9. 部分连通网络：审计实测发现 Android `PARTIAL_CONNECTIVITY` 仍可连接 Provider，但旧逻辑误判离线；现改为 WEAK 并由有界 Provider 请求判定，纯逻辑及 Android 设备测试覆盖。

## 边界说明

架构 §19 中“各厂商 ROM 通知读取、系统通讯录同步、存储超过 2GB 淘汰、五家真 Key 回复质量”是产品域/真机/外部凭据验收，不属于 `PHASE1_AGENT_MASTERPLAN.md` 定义的首批 Agent 闭环完成项，不能用 Fake 声称完成。第一阶段代码仍保留明确接口和降级边界，后续只能在具备对应设备与凭据时登记人工证据。

## 回归命令

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew \
  verifyAgentModuleBoundaries \
  :agent:runtime:test :agent:feature-ask:test :agent:memory:test :agent:mcp:test :agent:skills:test :agent:context:test \
  :app:testDebugUnitTest :app:connectedDebugAndroidTest --offline
```
