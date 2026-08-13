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
| 1.10 | CRM 下一步动作联动 | ✅已修复 | CRM 待跟进行只按截止时间跳到日历，实际 `crm_next_actions.scheduleId` 始终为空；日历改期/删除虽有外键模型，却没有任何创建链路会建立关联 | 创建 CRM 下一步动作后让 Agent 安排到日历并确认；检查动作记录的 `scheduleId`、截止时间与新日程 | 功能不可用 | `calendar.schedule.create` 不接受 CRM 动作标识，执行器只写日程，不在同一事务绑定动作；界面“查看日历”造成已联动假象 | `25f75fe` | `RoomScheduleToolExecutorTest.confirmedScheduleLinksItsPendingCrmActionInTheSameTransaction`；`missingCrmActionRollsBackTheConfirmedScheduleAndItsAudit`；`RuntimeToolContractsTest.schedule digest and plan bind the optional CRM action` | 2026-08-13 修复前定向用例红（期望 `schedule-1`、实际 `null`）；修复后模拟器及 SM-W7023 执行器均 12/12 通过。工具确认后在同一事务写日程、同步动作截止时间并绑定 `scheduleId`；无效动作整笔回滚。联网 UI 复验待安装含本修复的正式测试包后执行 |
| 1.11 | 消息日程误识别 | ✅已修复 | 含日期时间的普通“合约到期”消息被当作“约见”日程候选 | 用通知感知输入“明天下午3点这个合约到期” | 功能不可用 | 日程动作词把单字“约”作为任意子串匹配，命中“合约” | `a2773e5` | `SocialMessagePerceptionTest.contractDeadlineWithoutSchedulingIntentIsNotMistakenForAnAppointment`；`explicitAppointmentStillProducesASchedule` | 修复包待真机复验 |
| 1.12 | 日程完成、延期与结果反馈 | ✅已修复 | 日程到期后没有待反馈状态，也不能记录完成结果或延期，用户无法闭环安排 | 创建一个日程，等待到期后查看日历；尝试记录结果或改期 | 功能不可用 | `ScheduleEntity` 只有时间、标题和备注，没有生命周期与结果字段；日历行只有编辑、删除 | `a699b82` | `ScheduleLifecycleMigrationTest.existingSchedulesRemainPendingAfterLifecycleMigration`；`CalendarPersistenceEdgeTest.completionStoresFeedbackAndReschedulingReopensTheSchedule`；`ScheduleLifecycleLabelTest` | 2026-08-13 真机验证：数据库 37→38 与持久化 5/5 通过；新建 `AUDIT_LIFECYCLE` 后可打开“更新进展”，键盘反馈 `AUDIT_DONE` 持久化并显示“已完成 / 结果：AUDIT_DONE”；语音入口可启动系统识别器；延期进入原日程改期页 |
| 1.13 | 跨来源重复日程 | ✅已修复 | 知伴中已有同标题、同开始时间、同时长的日程，再导入系统日历会新增一条副本；不同消息候选也只按来源键去重；同一事项的“和张总开武汉项目复盘会 / 武汉项目复盘会”仍不能相认 | 先在知伴创建“向王经理发送武汉医院项目最终报价单”；再从系统日历导入同一事件；另用两个不同消息来源确认同一安排；最后测试仅相差明确参与人前缀的标题变体 | 功能不可用 | 去重仅使用各来源自己的 ID/sourceKey，初版语义身份又要求标题逐字相同；跨系统日历、通知、Agent 来源和参与人前缀变体无法相认 | `b3de213`；`2e74641` | `CalendarPersistenceEdgeTest.equivalentSystemEventDoesNotDuplicateAnExistingZhiBanSchedule`；`equivalentCrossSourceTitlesIgnoreOnlyAConcreteParticipantPrefix`；`differentMeetingSubjectsAtTheSameTimeRemainDifferentIdentities`；`AgentDataRepositoryTest.equivalentScheduleCandidatesFromDifferentMessagesReuseOneCalendarEvent` | 2026-08-13 真机设备测试 50/50 通过；补强测试修复前先红，修复后模拟器 2/2 通过。同标题标准化、开始时间 ±5 分钟、时长 ±5 分钟内复用既有日程；仅剥离“和/与+明确参与人+会议动作”前缀，不会把不同项目主题合并 |
| 1.14 | 每日待反馈汇总 | ✅已修复 | 昨日及更早未完成日程只留在原日期，今天首页不可见，用户无法每天集中完成、延期或补充结果 | 创建一个已过期未完成日程，回到今天；修复前首页不显示；修复后在“待反馈”卡片处理 | 功能不可用 | 日历只订阅当前选中日的时间范围，没有跨日查询 PENDING 且已结束的安排 | `3340b0d` | `CalendarPersistenceEdgeTest.pendingFeedbackIncludesOnlyElapsedUnfinishedSchedules` | 2026-08-13 真机设备测试 6/6 通过；今日页汇总近 90 天最多 20 项，完成后自动移出，延期后进入改期页；Agent 不凭时间自动完成 |
| 1.15 | Agent 日程标题质量 | ✅已修复 | 模型返回“日程安排/提醒事项”等空泛标题时直接落库；“和张总开项目复盘会”被清洗成生硬的“张总开项目复盘会” | 在问问输入“提醒我明天晚上8点接孩子”“明天下午3点和张总开武汉项目复盘会”并确认 | 功能不可用 | 最终工具调用规范化层无条件优先相信非空模型标题；标题清洗正则把所有“和…”前缀都删除 | `a7c1ac5` | `CalendarTimeResolutionTest.normalizeReplacesGenericModelTitleWithTheUsersActualTask`；`normalizeReplacesGenericReminderTitleAfterSanitization`；`deterministicTitleKeepsTheCounterpartyRelationshipNatural`；`deterministicTitleDoesNotMistakeAPlaceStartingWithHeForAPerson`；`deterministicTitleKeepsTheConcreteObjectAndAction`；`CalendarTitleNormalizationDeviceTest` | 2026-08-13 SM-W7023 设备测试 2/2 通过；首次设备测试还抓出“提醒事项”经 Android 清洗后变“事项”的差异并补修。联网实测因覆盖安装后设备模型连接丢失暂未验证 |
| 1.16 | 询问式安排与复合会议识别 | ✅已修复 | “明天下午三点开武汉项目复盘会”漏识别；“明天下午三点开会员续费服务”可能被“开会”误判；“……开会吗？”若联系人已匹配会被当作 0.99 高置信并自动写日历 | 分别输入确定安排、会员服务通知和询问式安排；检查候选置信度、自动写入及标题 | 功能不可用 | 动作词只支持固定“开会”，既不支持“开+主题+复盘会”，又把“开会员”前缀当作“开会”；置信度只看日期，未识别句尾疑问语气 | `e6c01f0` | `SocialMessagePerceptionTest.tentativeScheduleQuestionRemainsConfirmableButIsNotHighConfidence`；`confirmedCompoundMeetingTitleIsRecognizedWithHighConfidence`；`unrelatedMembershipTextIsNotMistakenForACompoundMeeting`；`AgentDataRepositoryTest.tentativeScheduleQuestionNeverAutomaticallyCreatesCalendarEvent` | 2026-08-13 修复前单测先红；修复后识别测试 22/22、模拟器持久层定向设备测试 1/1 通过。询问句仍可供用户确认，但置信度 0.90，不会自动写日历；确定句保持 0.99 |

