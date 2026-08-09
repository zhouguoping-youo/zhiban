# 知伴 Agent 第一阶段主线

## 唯一目标

先把“问问”做成真正可闭环的知伴 Agent。用户只通过模拟器验收：看得到、点得动、执行结果可核对、失败可以恢复。

本阶段不同时扩张日历、关系、能力、我的等页面的新功能；这些页面只作为 Agent 工具的数据源、设置入口或结果落点。

## 唯一集成主线

- 工作目录：`/Users/laozhou/Desktop/Claude/zhiban-t31`
- 分支：`task/t31-plan-dag`
- 规则：其他 worktree 只作为已完成实现和测试证据来源，不再直接面向模拟器开发。
- 未经盘点、测试和冲突审查，不删除任何 worktree，不覆盖未提交文件。

## 模块边界

第一阶段先建立稳定边界，再逐步迁移为独立 Gradle module。UI 不得直接调用 DAO，Provider 不得直接写业务表。

1. `agent-contracts`
   - UserMessage、Observation、Context、ToolSpec、ToolCall、ToolResult、ActionProposal、MemoryCandidate、RuntimeEvent。
   - 不依赖 Android UI、Room 和具体 Provider。
2. `agent-runtime`
   - 会话状态机、规划、执行、取消、恢复、幂等、租约和事件投影。
3. `agent-provider`
   - Provider Preset、密钥引用、能力探测、流式输出、超时、重试和模态级降级。
4. `agent-context`
   - 实体抽取、结构化过滤、FTS/Vector/Graph 多路检索、RRF、预算化上下文组装。
5. `agent-tools`
   - CapabilityRouter、ToolRegistry、权限/风险策略、Local/Remote transport。
6. `agent-governance`
   - ActionPolicy、用户确认、Domain Writer、ChangeLog、Undo、AuditLog、MemoryGate。
7. `agent-memory`
   - 记忆候选、确认写入、检索、删除、来源和作用域。
8. `feature-ask`
   - 问问 TAB、文字/语音/附件输入、流式消息、计划确认、工具结果、错误恢复。

## 产品域 Gradle 模块边界（用户确认）

- `agent`：知伴 Agent 本体，拥有 Contracts、LLM/Provider 环境、Runtime、Context、Memory、Governance 与问问交互。
- `calendar`：独立日历业务与数据；Agent 只能经日历合同/工具适配访问。
- `relationship`：独立联系人及联系人图谱业务与数据；Agent 不得直接引用其 DAO。
- `tools`：Agent 能力注册、路由与跨域适配；不拥有日历或关系图谱业务数据。
- `skills`：场景技能、激活规则与工作流建议；不得绕过 Runtime/ActionPolicy 写业务数据。
- `settings`：通用用户设置页面与偏好入口。
- 服务商 Key 明确归 `agent/provider` 所有。`settings` 只调用凭证合同，不保存、读取或记录 Key。

真实编译边界已包括 `:agent:contracts`、`:agent:runtime`、`:agent:feature-ask`、`:agent:provider`、`:agent:context`、`:agent:tools`、`:agent:governance`、`:agent:memory`、`:agent:skills`、`:agent:mcp`。其中 `:agent:runtime` 独立编译状态机、Session Actor、规划策略和幂等核心；`:agent:feature-ask` 独立编译对话及多模态 UI 状态合同；Compose、Android/Room 适配仍留在 App 组合层，依赖保持单向，禁止业务域反向依赖 App/UI。

## 第一阶段必须闭环

### A. 对话闭环

- 进入问问直接到聊天页。
- 文字发送、流式回复、停止、失败重试、应用重启恢复。
- Chat 只回答；Work 可以提出并执行工具计划。
- 回复复制、反馈、朗读、分享均调用真实系统能力。

### B. 多模态输入闭环

- 相机、照片、文件选择与权限处理。
- 附件安全暂存、过期、删除、重选、随消息进入 Runtime。
- 麦克风授权、录音、转录、取消、转录结果编辑后发送。
- Provider 不支持某模态时给出明确、可恢复的降级路径。

### C. 工具闭环

- 所有工具只通过统一 ToolRegistry/CapabilityRouter 暴露给模型。
- 第一批真实工具：日程查询/创建/修改/删除、联系人查询、记忆查询/候选/确认/删除。
- 读操作可自动执行；写操作必须经过 ActionPolicy。
- 工具结果重新注入模型，生成最终自然语言回复。

### D. 写入治理闭环

- LLM、UI、Skill、Tool 不能直接写业务 DAO。
- 写入统一经过 ActionProposal → ActionPolicy → Domain Writer。
- 写入有确认、幂等、审计；可逆操作提供 Undo。
- 拒绝、取消、进程死亡和重复点击不得产生重复写入。

### E. 上下文与记忆闭环

- 最近对话和已确认记忆进入 Prompt，且有 token 预算。
- 联系人/日程先完成结构化过滤和 FTS 检索。
- Vector/Graph/Rerank 未可用时必须有确定的降级行为，不能阻塞回复。
- 记忆只能由候选经 MemoryGate 确认后进入长期记忆。

### F. Provider 闭环

- 用户只选择服务商并填写 Key；模型配置由知伴管理。
- Key 使用 Android Keystore，不进入日志、数据库明文或 UI 状态。
- 保存 Key、冷启动、连续失败和手动检查时执行 HealthCheck。
- Text 不可用时阻止请求并引导设置；ASR/TTS/Embedding 按架构降级。

## 模拟器验收清单

每个版本必须从干净启动逐项点击并留证：

1. 问问初始页、键盘输入、多行输入、发送和停止。
2. Chat/Work 切换、模型选择、智能等级选择。
3. 相机、照片、文件、插件/工具入口。
4. 麦克风首次授权、拒绝、永久拒绝、系统设置返回。
5. 录音、正在转录、转录成功、取消和发送。
6. 普通回复、长回复、复制、赞/踩、朗读、分享、更多。
7. 日程读取、创建确认、拒绝、成功结果、Undo、重复点击。
8. 联系人查询和记忆写入确认/删除。
9. 无 Key、错误 Key、断网、超时、限流、应用杀死后恢复。

## 完成定义

“已落地”必须同时满足：

- 代码路径是真实实现，不是空回调、假数据或只弹说明框。
- 单元、集成和关键 Compose E2E 测试通过。
- 模拟器逐按钮回归通过。
- 写操作能够在数据库或目标页面核对，并有审计记录。
- 失败、取消、重启和重复操作均有测试证据。
