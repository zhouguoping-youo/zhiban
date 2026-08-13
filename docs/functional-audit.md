# 知伴 App 全功能真机审计清单

> 唯一事实源。测试设备：Samsung SM-W7023 / Android 13 / `R5CT20QKT9D`。  
> 起始基线：`d530d00`；包名：`com.zhiban.rebuild.debug`。  
> 状态：`❌已复现待修` / `✅已修复` / `⚪无法复现` / `🖐需人工` / `🧪需环境`。  
> 严重度：`崩溃` > `数据丢失` > `功能不可用` > `体验` > `文案`。

## 1. 日历 TAB

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 1.1 | 新建本地日程 | ⚪无法复现 | 未发现功能问题：保存后立即显示并提示“日程已添加” | 新建 `AUDIT_CAL_001`，日期 2026-08-13、13:00、60 分钟并保存 | — | — | — | 既有日历持久化测试 | 2026-08-13 真机验证通过 |
| 1.2 | 查看本地日程 | ⚪无法复现 | 未发现功能问题：周视图切换日期后数据正确刷新；全日历展开后仅显示月历，不重复显示周历 | 依次点 13、14 日检查当天及跨天日程；展开全日历并检查月视图 | — | — | — | 既有日历查询测试 | 2026-08-13 真机验证通过 |
| 1.3 | 编辑本地日程 | ⚪无法复现 | 未发现功能问题：编辑保存后标题立即刷新 | 将 `AUDIT_CAL_001` 改为 `AUDIT_CAL_001_EDIT` 后保存 | — | — | — | 既有日历持久化测试 | 2026-08-13 真机验证通过 |
| 1.4 | 删除本地日程 | ⚪无法复现 | 未发现功能问题：二次确认后日程消失并提示“日程已删除” | 删除 `AUDIT_CAL_001_EDIT` 并确认 | — | — | — | 既有日历持久化测试 | 2026-08-13 真机验证通过 |
| 1.5 | 跨天日程 | ⚪无法复现 | 未发现功能问题：23:30–次日 01:30 在开始日与次日都可见，次日重叠冲突可识别 | 创建 `AUDIT_CROSS_001`（2026-08-13 23:30、120 分钟），分别查看 13/14 日并新建 14 日 00:30 冲突项 | — | — | — | 既有跨日范围与冲突测试 | 2026-08-13 真机验证通过 |
| 1.6 | 到点提醒与提前 N 分钟 | ⚪无法复现 | 未发现功能问题：授予通知权限后，WorkManager 到点执行，通知标题、频道和锁屏隐私均正确 | 为 `AUDIT_CROSS_001` 设置提前 1 天提醒，使触发时间已到；检查通知中心和 WorkManager 完成状态 | — | — | — | `ScheduleReminderWorkerTest`；`ScheduleReminderSchedulerTest` | 2026-08-13 真机验证通过：通知已显示，频道 `schedule-reminders`，`VISIBILITY_PRIVATE`，Worker 状态 SUCCEEDED |
| 1.7 | 系统日历导入 | ✅已修复 | 三星系统日历存在正常事件，知伴仍显示“没有可导入的系统日程” | 授予日历权限；在三星日历新建 `AUDIT_SYS_CAL_001`；回到日历 TAB 点“导入手机日历” | 功能不可用 | `Instances.STATUS != ?` 会把 `STATUS=NULL` 的正常事件一并排除 | `0b2853d` | `SystemCalendarReaderSelectionTest.instanceQueryKeepsEventsWhoseStatusIsNull` | 2026-08-13 修复包复验：可读取 My calendar/中国节日共 10 条并成功导入 |
| 1.8 | 重复导入去重 | ⚪无法复现 | 未发现重复：第二次导入显示“10 条已更新”，没有新增副本 | 对同一批 10 条系统日历事件连续导入两次 | — | — | — | `AgentDataRepositoryTest.confirmedSystemCalendarImportIsIdempotent`；`CalendarPersistenceEdgeTest.duplicateSystemCalendarInstancesInOneImportAreStoredOnce` | 2026-08-13 真机验证通过 |
| 1.9 | 改期后旧提醒失效 | ⚪无法复现 | 未发现功能问题：同一日程改期后，旧唯一任务被替换，新任务按新时间重新计算延迟 | 将 `AUDIT_CROSS_001` 设为 2026-08-14 13:30、提前 1 天；记录任务后改为 14:30，再检查 WorkManager | — | — | — | `ScheduleReminderSchedulerTest.reschedulingSameScheduleReplacesOldReminder`；`ScheduleReminderWorkerTest.staleWorkerDoesNotNotify` | 2026-08-13 真机验证通过：唯一任务名不变，任务 ID `dd33…`→`4a4a…`，延迟约 34.6 分钟→94.2 分钟，旧任务已移除 |
| 1.10 | CRM 下一步动作联动 | — | 尚未实测 | — | — | — | — | — | — |
| 1.11 | 消息日程误识别 | ✅已修复 | 含日期时间的普通“合约到期”消息被当作“约见”日程候选 | 用通知感知输入“明天下午3点这个合约到期” | 功能不可用 | 日程动作词把单字“约”作为任意子串匹配，命中“合约” | `a2773e5` | `SocialMessagePerceptionTest.contractDeadlineWithoutSchedulingIntentIsNotMistakenForAnAppointment`；`explicitAppointmentStillProducesASchedule` | 修复包待真机复验 |
| 1.12 | 日程完成、延期与结果反馈 | ✅已修复 | 日程到期后没有待反馈状态，也不能记录完成结果或延期，用户无法闭环安排 | 创建一个日程，等待到期后查看日历；尝试记录结果或改期 | 功能不可用 | `ScheduleEntity` 只有时间、标题和备注，没有生命周期与结果字段；日历行只有编辑、删除 | `a699b82` | `ScheduleLifecycleMigrationTest.existingSchedulesRemainPendingAfterLifecycleMigration`；`CalendarPersistenceEdgeTest.completionStoresFeedbackAndReschedulingReopensTheSchedule`；`ScheduleLifecycleLabelTest` | 2026-08-13 真机验证：数据库 37→38 与持久化 5/5 通过；新建 `AUDIT_LIFECYCLE` 后可打开“更新进展”，键盘反馈 `AUDIT_DONE` 持久化并显示“已完成 / 结果：AUDIT_DONE”；语音入口可启动系统识别器；延期进入原日程改期页 |
| 1.13 | 跨来源重复日程 | ✅已修复 | 知伴中已有同标题、同开始时间、同时长的日程，再导入系统日历会新增一条副本；不同消息候选也只按来源键去重 | 先在知伴创建“向王经理发送武汉医院项目最终报价单”；再从系统日历导入同一事件；另用两个不同消息来源确认同一安排 | 功能不可用 | 去重仅使用各来源自己的 ID/sourceKey，没有统一的日程语义身份；跨系统日历、通知、Agent 来源无法相认 | `b3de213` | `CalendarPersistenceEdgeTest.equivalentSystemEventDoesNotDuplicateAnExistingZhiBanSchedule`；`AgentDataRepositoryTest.equivalentScheduleCandidatesFromDifferentMessagesReuseOneCalendarEvent` | 2026-08-13 真机设备测试 50/50 通过；修复前定向用例红：期望 0 新增、实际 1；修复后同标题标准化、开始时间 ±5 分钟、时长 ±5 分钟内复用既有日程 |
| 1.14 | 每日待反馈汇总 | ✅已修复 | 昨日及更早未完成日程只留在原日期，今天首页不可见，用户无法每天集中完成、延期或补充结果 | 创建一个已过期未完成日程，回到今天；修复前首页不显示；修复后在“待反馈”卡片处理 | 功能不可用 | 日历只订阅当前选中日的时间范围，没有跨日查询 PENDING 且已结束的安排 | `3340b0d` | `CalendarPersistenceEdgeTest.pendingFeedbackIncludesOnlyElapsedUnfinishedSchedules` | 2026-08-13 真机设备测试 6/6 通过；今日页汇总近 90 天最多 20 项，完成后自动移出，延期后进入改期页；Agent 不凭时间自动完成 |
| 1.15 | Agent 日程标题质量 | ✅已修复 | 模型返回“日程安排/提醒事项”等空泛标题时直接落库；“和张总开项目复盘会”被清洗成生硬的“张总开项目复盘会” | 在问问输入“提醒我明天晚上8点接孩子”“明天下午3点和张总开武汉项目复盘会”并确认 | 功能不可用 | 最终工具调用规范化层无条件优先相信非空模型标题；标题清洗正则把所有“和…”前缀都删除 | 本提交 | `CalendarTimeResolutionTest.normalizeReplacesGenericModelTitleWithTheUsersActualTask`；`normalizeReplacesGenericReminderTitleAfterSanitization`；`deterministicTitleKeepsTheCounterpartyRelationshipNatural`；`deterministicTitleDoesNotMistakeAPlaceStartingWithHeForAPerson`；`deterministicTitleKeepsTheConcreteObjectAndAction`；`CalendarTitleNormalizationDeviceTest` | 2026-08-13 SM-W7023 设备测试 2/2 通过；首次设备测试还抓出“提醒事项”经 Android 清洗后变“事项”的差异并补修。联网实测因覆盖安装后设备模型连接丢失暂未验证 |