## 2. 关系 TAB

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 2.1 | 系统通讯录导入 | ✅已修复 | 授权前说明称“不会默认全选”，进入选择页却默认勾选全部 1198 人，说明与真实行为相反 | 清除通讯录权限；关系 TAB 点导入→继续→允许；观察授权前说明与选择页初始状态 | 文案 | 授权说明硬编码了旧策略，选择页当前设计是默认全选且允许取消 | `ba3b3bb` | `RelationPhoneMatchingTest.contactImportPermissionIntroDescribesDefaultSelectionTruthfully` | 2026-08-13 修复包复验：说明明确“默认勾选、导入前可取消”；真实导入 1198 人约 22 秒完成，无 ANR/闪退，落库 1193 人、5 人匹配更新 |
| 2.2 | 搜索与标签筛选 | ⚪无法复现 | 未发现功能问题：1194 位联系人下，姓名、手机号、公司和备注均能精确命中；大类筛选不会串类 | 新建 `AUDIT_RELATION_001`，分别搜索姓名、`13800138001`、`Audit Company`、`AUDIT_NOTE_001`；清空搜索后依次选择“工作”和“家人” | — | — | — | `RelationGraphInferenceTest.expanded taxonomy participates in existing category filters`；既有 Room 联系人查询测试 | 2026-08-13 真机验证通过：四种字段均返回唯一测试联系人；工作包含、家人排除，无卡顿/闪退 |
| 2.3 | 联系人详情编辑 | ⚪无法复现 | 未发现功能问题：编辑保存后详情与列表即时刷新，未修改字段保持不变 | 打开 `AUDIT_RELATION_001` 详情，将职位 `Tester` 改为 `Tester2` 并保存；重新打开详情核对手机号、微信、公司和备注 | — | — | — | 既有联系人持久化与详情设备测试 | 2026-08-13 真机验证通过：详情显示 `Audit Company Ltd · Tester2`，其余字段未丢失 |
| 2.4 | 同一联系人多身份 | ⚪无法复现 | 未发现覆盖问题：同一联系人可以并存不同类型关系，新增第二种关系不会覆盖第一种 | 以“我”和 `AUDIT_RELATION_001` 先建立“下属”，再新增“兴趣圈友”；返回关系图并打开联系人详情 | — | — | — | `ContactTemporalWriteTest.endingOneRelationshipTypeKeepsOtherTypeCurrent`；`AgentDataRepositoryTest.signedInUserCanBeAConfirmedRelationshipEndpointWithoutBecomingAContact` | 2026-08-13 真机验证通过：关系图统计由 1 条增至 2 条；详情“关联的人”同时显示“兴趣圈友”和“下属”，两条均指向“我” |
| 2.5 | 合并与撤销合并 | ⚪无法复现 | 未发现数据丢失或无法恢复：合并源从列表隐藏，主资料保留恢复入口，撤销后源联系人重新可见 | 在“联系人维护”处理“叶孝玲 / 成都乐心”重复建议（两个手机号）；确认合并后分别搜索两个号码，再从主联系人详情点“恢复”并复搜 | — | — | — | `ContactMergeChainTest.undoConfirmedMergeRestoresSourceVisibilityAndClearsLink`；`AgentDataRepositoryTest.confirmedContactMergeIsNonDestructiveAndReversible` | 2026-08-13 真机验证通过：联系人总数 1194→1193，主资料显示“已合并资料/恢复”；恢复后总数回到 1194，`15682127000` 再次独立命中，真实资料已还原 |
| 2.6 | 关系图谱点击/拖动/缩放/聚焦 | ⚪无法复现 | 未发现交互失效：节点点击、节点拖动、画布平移和重置聚焦均有即时反馈 | 打开含 2 条关系的“我的关系图”；点击 `AUDIT_RELATION_001` 节点；关闭详情后拖动节点、平移画布并点击重置 | — | — | — | `RelationshipGraphInteractionTest.personNodeHasNamedTouchTargetAndOpensContact`；`ForceRelationshipGraphTest.seed places requested focus at viewport center`；`ForceRelationshipGraphTest.simulation combines repulsion spring centering and damping` | 2026-08-13 真机验证通过：点击打开包含关系强度和关联对象的详情抽屉；拖动后节点位置改变；画布可平移；重置后“我”回到中心。双指缩放实现纳入最终设备测试闸 |
| 2.7 | 智能完善建议确认/拒绝 | ✅已修复 | “联系人维护”的资料待核实入口只跳到空白问问会话，既未传入提示，也没有读取待核实候选的工具，26 条真实建议无法从维护入口处理 | 关系 TAB→联系人维护→点“26 条建议”；修复前进入问问且看不到候选；修复后直接打开候选面板，分别确认“于军”和拒绝“付铨”的公司建议 | 功能不可用 | `ContactMaintenancePage` 将已有 `pendingEnrichment` 错接到 `onAsk()`，没有复用联系人详情已存在的确认/拒绝路径 | `a4224f9` | `ContactMaintenanceEnrichmentTest.pendingSuggestionsAreVisibleAndExposeConfirmAndRejectActions`；`ContactEnrichmentConfirmTest.confirmAppliesScalarFieldToBlankProfileAndResolvesCandidate`；`ContactEnrichmentConfirmTest.rejectMarksCandidateDismissed` | 2026-08-13 真机修复包验证通过：面板显示联系人、字段、置信度和证据来源；确认/拒绝后计数 26→24，两条均移出待核实列表；定向真机设备测试 1/1 通过 |
| 2.8 | 通知候选处理 | ⚪无法复现 | 未发现功能问题：支持来源的新通知能进入待确认列表，展示推断结论和原始依据；确认后联系人、证据和日程写入一致 | 在真机临时安装不进仓库的 `audit.wechat.sender` 通知发送器，发送标题 `AUDIT通知联系人`、正文“明天下午3点开会，请确认”的真实 Android 消息通知；在关系页确认“新建联系人并加入日历” | — | — | — | `NotificationCandidateDialogTest.unresolvedMessageShowsAgentConclusionBeforeRawCollectionSettings`；`AgentDataRepositoryTest.confirmedNotificationCreatesEvidenceAndIdentity`；`AgentDataRepositoryTest.confirmNotificationScheduleIsIdempotent` | 2026-08-13 真机验证通过：Listener 实时接收；待确认页识别为微信收到的安排；确认后联系人总数 1193→1194，8 月 14 日 15:00 出现“开会，请确认”，来源显示“由微信消息确认添加 · AUDIT通知联系人”；临时发送器已卸载 |
| 2.9 | 通话备注 | 🧪需环境 | 通话记录同步正常，但测试机当前蜂窝网络 `OUT_OF_SERVICE`，10086 呼叫在 CONNECTING 阶段立即失败并落为 0 秒，按产品规则不会触发挂断备注，无法完成真实有效通话复验 | 授予通话记录/电话状态权限；开启“同步通话记录”和“挂断后提醒补充要点”；同步真实最近 90 天通话；尝试拨打 10086 | — | — | — | `CallLogImporterTest.callNotePersistsFactAndCompletesPrompt`；`CallLogImporterTest.dismissedCallNoteDoesNotReturnToPendingList`；`CallLogRepositoryTest` | 2026-08-13 真机已验证权限探针为“已允许，可读取”，同步 31 条真实记录；双卡无服务导致拨号立即失败，需 SIM 恢复网络后完成一次持续 >0 秒的呼叫，检查私密通知→手输/语音备注→联系人时间线 |

