# ZhiBanJoin 原代码对照审计（2026-07-27）

## 审计边界

- 旧工程：`/Users/laozhou/Desktop/ZhiBanJoin`
- 旧工程状态：分支 `设计师`，HEAD `66f73e39`，工作区存在大量未提交删除。
- 唯一落地工程：`/Users/laozhou/Desktop/Claude/zhiban-t31`
- 新工程状态：分支 `task/t31-plan-dag`，HEAD `cd364e1`，保留现有未提交工作。
- 原则：旧工程只读；同时参考当前文件与 Git HEAD，不恢复、不切分支、不整包复制。

旧工程当前主源码共 284 个 Kotlin 文件，约 5.5 万行；新工程主源码共
153 个 Kotlin 文件。文件数量不代表成熟度：新工程已经把 Runtime、MCP、
Tool、Governance、Memory、Context 和 Ask 合同拆成独立模块，并拥有更多测试。

## 模块对照

| 旧工程原代码 | 新工程对应模块 | 判断 | 处理 |
|---|---|---|---|
| `ai/runtime/*`、`data/agent/AgentEngine.kt` | `agent:runtime`、`runtime/kernel/*` | 新工程状态机、会话租约、恢复和流式投影更完整 | 不迁移旧 Runtime |
| `ai/provider/*`、`data/remote/LlmProvider.kt` | `agent:provider`、`runtime/provider/*` | 旧工程多 Provider 与当前“阶跃星辰唯一 Provider”产品决策冲突 | 不迁移旧 Provider 列表 |
| `ai/mcp/ZhiBanMcpLayer.kt`、`KoogMcpToolBridge.kt` | `agent:mcp`、`CapabilityRouter` | 新工程已有标准 Streamable HTTP、统一风险和确认 | 不迁移旧桥接层；只复用缺失 Tool 语义 |
| `skill/*`、`SkillTools.kt` | `agent:skills`、能力 TAB | 旧工程场景覆盖更广，新工程激活和治理边界更可靠 | 后续逐个迁移场景定义，不复制执行逻辑 |
| `SystemContactReader.kt` | `data/contact/SystemContactReader.kt` | 新工程缺失的真实手机数据入口 | **本轮已迁移**：权限后读取、预览、选择、确认 |
| `ContactImportService.kt` | `AgentDataRepository` 联系人域 | 旧实现会直接批量写入；不符合新治理要求 | **本轮重写**：按 lookup/手机号幂等、本人手机号拦截 |
| `SystemContactWriter.kt`、Authenticator | 尚无 | 属于修改系统通讯录的高风险操作 | 后续经 ActionProposal + 强确认 + 系统 Intent/Writer |
| `OwnerIdentity*`、`SelfContactGuard.kt` | `UserProfileStore` | 新工程已有姓名、手机号、微信、抖音，但本人保护不完整 | 本轮接入手机号保护；后续补邮箱与多平台账号 |
| `ContactIdentityService.kt`、平台账号/别名/合并 | 当前单一 `ContactEntity` | 旧工程身份解析、别名、平台账号、合并建议更成熟 | 通讯录后第二优先级；需新表和 Domain Writer |
| `RelationEvidenceRepository.kt`、InteractionEvidence | 关系边、关系事件、Fact | 新工程关系事件建模更清晰；旧工程消息证据来源更丰富 | 保留新模型，迁移证据入口和来源适配器 |
| `SystemCalendarBridge.kt`、`CalendarRepository.kt` | 本机 Schedule + Calendar Tool | 新工程 Agent 日程 CRUD 更可靠，但没有系统日历同步 | 第三优先级：读取预览、冲突、确认写回 |
| `ScheduleExtractor/RuleExtractor/SceneClassifier` | Agent 意图/实体 + Calendar Tool | 旧工程有离线规则降级价值 | 迁移为感知层 fallback，不允许直接写日程 |
| `ScheduleReminder*` | `ScheduleReminderScheduler` + `ScheduleReminderWorker` + Agent Calendar Tool | 新工程已支持用户和 Agent 工具日程的可替换、可取消近似时间通知，并在 Android 13+ 按场景请求通知权限 | 保持 WorkManager 低权限方案；精准闹钟权限只在产品确需秒级触发时评估 |
| `MessageAggregator`、通知 pipeline | 本机 `notification_candidates` 候选箱 + 用户确认的联系人证据 | “只暂存、不自动写业务域”；候选必须由用户选定联系人后才生成可追溯证据，删除证据会重新开放候选 | 保持显式确认和可撤销边界，再扩展短信、通话等来源 |
| `NotificationListener`、Accessibility Service | `ZhiBanNotificationListenerService`；不使用无障碍服务 | 按系统专用通知访问页显式授权，只订阅 conversations/alerting，过滤知伴自身、常驻服务和空内容 | 保持通知访问可见可撤销；无障碍服务不作为默认感知方案 |
| `SmsCollector`、`CallLogCollector` | `ACTION_SEND text/plain` 用户主动分享 + 通知候选箱 | `READ_SMS`、`READ_CALL_LOG` 属于受限权限，旧轮询方案不适合普通上架应用；新入口清洗、限长、去重且不自动写联系人 | 除非产品正式成为默认短信、电话或助手应用并通过合规审核，否则不恢复后台读取 |
| `SocialVisibleTextParser`、`WeChatProfileParser` | 尚无 | 可形成联系人证据，但厂商 UI 易变化 | 放入可禁用适配器，失败不得污染联系人主数据 |
| `AttachmentExtractors/*`、ShareIngestor | 新工程图片/PDF，多数文档格式缺失 | 旧工程本地文本提取与沙箱值得复用 | 在感知入口之后迁移 Office/文本/分享进入 |
| `AgentGrowthRepository/Policy` | 反馈、运行记录 | 新工程有可观测性，尚未形成可解释的策略学习 | 最后迁移，必须可查看、可撤销、可重置 |
| 旧 onboarding/register 页面 | 当前直接进入主界面 | 与用户明确要求“不要引导页”冲突 | 不迁移 |
| 旧 UI、主题和超大 Compose 页面 | 当前 ChatGPT 风格组件 | 当前真实 App 是视觉基线 | 不复制旧页面，只复用信息与能力 |

## 已完成的第一条迁移

关系 TAB 新增手机通讯录导入：

1. 用户点击通讯录图标后才申请 `READ_CONTACTS`。
2. 只读 Android Contacts Provider，不在 Reader 中写数据库。
3. 先展示手机实际返回的联系人，支持全选和逐项取消。
4. 用户确认后才写入知伴联系人域。
5. Android lookup key 优先保证重复导入幂等；其次按标准化手机号匹配。
6. 与个人设置中用户手机号一致时，阻止创建“本人联系人”。
7. 不修改手机通讯录，不上传通讯录内容。

## 后续落地顺序

1. 通知 → MessageAggregator → 感知候选 → 用户确认的统一入口。
5. 短信、通话、无障碍和社交适配器；逐项独立授权和总开关。
6. 本地文档提取、分享进入、后台维护。
7. 可解释、可重置的 Agent 成长。