## 2. 关系 TAB

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 2.1 | 系统通讯录导入 | ✅已修复 | 授权前说明称“不会默认全选”，进入选择页却默认勾选全部 1198 人，说明与真实行为相反 | 清除通讯录权限；关系 TAB 点导入→继续→允许；观察授权前说明与选择页初始状态 | 文案 | 授权说明硬编码了旧策略，选择页当前设计是默认全选且允许取消 | `ba3b3bb` | `RelationPhoneMatchingTest.contactImportPermissionIntroDescribesDefaultSelectionTruthfully` | 2026-08-13 修复包复验：说明明确“默认勾选、导入前可取消”；真实导入 1198 人约 22 秒完成，无 ANR/闪退，落库 1193 人、5 人匹配更新 |
| 2.2 | 搜索与标签筛选 | ⚪无法复现 | 未发现功能问题：1194 位联系人下，姓名、手机号、公司和备注均能精确命中；大类筛选不会串类 | 新建 `AUDIT_RELATION_001`，分别搜索姓名、`13800138001`、`Audit Company`、`AUDIT_NOTE_001`；清空搜索后依次选择“工作”和“家人” | — | — | — | `RelationGraphInferenceTest.expanded taxonomy participates in existing category filters`；既有 Room 联系人查询测试 | 2026-08-13 真机验证通过：四种字段均返回唯一测试联系人；工作包含、家人排除，无卡顿/闪退 |
| 2.3 | 联系人详情编辑 | ⚪无法复现 | 未发现功能问题：编辑保存后详情与列表即时刷新，未修改字段保持不变 | 打开 `AUDIT_RELATION_001` 详情，将职位 `Tester` 改为 `Tester2` 并保存；重新打开详情核对手机号、微信、公司和备注 | — | — | — | 既有联系人持久化与详情设备测试 | 2026-08-13 真机验证通过：详情显示 `Audit Company Ltd · Tester2`，其余字段未丢失 |
| 2.4 | 同一联系人多身份 | ⚪无法复现 | 未发现覆盖问题：同一联系人可以并存不同类型关系，新增第二种关系不会覆盖第一种 | 以“我”和 `AUDIT_RELATION_001` 先建立“下属”，再新增“兴趣圈友”；返回关系图并打开联系人详情 | — | — | — | `ContactTemporalWriteTest.endingOneRelationshipTypeKeepsOtherTypeCurrent`；`AgentDataRepositoryTest.signedInUserCanBeAConfirmedRelationshipEndpointWithoutBecomingAContact` | 2026-08-13 真机验证通过：关系图统计由 1 条增至 2 条；详情“关联的人”同时显示“兴趣圈友”和“下属”，两条均指向“我” |
| 2.5 | 合并与撤销合并 | ⚪无法复现 | 未发现数据丢失或无法恢复：合并源从列表隐藏，主资料保留恢复入口，撤销后源联系人重新可见 | 在“联系人维护”处理“叶孝玲 / 成都乐心”重复建议（两个手机号）；确认合并后分别搜索两个号码，再从主联系人详情点“恢复”并复搜 | — | — | — | `ContactMergeChainTest.undoConfirmedMergeRestoresSourceVisibilityAndClearsLink`；`AgentDataRepositoryTest.confirmedContactMergeIsNonDestructiveAndReversible` | 2026-08-13 真机验证通过：联系人总数 1194→1193，主资料显示“已合并资料/恢复”；恢复后总数回到 1194，`15682127000` 再次独立命中，真实资料已还原 |
| 2.6 | 关系图谱点击/拖动/缩放/聚焦 | ⚪无法复现 | 未发现交互失效：节点点击、节点拖动、画布平移和重置聚焦均有即时反馈 | 打开含 2 条关系的“我的关系图”；点击 `AUDIT_RELATION_001` 节点；关闭详情后拖动节点、平移画布并点击重置 | — | — | — | `RelationshipGraphInteractionTest.personNodeHasNamedTouchTargetAndOpensContact`；`ForceRelationshipGraphTest.seed places requested focus at viewport center`；`ForceRelationshipGraphTest.simulation combines repulsion spring centering and damping` | 2026-08-13 真机验证通过：点击打开包含关系强度和关联对象的详情抽屉；拖动后节点位置改变；画布可平移；重置后“我”回到中心。双指缩放实现纳入最终设备测试闸 |
| 2.7 | 智能完善建议确认/拒绝 | ✅已修复 | “联系人维护”的资料待核实入口只跳到空白问问会话，既未传入提示，也没有读取待核实候选的工具，26 条真实建议无法从维护入口处理 | 关系 TAB→联系人维护→点“26 条建议”；修复前进入问问且看不到候选；修复后直接打开候选面板，分别确认“于军”和拒绝“付铨”的公司建议 | 功能不可用 | `ContactMaintenancePage` 将已有 `pendingEnrichment` 错接到 `onAsk()`，没有复用联系人详情已存在的确认/拒绝路径 | 本提交 | `ContactMaintenanceEnrichmentTest.pendingSuggestionsAreVisibleAndExposeConfirmAndRejectActions`；`ContactEnrichmentConfirmTest.confirmAppliesScalarFieldToBlankProfileAndResolvesCandidate`；`ContactEnrichmentConfirmTest.rejectMarksCandidateDismissed` | 2026-08-13 真机修复包验证通过：面板显示联系人、字段、置信度和证据来源；确认/拒绝后计数 26→24，两条均移出待核实列表；定向真机设备测试 1/1 通过 |
| 2.8 | 通知候选处理 | ⚪无法复现 | 未发现功能问题：支持来源的新通知能进入待确认列表，展示推断结论和原始依据；确认后联系人、证据和日程写入一致 | 在真机临时安装不进仓库的 `audit.wechat.sender` 通知发送器，发送标题 `AUDIT通知联系人`、正文“明天下午3点开会，请确认”的真实 Android 消息通知；在关系页确认“新建联系人并加入日历” | — | — | — | `NotificationCandidateDialogTest.unresolvedMessageShowsAgentConclusionBeforeRawCollectionSettings`；`AgentDataRepositoryTest.confirmedNotificationCreatesEvidenceAndIdentity`；`AgentDataRepositoryTest.confirmNotificationScheduleIsIdempotent` | 2026-08-13 真机验证通过：Listener 实时接收；待确认页识别为微信收到的安排；确认后联系人总数 1193→1194，8 月 14 日 15:00 出现“开会，请确认”，来源显示“由微信消息确认添加 · AUDIT通知联系人”；临时发送器已卸载 |
| 2.9 | 通话备注 | 🧪需环境 | 通话记录同步正常，但测试机当前蜂窝网络 `OUT_OF_SERVICE`，10086 呼叫在 CONNECTING 阶段立即失败并落为 0 秒，按产品规则不会触发挂断备注，无法完成真实有效通话复验 | 授予通话记录/电话状态权限；开启“同步通话记录”和“挂断后提醒补充要点”；同步真实最近 90 天通话；尝试拨打 10086 | — | — | — | `CallLogImporterTest.callNotePersistsFactAndCompletesPrompt`；`CallLogImporterTest.dismissedCallNoteDoesNotReturnToPendingList`；`CallLogRepositoryTest` | 2026-08-13 真机已验证权限探针为“已允许，可读取”，同步 31 条真实记录；双卡无服务导致拨号立即失败，需 SIM 恢复网络后完成一次持续 >0 秒的呼叫，检查私密通知→手输/语音备注→联系人时间线 |