## 3. 问问（Agent 对话）

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 3.1 | 文本问答 | 🧪需环境 | 当前真机模型连接状态为“未连接”，无法把云端正文质量冒充为已验证；未配置错误后输入框立即可继续输入，不会锁死会话 | 问问发送文本，观察安全错误态；随后输入 `after_failure_input` 并检查编辑、返回和新会话 | — | — | — | `AgentConversationScreenE2ETest.providerNetworkAndFinalErrorsExposeOnlyValidRecoveryActions`；`RuntimeInputProcessorTest.escapedProviderFailureIsContainedAndMakesSessionRetryable` | 2026-08-13 真机无 FATAL/ANR，失败后输入正常；需在真机重新建立模型连接后补一轮真实回复验收 |
| 3.2 | 语音输入转写 | 🧪需环境 | 本地录音入口、权限态、失败恢复均正常；云端 ASR 需要有效模型连接，当前不能完成真实转写 | 点输入栏麦克风，覆盖允许、永久拒绝、录音中、失败和重试状态 | — | — | — | `AgentConversationScreenE2ETest.emptyInputOffersOneVoiceToTextActionUsingTheWorkingRecordingPath`；`recordingAndPermanentPermissionStatesRenderActionableControls`；`ProviderCloudAsrGatewayTest` | 2026-08-13 模拟器问问 UI 20/20 通过；真机需重连模型后说一段真实语音复验 |
| 3.3 | 图片识别 | 🧪需环境 | 图片选择、私有暂存、仅附件发送和失败恢复链路正常；当前无法验证云端识别正文 | 问问→“+”→图片；不输入文字直接发送；检查附件暂存和错误恢复 | — | — | — | `AgentConversationScreenE2ETest.attachmentWithoutTextExposesSendAndDispatchesAnAnalysisRequest`；`AppPrivateAttachmentStagerInstrumentationTest`；`RuntimeInputProcessorTest.verifiedImageMetadataFlowsFromRuntimeEnvelopeIntoProviderRequest` | 2026-08-13 设备链路通过；需模型重连后用一张测试图片补识别结果验收 |
| 3.4 | 基于关系库查询 | 🧪需环境 | 联系人和关系工具能从 Room 取真实结果并回送观察，但当前不能验证模型对“我该联系谁”的最终自然语言回答 | 用关系库测试数据执行联系人搜索与关系搜索，检查工具路由、账本和观察结果 | — | — | — | `RuntimeInputProcessorTest.contactSearchAutoExecutesWithoutApprovalAndFeedsFinalAnswer`；`relationshipSearchRunsThroughRouterLedgerAndObservation` | 2026-08-13 模拟器 49 项 Runtime 状态机测试全绿；需模型重连后在真机查询真实联系人 |
| 3.5 | 确认卡：缺参/取消/确认/pending | ⚪无法复现 | 未发现死锁、重复执行或拒绝失效：确认可跨进程恢复且只执行一次，取消在探测、流式和观察阶段均能终止 | 分别构造缺参、待确认、拒绝、重启后确认、执行中取消和工具观察卡住 | — | — | — | `RuntimeInputProcessorTest.workToolApprovalSurvivesProcessorRestartThenCreatesScheduleExactlyOnce`；`hangingProbeIsCancelledByCancelCommand`；`stalledToolObservationFallsBackAndUnlocksConversation`；`cancelCommandRemainsProcessableWhileProviderStreamIsActive` | 2026-08-13 模拟器 Runtime 49/49、问问 UI 20/20 通过；真机失败会话输入恢复，无闪退 |
| 3.6 | 流式输出完整性 | ⚪无法复现 | 未发现丢正文或会话卡死；连续长流全部进入终态且会话保持可写，长结构化正文能自动滚到最新内容 | 连续执行 25 个长流，混合取消、超时、恢复和长 Markdown 输出 | — | — | — | `RuntimeInputProcessorTest.twentyFiveSequentialLongStreamsLeaveSessionWritableAndEveryRunTerminal`；`AgentConversationScreenE2ETest.structuredLongReplyRendersHeadingsListsCodeAndAutomaticallyShowsLatestContent` | 2026-08-13 模拟器定向设备测试通过；真机 provider 失败后仍可立即编辑，logcat 无 FATAL/ANR |
| 3.7 | 记忆写入与召回 | ⚪无法复现 | 未发现写入后不可召回、撤销后仍召回或关闭策略仍外发的问题 | 通过记忆工具确认写入，检索召回；关闭记忆策略后检查上下文；检查我的→智能体设置→记忆入口 | — | — | — | `RuntimeInputProcessorTest.memoryToolRequiresApprovalThenBecomesRetrievableContext`；`disabledMemoryAndFeedbackPoliciesExcludeTheirContextFromProviderRequests`；`AgentMemoryPageTest` | 2026-08-13 模拟器设置与运行时测试通过，记忆页开关与高级入口可操作 |

