# 知伴 Agent 架构差距矩阵（2026-07-21）

基准：`zhiban_agent_architecture_20260705.html`。状态只按代码真实路径和测试证据判断。

| 架构模块 | 当前状态 | 已有实现 | 第一阶段缺口 |
|---|---|---|---|
| 问问 UI | 第一阶段完成 | 独立 `:agent:feature-ask` 物理模块拥有对话、多模态、权限、附件和转写 UI 状态合同；Compose 组合层实现直接聊天、可可靠点击聚焦且高度受限的两段式输入框、文字后发送箭头、Chat/Work 紧凑模式菜单、跟随当前 Provider Profile 的模型白名单、附件入口、语音状态、计划卡片、回复操作；回复支持标题、段落、列表、引用、代码块、行内重点及流式未闭合代码块，长回复提供“滚动到最新”；Provider 未配置/鉴权、离线/弱网、可重试/最终失败、附件失败/URI 过期、转写失败均提供正确且可执行的恢复控件；云端转写失败保留私有录音并提供真实重试/删除；更多菜单可新建、列出、切换和删除 Runtime 持久会话；插件入口连接 Agent 工具/MCP 管理；模拟器实际发现并修复离线重试崩溃，现已重复点击验证不崩溃 | 后续阶段补历史搜索、会话重命名和跨设备同步 |
| Multimodal Gateway | 较完整 | 文本、云端优先/系统降级 ASR、相机/照片/文件选择与 App 私有安全暂存；ASR 经 Agent 自有 `CloudAsrGateway` 隔离 UI 与厂商协议，智谱 Profile 使用其同一 Keystore Key 调用官方 `glm-asr-2512`，其他未证明 Key 可复用的 Provider 在录音前降级系统 SpeechRecognizer；图片在发送前重新校验受控 cache 路径、TTL、长度和 SHA-256，并转换为 OpenAI 兼容的 multimodal `image_url` 内容进入统一 Provider；PDF 使用 Android `PdfRenderer` 将最多前三页渲染为受限尺寸图片后进入同一视觉理解链路；请求和 Runtime 事件不暴露私有 cacheRef；视觉能力声明与不支持模态的显式失败已接线 | 阿里/火山/MiniMax/腾讯各自官方 ASR bridge（只在确认同 Key/独立凭据后接入）、Office 文档解析、视频理解与更细粒度模态 fallback |
| Runtime Kernel | 较完整 | 独立 `:agent:runtime` 物理模块编译状态机、Session Actor、规划策略与幂等核心，App 仅保留 Android/Room 组合适配；持久化状态机、lease/fence、取消、重试、恢复、事件投影；规划策略选择器已将 Chat 映射为 Direct、Work 快速/平衡/深度映射为 Direct/ReAct/Plan-then-execute，并保持写操作确认边界；网络分级区分无 INTERNET、部分连通、极弱/弱/正常，Android 验证探针失败但 Provider 可达时按弱网执行有界请求，不再误判完全离线 | 多 Tool DAG 的并发调度证据、规划过程结构化事件、全链路 trace |
| Provider | 第一阶段完成 | 独立 `:agent:provider`；产品边界只暴露阶跃星辰一个 Preset，统一使用 Keystore credential；文字/图片/工具调用走 OpenAI-compatible streaming，文件转写走 `stepaudio-2.5-asr` SSE，右侧语音入口走 `stepaudio-2.5-realtime` WebSocket 双向实时语音；新 Key 先 probe 后发布，失败销毁临时版本并保留旧配置；HealthCheck 缓存 1 小时；瞬时错误受限退避并带熔断。阶跃未提供官方 Embedding API，因此生产检索显式降级为 FTS + Graph，不再要求第二把 Key | 真机麦克风、扬声器与弱网打断复测 |
| Tool 总线 | 较完整 | 独立 `:agent:tools` 合同/目录和 `:agent:governance` 策略边界；CapabilityRouter 统一注册、模型暴露、别名、预算、风险、确认、超时与读/写分派；独立 `:agent:mcp` 按官方 2025-06-18 实现 JSON-RPC 初始化、initialized 通知、分页工具发现、调用、错误校验及 Streamable HTTP（JSON/SSE、会话头、大小限制、HTTPS/本机限制）；工具页可添加/检测/启停/移除外部服务，Bearer Token 仅存 Keystore，换 Key 先以临时密钥验证再替换；发现的远端工具动态进入同一 Router，默认确认，执行后原子写账本并进入 Observation；配置、轮换、UI 与端到端执行均有设备测试 | Android 不适用的 Stdio 需在桌面宿主实现；MCP Resources/Prompts；完整审计查询 UI；远端参数的独立加密暂存/过期清理 |
| Calendar Tool | 第一阶段完成 | `calendar.schedule.search` 真实时间范围读取；`calendar.schedule.conflicts` 重叠区间检测；create/update/delete 均进入 CapabilityRouter，写操作必须确认并原子完成业务表、Fact、ToolAudit、ChangeLog、执行账本与 Observation；三种写操作均支持 Runtime Undo | 后续阶段补日历 TAB 的已有日程手动编辑 UI 与外部系统日历双向同步 |
| Contact Tool | 部分完成 | v10 Contact/ContactRole 真实数据模型；`contact.search/getDetail` 已经由 CapabilityRouter 自动执行、入工具账本并回灌模型；`contact.createCandidate` 已完成独立暂存、确认、Domain Writer、审计、ChangeLog 与 Runtime Undo | 联系人导入/编辑入口；update/merge/sync 写工具及治理；敏感暂存加密 |
| Memory Tool | 较完整 | 独立纯 Kotlin `:agent:memory` 物理模块定义无 Android/Room 依赖的统一 MemoryGate；RoomMemoryGate 是可替换持久层适配器，生产环境中的设置、remember/search/delete、Context 检索及生命周期维护已全部通过 Gate；会话短期记忆持久化并回灌；长期记忆 remember 候选、确认、原子提交、精确+FTS+Vector+Graph 融合检索；删除必须确认，并在单一事务内完成 tombstone、FTS/Fact/Embedding 失效屏障、ToolAudit、执行账本及 Observation；Fact TTL 级联清理、180 天休眠、启动及 WorkManager 周期维护均已接线 | 跨设备记忆同步与更细的用户可见证据管理属于后续阶段 |
| Relationship Tool | 较完整 | Room v15 `relationship_edges` 独立数据模型与 14→15 迁移；真实 2-hop 遍历；`relationship.search`、脱敏 `relationship.getEvidence`、`relationship.createCandidate` 均进入 Capability Router；写入必须用户确认并经唯一 Domain Writer，具备审计、执行账本、observation 和 Undo；事件不保存证据原文 | 已有关系的 update/review 状态流与关系维护 UI |
| Context Retrieval | 较完整 | 独立 `:agent:context`；本地意图识别和 Entity Extraction，50ms 超时降级；Structured/FTS/Vector/Graph 三路并行与 500ms 单路降级；中文字符分词补偿；关系 Graph 2-hop；本地 RRF；Provider LLM rerank；top-15 与 token budget；火山方舟官方 Embeddings 生产适配器采用 Keystore、探测后发布、安全换 Key、语义空间隔离，全量重建完成前 FTS-only，启动与 WorkManager 分批 backfill；运行事件只保存脱敏 ID/统计 | Android framework SQLite 当前实测无 FTS5，运行时探测后使用 FTS4；待依赖条件允许升级 BundledSQLiteDriver；意图分类词典持续扩展 |
| Skill | 第一阶段完成 | 独立 `:agent:skills` 物理模块；版本化 SkillSpec、确定性 Activator、工具可用性门控；内置日程协调、联系人关系维护、偏好记忆技能；仅在 Work 模式匹配意图时激活，并以可信技能策略进入规划上下文 | 后续阶段补安装包、签名、第三方 Skill 与可视化编排 |
| Governance | 第一阶段完成 | 所有生产 Tool 仅由 CapabilityRouter 暴露；写操作统一经过 ActionProposal、ActionPolicy、持久确认、digest、幂等与 lease fencing；日历事务写边界、ContactDomainWriter、RelationshipDomainWriter、MemoryAtomicStore 分别原子维护业务表、Fact、ToolAudit、执行账本与 Observation；第一阶段日历 create/update/delete、联系人候选、关系候选及记忆删除均有 ChangeLog/Undo 或删除屏障；统一只读审计投影和内容白名单诊断导出，ToolAudit 90 天保留 | 后续新增任何写域必须同步注册 Domain Writer 与 Undo；更细粒度审计检索属于后续阶段 |
| Data | 较完整 | Room v16；Runtime/Plan/Memory/Attachment/Contact/Relationship/ChangeLog/Fact/EmbeddingVector 表与正式迁移；Fact、FTS 和向量级联一致性；TTL、180 天休眠、启动维护及 12 小时 WorkManager；Provider 切换不混合语义空间；ToolAudit 可按 runtimeRunId 进入统一脱敏查询模型 | Bundled SQLite FTS5；旧域数据一次性 Fact backfill |
| Security | 较完整 | Keystore Key、API Key/Bearer/手机号/邮箱/身份证号统一诊断脱敏、私有附件暂存、全局禁用明文网络且只为模拟器本机 MCP 保留 localhost 例外；五家正式 Provider 主机均启用中间 CA + 根 CA 备用 SPKI 固定，服务商注册表与固定主机集合由单测锁定，并在 Android 15 模拟器逐家完成真实 HTTPS 握手；轮换规则为先加入替代中间 CA、发布覆盖后再移除旧 pin；Agent Room 数据库使用 SQLCipher 4.15，32-byte 随机口令仅以 Android Keystore AES-GCM 包装后持久化；已有明文库启动前经 `sqlcipher_export` 原子迁移、完整性验证并擦除备份，设备测试验证文件头/敏感明文不可见、版本及数据可重复重开 | 完整自动化 PII 污染扫描；SQLCipher 目前为 Agent 单库全量加密，后续可按独立物理库细分 L2 数据域 |
| Observability | 较完整 | Runtime event、attempt、tool execution 与 ToolAudit 已统一投影为只读脱敏 AgentRunTrace；智能体设置“运行记录”按感知、记忆、规划、用户确认、工具执行、环境/人类反馈展示闭环状态、工具名和安全降级路径，并展示最近 50 次的成功率、平均耗时、首字 p95、Context 检索 p95、工具平均耗时、工具执行数和安全降级率；指标不读取对话正文；ToolAudit 执行 90 天强制保留清理；页面可生成并通过系统分享面板导出内容白名单诊断 JSON，设备污染测试证明即使事件含姓名、手机号、邮箱、Key，也不导出正文和任何 Runtime 标识 | Vector/Graph/Rerank 各分路 p95、正式脱敏错误上报通道 |
| Feature Flag | 第一阶段完成 | `AgentDynamicConfigStore` 实现代码默认值→远程快照→用户覆盖三级优先级并严格校验；运行时实时控制强制 FTS-only、混合检索、LLM rerank、Skill 下架、MCP 远端能力、Provider 黑名单、LLM 超时和 Context 上限；文本 Runtime 核心不允许被非受控用户 Flag 关闭；远程强制降级和 Provider 封禁均有设备集成测试 | 后续接具体 Remote Config 拉取/签名通道和运营后台 |
| Agent 设置 | 较完整 | 统一视觉的七个真实入口：个性化、记忆、大模型连接、工具、行为与安全、反馈与改进、运行记录；开关/执行偏好持久化并进入 Runtime；工具逐项启停；MCP 服务增删、检测、启停；大模型连接页同时提供火山语义检索配置、健康检查、安全换 Key 与清除，密钥仅进 Keystore；七页导航和关键交互有 Compose 设备端 E2E | 工具权限按场景/联系人/数据敏感度进一步细分 |
| 测试 | 部分完成 | Runtime/Provider/Plan/Memory/Embedding/Entity Extraction/RRF/Rerank/Skill 单测与 instrumentation；阶跃星辰唯一 Preset、旧 Provider 拒绝、模型隔离、Keystore、动态降级、Provider 退避/流安全重试/熔断；图片/PDF 私有暂存；持久会话；日历与工具治理；MCP、联系人、关系、记忆闭环；Room v16 与 SQLCipher；诊断包隐私；智能体设置和问问 Compose E2E。2026-07-22：179 项单测与 Android 15 模拟器 188 项连接测试通过；真实 Key HealthCheck、文字 SSE、文件 ASR SSE、Realtime WebSocket 101、实时音频输出均在模拟器通过 | 真机麦克风/扬声器、弱网中断恢复、图片真实理解与写工具逐按钮最终复测 |

## 实施顺序

1. 固化当前主线：将 t31 未提交整合按模块拆分并建立回归基线。
2. 统一工具合同与 CapabilityRouter，先迁移现有日程/记忆工具。
3. 补齐 ActionPolicy、Domain Writer、Audit/Undo。
4. 合并 t39 记忆检索和 t41 Provider ASR，不覆盖现有 UI。
5. 实现联系人读工具与最小结构化检索/FTS。
6. Provider HealthCheck/Fallback。
7. 模拟器逐按钮、断网、重启、重复操作回归。

## 不计为完成

- 空回调、假数据、仅 Toast、仅弹“已接入”说明。
- 只有单元测试但生产入口未接线。
- 只有 UI 能点但数据库/系统目标无可核对结果。
- 只在某个旁支 worktree 存在、未进入唯一集成主线。