## 3. 问问（Agent 对话）

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 3.1 | 文本问答 | — | 尚未实测 | — | — | — | — | — | — |
| 3.2 | 语音输入转写 | — | 尚未实测 | — | — | — | — | — | — |
| 3.3 | 图片识别 | — | 尚未实测 | — | — | — | — | — | — |
| 3.4 | 基于关系库查询 | — | 尚未实测 | — | — | — | — | — | — |
| 3.5 | 确认卡：缺参/取消/确认/pending | — | 尚未实测 | — | — | — | — | — | — |
| 3.6 | 流式输出完整性 | — | 尚未实测 | — | — | — | — | — | — |
| 3.7 | 记忆写入与召回 | — | 尚未实测 | — | — | — | — | — | — |

## 4. 能力 TAB

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 4.1 | CRM 线索进入候选池 | — | 尚未实测 | — | — | — | — | — | — |
| 4.2 | CRM 候选转正为商机 | — | 尚未实测 | — | — | — | — | — | — |
| 4.3 | CRM 商机阶段推进 | — | 尚未实测 | — | — | — | — | — | — |
| 4.4 | CRM 沟通活动 | — | 尚未实测 | — | — | — | — | — | — |
| 4.5 | CRM 下一步动作 | — | 尚未实测 | — | — | — | — | — | — |
| 4.6 | 生活助理：重要日期 | — | 尚未实测 | — | — | — | — | — | — |
| 4.7 | 生活助理：承诺提醒 | — | 尚未实测 | — | — | — | — | — | — |
| 4.8 | 一起安排：多人协调 | — | 尚未实测 | — | — | — | — | — | — |