## 4. 能力 TAB

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 4.1 | CRM 线索进入候选池 | ⚪无法复现 | 自动发现只进入候选池，不混入正式线索和预测统计；演示模式有明确标识 | 能力→个人 CRM→查看演示；检查候选池、正式统计和演示标识 | — | — | — | `CrmAgentSuggestionChainTest.newLeadSuggestedForHighConfidenceMatchWithoutLead`；`CrmDashboardCountsTest.countsOnlyFormalLeadsAndActivitiesInsideWindow`；`CrmCandidatePoolUiTest` | 2026-08-13 真机 CRM 演示工作台可打开；模拟器 CRM 定向设备测试 49/49 通过 |
| 4.2 | CRM 候选转正为商机 | ⚪无法复现 | 候选不能直接转换商机；转正后进入正式线索，再转换时同时建立商机、阶段历史和活动 | 对候选执行转正，再对正式线索填写商机信息并转换；检查预测统计 | — | — | — | `CrmLeadPoolTest.convertLeadCreatesOpportunityHistoryAndActivity`；`convertLeadIsRejectedForCandidateAndConvertedLead`；`CrmCandidatePoolUiTest` | 2026-08-13 模拟器路径与数据库断言通过，候选与正式数据未混淆 |
| 4.3 | CRM 商机阶段推进 | ⚪无法复现 | 未发现终态回退或横向阶段不可达；活动阶段可推进，赢单/失单不再提供继续推进 | 在商机看板逐级推进；检查终态卡片 | — | — | — | `CrmOpportunityBoardUiTest.activeStageCardOffersAdvanceToNextStage`；`terminalStageCardHidesAdvanceAction`；`everyStageIsReachableWithoutHorizontalScrolling` | 2026-08-13 真机演示看板显示 3 条机会；模拟器阶段交互测试通过 |
| 4.4 | CRM 沟通活动 | ⚪无法复现 | 未发现追加失败、证据丢失或撤销无效 | 对开放商机接受通话跟进建议，再撤销 | — | — | — | `CrmAgentSuggestionChainTest.acceptCallFollowUpWritesUndoableActivity`；`undoAcceptedCallFollowUpRestoresPendingAndDeletesActivity` | 2026-08-13 模拟器真实 Room 写入与撤销通过 |
| 4.5 | CRM 下一步动作 | ✅已修复 | 下一步动作能创建，但与日历没有真实关联 | 从 CRM 创建下一步动作并确认写入日历；检查 `scheduleId` 与截止时间 | 功能不可用 | 日历工具缺少 CRM 动作标识，写日程和绑定动作不在同一事务 | `25f75fe` | `RoomScheduleToolExecutorTest.confirmedScheduleLinksItsPendingCrmActionInTheSameTransaction`；`missingCrmActionRollsBackTheConfirmedScheduleAndItsAudit` | 2026-08-13 SM-W7023 执行器 12/12 通过；参见 1.10 |
| 4.6 | 生活助理：重要日期 | ⚪无法复现 | 未发现空页无入口或重要联系人日期遗漏；空态只有一个明确主操作 | 能力→生活助理；检查空态和重要联系人日期卡片 | — | — | — | `LifeAssistantPageTest.emptyWorkbenchHasOneClearContactAction` | 2026-08-13 真机打开生活助理空态正常，模拟器页面测试通过 |
| 4.7 | 生活助理：承诺提醒 | ⚪无法复现 | 承诺候选可查看、检查本地和系统日历冲突、确认后写日历并设置提醒 | 构造承诺候选，点加入日历并检查日程与提醒 | — | — | — | `LifeAssistantPageTest.commitmentIsVisibleAndCanBeAddedToCalendar` | 2026-08-13 模拟器设备测试通过；最终写入复用日历冲突和提醒链路 |
| 4.8 | 一起安排：多人协调 | ⚪无法复现 | 未发现无参与人仍能写日历、移除参与人后残留或确认非幂等 | 新建聚会，添加/移除联系人，确认到日历并重复确认 | — | — | — | `EventPlanningRepositoryTest.planParticipantAndCalendarConfirmationCommitAsOneCoherentFlow`；`calendarConfirmationRequiresParticipantAndRemovedParticipantCannotBeUpdated`；`EventPlanningPageTest` | 2026-08-13 模拟器 3/3 通过；联系人、计划和日程保持同一闭环 |

## 5. 我的 / 设置

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 5.1 | 大模型连接配置与重连 | 🧪需环境 | 配置、探测失败、密钥轮换与清除顺序测试均正常；当前真机设置显示“未连接”，不能验证真实公网握手 | 我的→智能体设置→大模型连接；保存后重启 App 并发送测试问答 | — | — | — | `ProviderConfigurationBridgeTest`；`ProviderConfigurationManagerTest` | 2026-08-13 模拟器配置桥设备测试通过；需真机重新保存有效凭据后补握手与重启复验 |
| 5.2 | 个人资料保存 | ⚪无法复现 | 未发现字段丢失、头像路径越界或非法手机号被保存 | 完善个人资料，保存姓名、称呼、电话、社交账号和职业信息并重新打开 | — | — | — | `UserProfilePageTest.validProfileShowsSavedResultAndPersists`；`UserProfileStoreTest`；`UserProfileValidationTest` | 2026-08-13 模拟器个人资料 3 项页面测试及存储测试通过 |
| 5.3 | 记忆管理 | ⚪无法复现 | 简化开关、临时模式和高级入口均可达，关闭后策略真实生效 | 我的→智能体设置→记忆；切换记忆与反馈策略并重新进入 | — | — | — | `AgentMemoryPageTest`；`AgentMemorySettingsServiceTest` | 2026-08-13 模拟器设置设备测试通过 |
| 5.4 | 存储占用显示与缓存清理 | ⚪无法复现 | 占用总量、数据库、附件与临时文件分项可见；清理只删除可再生缓存 | 我的→存储；读取目录与大小，执行临时文件清理 | — | — | — | `GeneralSettingsCacheTest`；`AgentSettingsNavigationE2ETest.everyProfileSettingsEntryOpensAndBackReturns` | 2026-08-13 真机显示应用专属目录、4 KB 数据库、93 B 附件、0 B 临时文件；页面无崩溃 |
| 5.5 | 权限管理 | ⚪无法复现 | 权限页入口可达，系统权限状态来自真实授权结果；撤销联系人权限后页面安全降级 | 我的→隐私与权限；撤销 `READ_CONTACTS`，重启并打开关系页，再恢复权限 | — | — | — | `AgentSettingsNavigationE2ETest.settingsPrioritizeUsefulPermissionsAndRemoveTechnicalDuplicates` | 2026-08-13 真机撤权后关系页显示 0 位联系人和导入入口，无 FATAL/ANR；权限已恢复 |
| 5.6 | 隐私开关 | ⚪无法复现 | 未发现开关不持久化或技术性重复入口；记忆、反馈和通知采集策略均能独立保存 | 进入隐私与权限、记忆、通知采集设置，切换后重开页面 | — | — | — | `AgentControlStoreTest`；`MessageCollectionPreferencesTest`；`AgentSettingsNavigationE2ETest` | 2026-08-13 模拟器设置测试通过；真机通知监听与发出消息感知已恢复启用 |