## 5. 我的 / 设置

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 5.1 | 大模型连接配置与重连 | — | 尚未实测 | — | — | — | — | — | — |
| 5.2 | 个人资料保存 | — | 尚未实测 | — | — | — | — | — | — |
| 5.3 | 记忆管理 | — | 尚未实测 | — | — | — | — | — | — |
| 5.4 | 存储占用显示与缓存清理 | — | 尚未实测 | — | — | — | — | — | — |
| 5.5 | 权限管理 | — | 尚未实测 | — | — | — | — | — | — |
| 5.6 | 隐私开关 | — | 尚未实测 | — | — | — | — | — | — |

## 6. 系统边界

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 6.1 | 通知监听断开后重连 | — | 尚未实测 | — | — | — | — | — | — |
| 6.2 | 无障碍服务被杀后恢复 | — | 尚未实测 | — | — | — | — | — | — |
| 6.3 | 权限撤销后降级 | — | 尚未实测 | — | — | — | — | — | — |
| 6.4 | 弱网下 Agent 行为 | — | 尚未实测 | — | — | — | — | — | — |
| 6.5 | 断网下 Agent 行为 | — | 尚未实测 | — | — | — | — | — | — |
| 6.6 | App 被杀后提醒仍触发 | — | 尚未实测 | — | — | — | — | — | — |

## 需人工 / 需环境汇总

- `2.9 通话备注`：SM-W7023 当前两张 SIM 均无蜂窝服务。恢复任一卡网络后，拨打一通持续至少 5 秒的电话并挂断；预期 2 秒后出现私密“记录刚才的通话要点”通知，保存文字或语音备注后应进入已匹配联系人的最近互动，且不再重复提示。

## 建议（不在本任务实现）

尚无。仅记录非 bug 的产品增量想法。