## 6. 系统边界

| 编号 | 功能点 | 状态 | 现象 | 复现步骤（真机） | 严重度 | 根因 | 修复 commit | 回归测试 | 真机验证 |
|---|---|---|---|---|---|---|---|---|---|
| 6.1 | 通知监听断开后重连 | ⚪无法复现 | 未发现重新授权后不绑定；短断档不误报，超过 30 分钟记录固定降级原因 | 用系统命令关闭监听，再重新允许；检查设置与 ServiceRecord | — | — | — | `MessageCollectionPreferencesTest` | 2026-08-13 真机关闭后列表不含知伴，恢复后立即重新出现且 1 个 ServiceRecord 绑定；无 FATAL/ANR |
| 6.2 | 无障碍服务被杀后恢复 | 🖐需人工 | 正常运行时系统保持服务连接；“强行停止”会按 Android 设计关闭整个无障碍开关，应用无权自动恢复，不能把它当普通进程回收 | 保持发出消息感知开启，让系统在后台自然回收 App；再发一条白名单 App 消息检查是否重连 | — | Android 系统边界：force-stop 与低内存回收语义不同 | — | `AccessibilityTraversalBudgetTest`；既有发出消息采集设备测试 | 2026-08-13 真机服务当前已恢复，设置值为 1 且存在活动连接；需在不使用 force-stop 的长时后台场景人工复验 |
| 6.3 | 权限撤销后降级 | ⚪无法复现 | 撤销联系人权限后 App 不崩溃、不读取系统联系人，关系页降级为空态并保留导入入口 | 撤销联系人读取权限，重启并进入关系 TAB；检查后恢复 | — | — | — | `CallLogAccessProbeTest`；权限页设备测试 | 2026-08-13 真机实测通过，logcat 无 FATAL/ANR，权限已恢复 |
| 6.4 | 弱网下 Agent 行为 | 🧪需环境 | 状态机在弱网会跳过向量和重排、极差网络在 provider 前安全失败；尚未做真实链路整形 | 注入 DEGRADED/EXTREME 网络质量并执行一次会话 | — | — | — | `RuntimeInputProcessorTest.weakNetworkSkipsVectorAndRerankWhileExtremeNetworkFailsBeforeProvider`；`AndroidNetworkQualityGatewayTest` | 2026-08-13 模拟器确定性设备测试通过；需可控网络整形环境补真实请求延迟与恢复验收 |
| 6.5 | 断网下 Agent 行为 | 🧪需环境 | 离线判定和可重试错误 UI 正常，但当前真机模型未连接，配置错误会先于网络错误，无法验证真实断网请求 | 模型已连接时关闭 Wi‑Fi/蜂窝，发送文本，再恢复网络重试 | — | — | — | `AgentConversationScreenE2ETest.providerNetworkAndFinalErrorsExposeOnlyValidRecoveryActions`；`NetworkQualityTest` | 2026-08-13 离线状态与错误恢复自动测试通过；需模型重连后补真机断网实测 |
| 6.6 | App 被杀后提醒仍触发 | 🖐需人工 | WorkManager 提醒不依赖前台页面，Worker 会重新读库并拒绝已删除/改期快照；本轮未等待真实未来触发 | 建立 10 分钟后的日程提醒，返回桌面并让系统自然回收 App，等待通知 | — | — | — | `ScheduleReminderWorkerTest`；`ScheduleReminderValidationTest`；`ScheduleReminderPrivacyTest` | 2026-08-13 Worker、过期校验和锁屏隐私测试通过；需自然回收而非 force-stop 的定时人工复验 |

## 需人工 / 需环境汇总

- `2.9 通话备注`：SM-W7023 当前两张 SIM 均无蜂窝服务。恢复任一卡网络后，拨打一通持续至少 5 秒的电话并挂断；预期 2 秒后出现私密“记录刚才的通话要点”通知，保存文字或语音备注后应进入已匹配联系人的最近互动，且不再重复提示。
- `3.1–3.4、5.1、6.5`：当前真机的大模型连接状态为“未连接”。重新在 App 内保存有效凭据后，补文本、语音、图片、关系库问答和断网恢复的真实公网验收；凭据不得进入代码、文档、命令日志或测试产物。
- `6.2`：不要用 Android“强行停止”模拟普通后台回收；它会依法关闭无障碍能力。保持开关开启并让系统自然回收后，再从白名单社交 App 发消息复验自动重连。
- `6.4`：需要可控弱网整形环境，分别验证高延迟、抖动和短时丢包下的超时、降级与重试。
- `6.6`：建立 10 分钟后的提醒，退出到桌面并让系统自然回收，等待真实通知；force-stop 会改变系统任务语义，不作为验收方式。

## 建议（不在本任务实现）

尚无。仅记录非 bug 的产品增量想法。

## 最终自动化闸

- `./gradlew check`：2026-08-13 通过；JVM XML 共记录 972 次测试执行（含 debug/release 变体重复执行），0 失败、0 错误。
- `ANDROID_SERIAL=emulator-5556 ./gradlew connectedDebugAndroidTest`：471 项设备测试完成，0 失败，1 项受控跳过。跳过项为 `ProviderCertificatePinsControlledTest.liveAndroidHandshakeAcceptsEveryPinnedProviderChain`，需要显式开启真实公网证书握手环境，已归入 `6.4/6.5` 环境项。
- 全量设备测试首次运行发现 `FactIndexMigrationTest` 的测试专用迁移列表停在 `36→37`，未带上生产代码已注册的 `37→38`；`8a4f492` 补齐测试列表。该问题只影响历史迁移回归测试本身，不影响生产数据库构建器。定向 1/1 及随后全量 471 项均通过。
