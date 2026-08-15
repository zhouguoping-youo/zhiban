# 知伴 App 全面破坏者测试 · 主权审计表

> 分支 `fix/agent-conversation-ux` 之上的全 App 审计。每个检查点一行。
> 状态：`未测` / `已复现` / `无法复现` / `已修复` / `需人工` / `需环境` / `上一轮已修`
> 严重度：`崩溃` > `数据丢失` > `功能不可用` > `体验` > `文案`
> 复现方式：`真机` / `设备测试` / `单测` / `代码审查`

## 状态图例
- ✅ 已修复（含 commit + 回归测试）
- ❌ 已复现待修
- ⚪ 无法复现（注明原因）
- 🖐 需人工验证（附操作步骤）
- 🧪 需特定环境（注明条件）
- ⏭ 上一轮（对话链路）已修，见 `fix/agent-conversation-ux` 11 commit
- ⬜ 未测

## 维度 6 · 对话链路（⏭ 上一轮已修，不在本轮重复跑）
对话链路 27 项已在 `907fb05..HEAD` 11 个 commit 全部修复并真机/设备验证：
幻觉已创建、确认卡缺参/不消失/拒绝无效/pending 死锁、流式丢正文、日历不显示、
CRM 强制联系人、message.compose 弹选择器、覆盖安装丢 key、记忆卡预览（隐私边界）等。
详见各 commit message。本轮**跳过**，聚焦其余 19 个维度。

---

## 维度 1 · 采集链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 1.1 | 通知监听断开/重连后通知丢失 | ⚪ | — | Android 无法补回所有已消失通知；当前实现会恢复近 15 分钟仍活跃的通知，并把超过 30 分钟的监听断档作为无内容的固定 degradation 带入下一次上下文，符合可感知降级边界 | 审计提交 | `MessageCollectionPreferencesTest.markGapOnlyWhenGapExceedsThreshold` + `consumeGapReasonClearsAfterRead` |
| 1.2 | 无障碍被杀后消息漏采 | 🖐 | 功能不可用 | 需三星系统真实后台回收；`am force-stop` 会人为禁止组件重启，不能作为有效复现。步骤：开启“发出消息采集”→锁屏并在系统设备维护中清理后台→等待 10 分钟→从微信发送唯一文本→解锁检查无障碍服务仍开启且关系候选箱出现该文本；若缺失同时记录 `dumpsys accessibility` | — | SM-W7023 人工步骤 |
| 1.3 | 通话记录同步中断漏记 | ✅ | 数据丢失 | 通话已提交后若进程在 CRM 建议前中断，重试会因 `createdAt != 本次同步时间` 永久跳过跟进建议；改为按通话证据 ID 幂等重放，旧记录可补、同一通话不重复提示 | 本提交 `fix(1.3)` | `CallLogImporterTest.replayAfterImportCanRecoverMissingCrmSuggestionExactlyOnce` + `CrmAgentSuggestionChainTest.processedCallEvidenceIsNeverSuggestedAgainButANewCallCanBeSuggested` |
| 1.4 | 语音转写网络超时丢录音 | ✅ | 功能不可用 | 批量 ASR 在凭据回调内嵌套 `runBlocking`，外层取消无法可靠终止；凭据/意外传输异常还会逃到 UI 令“转写中”卡住。凭据作用域改为 suspend，取消正常传播，非取消异常返回安全失败码且临时录音保留供重试 | 本提交 `fix(1.4)` | `ProviderCloudAsrGatewayTest.unexpected transport failure becomes retryable result instead of escaping to UI` + `transcription cancellation propagates through credential scope` |
| 1.5 | 分享文本/图片格式异常崩溃 | ✅ | 崩溃 | 外部 ACTION_SEND 的 EXTRA_TEXT/EXTRA_STREAM 原先直接解包，损坏或类型错误的 Parcelable 异常可逃到 Activity；改为在入口失败关闭，非法字段忽略且不入库 | 本提交 `fix(1.5)` | `SharedIntentExtractionTest.malformedExtraTypesAreRejectedWithoutEscapingAnException` |
| 1.6 | 通知含验证码/密码是否过滤 | ✅ | 隐私 | 收到消息已覆盖验证码、动态口令、交易密码和上下文密钥；发出消息过滤器规则较窄，交易密码和密钥仍会落候选库。收发两条采集入口现统一使用同一敏感消息过滤器 | 本提交 `fix(1.6)` | `SocialMessagePerceptionTest.verificationCodesAndUnsupportedAppsAreNeverStaged` + `OutgoingMessageCandidateTest.rejectsUnsupportedPackageAndOneTimeCodes` |
| 1.7 | 通话同步时联系人不存在崩溃 | ⚪ | — | 导入先按规范号码查询，零匹配明确写为 UNMATCHED 且 linkedContactId 为空，不会解引用联系人；设备回归未复现崩溃 | 审计提交 | `CallLogImporterTest.unknownContactIsStoredAsUnmatchedWithoutCrashing` |
| 1.8 | 录音中途权限撤销卡死 | ✅ | 功能不可用 | 实时语音已在 AudioRecord 负读数时失败退出；普通 MediaRecorder 原先吞掉 maxAmplitude 异常且不复查权限，可能永久停在录音态。现权限消失或 recorder 错误均立即释放资源、删除失败录音并显示可重试失败 | 本提交 `fix(1.8)` | `RecordingHealthPolicyTest.revokedPermissionOrRecorderErrorTerminatesRecording` + `StepFunRealtimeProtocolTest.negative recorder read is treated as terminal microphone failure` |
| 1.9 | 通知监听被杀 requestRebind 恢复 | ⚪ | — | onListenerDisconnected 记录断开时间并调用 requestRebind；onListenerConnected 恢复活跃通知且标记长断档。OEM 是否及时回调仍属系统边界，代码路径未复现缺失 | 审计提交 | `MessageCollectionPreferencesTest` + 代码审查 |
| 1.10 | 无障碍截图 Bitmap 是否释放 | ✅ | 崩溃 | 正常 OCR 完成会 recycle Bitmap、hardwareBuffer 也立即 close；但 ML Kit process 同步抛错时原先既不回收 Bitmap 也不清 in-flight。现资源与启动绑定，启动失败立即且仅释放一次 | 本提交 `fix(1.10)` | `ScreenshotOcrResourceTest.synchronousOcrStartFailureReleasesCapturedResourceExactlyOnce` |
| 1.11 | 号码格式异常(+86/空格/横线)匹配失败 | ⚪ | — | 通话导入与联系人方法共用 normalizeContactPhone；+86、空格和横线均归一成 canonical phone 后匹配 | 审计提交 | `CallLogImporterTest.importsIdempotentlyAndLinksCountryCodeVariantThroughCanonicalPhone` + `unknownContactIsStoredAsUnmatchedWithoutCrashing` |
| 1.12 | 语音转写返回空文本崩溃 | ✅ | 功能不可用 | 系统识别与实时交换已有空值保护；CloudAsrGateway 原先仍信任 transport 的 Success 空串，可能进入空 FINAL。最终 Provider 边界现统一 trim 并把空成功降为 ASR_EMPTY_RESULT | 本提交 `fix(1.12)` | `ProviderCloudAsrGatewayTest.blank transport success is rejected at the gateway boundary` |
| 1.13 | 分享图片>10MB 是否 OOM | ✅ | 崩溃 | 图片分享原先在主线程直接按原始分辨率解码，既无压缩大小上限也无像素上限；改为后台读取，声明大小超过 20MB 先拒绝，其他图片在分配 Bitmap 前缩到最长边 2048，OCR 完成后显式回收 | 本提交 `fix(1.13)` | `SharedImageDecodePolicyTest`（文件上限 + 分配前尺寸约束） |
| 1.14 | 分享文本特殊字符(emoji/换行/链接)崩溃 | ⚪ | — | 合法 CharSequence 会安全转 String；候选规范化空白并限制长度，不解释 emoji/链接，设备回归未复现崩溃 | 审计提交 | `SharedIntentExtractionTest.validSpecialCharactersAndImageUriArePreserved` + `SharedTextCandidateTest` |

## 维度 2 · CRM 链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 2.1 | 线索转商机网络断开留半成品 | ⚪ | — | 未复现：转化不依赖网络，线索状态、商机、首条阶段历史和转化活动都在同一个 Room `withTransaction` 中；任一写失败会整体回滚 | — | `CrmLeadPoolTest.convertLeadCreatesOpportunityHistoryAndActivity`；Room 事务回归随全套设备测试 |
| 2.2 | 看板拖动改阶段 App 被杀回滚 | ⚪ | — | 未复现：当前看板不是拖拽写入，而是明确的“推进阶段”操作；阶段、状态、概率及历史在一个 Room 事务提交，进程只能看到提交前或提交后状态，不存在半条历史 | — | `RoomCrmToolExecutorTest.allEightConfirmedToolsWriteAuditAndChangeRecords`、`CrmLeadPoolTest.convertLeadCreatesOpportunityHistoryAndActivity` |
| 2.3 | 建议过期重新触发重复生成 | ✅ | 功能不可用 | 建议去重本身按 evidence 生效，但状态更新没有 `PENDING` 守卫，旧界面能把已过期建议重新改成 ACCEPTED/DISMISSED；详情页“让知伴准备”还会在未执行任何动作时提前消费建议。状态转换增加原子守卫，准备问问不再修改建议 | 本提交 `fix(2.3)` | `CrmAgentSuggestionChainTest.expiredSuggestionCannotBeResurrectedByAStaleUiDecision` + 既有建议接受/过期回归 |
| 2.4 | 商机删除后联系人详情仍显示 | ⚪ | — | 未复现/当前不适用：产品没有用户可达的商机删除 API 或按钮；仅演示数据迁移可删除 DEMO 商机，联系人详情查询直接观察存活商机表，不维护独立缓存 | — | `CrmContactLinkTest.observeOpportunitiesByContactReturnsOnlyThatContact` |
| 2.5 | 仪表盘数据与实际不符 | ⚪ | — | 未复现：统计查询只计时间窗内正式线索与活动，排除候选线索，并由 Room Flow 在新增后重发 | — | `CrmDashboardCountsTest.countsOnlyFormalLeadsAndActivitiesInsideWindow`、`emitsZeroAndReEmitsOnInsert` |
| 2.6 | 线索候选转正后候选区仍显示 | ⚪ | — | 未复现：候选查询限定 `status='CANDIDATE'`，转正原子改为 QUALIFIED 后立即离开候选 Flow，并进入正式统计 | — | `RoomCrmToolExecutorTest.candidatePromotionEntersFormalListAndIgnoreRemovesCandidate`、`CrmCandidatePoolUiTest.promotedLeadIsVisibleAsFormalAndIncludedInLeadOverview` |
| 2.7 | 活动追加时联系人不存在崩溃 | ⚪ | — | 未复现：审批前校验商机与联系人，最终执行仍二次校验；失效引用以受控领域错误拒绝，外键也阻止脏引用 | — | `CrmMutationToolBindingTest.activity append with unknown contact is rejected before approval` + 写入矩阵 |
| 2.8 | 下一步动作创建时商机不存在崩溃 | ⚪ | — | 未复现：审批前和最终执行均要求商机存在；不存在时拒绝工具写入而非形成孤儿动作，数据库外键是最后防线 | — | `CrmMutationToolBindingTest.next action create with unknown opportunity is rejected before approval` + 写入矩阵 |
| 2.9 | 阶段 WON→LEAD 终态守卫拦截 | ⚪ | — | 未复现：用户页面与 Agent 工具最终写入点都调用 `CrmOpportunityStage.requireTransitionAllowed`，WON/LOST 不可重新打开 | — | `AgentDataRepositoryTest.userStageChangeCannotReopenTerminalOpportunity`、`RoomCrmToolExecutorTest.ordinaryStageToolCannotReopenTerminalOpportunity` |
| 2.10 | 阶段概率错误(CONTACTED 25 非 20) | ⚪ | — | 未复现：概率单源为 `CrmOpportunityStage.probabilityPercent`，CONTACTED 固定 25，页面与工具共用 | — | `CrmOpportunityStageTest.allWritersShareOneStageProbabilityPolicy` |
| 2.11 | 建议接受后状态更新 ACCEPTED | ⚪ | — | 未复现：接受产生的活动/线索与 PENDING→ACCEPTED 条件更新在同一事务；状态竞争失败则整体回滚 | — | `CrmAgentSuggestionChainTest.acceptCallFollowUpWritesUndoableActivity`、`acceptNewLeadWritesUndoableLead` |
| 2.12 | 建议撤销后写入的活动/线索删除 | ⚪ | — | 未复现：接受时写 ChangeLog 逆向载荷；撤销同事务删除所建活动/线索并把建议恢复 PENDING | — | `CrmAgentSuggestionChainTest.undoAcceptedCallFollowUpRestoresPendingAndDeletesActivity`、`undoAcceptedNewLeadRestoresPendingAndDeletesLead` |
| 2.13 | 建议拒绝后状态更新 DISMISSED | ⚪ | — | 未复现：拒绝使用受状态守卫的 PENDING→DISMISSED CAS；处理过的 evidence 不会再次生成同类建议 | — | `CrmAgentSuggestionChainTest.processedCallEvidenceIsNeverSuggestedAgainButANewCallCanBeSuggested` |
| 2.14 | 商机金额分/元混淆 | ✅ | 数据完整性 | 存储和表单换算虽约定最小单位“分”，显示层却把小数位强制为 0，金额会被四舍五入；表单用 Double 还会把 Infinity/溢出值钳成 Long 极值。改为 BigDecimal 精确换算、最多两位小数和 exact Long 边界校验 | 本提交 `fix(2.14)` | `CrmMoneyLogicTest`（分/元显示、精确输入、超精度、Infinity、溢出） |
| 2.15 | 阶段历史记录完整 | ⚪ | — | 未复现：商机创建写首条 `fromStage=null` 历史，后续阶段变化写 from/to/reason/source/确认标记，均与主体变更同事务 | — | `CrmLeadPoolTest.convertLeadCreatesOpportunityHistoryAndActivity`、`RoomCrmToolExecutorTest.allEightConfirmedToolsWriteAuditAndChangeRecords` |
| 2.16 | 建议 evidenceRefs 引用真实数据 | ✅ | 数据完整性 | 通知线索建议曾在通知候选落库前创建，且直接手拼 `candidateId` JSON；内部调用或特殊字符可留下不存在/不可解析的证据引用。现在先在同一事务保存候选，建议创建再校验候选存在且匹配联系人，并用 kotlinx.serialization 生成 evidenceRefs | 本提交 `fix(2.16)` | `CrmAgentSuggestionChainTest.newLeadSuggestionRejectsMissingOrMismatchedEvidence`、`newLeadSuggestionSerializesEvidenceIdAsJson` + 高置信建议回归 |
| 2.17 | 建议 confidence 保持 0–1 | ✅ | 数据完整性 | 通知建议入口只检查下限，允许 `>1`/非有限值落库；同时调用参数可与证据候选自身置信度不一致。现在删除重复参数，以已持久化候选为单源，并在最终写入前强制 `0.7..1.0` 有限范围 | 本提交 `fix(2.17)` | `CrmAgentSuggestionChainTest.newLeadSuggestionRejectsOutOfRangeConfidence` + 高/低置信回归 |
| 2.18 | 建议过期后是否清理 | ⚪ | — | 未复现：7 天后的 PENDING 建议原子转 EXPIRED 并从待处理查询消失；不物理删除是有意保留审计和去重证据，过期记录不能被旧 UI 复活 | — | `CrmAgentSuggestionChainTest.stalePendingSuggestionsExpireAfterSevenDays`、`expiredSuggestionCannotBeResurrectedByAStaleUiDecision` |
| 2.19 | 线索转化后能否再次转化 | ✅ | 数据完整性 | 页面转化会把线索置为 CONVERTED，但 Agent 的 `crm.opportunity.create` 只校验 sourceLeadId 存在，不修改线索状态，也不查已有来源商机；不同工具调用可从同一线索生成多个商机。最终事务现原子校验并标记 CONVERTED，重复转化失败 | 本提交 `fix(2.19)` | `RoomCrmToolExecutorTest.opportunityCreateConvertsSourceLeadAndRejectsASecondConversion` + 8 工具幂等回归 |
| 2.20 | 商机删除后关联数据级联 | ⚪ | — | 未复现：活动、下一步动作、建议、阶段历史、利益相关人均以 opportunityId 外键 CASCADE；联系人/日程等可选引用用 SET_NULL。设备测试实际删除商机并逐表断言无孤儿行 | — | `RoomCrmToolExecutorTest.deletingOpportunityCascadesEveryOwnedCrmRecord`、`CrmReferenceIntegrityMigrationTest.migration28To29RepairsAndMaintainsOptionalReferences` |

## 维度 3 · 自动写链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 3.1 | 撤销后 Fact 真删除 | ⚪ | — | 未复现：互动摘要撤销经 FactIndex 同事务删除主表记录并同步 FTS，不是仅在 UI 隐藏 | — | `AgentDataRepositoryTest.structuredMessageSuggestsExactContactButWaitsForUserConfirmation` |
| 3.2 | 用户改联系人后撤销覆盖新内容 | ⚪ | — | 未复现：联系人标签撤销先比对自动写后的规范 digest；用户随后改过 tags 时撤销失败且保留用户新值 | — | `ContactTagAutoWriteTest.userEditedTagsAreNeverOverwrittenByAutomaticUndo` |
| 3.3 | 回执过期后能否撤销 | ⚪ | — | 未复现：维护任务把 90 天外 AVAILABLE 变为 EXPIRED 并擦除 inverse；协调器只接受 AVAILABLE，Fact 保留且不能再撤销 | — | `ChangeLogRetentionTest.oldUndoPayloadExpiresAndOnlyTerminalHistoryIsDeleted`、`AutoWriteCorrectionTest.expiredAutoWriteCannotBeUndoneAndKeepsItsFact` |
| 3.4 | 互动摘要纠正联系人后原记录残留 | ⚪ | — | 未复现：纠正原地更新同一 factId 的 contactId 与 FTS，只保留一条 Fact；原撤销关闭、回执标 CORRECTED，并另写用户纠正审计 | — | `AutoWriteCorrectionTest.correctingInteractionMovesTheSingleFactAndClosesOriginalUndo` |
| 3.5 | ChangeLog 与可见回执原子写入 | ✅ | 数据完整性 | 生产调用点虽有外层事务，但公共写入函数自身先写 ChangeLog、再写 receipt；脱离外层使用时，第二次写因外键失败会留下孤立审计。现函数内部也使用 Room 事务，嵌套调用继续参与领域事务 | 本提交 `fix(P1)` | `AutoWriteAtomicityTest.receiptFailureRollsBackTheAuditWhenCalledWithoutAnOuterTransaction`、`injectedFailureRollsBackDomainChangeAuditAndReceiptTogether` |
| 3.6 | auto_write_receipts reviewState 更新 | ✅ | 体验/状态一致性 | 成功撤销或“忽略候选”只把 ChangeLog 改为 UNDONE，回执仍为 UNREVIEWED，导致未读徽标继续计算已处理记录。撤销现于同一事务把 receipt 标为 CORRECTED，失败则整体回滚 | 本提交 `fix(3.6)` | `AgentDataRepositoryTest.structuredMessageSuggestsExactContactButWaitsForUserConfirmation`（撤销后回执与未读数）+ `RoomCrmToolExecutorTest.candidatePromotionEntersFormalListAndIgnoreRemovesCandidate` |
| 3.7 | 同 source/idempotency 重放重复写入 | ⚪ | — | 未复现：工具执行以 idempotencyKey 唯一索引和 payload/providerCall 一致性校验重放原结果；通知自动摘要另以 sourceKey/确定性键去重 | — | `RoomCrmToolExecutorTest.allEightDuplicateSubmissionsReturnOriginalResultWithoutSecondWrite`、通知候选幂等回归 |
| 3.8 | undoState UNDONE 后能否再撤销 | ⚪ | — | 未复现：markUndone 是 `WHERE undoState='AVAILABLE'` 的 CAS；二次撤销返回 false，不重复应用 inverse | — | `ContactTagAutoWriteTest.automaticContactTagIsVisibleAndCanBeUndone`（含二次撤销） |
| 3.9 | 目标被用户修改后撤销检测阻止覆盖 | ⚪ | — | 未复现：Fact/CRM/联系人标签均在 inverse 前比较当前规范 digest；目标变化时拒绝撤销并保持 AVAILABLE，提示走纠正 | — | `RoomCrmToolExecutorTest.undoDoesNotOverwriteCandidateChangedAfterAutomaticWrite`、`ContactTagAutoWriteTest.userEditedTagsAreNeverOverwrittenByAutomaticUndo` |
| 3.10 | presentationType 映射错误标签 | ⚪ | — | 未复现：五类自动写固定映射为 INTERACTION_SUMMARY/CONTACT_TAG/CRM_LEAD_CANDIDATE/CRM_ACTIVITY/CRM_NEXT_ACTION，设置页逐值映射中文标题，未知值安全回退 | — | `AutoWritePageTest` 各类型交互 + `RoomCrmToolExecutorTest.automaticActivityAndNextActionAreVisibleAndReversible` |

## 维度 4 · 联系人链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 4.1 | 合并联系人撤销图谱恢复 | ⚪ | — | 未复现：合并时不改写原边，图查询动态投影 canonical；撤销后 source 重新可见，边端点和触达关系恢复 | — | `ContactDaoTest.mergedSourceIsHiddenFromSearchAndGraphUntilUndo`、`ContactMergeChainTest.undoConfirmedMergeRestoresSourceVisibilityAndClearsLink` |
| 4.2 | 软删联系人后 Fact/关系/活动清理 | ⚪ | — | 未复现：删除对整个活动身份簇同事务软删，关系边/关系事件置 INACTIVE、Fact 置 REVOKED，查询层也过滤遗留软删数据；合并撤销不能复活 | — | `AgentDataRepositoryTest.deletingMergedContactClusterHidesDerivedFactsRelationshipsAndEvents`、`ContactDaoTest.legacySoftDeletedContactCannotLeakThroughFactsOrGraph` |
| 4.3 | 通讯录导入重复联系人重复条目 | ⚪ | — | 未复现：单批先按 sourceId 去重，再按规范手机号/微信方法逐条匹配；前一条写入的方法在同一事务内对后一条立即可见，格式不同也只保留一个联系人 | — | `AgentDataRepositoryTest.duplicatePhonesInsideOneSystemImportBatchCollapseToOneContact`、`systemImportMatchesExistingFormattedPhoneThroughCanonicalContactMethod` |
| 4.4 | 智能完善建议过期后能否确认 | ✅ | 数据完整性 | 确认入口曾直接使用 UI 传回的候选对象，不回读状态/有效期；候选被清理后旧页面仍能写联系人，调用方还能替换 proposedValue。现事务内回读持久候选，只接受未过期 PENDING，并检查资料写与状态转换结果 | 本提交 `fix(4.4)` | `ContactEnrichmentConfirmTest.staleUiCannotApplyExpiredOrPurgedEnrichment`、`confirmUsesPersistedCandidateInsteadOfCallerModifiedPayload` + 既有确认回归 |
| 4.5 | 合并后搜索仍显示已合并联系人 | ⚪ | — | 未复现：搜索命中 source 的 FTS/别名/方法时只投影 canonical，活动合并源不会形成第二条；撤销后 source 恢复 | — | `ContactDaoTest.mergedSourceIsHiddenFromSearchAndGraphUntilUndo` |
| 4.6 | 编辑后 normalizedValue 更新 | ⚪ | — | 未复现：用户编辑会先删除 USER 来源旧 PHONE/WECHAT 方法，再用共享规范函数重建；旧号码/账号不再可检索，新值立即命中 | — | `AgentDataRepositoryTest.editingUserPhoneRemovesStaleNormalizedIdentity` |
| 4.7 | 删除后 CRM primaryContactId SET_NULL | ✅ | 数据完整性 | CRM 外键的 SET_NULL/CASCADE 只在物理删除触发，联系人产品删除实际是软删，导致商机等继续持有不可打开的隐藏 contactId。现软删事务显式模拟外键语义：可空 CRM 联系人引用统一置空、stakeholder 关联删除，业务快照和历史记录保留 | 本提交 `fix(4.7)` | `ContactSoftDeleteCrmTest.softDeleteDetachesEveryCrmContactReferenceAndPreservesBusinessHistory` |
| 4.8 | 智能完善模型返回非法 JSON 崩溃 | ✅ | 稳定性（roadmap 路径） | 保留的 LLM 智能完善 Provider 对无数组、坏 JSON、字段类型错误会直接抛异常；虽尚未接 UI，启用后会把模型格式偏差升级成链路失败。现所有外部结构先安全校验，非法项/非法整体返回空候选且不写数据 | 本提交 `fix(4.8)` | `LlmContactEnrichmentProviderTest.malformed model output is ignored instead of crashing enrichment` + 有效输出/代码围栏回归 |
| 4.9 | 合并后通话记录仍关联已合并联系人 | ✅ | 体验/数据可见性 | 联系人时间线查询已做 canonical 匹配，但首页挂断备注使用原始 linkedContactId；合并源被联系人列表隐藏后卡片姓名为空。pending call Flow 现与活动合并映射组合投影，撤销即恢复 source，原始通话外键不被破坏 | 本提交 `fix(4.9)` | `CallLogImporterTest.pendingCallAndContactTimelineProjectActiveMergeAndUndoRestoresSource` |
| 4.10 | 合并后通知候选仍关联已合并联系人 | ✅ | 功能不可用 | 联系人列表隐藏合并源，但通知候选 Flow 原样返回 source 的 suggested/linkedContactId，候选卡无法找到联系人名称和正确入口。现将候选与活动合并映射组合投影为 canonical；不改原外键，撤销后即时恢复 source | 本提交 `fix(4.10)` | `AgentDataRepositoryTest.notificationCandidateProjectsMergedContactAndUndoRestoresSource` |
| 4.11 | 合并后 CRM 商机仍关联已合并联系人 | ✅ | 功能不可用 | 通话和关系查询已按 contact_merge_links 投影主联系人，但 CRM 六条按联系人查询仍原始等值匹配；合并源的线索、商机、活动和动作会从主联系人详情消失。现统一 canonical 映射，撤销合并自动恢复原作用域且不改写外键 | 本提交 `fix(4.11)` | `CrmContactLinkTest.mergedContactSeesSourceCrmRecordsAndUndoRestoresOriginalScope` + 原联系人过滤回归 |
| 4.12 | 智能完善确认覆盖用户已有数据 | ⚪ | — | 未复现：确认写入是 additive-only，逐字段只填空值；已有公司等用户数据保持不变，候选仍被正常处理 | — | `ContactEnrichmentConfirmTest.confirmNeverOverwritesExistingNonBlankValue` |
| 4.13 | 智能完善拒绝后能否再生成 | ✅ | 数据完整性 | 候选主键由 runId+providerCallId 稳定生成，但 REPLACE 会在同一调用重放时把用户已拒绝的 DISMISSED 覆盖回 PENDING。现改为 INSERT IGNORE：同一证据不可复活，新 providerCallId/新证据仍可生成独立候选 | 本提交 `fix(4.13)` | `ContactEnrichmentConfirmTest.replayCannotResurrectDismissedCandidateButNewEvidenceCanBeStaged` |
| 4.14 | 多平台账号(微信/飞书/钉钉/QQ)匹配 | ⚪ | — | 未复现：平台与 handle 分别规范化，已确认身份按 platform+normalizedHandle 精确匹配；四个平台候选均以 1.0 置信关联同一联系人 | — | `AgentDataRepositoryTest.confirmedPlatformHandlesMatchWechatFeishuDingtalkAndQqCandidates` |
| 4.15 | 电话格式不一致匹配失败 | ⚪ | — | 未复现：共享号码规范支持分隔符、空格、+86 与本地 11 位号码等价；导入、方法检索和合并建议均使用规范值 | — | `RelationPhoneMatchingTest.formattedAndCanonicalPhoneNumbersMatch`、`AgentDataRepositoryTest.systemImportMatchesExistingFormattedPhoneThroughCanonicalContactMethod` |

## 维度 5 · 日历链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 5.1 | 时间解析错误("明天下午3点") | ⚪ | — | 未复现：本地实体提取按设备时区解析“明天+下午”为次日 15:00；即使模型返回 03:00，最终工具调用也被本地时间覆盖纠正 | — | `CalendarTimeResolutionTest.deterministicFallbackComputesLocalTomorrow3pm`、`normalizeOverridesWrongProviderEpochWithLocalTomorrow3pm` |
| 5.1a | 紧凑日期时段“今晚/明晚/明早/大后天”解析错误 | ✅ | 数据正确性 | 日期锚点和时段标记被分开解析：“明晚8点”只命中“8点”并落成次日 08:00；“大后天”又被“后天”优先命中。现按最长日期词优先，并把紧凑时段与时钟一起解析 | 本提交 `fix(P0-1)` | `EntityExtractionTest.compactDayPeriodAnchorsResolveDateAndClockTogether`、`bigDayAfterTomorrowIsNotCollapsedIntoDayAfterTomorrow` |
| 5.2 | 改期后旧提醒仍触发 | ⚪ | — | 未复现：唯一 Work 采用 REPLACE，Worker 到点重新读取加密库并核对 start/reminder 快照；删除、改期或提醒设置改变都会静默退出 | — | `ScheduleReminderValidationTest.rescheduledOrChangedReminderInvalidatesOldWorkerSnapshot`、`deletedOrRescheduledWorkerSnapshotNeverDispatchesNotification` |
| 5.3 | 系统日历和本地日程冲突误报 | ⚪ | — | 未复现：本地冲突使用严格重叠边界；已导入系统实例按 sourceId 从外部冲突结果排除，不会本地+系统重复计数 | — | `CalendarSearchToolBindingTest.conflict tool does not duplicate an imported system event`、`RoomScheduleToolExecutorTest.overlappingScheduleIsRejectedBeforeAnyAgentSideEffect` |
| 5.4 | 重复事件最后一天取消误报冲突 | ⚪ | — | 未复现：Instances 查询同时要求 VISIBLE=1 且 STATUS!=STATUS_CANCELED，已取消的单个重复实例不会进入导入或冲突结果 | — | `SystemCalendarReaderSelectionTest` |
| 5.5 | 标题特殊字符(emoji/换行)崩溃 | ⚪ | — | 未复现：标题/备注以 Kotlin String→Room TEXT 原样往返，emoji 和内部换行不参与 SQL 拼接；读取结果保持一致 | — | `CalendarPersistenceEdgeTest.localScheduleRoundTripsEmojiAndNewlinesWithoutSystemCalendarAccess` |
| 5.6 | 提醒到期 App 被杀提醒丢失 | 🖐 | — | 代码使用持久化 OneTimeWorkRequest+唯一 work，理论上进程死亡后由 WorkManager 恢复；同进程自动测试不能证明 OEM 杀进程后的真实投递。人工步骤：建 10 分钟提醒→强停 App→等待触发→核对锁屏私密通知 | — | `ScheduleReminderValidationTest.workManagerSnapshotNeverContainsScheduleTitle`；待人工真机投递 |
| 5.7 | 删除日程后关联 CRM 下一步清理 | ⚪ | — | 未复现：crm_next_actions.scheduleId 有 SET_NULL 外键；删除日程后动作业务记录保留、日程引用清空，不产生孤儿 | — | `CalendarPersistenceEdgeTest.deletingScheduleNullsCrmActionReferenceButPreservesAction`、`CrmReferenceIntegrityMigrationTest` |
| 5.8 | 系统日历权限关闭本地日程正常 | ⚪ | — | 未复现：系统读取无权限仅返回空 events+提示；本地保存/查询只依赖加密 Room，不调用 ContentResolver，特殊标题本地写入回归通过 | — | `CalendarPersistenceEdgeTest.localScheduleRoundTripsEmojiAndNewlinesWithoutSystemCalendarAccess` |
| 5.9 | 跨天日程显示正确 | ✅ | 功能不可用 | 日历页、列表和检索只用 startAt 是否落在查询日判断，23:30 开始并跨午夜的日程在次日完全消失。三条查询现统一为时间区间重叠语义，结束恰在日界线的事件不会误入次日 | 本提交 `fix(5.9)` | `ScheduleObserveRangeReproTest.crossMidnightScheduleIsVisibleOnEveryOverlappedDay` + 当日插入/Flow 更新回归 |
| 5.10 | 提醒提前时间按设置触发 | ⚪ | — | 未复现：触发时刻严格按 start-reminderMinutes 计算；已进入提醒窗口立即排队，开始时间已过则取消，不产生负延迟 | — | `ScheduleReminderValidationTest.movedEarlierScheduleWithinReminderWindowRunsImmediately` |
| 5.11 | 创建冲突检测误报 | ⚪ | — | 未复现：本地冲突条件为 existing.start < new.end 且 existing.end > new.start，相邻不重叠；Agent 写入在任何副作用前检查冲突 | — | `RoomScheduleToolExecutorTest.overlappingScheduleIsRejectedBeforeAnyAgentSideEffect`、`CalendarSearchToolBindingTest.conflict tool includes device calendar events` |
| 5.12 | 时间已过(昨天)是否警告 | ✅ | 数据正确性 | 消息候选会拒绝过期时间，但手动保存和 Agent 最终执行只校验 epoch>0，昨天的误解析可无警告落库。两个最终写入口现统一拒绝超过 5 分钟容差的过去时间；确认延迟不误伤，失败无任何副作用 | 本提交 `fix(5.12)` | `AgentDataRepositoryTest.manualScheduleFromYesterdayIsRejectedWithoutWrite`、`RoomScheduleToolExecutorTest.confirmedScheduleFromThePastIsRejectedBeforeAnySideEffect` |
| 5.13 | 系统日历同步重复事件实例去重 | ⚪ | — | 未复现：读取端和导入端均按 eventId+instanceStart 的 sourceId 去重；同一实例单批只创建一次，重复导入更新同一稳定 ID | — | `CalendarPersistenceEdgeTest.duplicateSystemCalendarInstancesInOneImportAreStoredOnce`、`AgentDataRepositoryTest.confirmedSystemCalendarImportIsIdempotent` |

## 维度 7 · 设置链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 7.1 | 个人资料保存返回数据丢失 | ⚪ | — | 未复现：表单校验后一次性写入加密资料库并同步 StateFlow；姓名、联系方式、附加账号、职业和指令均从同一规范化对象回读 | — | `UserProfileStoreTest.mergeMissingIdentityOnlyFillsBlankFields` + `UserProfileTest` |
| 7.2 | 智能体档位改后行为真变化 | ⚪ | — | 未复现：执行偏好持久化后由 ProviderEngineConfig 每次运行读取；FAST 关闭重排并强制 FTS，DEEP 提升上下文上限 | — | `ExecutionPreferenceConfigTest`、`PlanningStrategyTest.workMapsAllExecutionPreferences` |
| 7.3 | 隐私权限关闭后数据仍发送 | ⚪ | — | 未复现：ASR、远程 MCP、远程 embedding 和自动检索个人上下文均在最终出站门读取当前设置；关闭时拒绝或省略，且不依赖 UI 状态 | — | `OutboundDataPolicyTest`、`ProviderCloudAsrGatewayTest.cloud speech is blocked without consent`、`McpRemoteEnvironmentTest`、`VolcEmbeddingEnvironmentTest` |
| 7.4 | 外观切换主题立即生效 | ⚪ | — | 未复现：主题页直接更新应用级 ThemePreference 状态并持久化，根 Compose 观察该状态重组；真机页面切换和回读均通过 | — | `AgentSettingsNavigationE2ETest.appearancePageOffersThreeThemeChoices` + `ThemePreferenceTest` |
| 7.5 | 记忆开关关后 Agent 仍读取 | ⚪ | — | 未复现：每轮运行动态读取 MemoryPolicy；长期记忆关闭或临时模式时不检索长期记忆，会话记忆关闭时不组装对话上下文，自动记忆工具也被禁用 | — | `RuntimeInputProcessorTest` 关闭会话/长期记忆回归 + `AgentControlStoreTest` |
| 7.6 | 对话风格切换回答风格变化 | ⚪ | — | 未复现：保存的 ResponseStyle 在每轮组装 personalization 时转为对应 promptFragment；CUSTOM 改用用户资料中的自定义指令 | — | `AgentPersonalizationPageTest.selectingPresetStylePersistsAfterSave` + `ResponseStyleTest` |
| 7.7 | 工具开关关后仍调用该工具 | ⚪ | — | 未复现：禁用集合持久化；ProviderEngineConfig.toolEnabled 直连 AgentControlStore，工具暴露与执行路由均受同一策略过滤 | — | `AgentControlStoreTest.disabledToolPersistsAndCanBeReenabled` + `AgentSettingsNavigationE2ETest.everyAgentSettingsEntryOpensAndBackReturnsThenToolTogglePersists` |
| 7.8 | 技能开关关后仍触发该技能 | ⚪ | — | 未复现：每轮只组装 `activeSpecs` 且再经 `isSkillEnabled` 过滤；技能允许的工具集合在最终工具调用前二次校验 | — | `AgentSkillsPageTest.togglingSkillPersistsDisabled` + 最终 `toolAllowlist` 代码审查 |
| 7.9 | 自动写开关关后仍自动写入 | ⚪ | — | 检查前提不成立：当前产品没有全局自动写开关；只允许五类固定白名单的可逆低风险自动整理，并强制 ChangeLog、可见回执与撤销。不能把不存在的开关当作失效设置 | — | `AutoWriteAtomicityTest`、`ContactTagAutoWriteTest`、`RoomCrmToolExecutorTest` |
| 7.10 | 通知分类开关关后仍发送 | ⚪ | — | 未复现：实际发送系统通知的日程与通话采集 Worker 均在 notify 前读取持久化分类开关；CRM/AUTO_WRITE 当前没有独立系统通知发送器，不存在绕过发送 | — | `ScheduleReminderPrivacyTest` + 两个 Worker 最终发送点代码审查 + `NotificationCategoryTest` |
| 7.11 | 头像选择后立即显示 | ⚪ | — | 未复现：选择器返回后后台加密复制并回读字节，成功后同一次 state update 同步 avatarUri/avatarBytes；明文旧头像也会首次读取迁移 | — | `UserProfileStoreTest.avatarIsEncryptedAtRestAndCanBeReadBack`、`legacyPlaintextAvatarMigratesOnFirstRead` |
| 7.12 | 多平台账号添加后保存 | ⚪ | — | 未复现：飞书/企微/钉钉/QQ 及同平台多账号统一编码进加密资料库，重建表单时去重回读 | — | `UserProfilePageTest.addAccountShowsPlatformPicker` + `UserProfileTest` |
| 7.13 | 职业多选后保存 | ⚪ | — | 未复现：职业使用 Set 多选、序列化持久化并由同一 profile StateFlow 回读，不会单选覆盖 | — | `UserProfilePageTest.showsProfileSectionsAndOccupationChips` + `UserProfileTest` |
| 7.14 | 给知伴指令注入 prompt | ⚪ | — | 未复现：用户资料转义并限制长度后写入 user.md，ProviderContextAssembler 作为 PERSONAL/AUTO_RETRIEVED 上下文注入，最终仍经过出站策略 | — | `UserProfileTest.markdownSanitizesCustomInstructionsLineBreaks` + ProviderContextAssembler 代码审查 |
| 7.15 | API Key 修改后立即生效 | ⚪ | — | 未复现：新凭据先 provision、健康探测成功后原子切换 profile；失败轮换保留旧 profile，不把未验证 key 发布给运行时 | — | `ProviderConfigurationBridgeTest.fiveProviderSwitchPublishesOnlyVerifiedKeyAndFailedRotationKeepsPrevious` |
| 7.15a | 网络恢复后“检查连接”仍显示旧失败 | ✅ | 体验 | 健康检查曾把失败快照与成功快照同样缓存一小时，且用户主动检查也不强制刷新。现仅复用/保存成功快照，主动检查始终探测当前网络 | 本提交 `fix(P1)` | `ProviderConfigurationManagerTest.failed health cache never masks a recovered provider`、`explicit health refresh bypasses a positive snapshot` |
| 7.15b | Keystore 瞬态失败不会删除 API Key 密文 | ✅ | 数据丢失 | 旧读取路径把密钥条目不存在与 Keystore 服务暂时不可用统一处理为删除密文，瞬态故障会造成不可逆凭据丢失。现只有确定的 `CredentialKeyNotFoundException` 清理孤儿密文，其他失败保留密文并提示可重试 | 本提交 `fix(7.15b)` | `KeystoreCredentialVaultPolicyTest`（缺失清理/瞬态保留）+ `KeystoreCredentialVaultTest.canaryIsCiphertextBoundToRefAndVersionAndCanBeDeleted`（SM-W7023） |
| 7.16 | API Key 删除后显示未配置 | ⚪ | — | 未复现：clear 同时删除活动 profile 与凭据，设置页刷新通过 `isConfigured()` 得到 false；运行时也不再可解析旧 credentialRef | — | `ProviderConfigurationManagerTest.clear removes profile and bound credential` |

## 维度 8 · 导航与路由
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 8.1 | 深链跳转返回正确页面 | ⚪ | — | 当前版本未声明 URI/App Link 深链，只有受控的显式通知和 ACTION_SEND 入口；不存在可被外部构造的深链返回栈 | — | AndroidManifest + typed NavGraph 代码审查 |
| 8.2 | 底部 TAB 切换返回原位置 | ⚪ | — | 检查文案与既定产品行为不一致：四个浏览 TAB 点击后明确回各自一级根页面，不恢复从能力页跨到关系页形成的旧子栈；这是此前修复“能力 TAB 再也回不去”的必要约束 | — | `AgentConversationUiStateTest.ask conversation and nested assistant chat are full screen` + NavGraph flat-tab 代码审查 |
| 8.3 | 页面嵌套过深返回键混乱 | ⚪ | — | 未复现：设置与 CRM 子页逐层 `popBackStack`，对话从 CRM 使用 BACK 返回来源；问问主入口才回日历根页。各设置入口逐页进出真机通过 | — | `AgentSettingsNavigationE2ETest.everyAgentSettingsEntryOpensAndBackReturnsThenToolTogglePersists` + `AgentConversationScreenE2ETest.approvalReplyActionsErrorRecoveryAndBackCallbacksAreOperable` |
| 8.4 | 路由参数缺失崩溃 | ⚪ | — | 未复现：路由使用 Kotlin Serialization typed route；可选参数有默认值，必需 opportunityId 只能由内部实体导航生成。实体随后消失时详情 Flow 返回空态而不解引用崩溃 | — | Screen/NavGraph typed-route 代码审查 + CRM 删除/引用完整性设备测试 |
| 8.5 | 旋转后导航状态丢失 | ⚪ | — | 未复现：NavController 由 `rememberNavController` 保存/恢复返回栈，外部 Inbox 请求的已处理序号另用 rememberSaveable 防止旋转重开弹层；表单状态由 ViewModel 持有 | — | NavGraph/各设置 ViewModel 状态代码审查 + SM-W7023 完整设备回归 |
| 8.6 | 通知点击进入正确页面 | ✅ | 功能不可用 | 日程提醒原先没有 contentIntent，App 已在后台其他页面时点击毫无动作；通知现携带目标日程时间，MainActivity 只消费一次并把 NavHost 导航到对应日期。通话备注仍进入关系页待备注项 | 本提交 `fix(8.6)` | `ScheduleReminderPrivacyTest.lockScreenVersionDoesNotContainScheduleDetails`（含 contentIntent）+ `SharedIntentExtractionTest.scheduleReminderFocusAcceptsOnlyPositiveEpochAndIgnoresMissingInput` |
| 8.7 | 分享 Intent 进入正确页面 | ⚪ | — | 未复现：ACTION_SEND 文本/图片成功落候选后才递增一次性 inbox request，NavHost 切到关系页并打开候选箱；非法或空分享不导航 | — | `SharedIntentExtractionTest` + `SharedTextCandidateTest` + 关系页 inbox request 代码审查 |
| 8.8 | CRM 建议点击进入正确页面 | ⚪ | — | 未复现：机会型建议“查看机会”使用其持久化 opportunityId 进入详情；联系人型新线索建议不显示无效机会入口；“问问知伴”进入 Work 上下文并以 BACK 返回 CRM | — | `CrmAgentSuggestionChainTest` + `CrmAskPromptsTest` + `CrmSuggestionCard` 分支代码审查 |

## 维度 9 · 数据与持久化
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 9.1 | 数据库迁移失败崩溃 | 🧪 | 数据丢失风险 | 1→34 每条迁移均有 MigrationTestHelper 覆盖且最新全链迁移通过；真正失败时 Room 会拒绝打开以避免破坏数据。恢复界面需先定义“保留损坏库/导出/重置”产品策略，不能用 destructive fallback 冒充修复 | — | `FactIndexMigrationTest` 全链 + 各版本专项迁移测试；需专用损坏数据库安装包验收恢复 UX |
| 9.2 | SQLCipher 密钥丢失打不开 | ✅ | 数据丢失风险 | 设备 Keystore 密钥丢失时仍坚持 fail-closed、不静默重建；用户可提前创建完整的密码加密 SQLCipher 备份，恢复时用目标设备的新数据库密钥重新加密，因此有备份即可跨设备或密钥重置恢复。备份不含 API Key，未创建备份时不可逆密钥丢失仍无法恢复 | 本提交 `fix(9.2/9.13)` | `AgentDatabasePortableBackupTest.portableBackupReencryptsAndRestoresTheCompleteDatabaseOnColdStart` + `wrongPasswordCannotStageRestore` + `AgentPortableBackupServiceTest.serviceBacksUpAnOpenWalDatabaseWithoutDroppingCommittedRows` |
| 9.3 | 数据库文件损坏崩溃 | 🧪 | 数据丢失风险 | SQLCipher 打开损坏文件会拒绝读取，当前无安全的隔离/恢复界面；不能自动删库。需构造损坏库的隔离安装包，并在产品确定保留原文件、诊断导出和重置路径后验收 | — | 明文转密文前执行 `integrity_check`/`cipher_integrity_check`；运行期损坏恢复待专用环境 |
| 9.4 | 并发写入死锁 | ⚪ | — | 生产多表写统一走 Room `withTransaction`，Room 事务执行器串行化；SM-W7023 全量设备测试与自动写原子性/Runtime 并发回归未出现死锁或半写 | 审计提交 | `AutoWriteAtomicityTest` + `RoomRuntimeStoreTest` + `RuntimeGatewayTest` |
| 9.5 | Room 查询超时 ANR | ⚪ | — | 生产数据库未启用 `allowMainThreadQueries`，DAO 均由 suspend/Flow 或 IO 工作线程消费；真机全量回归未出现主线程数据库访问或 ANR。测试内的 `allowMainThreadQueries` 仅用于隔离数据库 | 审计提交 | SM-W7023 319 项设备回归 + 代码审查 |
| 9.6 | 导出文件过大 OOM | ✅ | 功能不可用 | 原实现一次加载各表全部实体，再构建完整 JSON 树和编码字符串，峰值内存随全量数据多重增长；改为每页 200 条读取并直接流式写入临时文件，完成后原子提交 | 本提交 `fix(9.6)` | `AgentDataExportServiceTest.exportStreamsEveryPageWithoutDroppingRows` + 既有完整性/脱敏测试 |
| 9.7 | 导出含敏感信息脱敏 | ✅ | 数据完整性 / 隐私 | 导出误用诊断日志的 512 字符脱敏上限，长对话和备注尾部被静默截断；结构化微信号也不匹配通用正则而原样导出。导出改用不截断的脱敏路径，联系通道 ID 按字段整体隐藏，诊断日志仍保留原上限 | 本提交 `fix(9.7)` | `AgentDataExportServiceTest.exportRedactsPhoneEmailAndNeverContainsSecrets` + `exportPreservesLongConversationAfterRedactingDirectIdentifiers` |
| 9.8 | 清除后是否真删除(非软删) | 🖐 | 数据丢失（测试风险） | “清除全部数据”明确跳系统应用信息页，由 Android 执行应用沙箱级清除；在用户当前真机执行会删除真实联系人、密钥和设置，不得自动测试。应在一次性测试安装中清除后用 `run-as` 检查 databases/files/shared_prefs 均不存在 | — | SM-W7023 人工步骤（仅一次性测试数据） |
| 9.9 | 迁移后旧数据保留 | ✅ | 数据完整性 | 24→25 迁移把旧手机号原文直接写进 normalizedValue，导致 `+86`、空格或横线格式的旧联系人在升级后无法按当前规范号码匹配；新增 33→34 迁移复用生产归一化函数，并在唯一键冲突时按主号码/用户确认/验证时间保留最佳记录 | 本提交 `fix(9.9)` | `ContactMethodNormalizationMigrationTest.formattedLegacyPhonesAreCanonicalizedAndDuplicatesAreCollapsed` + `FactIndexMigrationTest` 全迁移链 |
| 9.10 | 迁移后索引正确 | ⚪ | — | MigrationTestHelper 对每次迁移后的 34 版导出 schema 做严格比较，callback 管理的部分索引另有显式断言；未发现缺失/重复索引 | 审计提交 | `PlanDagMigrationTest` + `CrmDomainMigrationTest` + `FactIndexMigrationTest` |
| 9.11 | 迁移后外键约束正确 | ⚪ | — | 迁移严格校验通过；CRM、Runtime、关系事件和通话表的 FK 行为有开启 `PRAGMA foreign_keys=ON` 的删除/SET_NULL/CASCADE 设备测试，未发现悬空引用 | 审计提交 | `CrmReferenceIntegrityMigrationTest` + `RuntimeStoreMigrationTest` + `RelationshipEventMigrationTest` + `CallLogMigrationTest` |
| 9.12 | SQLCipher 加密查询性能下降 | 🧪 | 体验 | 正确性回归已在真实加密库通过，但仓库没有可复现的大数据基准；需基准构建预置 1000 联系人/500 日程/100 商机，对比冷开、FTS P95 和事务 P95，普通功能测试不能给性能结论 | — | 待 Macrobenchmark/固定数据集 |
| 9.13 | 备份恢复后数据完整 | ✅ | 数据完整性 | Android 系统备份继续关闭，避免数据库与设备 Keystore 密钥产生半恢复；新增用户主动创建的便携加密备份，覆盖联系人、关系、日程、记忆和 CRM 等完整数据库。导出前强制 WAL checkpoint，导入先校验完整性与 schema，再暂存为目标设备密钥加密库；冷启动原子替换，异常或中断时从 `.pre-restore` 回滚 | 本提交 `fix(9.2/9.13)` | `AgentDatabasePortableBackupTest.portableBackupReencryptsAndRestoresTheCompleteDatabaseOnColdStart` + `coldStartRecoversAnInterruptedReplacementBeforeApplyingPendingRestore` + `AgentPortableBackupServiceTest.serviceBacksUpAnOpenWalDatabaseWithoutDroppingCommittedRows` + `AgentSettingsNavigationE2ETest.dataManagementExposesPortableBackupAndExplainsCredentialBoundary` |
| 9.14 | 清除后 WAL 文件清理 | 🖐 | 数据丢失（测试风险） | 系统“清除数据”应移除整个应用数据目录，包含 db/wal/shm、SharedPreferences、缓存和 Keystore 归属；当前真机含用户数据，不执行破坏性验证。一次性安装清除后检查沙箱目录与重新启动建库 | — | SM-W7023 人工步骤（仅一次性测试数据） |
| 9.15 | 真机测试清空生产用户资料 | ✅ | 数据丢失 | 两个设备测试直接复用 `user_profile_secure` 并调用 `clear()`；为 UserProfileStore 增加测试命名空间，测试结束只删除隔离数据 | 本提交 `fix(9.15)` | `UserProfileStoreTest` + `UserProfilePageTest` |
| 9.16 | 覆盖安装后数据库版本/密钥兼容 | 🖐 | 数据丢失（测试风险） | 普通设备回归只证明全新测试安装；覆盖升级必须先在一次性构建写入唯一联系人/日程并记录 DB 版本，再 `adb install -r` 新 APK，确认数据、wrapped key 与 34 版迁移均保留。不得用当前用户资料做破坏性升级演练 | — | SM-W7023 人工升级矩阵 |
| 9.17 | 清除数据重装后是否重建 | 🖐 | 数据丢失（测试风险） | Android 清除数据/卸载会删除应用沙箱；需一次性测试安装执行清除→启动→确认创建全新加密 34 版库、旧唯一数据不存在。当前用户设备不自动执行 | — | SM-W7023 人工步骤（一次性数据） |
| 9.18 | 数据库文件权限被改后是否崩溃 | 🧪 | 功能不可用 | 非 root 生产设备不能修改应用私有数据库权限；需 root 模拟器把 DB 设为不可读并验证 fail-closed/恢复界面。SM-W7023 非 root，无法安全复现 | — | root 模拟器专用场景 |

## 维度 10 · 网络与 Provider
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 10.1 | StepFun 超时卡住 | ⚪ | — | 主推理同时受总超时（默认 120 秒、弱网 15 秒）与逐事件空闲超时（默认 30 秒）约束；超时主动取消 Provider 请求并落 `TIMEOUT/FAILED_RETRYABLE`，不会永久卡住，租约心跳期间仍能安全写终态 | 审计提交 | `RuntimeInputProcessorTest.stalledProviderTimesOutWhileLeaseHeartbeatKeepsSafeFailureWritable` |
| 10.2 | 返回错误码显示错误不崩溃 | ⚪ | — | HTTP 401/403、408、429、5xx 及阶跃业务错误均在 Provider 边界映射为固定安全码；响应 message 不进入 UI，映射器给出中文可操作提示 | 审计提交 | `ProviderModuleTest.probeStreamSchemaUsageAndErrorMapping` + `stepFunHttpErrorsMapSafeRequestId` + `standardCompatibleProviderErrorsAreMappedWithoutExposingMessages` + `AgentProjectionUiMapperTest.failure taxonomy maps to actionable safe Chinese messages` |
| 10.3 | 返回非法 JSON 崩溃 | ✅ | 功能不可用 | 非法 SSE JSON 原先被静默跳过，最终只表现为延迟出现的流不完整错误。现入口立即转为固定、可重试的 `PROVIDER_PROTOCOL_ERROR`，不泄露外部响应正文；工具参数继续 fail-closed | 本提交 `fix(P0-2)` | `ProviderModuleTest.malformedSseChunkReturnsFixedProtocolFailure` + `invalidOrOversizedToolArgumentsFailClosed` |
| 10.3a | SSE 直接 DONE 丢失工具调用 | ✅ | 功能不可用 | Provider 在 tool_calls 片段后直接发送 `[DONE]` 且省略 finish_reason 时，pending tool 原先未落定且没有 Final。现 DONE 与 finish_reason 共用同一落定路径，按序发出完整工具调用并且恰好一个 Final | 本提交 `fix(P0-2)` | `ProviderModuleTest.toolCallIsFlushedWhenDoneArrivesWithoutFinishReason` + `doneWithoutFinishReasonEmitsExactlyOneFinal` + `usageAndFinalAreEmittedAtMostOnce` |
| 10.3b | SSE 显式 null 不会提前终止工具调用 | ✅ | 功能不可用 | OpenAI 兼容流的中间帧常带 `content:null` 与 `finish_reason:null`；旧解析把 JsonNull 的 content 当字符串 `"null"`，会输出伪正文并在工具参数仍分片时提前 finalize。现所有可空协议字段使用 contentOrNull，等待真实终止原因 | 本提交 `fix(10.3b)` | `OpenAiCompatibleProviderAdapterTest.explicitNullFieldsDoNotFinalizeOrEmitLiteralNullDuringFragmentedToolCall`（红→绿） |
| 10.4 | 返回空响应显示空不崩溃 | ⚪ | — | 无 Final 的空流转为 `PROVIDER_STREAM_INCOMPLETE`；有 Final 但正文为空转为 `EMPTY_RESPONSE`，投影层显示“AI 没有返回内容”，不会渲染空白成功态 | 审计提交 | `AgentProjectionUiMapperTest.failure taxonomy maps to actionable safe Chinese messages` + `ProviderExecutionEngine.consumeReActStream` 终态审查 |
| 10.5 | 网络断开离线提示 | ⚪ | — | 网络预检在 Provider 调用前把离线映射为 `NETWORK_OFFLINE`；UI 明确说明仍可查看本地日程、联系人和记忆，并提供联网后重试语义 | 审计提交 | `RuntimeInputProcessorTest.weakNetworkSkipsVectorAndRerankWhileExtremeNetworkFailsBeforeProvider` + `AgentProjectionUiMapperTest.failure taxonomy maps to actionable safe Chinese messages` |
| 10.6 | 网络恢复自动重试 | ⚪ | — | 瞬态失败只在尚未收到首个流事件时按 1s/2s 有界退避重试；一旦已有正文绝不自动重放，避免重复内容/工具调用。恢复超出该窗口后由 `FAILED_RETRYABLE` 的显式重试入口继续，不做无限后台重试 | 审计提交 | `ResilientProviderAdapterTest.probeRetriesTransientFailuresWithBoundedBackoff` + `streamRetriesOnlyBeforeFirstEvent` |
| 10.7 | API Key 过期明确提示 | ⚪ | — | HTTP 401/403 与兼容服务鉴权错误统一映射 `AUTHENTICATION_FAILED`（不可重试），UI 明确引导检查大模型连接设置且开启凭据缺失入口 | 审计提交 | `ProviderModuleTest.stepFunHttpErrorsMapSafeRequestId` + `AgentProjectionUiMapperTest.failure taxonomy maps to actionable safe Chinese messages` |
| 10.8 | 证书锁定失败明确提示 | ✅ | 功能不可用/安全 | TLS 主机或 SPKI 校验失败原先作为普通 IOException 重试，最终只显示网络不可用；Provider 传输边界现映射为不可重试的 `TLS_VERIFICATION_FAILED`，运行记录保留固定码，UI 明确提示安全连接验证失败且不泄露证书细节 | 本提交 `fix(10.8)` | `ProviderModuleTest.tlsVerificationFailureIsSafeAndNeverRetryableForProbeOrStream` + `AgentProjectionUiMapperTest.failure taxonomy maps to actionable safe Chinese messages` |
| 10.9 | 流式中断显示已接收部分 | ⚪ | — | 每个 Delta 到达即独立写事件日志，失败终态不会删除既有 Delta；投影仍以 `assistantText` 展示已收正文，并保留失败提示。重连还能从 journal 回填正文 | 审计提交 | `GatewayRuntimeUiClientTest.reconnect backfills assistant body from journal when snapshot skipped the deltas` + `AgentSessionReducerTest.assistant deltas merge once by attempt and ordinal then finalize` |
| 10.10 | 流式重复去重 | ⚪ | — | Provider 为事件分配单调 ordinal，Usage/Final 各最多发一次；投影以 `attemptId:ordinal` 去重，同一序号重放不会重复拼正文，不同重试 attempt 仍隔离 | 审计提交 | `ProviderModuleTest.usageAndFinalAreEmittedAtMostOnce` + `AgentSessionReducerTest.assistant deltas merge once by attempt and ordinal then finalize` |
| 10.11 | 响应>100KB OOM | ⚪ | — | 模型列表上限 256KiB、单 SSE 帧 64KiB、整条流 4MiB；读取前/逐行累计检查，越界先取消 Call 再返回固定失败码。100KiB 多帧合法回复不会一次性聚合进内存，超限也不会无界增长 | 审计提交 | `ProviderModuleTest.oversizedModelsBodyAndSseFrameCancelBeforeUnboundedRead` + Provider 总流量上限代码审查 |
| 10.12 | 响应特殊字符崩溃 | ⚪ | — | 请求/响应均由 kotlinx.serialization JSON 处理 UTF-8；中文和 emoji 的 token 上限按 Unicode 安全路径 fail-closed，SSE 文本由 `JsonPrimitive.content` 解码，不手拼/二次解析可见正文 | 审计提交 | `ProviderModuleTest.outputLimitAndTotalContextFailClosed` + `probeStreamSchemaUsageAndErrorMapping` |

## 维度 11 · 权限与隐私
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 11.1 | 通知监听撤销后仍采集 | 🖐 | 隐私（需系统设置实测） | Android 撤销通知使用权后系统应解绑 Listener，进程内没有旁路读取通知；断连只记录时间并请求系统 rebind。需在系统设置撤销后发送唯一测试通知，确认候选计数不增，再恢复权限 | — | SM-W7023 人工权限步骤 |
| 11.2 | 无障碍撤销后崩溃 | 🖐 | 功能不可用（需系统设置实测） | 服务所有入口受系统绑定与用户总开关约束，销毁时清 Handler、Recognizer 和 scope；需录入草稿后在系统设置关闭知伴无障碍，确认无崩溃且不再生成发出候选 | — | SM-W7023 人工权限步骤 |
| 11.3 | 通话记录撤销后崩溃 | 🖐 | 功能不可用（需权限切换实测） | Source 查询前检查 `READ_CALL_LOG`，同步协调器对检查后竞态产生的 `SecurityException` 降级为空；需在一次增量同步前撤销权限并确认同步安静结束、游标不错误前移 | — | `CallLogAccessProbeTest` + SM-W7023 人工权限步骤 |
| 11.4 | 录音撤销后崩溃 | 🖐 | 隐私/功能不可用（需录音中实测） | 采集循环把 AudioRecord 负错误码转成明确失败并停止，finally 清零帧缓冲，停止路径释放 recorder/effects；仍需录音中途撤销麦克风权限验证三星驱动实际返回值与 UI 终态 | — | `StepFunRealtimeProtocolTest.audio read failures are terminal` + SM-W7023 人工权限步骤 |
| 11.5 | 通讯录撤销后崩溃 | 🖐 | 功能不可用（需权限切换实测） | Reader 查询前检查 `READ_CONTACTS`，查询竞态走 `runSuspendCatching` 返回固定错误而非崩溃；需导入页打开期间撤销权限验证页面提示与零落库 | — | `SystemContactReader` 代码审查 + SM-W7023 人工权限步骤 |
| 11.6 | 日历撤销后崩溃 | 🖐 | 功能不可用（需权限切换实测） | Reader 查询前检查 `READ_CALENDAR`，Provider 异常降级为空结果；需系统日历二级页打开期间撤销权限，确认无崩溃、无半写入 | — | `SystemCalendarReader` 代码审查 + SM-W7023 人工权限步骤 |
| 11.7 | 敏感数据脱敏后发送 | ⚪ | — | 所有模型请求经 `PolicyEnforcingProviderAdapter`；自动检索 PERSONAL 的手机号/邮箱/身份证脱敏，SENSITIVE 整块省略，工具观察结构化私有字段清除，自动敏感附件 fail-closed | 审计提交 | `OutboundDataPolicyTest.automaticallyRetrievedPersonalIdentifiersAreRedactedWithoutTruncatingContext` + `toolObservationRemovesStructuredPrivateFieldsButKeepsUsefulProfileFields` + `userAuthoredIdentifiersRemainIntactButAutomaticSensitiveContentIsOmitted` |
| 11.8 | 敏感数据写入日志 | ⚪ | — | 生产 Kotlin 无 `Log/println/printStackTrace/Timber` 调用；出站审计仅保存通道、敏感度、数量、结果和时间，不记录正文/音频/工具参数，Provider 错误仅保留固定码和安全 requestId | 审计提交 | `ProviderModuleTest.redactorRemovesCanaryBearerAndRejectsUnsafeRequestId` + 全生产源码静态扫描 |
| 11.9 | 敏感数据写未加密存储 | ⚪ | — | Room 主库由 Keystore 包裹随机密钥的 SQLCipher 打开，旧明文库原子迁移；API Key/用户档案使用加密偏好或加密文件，Manifest 禁止备份数据库与设备迁移 | 审计提交 | `AgentDatabaseEncryptionTest.plaintextDatabaseIsAtomicallyEncryptedAndReopensWithStableKeystoreKey` + `newRoomDatabaseIsEncryptedAndReopensWithoutDataLoss` |
| 11.10 | 无障碍截图 Bitmap 释放 | ⚪ | — | 截图 hardwareBuffer 在复制后立即 close；Bitmap 在 OCR 启动失败回调和完成回调两条路径 recycle，服务销毁关闭 recognizer；截图不落盘且只在知伴发起的待验证 handoff 期间触发 | 审计提交 | `ScreenshotOcrResourceTest` + `OutgoingMessageAccessibilityService.recognizeExpectedHandoffScreenshot` 资源路径审查 |
| 11.11 | 通知含验证码过滤 | ⚪ | — | 中英文 OTP/验证码/动态口令/交易密码及邻近 4–8 位码在 Parser 入库前丢弃，发出消息候选复用同一过滤器；SMS 默认未启用且服务号另有阻断 | 审计提交 | `SocialMessagePerceptionTest.verificationCodesAndUnsupportedAppsAreNeverStaged` + `OutgoingMessageCandidateTest` |
| 11.12 | 通知含银行卡号过滤 | ✅ | 隐私泄露 | 原过滤器只覆盖验证码、密码和密钥，普通社交消息里的银行卡号可进入候选；现按银行卡关键词邻近数字/掩码过滤，并对无关键词的 13–19 位号码用 Luhn 校验，避免把普通长编号一律误杀 | 本提交 `fix(11.12)` | `SocialMessagePerceptionTest.verificationCodesAndUnsupportedAppsAreNeverStaged` |
| 11.13 | 通话含敏感号码(10086)过滤 | ⚪ | — | 100xx、95/96、106 等服务短号在社交通知解析和自动关系证据阶段均 fail-closed；通话日志只把号码可识别且能匹配真实联系人的记录关联关系，不会为服务号自动建联系人 | 审计提交 | `SocialMessagePerceptionTest.genericOrBroadcastSmsIsNotAddedToRelationshipInbox` + `AgentDataRepositoryTest.purgeNonPersonalSmsCandidates` + `CallLogSyncCoordinator` 代码审查 |
| 11.14 | 语音上传前明确授权 | ⚪ | — | ASR_BATCH/REALTIME 默认关闭；每次发送音频前由 `OutboundExportGate` 检查独立“允许语音识别上云”同意，拒绝时不读取凭据、不调用 transport，并记录不含正文的阻断审计 | 审计提交 | `ProviderCloudAsrGatewayTest.cloud speech is blocked before credential and transport without consent` + `StepFunRealtimeVoiceControllerTest.startWithoutCloudSpeechConsentFailsWithActionableMessage` |
| 11.15 | 联系人上传前明确授权 | ⚪ | — | App 不批量上传通讯录；仅在用户发起模型请求时注入相关联系人摘要。自动手机号/邮箱等始终脱敏、关系边等 SENSITIVE 省略，设置页可关闭全部自动个人资料发送；主动联系人完善也经过同一出站策略 | 审计提交 | `OutboundDataPolicyTest.userCanDisableAllAutomaticallyRetrievedPersonalContext` + `LlmContactEnrichmentProviderTest` |
| 11.16 | 消息内容上传前明确授权 | ⚪ | — | 用户在问问点发送的原文属于当次主动授权；通知/记忆自动检索内容标为 AUTO_RETRIEVED，经策略脱敏或省略后才进入模型，原始通知候选不会直接裸发。远程 MCP/语音另有默认关闭的独立同意门 | 审计提交 | `OutboundDataPolicyTest.userAuthoredIdentifiersRemainIntactButAutomaticSensitiveContentIsOmitted` + `ProviderRetrievalRerankerTest` |

## 维度 12 · 性能与内存
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 12.1 | 1000+联系人搜索卡顿 | 🧪 | 性能（缺规模基准） | DAO 检索已用 FTS 且无 LIKE；关系页主列表仍观察全部联系人并在 Compose 内存过滤，同时有较多 StateFlow 订阅。未发现正确性 bug，但必须用 1k/5k 数据集测输入到首帧耗时与重组次数后才能判绿 | — | Macrobenchmark/Compose recomposition 基准待建 |
| 12.2 | 500+日程日历卡顿 | 🧪 | 性能（缺规模基准） | 日程列表为带稳定 key 的 LazyColumn，范围查询有索引和 limit；尚无 500/5k 日程滚动与月份切换帧耗基准，不能仅凭 LazyColumn 声称通过 | — | 规模数据 Macrobenchmark 待建 |
| 12.3 | 100+商机看板卡顿 | 🧪 | 性能（缺规模基准） | 机会列表与各看板列均使用 Lazy 容器和稳定 opportunityId；ViewModel 仍一次投影完整机会集合，需 100/1k 商机的列切换、滚动和 Room emit 基准 | — | CRM Macrobenchmark 待建 |
| 12.4 | 100+待确认候选箱卡顿 | 🧪 | 性能（缺规模基准） | 候选箱使用带 candidateId key 的 LazyColumn，但数据库/状态层仍向 UI 发送完整待确认集合；需 100/1k 候选的打开、搜索与逐条处理基准 | — | 候选箱规模基准待建 |
| 12.5 | 流式回复卡顿 | ⚪ | — | Delta 按事件增量写日志和 reducer 合并，消息 LazyColumn 使用稳定 id；滚动只在用户位于底部时跟随，运行状态不再每 token 双查数据库。真机完整对话设备回归无 ANR/崩溃 | 审计提交 | `AgentSessionReducerTest.assistant deltas merge once by attempt and ordinal then finalize` + `AgentConversationScreenE2ETest` |
| 12.6 | 500+图谱节点卡顿 | ⚪ | — | 图谱数据可有 500+ 联系人，但当前画布严格只取当前中心的前 24 个邻居（总节点最多 25）并显示隐藏数量；O(n²) 斥力不会直接处理全部 500 节点 | 审计提交 | `RelationshipGraphSection.graphNeighborIds` 上限代码审查 + `ForceRelationshipGraphTest` |
| 12.7 | 图片加载过多 OOM | ⚪ | — | 输入附件限制数量/单项/总字节；共享图片先读 bounds 并按尺寸采样，Provider 解码限制 10MiB、按目标尺寸分配 Bitmap 且 finally recycle，响应附件不会无界常驻 UI | 审计提交 | `AppPrivateAttachmentStagerTest` + `SharedImageDecodePolicyTest.oversizedDimensionsAreReducedBeforeBitmapAllocation` + `ProviderAttachmentResolverTest` |
| 12.8 | Activity/Fragment 泄漏 | ⚪ | — | 应用为单 Activity Compose、无 Fragment；Activity 注册的 launcher/recognizer 绑定生命周期，异步图片 OCR 完成或失败均释放 Bitmap。未发现静态 Activity/Context 被长生命周期单例持有 | 审计提交 | Hilt/Compose 生命周期代码审查 |
| 12.9 | ViewModel 泄漏 | ⚪ | — | ViewModel 协程统一使用 viewModelScope，未发现 ViewModel 保存 Activity/View/Composable lambda；注入对象使用 ApplicationContext 或纯仓库接口 | 审计提交 | 全 ViewModel 构造与 scope 静态审查 |
| 12.10 | 协程未取消泄漏 | ⚪ | — | UI 使用 rememberCoroutineScope/viewModelScope；通知与无障碍 Service 在 onDestroy cancel，自有实时语音 stop/fail 释放硬件；应用级 runtime/startup scope 与进程同寿命。未发现 GlobalScope | 审计提交 | `rg CoroutineScope/GlobalScope` 静态审查 + 服务资源测试 |
| 12.11 | 监听器未注销泄漏 | ⚪ | — | Relation/Settings 的 LifecycleObserver 均在 DisposableEffect.onDispose 移除；实时网络/语音回调由各自 stop/close 路径解除，未发现仅注册不注销的生产监听器 | 审计提交 | `rg addObserver/removeObserver` 静态审查 |
| 12.12 | 广播接收器未注销泄漏 | ⚪ | — | 生产代码未动态调用 registerReceiver，也没有持有需要手动注销的 BroadcastReceiver；Manifest 静态组件由系统管理 | 审计提交 | 全生产源码 `registerReceiver/unregisterReceiver` 静态扫描 |
| 12.13 | 服务未停止泄漏 | ⚪ | — | NotificationListener/AccessibilityService 由系统绑定，不自启常驻前台服务；onDestroy 关闭 Channel/Handler/Recognizer/scope。通话挂断对账使用一次性 WorkManager，不保活 Service | 审计提交 | `ZhiBanNotificationListenerService.onDestroy` + `OutgoingMessageAccessibilityService.onDestroy` 代码审查 |

## 维度 13 · 兼容性与边界
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 13.1 | 屏幕密度 420/560dpi UI 变形 | 🧪 | 体验（设备矩阵缺口） | Compose 使用 dp/sp 和系统 density，未发现 px 固定布局；当前仅 SM-W7023 密度真机覆盖，仍需 420/560dpi 模拟器截图与点击目标测试 | — | 420/560dpi 模拟器矩阵 |
| 13.2 | 手机/平板 UI 变形 | ⚪ | — | 根 Scaffold 按 600dp 切换底栏/导航 Rail，内容最大宽 840dp并处理 safeDrawing；320/599/600/840dp 边界有单测，SM-W7023 平板设备套件通过 | 审计提交 | `ZhiBanAdaptiveLayoutTest` + SM-W7023 设备测试 |
| 13.3 | 深色模式颜色正确 | 🖐 | 体验（视觉验收） | Material 亮/暗 ColorScheme、系统栏图标和关系图 token 均按主题切换，节点文字对比度有单测；全页面视觉一致性仍需真机逐页暗色截图验收 | — | `RelationshipGraphColorsTest.node labels retain accessible contrast in light and dark themes` + 人工截图矩阵 |
| 13.4 | 字体放大2倍 UI 溢出 | ⚪ | — | 统一页头和关键按钮已移除会裁字的固定文字高度，设备测试以 fontScale=2 渲染主视觉和输入区域并验证可显示/可操作 | 审计提交 | `ZhiBanVisualFontScaleTest` |
| 13.5 | 横屏 UI 变形 | 🖐 | 体验（方向矩阵缺口） | Scaffold 基于实时 maxWidth 自适应并处理系统 Insets，但关系/日历/CRM 二级页尚无横屏截图金丝雀；需 SM-W7023 强制横屏逐页检查无裁切和不可达操作 | — | SM-W7023 人工横屏矩阵 |
| 13.6 | Android 26/30/33/35 功能一致 | 🧪 | 兼容性（系统矩阵缺口） | minSdk 26/target 35，版本分支覆盖通知、截图、Parcelable、TelephonyCallback 等 API；当前设备仅 Android 13(API 33)，必须补 API 26/30/35 模拟器套件，不能外推一致 | — | API 26/30/35 模拟器矩阵 |
| 13.7 | 2GB 低端设备卡顿 | 🧪 | 性能（环境缺口） | 有附件、图谱节点、Provider 响应上限，但没有 2GB/低 CPU 设备或低内存模拟器的启动、滚动和多任务回收基准 | — | 2GB AVD Macrobenchmark |
| 13.8 | 中英文混合输入处理 | ⚪ | — | 输入按 UTF-8 暂存、JSON 序列化和 Unicode token 估算，不做 ASCII 假设；中文、英文、emoji 路径均在 Provider/解析测试覆盖 | 审计提交 | `ProviderModuleTest.outputLimitAndTotalContextFailClosed` + `SocialMessagePerceptionTest` |
| 13.9 | 特殊字符输入崩溃 | ⚪ | — | 用户文本不拼接 SQL/JSON，Room 参数绑定与 kotlinx.serialization 负责转义；换行、引号、反斜线及 emoji 受统一 UTF-8 字节上限约束 | 审计提交 | `ToolArgumentParserTest` + `AppPrivateAttachmentStagerTest` + Provider Unicode 测试 |
| 13.10 | 超长文本(>1000字)卡顿 | ⚪ | — | 文本输入允许到 65,536 UTF-8 字节但在暂存和 Provider 上下文处有硬上限；超模型上下文在网络前 fail-closed，不会构造无限请求。1k 字本身远低于上限 | 审计提交 | `RuntimeInputProcessorTest` 输入上限 + `ProviderModuleTest.outputLimitAndTotalContextFailClosed` |
| 13.11 | 快速连续点击重复触发 | ⚪ | — | Start 使用稳定 actionId/commandId 与数据库唯一约束，双击只暂存一次；工具写再由 canonical digest + providerCallId 幂等，重复确认/执行不会产生第二次领域写 | 审计提交 | `V2AgentConversationBackendTest.double start stages once and rejected receipt discards staged input` + `PlanEnvelopeFactoryTest` + 工具幂等设备测试 |
| 13.12 | 快速滑动卡顿 | 🧪 | 性能（帧基准缺口） | 主要长列表使用 Lazy 容器和稳定 key，未发现嵌套无限测量；尚无快速 fling 的 jank/帧时长基准，需在 1k 联系人和 500 日程数据集测量 | — | Macrobenchmark fling 场景待建 |

## 维度 14 · 状态与生命周期
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 14.1 | 杀后重启会话状态恢复 | ⚪ | — | 会话、run、attempt、事件与 UI projection 均落 SQLCipher Room；数据库关闭重开后可 claim 恢复句柄并继续状态机 | 审计提交 | `RoomRuntimeStoreTest.fileReopenRecoveryHandleCarriesSnapshotAndCanContinueWithClaimedLease` |
| 14.2 | 杀后重启草稿保留 | ✅ | 数据丢失 | 问问输入正文原先只用 `remember`，Activity 重建/系统保存状态恢复后清空；现用 `rememberSaveable` 保存未发送正文 | 本提交 `fix(14.2)` | `AgentConversationScreenE2ETest.unsentDraftSurvivesSavedInstanceStateRestoration` |
| 14.3 | 杀后重启未发消息保留 | ✅ | 数据丢失 | 未发送文本与草稿是同一状态，现可随 saved instance state 恢复；未发送附件仍只存在私有暂存并受到期清理，不把 Uri/文件句柄塞进 Bundle | 本提交 `fix(14.2)` | `AgentConversationScreenE2ETest.unsentDraftSurvivesSavedInstanceStateRestoration` |
| 14.4 | 杀后重启未完成确认保留 | ⚪ | — | pending approval 由持久化 run 状态与 approval 事件重建；冷启动时 `GatewayRuntimeUiClient` 解析持久化 payload，确认/拒绝仍引用原 proposal | 审计提交 | `AgentSessionReducerTest`（approval proposalId/payloadRef 回放）+ `AgentRuntimeProjectionControllerTest`（确认/拒绝命令） |
| 14.5 | 杀后重启自动写回执保留 | ⚪ | — | `change_log` 与 `auto_write_receipts` 同事务写入 Room，重建 Repository 后仍可观察；迁移测试覆盖旧库回执保留 | 审计提交 | `AutoWriteAtomicityTest` + `AutoWriteMigrationTest` + `AgentDataRepositoryTest.inferredReplyInteractionIsPersistedAsReversibleAutoWrite` |
| 14.6 | 覆盖安装 API Key 丢失 | ⏭ | — | 上一轮 #22 已修+真机验证 | `c685d9b` | 真机 |
| 14.7 | 覆盖安装用户数据保留 | 🖐 | 数据丢失 | 当前设备承载用户数据，不执行破坏性升级矩阵；需一次性测试档案按“旧 APK 建数据→`adb install -r` 新 APK→逐表核对”验收 | | 手工升级矩阵 |
| 14.8 | 覆盖安装设置保留 | 🖐 | 数据丢失 | 与 14.7 同批验证 DataStore、加密偏好和 Keystore；现有 API Key 覆盖安装修复不能替代全设置矩阵 | | 手工升级矩阵 |
| 14.9 | 清除数据后真清空 | 🖐 | 隐私 | `pm clear` 会删除用户数据，禁止在当前用户档案执行；需专用测试档案清除后验证首次启动状态 | | 专用测试档案 |
| 14.10 | 清除后残留文件 | 🖐 | 隐私 | 需专用测试档案在 `pm clear` 前后核对 app files/cache/no-backup 目录；当前真机不做破坏性操作 | | 专用测试档案 |
| 14.11 | 清除后残留数据库 | 🖐 | 隐私 | 需专用测试档案清除后确认 SQLCipher DB/WAL/SHM 均不存在 | | 专用测试档案 |
| 14.12 | 清除后残留缓存 | 🖐 | 隐私 | 需专用测试档案清除后核对附件暂存、OCR 与图片缓存；当前真机不做破坏性操作 | | 专用测试档案 |
| 14.13 | 卸载重装数据清空 | 🖐 | 隐私 | 卸载会删除当前用户数据与凭据，禁止直接执行；需专用测试档案卸载/重装后核对 DB、设置与 Keystore 新建 | | 专用测试档案 |

## 维度 15 · 特殊场景（多需人工/环境）
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 15.1 | 双卡通话识别 SIM 卡 | 🧪 | 功能边界 | 当前 SM-W7023 只有单卡测试条件；需插入两张 SIM，分别呼入/呼出后核对 subscription/phoneAccount 映射 | | 双卡实呼矩阵 |
| 15.2 | 飞行模式降级不崩溃 | 🖐 | 功能可用性 | 需人工在流式对话、语音与后台同步中途切飞行模式，核对安全失败、重试与恢复；自动化不能可靠切换 OEM 飞行模式 | | 人工网络切换 |
| 15.3 | 低电量后台任务受限 | 🖐 | 功能可用性 | 需在三星电量保护与低电量模式下观察 WorkManager/通知监听/通话对账至少一个调度周期 | | 人工电量矩阵 |
| 15.4 | 充电/不充电行为一致 | 🖐 | — | 当前 Worker 未声明充电约束；仍需人工插拔电源验证 OEM 不额外限制采集与维护任务 | | 人工电源矩阵 |
| 15.5 | 耳机插拔语音中断 | 🧪 | 功能可用性 | 无有线耳机测试条件；需录音/播放期间插拔并确认资源释放、错误提示与可重新开始 | | 外设矩阵 |
| 15.6 | 蓝牙连接/断开语音中断 | 🧪 | 功能可用性 | 无蓝牙音频设备测试条件；需 SCO/A2DP 连接切换矩阵，确认录音不会假停留在 Recording | | 外设矩阵 |
| 15.7 | 锁屏后台采集继续 | 🖐 | 采集完整性 | 需锁屏后从白名单 App 收消息、拨打电话并等待提醒，核对通知监听与 CallLog 对账；涉及真实系统事件 | | 人工锁屏矩阵 |
| 15.8 | 解锁 UI 正常恢复 | 🖐 | 体验 | 与 15.7 连续执行，解锁后核对当前 Tab、草稿、录音状态与新采集记录 | | 人工锁屏矩阵 |
| 15.9 | 分屏 UI 变形 | 🖐 | 体验 | SM-W7023 需人工逐页进入分屏最小宽度，检查日历/关系图/CRM/问问无裁切与不可达按钮 | | 人工窗口矩阵 |
| 15.10 | 弹出窗口 UI 变形 | 🖐 | 体验 | 三星弹出视图尺寸由系统手势控制，需逐页缩到最小窗口并核对滚动/弹窗/键盘避让 | | 人工窗口矩阵 |
| 15.11 | 系统字体繁体 UI 变形 | 🖐 | 体验 | 需切换系统繁体与最大字体/显示缩放后逐页截图比对；自动测试仅覆盖当前简体环境 | | 人工字体矩阵 |
| 15.12 | 系统语言英文 UI 变形 | 🖐 | 产品边界 | 当前产品主要为硬编码中文且未承诺完整 i18n；英文系统下仍需验证布局不崩溃，但不会验收英文翻译完整性 | | 人工语言矩阵 |
| 15.13 | 系统时间未来日程异常 | 🖐 | 数据正确性 | 改系统时间会影响真实提醒与证书，需专用测试档案跳到未来，核对过期候选、提醒与时区处理 | | 专用时间矩阵 |
| 15.14 | 系统时间过去日程异常 | 🖐 | 数据正确性 | 需专用测试档案回拨时间，核对幂等、租约、撤销窗口及提醒不会重复；当前用户档案不做时钟破坏 | | 专用时间矩阵 |

## 维度 16 · Agent 内核与状态机
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 16.1 | 状态机转换合法性 | ⚪ | — | 当前实现按合法路径原子更新 run/attempt 并追加事件，真机回归未复现错序或丢事件 | 审计提交 | `RoomRuntimeStoreTest.runAndAttemptTerminalTransitionAppendEventAtomically` |
| 16.2 | 非法转换被拒绝 | ⚪ | — | 状态机拒绝非法信号，失败后 run 状态与事件流均不变化 | 审计提交 | `RoomRuntimeStoreTest.persistentKernelRejectsIllegalTransitionWithoutEventOrStateChange` |
| 16.3 | 命令分发正确 | ⚪ | — | START/APPROVE/REJECT/CANCEL/RETRY/RESUME 六类命令均按 CAS revision 持久化；重复命令不重复追加 | 审计提交 | `RuntimeGatewayTest.sixCommandsPersistWithCasAndDuplicateDoesNotAppendAgain` |
| 16.4 | 退避策略正确 | ⚪ | — | 临时失败按 1s/2s 有界退避；首个流事件后不重试；致命错误不重试；取消直接传播 | 审计提交 | `ResilientProviderAdapterTest`（有界退避、流式边界、熔断、取消） |
| 16.5 | 会话租约 fencing 正确 | ⚪ | — | 租约到期可由新 owner 原子接管；旧 owner 的 command/tool 写入被 fencing 拒绝，projection 不允许倒退 | 审计提交 | `RoomRuntimeStoreTest.expiredLeaseCanBeReclaimedAndRejectsStaleWriter` + `fencedLedgersRejectOldWriterAndProjectionCannotMoveBackward` |
| 16.6 | 事件溯源回放正确 | ⚪ | — | 恢复快照包含 run/attempt/projection/单调事件序列；snapshot 后观察能补齐竞态窗口且不重复 | 审计提交 | `RoomRuntimeStoreTest.attemptRunEventAndProjectionArePersistedForRecovery` + `RuntimeGatewayTest.snapshotThenObserveCatchesEventsWrittenBeforeCollectionWithoutDuplicates` |
| 16.7 | 幂等 eventId 去重 | ✅ | 数据一致性 | Provider/Runtime 已校验重放载荷；补齐 Observation 同 eventId 不同载荷的冲突拒绝，防止恢复分叉被静默隐藏 | `b24e824` | `RoomRuntimeStoreTest.observationReplayWithChangedPayloadIsRejectedWithoutSecondEvent` |
| 16.8 | 恢复扫描器完整重放 | ⚪ | — | 只扫描租约已过期且未终止的会话；数据库重开后可携快照重新 claim 并继续状态转换 | 审计提交 | `RoomRuntimeStoreTest.recoveryScannerOnlyReturnsExpiredNonTerminalSessions` + `fileReopenRecoveryHandleCarriesSnapshotAndCanContinueWithClaimedLease` |
| 16.9 | 工具确认门拦截 | ⚪ | — | 未确认或确认载荷不匹配时，日程、审计和工具结果均保持零写入 | 审计提交 | `RoomScheduleToolExecutorTest.unconfirmedOrMismatchedApprovalWritesNothing` |
| 16.10 | 工具执行幂等 | ⚪ | — | 同一幂等键重复执行返回原结果，仅保留一条日程与一条审计；同键不同 digest 明确冲突 | 审计提交 | `RoomScheduleToolExecutorTest.confirmedWriteIsAtomicAndDuplicateReturnsOriginalResult` + `idempotencyKeyWithDifferentCanonicalDigestIsConflictWithoutSecondWrite` |
| 16.11 | attempt 创建前立即取消可终结 | ✅ | 功能不可用 | Start 已进入 ASSEMBLING_CONTEXT、attempt 尚未创建时，Cancel 会转入 CANCEL_REQUESTED；旧终结逻辑强制 activeAttemptId 非空而永久卡住。取消事件现允许无 attempt 落库并原子转为 CANCELLED | 本提交 `fix(16.11)` | `RuntimeInputProcessorTest.immediateCancelBeforeAttemptExistsStillReachesCancelled`（真机红→绿） |
| 16.12 | 空闲命令 runner 不热自旋 | ✅ | 性能/耗电 | 无待处理命令且无租约到期时间时，旧循环向自己的 conflated channel 发送再立即接收，2 秒读取时钟 467 次并反复扫库。现首次主动排空，之后只等待真实工作信号或租约超时 | 本提交 `fix(16.12)` | `RuntimeInputProcessorTest.idleRunnerWaitsForAWorkSignalInsteadOfHotSpinning`（SM-W7023：467 次红→≤8 次绿） |
| 16.13 | 过期审批不会卡在 EXECUTING | ✅ | 功能不可用 | 确认卡超过 24 小时后暂存计划已过期；旧执行入口取不到计划便静默返回，run 永久停在 EXECUTING。现写入固定安全失败码并原子终结为 FAILED_FINAL | 本提交 `fix(16.13)` | `RuntimeInputProcessorTest.approvingAnExpiredPlanFailsTerminallyInsteadOfStayingExecuting`（SM-W7023：EXECUTING 红→FAILED_FINAL 绿） |
| 16.14 | 观察期可继续调用不同的读取工具 | ✅ | 功能不可用 | 旧递归保护在工具写入后才读取 completedTools，新工具因此也被误判成重复；当前 attempt 已结束后又用它终结，run 永久停在 OBSERVING。现与观察流启动前快照比较，新工具进入下一观察 attempt，只有真实重复调用走兜底 | 本提交 `fix(16.14)` | `RuntimeInputProcessorTest.observationCanExecuteADifferentReadToolWithoutLeavingRunStuck`（SM-W7023：OBSERVING 红→SUCCEEDED 绿） |
| 16.15 | 确认后工具超时可安全重试 | ✅ | 功能不可用 | `runSuspendCatching` 会按取消语义直接重抛 `TimeoutCancellationException`，旧代码因此绕过 TIMEOUT 映射并被外层收容成 `RUNTIME_INTERRUPTED/FAILED_FINAL`。现显式区分超时与协作取消：超时落 `TIMEOUT/FAILED_RETRYABLE`、保留原输入、清理旧审批暂存，领域事务不产生半写 | 本提交 `fix(16.15)` | `RuntimeInputProcessorTest.approvedToolTimeoutIsRetryableInsteadOfRuntimeInterrupted`（SM-W7023：异常外逃红→FAILED_RETRYABLE 绿） |
| 16.16 | 模型工具参数错误可受控自纠 | ✅ | 功能不可用 | 工具名有效但参数缺失/非法时，旧引擎直接把 run 终结为 `FAILED_FINAL`，模型看不到错误也无法修正。现原子记录脱敏 `ToolFailed`，只把固定错误码回灌并仅开放原工具重试一次；修正成功继续观察链，连续第二次错误明确终结，不会循环或假报成功 | 本提交 `fix(16.16)` | `RuntimeInputProcessorTest.invalidToolArgumentsAreReturnedToModelForOneCorrection`（SM-W7023：FAILED_FINAL 红→三段 ReAct 成功绿）+ `repeatedInvalidToolArgumentsStopAfterOneCorrection`（两次后有界终结） |

## 维度 17 · 检索与上下文
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 17.1 | 检索空显示"没有找到"不卡住 | ⚪ | — | 联系人/关系/CRM 空结果均由权威结果格式化为明确“没有找到”，不会等待模型补写结果 | 审计提交 | `ProviderExecutionDomainLogicTest`（空搜索权威文案） |
| 17.2 | 检索过多截断不 OOM | ⚪ | — | RRF 强制 `limit <= 100`，各 Room 查询有固定 limit，越界直接拒绝而非无界聚合 | 审计提交 | `RetrievalPipelineTest.rrfRejectsUnboundedLimit` |
| 17.3 | 检索超时降级不卡住 | ⚪ | — | 每条检索 path 有独立 timeout，返回固定 `path:timeout` degradation；取消仍上抛 | 审计提交 | `RetrievalAttemptTest.timeoutHasDistinctFixedReason` + `RuntimeInputProcessorTest.rerankTimeoutCancelsOnlyRerankAndFallsBackToRrf` |
| 17.4 | 检索含敏感信息脱敏 | ⚪ | — | 自动检索候选携带 sensitivity/purpose，LLM 与 rerank 最终出站口统一脱敏，SENSITIVE 候选保持本地 | 审计提交 | `OutboundDataPolicyTest.userAuthoredIdentifiersRemainIntactButAutomaticSensitiveContentIsOmitted` + `ProviderRetrievalRerankerTest.governedRerankRedactsPersonalIdentifiersAndKeepsSensitiveCandidatesLocal` |
| 17.5 | 上下文 token 超限截断 | ✅ | 功能不可用 | PromptAssembler 原先先让检索与历史上下文贪心占满预算，最后追加的本轮用户输入可能被静默省略。现将稳定策略与显式 required 块作为不可丢弃组预留实际 token，剩余预算才分配可选上下文；required 总量超限时明确失败 | 本提交 `fix(P0-3)` | `ContextModuleTest.requiredInputIsNeverOmittedWhenOptionalContextFillsBudget` + `requiredInputOverflowFailsExplicitlyInsteadOfSilentlyDroppingInput` + `ProviderContextAssemblerPresentationTest.currentUserInputIsNeverOmittedWhenSessionContextFillsBudget` |
| 17.6 | 上下文含敏感信息脱敏 | ⚪ | — | 最终 `ModelRequest` 经过 `PolicyEnforcingProviderAdapter`，自动召回手机号/邮箱被掩码或阻断；用户主动输入保持原意 | 审计提交 | `OutboundDataPolicyTest` |
| 17.7 | 上下文含错误信息过滤 | ⚪ | — | 检索/快照异常只进入固定原因码，不带 exception message/SQL/用户正文；不可信工具/模型内容只能成为 DATA 角色，不能伪造 SYSTEM | 审计提交 | `RetrievalAttemptTest` + `GatewayRuntimeUiClientTest.malformedEventPayloadEmitsAFixedDegradationReason` + `ContextModuleTest.delimiterInjectionStaysDataAndCannotCreateSystemMessage` |
| 17.8 | LLM 重排有现实网络预算且失败可降级 | ✅ | 功能不可用 | 默认启用的网络 LLM 重排旧预算仅 200ms，正常请求几乎必然降级。现预算纳入 `ProviderEngineConfig`，默认 2500ms；测试可注入 50ms 验证超时时只取消重排、保留原 RRF/FTS 并让主 run 成功 | 本提交 `fix(17.8)` | `ProviderEngineConfigTest.defaultRerankBudgetAllowsNormalNetworkLatency`（200ms 红→2500ms 绿）+ `RuntimeInputProcessorTest.rerankTimeoutCancelsOnlyRerankAndFallsBackToRrf`（SM-W7023） |
| 17.9 | 中文自然问法可召回相关记忆 | ✅ | 功能不可用 | 旧检索把整句中文视为一个 unicode61 token，且所有词用 AND 连接；“做数据库的是谁”无法召回“张三在知伴科技负责数据库项目”。现保留 FTS 并改为 OR，同时在相同 namespace、当前版本、未过期、无 tombstone 约束下，以受限中文片段补充本地召回 | 本提交 `fix(17.9)` | `MemoryAtomicCommitStoreTest.naturalChineseQuestionRecallsMemoryByMeaningfulSubstring`（SM-W7023：空结果红→正确记忆绿） |
| 17.10 | 中文自然问法可召回联系人 | ✅ | 功能不可用 | 联系人检索旧调用把完整中文问句直接交给 unicode61 FTS，“做数据库的那家客户是谁”无法命中公司、标签或备注。现统一抽取受限中文检索片段，对 FTS 与本地 substring 结果按命中数去重排序；联系人合并源、软删除过滤仍由原查询执行 | 本提交 `fix(17.10)` | `EmbeddingIndexIntegrationTest.naturalChineseQuestionRecallsContactByCompanyAndNote`（SM-W7023：空结果红→正确联系人绿） |

## 维度 18 · 工具执行与确认
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 18.1 | 参数缺失拒绝不崩溃 | ⚪ | — | ToolArgumentParser 与各 binding 做必填字段/引用校验，缺失返回固定安全错误，不进入 DomainWriter | 审计提交 | `ToolArgumentParserTest` + `CrmMutationToolBindingTest` |
| 18.2 | 参数非法拒绝不崩溃 | ✅ | 功能不可用 | 畸形 JSON、未知字段、幻觉 contact/opportunity id 均在审批前 fail-closed；有效工具的参数错误会以固定码回灌并仅允许同工具自纠一次，原始参数和异常正文不进入日志；未知工具仍直接拒绝 | 本提交 `fix(16.16)` | `ToolArgumentParserTest.rejectsUnknownKeysAndMalformedJsonWithSameSafeFailure` + `RuntimeInputProcessorTest.invalidToolCallFailsClosedWithoutSuccess` + `invalidToolArgumentsAreReturnedToModelForOneCorrection` |
| 18.3 | 执行超时显示错误 | ⚪ | — | Provider/感知/重排均有有界 timeout；主执行超时落 `FAILED_RETRYABLE` 与安全失败码，UI reducer 显示可重试状态 | 审计提交 | `RuntimeInputProcessorTest.stalledProviderTimesOutWhileLeaseHeartbeatKeepsSafeFailureWritable` + `AgentProjectionUiMapperTest` |
| 18.4 | 执行失败显示错误不卡住 | ✅ | 功能不可用 | 认证失败、未知工具与网络失败均终结 attempt/run；可纠正的工具参数错误先进入一次有界自纠，连续失败再落固定 `INVALID_TOOL_ARGUMENTS/FAILED_FINAL`，不保留 OBSERVING/EXECUTING 死状态 | 本提交 `fix(16.16)` | `RuntimeInputProcessorTest.providerAuthenticationFailureIsFinalAndPersistsOnlySafeCode` + `invalidToolCallFailsClosedWithoutSuccess` + `repeatedInvalidToolArgumentsStopAfterOneCorrection` |
| 18.5 | 确认后执行失败显示错误 | ⚪ | — | approval 与执行分离持久化，确认后的 DomainWriter 失败不写成功事件/领域数据，run 投影安全失败 | 审计提交 | `RoomScheduleToolExecutorTest.unconfirmedOrMismatchedApprovalWritesNothing` + 原子失败测试 |
| 18.6 | 确认后执行成功显示权威结果 | ⚪ | — | 成功正文由已提交 ToolExecution 的 safeResult/领域记录生成，不允许模型仅凭意图宣称成功 | 审计提交 | `RuntimeInputProcessorTest.workToolCallRequiresApprovalThenCreatesScheduleExactlyOnce` |
| 18.7 | 拒绝后能否再提议 | ⚪ | — | REJECT 终结当前 run 并清 pendingApproval；后续 START 使用新 run/idempotency key，可再次形成独立提议 | 审计提交 | `AgentRuntimeProjectionControllerTest`（reject 命令）+ `RuntimeGatewayTest.sixCommandsPersistWithCasAndDuplicateDoesNotAppendAgain` |
| 18.8 | 执行后 safeResult 权威结果 | ⚪ | — | observation/final answer只消费持久化 `safeResultJson`，重复执行返回原结果，同键不同 digest 冲突 | 审计提交 | `RoomScheduleToolExecutorTest.confirmedWriteIsAtomicAndDuplicateReturnsOriginalResult` + `RuntimeInputProcessorTest.contactSearchAutoExecutesWithoutApprovalAndFeedsFinalAnswer` |
| 18.9 | 执行后 ChangeLog 记录 | ⚪ | — | 可变更工具的领域写与 ChangeLog 在同一 Room transaction；八个 CRM 写工具逐一覆盖 | 审计提交 | `RoomCrmToolExecutorTest.allEightConfirmedToolsWriteAuditAndChangeRecords` + `RoomScheduleToolExecutorTest` |
| 18.10 | 执行后 ToolAudit 记录 | ⚪ | — | 成功/失败工具执行均写 tool ledger/audit；未确认或载荷不匹配保持零审计和零领域写 | 审计提交 | `RoomScheduleToolExecutorTest` + `RoomCrmToolExecutorTest.allEightConfirmedToolsWriteAuditAndChangeRecords` |
| 18.11 | 目标 App 启动失败后立即重试被误判已打开 | ✅ | 功能不可用 | 去重占位在解析/启动前写入且失败不释放；改为可释放 reservation，失败路径立即回收 | 本提交 `fix(18.11)` | `RecentHandoffLaunchGuardTest.failedLaunchReservationCanBeReleasedForImmediateRetry` |

## 维度 19 · 记忆与个性化
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 19.1 | 记忆保存后 Agent 能读取 | ⚪ | — | 用户确认后的候选原子提交 memory record/FTS/fact projection，下一次检索可召回并进入模型上下文 | 审计提交 | `RuntimeInputProcessorTest.memoryToolRequiresApprovalThenBecomesRetrievableContext` |
| 19.2 | 记忆删除后 Agent 还读取 | ⚪ | — | 删除生成 generation tombstone；recall、FTS 与 canonical 查询均排除 tombstone，晚到 ack 与数据库重开也不能复活 | 审计提交 | `MemoryAtomicCommitStoreTest.tombstoneGenerationBlocksRecallAcrossLateAckAndRestart` |
| 19.3 | 记忆过期后被清理 | ⚪ | — | 待确认候选 TTL 到期硬清正文；长期记忆 180 天无使用转 DORMANT 并退出召回但保留可审计记录 | 审计提交 | `RoomStagedMemoryCandidateStoreTest.directApprovalAfterExpiryFailsAndHardClearsContent` + `MemoryAtomicCommitStoreTest.memoryBecomesDormantAfter180DaysWithoutBeingDeleted` |
| 19.4 | 记忆冲突时合并 | 🧪 | 产品缺口 | 当前只按 canonicalText 阻止完全重复，并未对同 subject/predicate 的语义矛盾自动合并；需先定义“覆盖旧值/并存/让用户裁决”的产品策略，不能让模型擅自覆盖 | | 需产品裁断后补冲突矩阵 |
| 19.5 | 记忆含敏感信息脱敏 | ⚪ | — | SENSITIVE 记忆不进入远程 embedding；自动召回内容在 LLM/RERANK 出站口按 sensitivity/purpose 阻断或脱敏 | 审计提交 | `EmbeddingIndexIntegrationTest.sensitiveFactsAreNeverOfferedToEmbeddingGatewayOrCountedAsPending` + `OutboundDataPolicyTest` |
| 19.6 | 个性化修改后立即生效 | ⚪ | — | Provider 每次组装请求都重新调用 personalization lambda 读取加密 store 与当前 profile StateFlow，不缓存旧提示词 | 审计提交 | `AgentPersonalizationPageTest.selectingPresetStylePersistsAfterSave` + `UserProfileTest` |
| 19.7 | 个性化删除后恢复默认 | ⚪ | — | UserProfile `clear()` 原子清加密偏好并将 StateFlow 置默认；对话预设可保存 BALANCED 恢复默认 prompt | 审计提交 | `UserProfileStoreTest` + `ResponseStyleTest` |
| 19.8 | 对话风格修改后风格变化 | ✅ | 功能不可用 | 从 CUSTOM 切到预设后，旧 `customInstructions` 仍随 user.md 注入，界面与实际回答风格不一致；最终上下文现仅在 CUSTOM 模式注入自定义指令 | 本提交 `fix(19.8)` | `UserProfileTest.presetStyleDoesNotInjectStaleCustomInstructions` |
| 19.9 | 对话风格删除后恢复默认 | ⚪ | — | 保存 BALANCED 后最终上下文使用默认片段；旧 CUSTOM 指令保留供以后切回但已被 19.8 的最终注入边界隔离 | `f0138af` | `UserProfileTest.presetStyleDoesNotInjectStaleCustomInstructions` |

## 维度 20 · 错误处理与恢复（静态扫描）
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 20.1 | suspend 全用 runSuspendCatching | ✅ | 功能不可用 | 启动协程曾用 `runCatching` 包裹配置迁移、健康检查、维护和通话设置读取，会吞取消；统一走传播取消的 `runStartupAction` | 本提交 `fix(20.1)` | `ZhiBanAppCancellationTest.startupActionPropagatesCoroutineCancellation` + `auditCancellationSafety` |
| 20.2 | 资源(Cursor/Stream/WS/Bitmap)finally/use 释放 | ✅ | 数据丢失 | Cursor/Stream/Bitmap/实时语音资源均有 use/finally；会话工件原子写入失败会遗留 `.part`，现失败路径删除临时文件 | 本提交 `fix(20.2)` | `SessionWorkspaceAtomicWriteTest.failedAtomicRenameDeletesPartialArtifact` |
| 20.3 | 异常捕获并记录降级原因 | ✅ | — | 静态扫描确认核心 suspend/IO 韧性路径均使用固定 degradation code，取消传播；本轮已单独修复唯一发现的 embedding 维护静默吞错 | 审计提交 | `auditCancellationSafety` + `RetrievalAttemptTest` + `AgentMaintenanceDegradationTest` + `GatewayRuntimeUiClientTest` |
| 20.3a | Embedding 维护失败被空 catch 静默吞掉 | ✅ | 可调试性 | 本地 FTS 仍可用时保留韧性，但返回固定 `embedding_backfill:failure`；不记录异常详情，取消仍上抛 | 本提交 `fix(20.3a)` | `AgentMaintenanceDegradationTest`（失败原因码 + 取消传播） |
| 20.4 | 空 catch 块存在 | ✅ | — | 全量 Kotlin 正则扫描无字面空 catch；Embedding 维护原“仅注释 catch”已在 20.3a 改为可见降级原因 | 审计提交 | `detekt` + `rg -U 'catch...{\\s*}'` |
| 20.5 | 硬编码密钥存在 | ✅ | — | 仓库扫描无硬编码 key/token/password，凭据走 Keystore vault；根 check 已包含秘密扫描 | 审计提交 | `verifyNoCommittedSecrets` |
| 20.6 | 敏感数据脱敏后发送 | ✅ | — | LLM/重排统一经 PolicyEnforcingProviderAdapter；ASR/MCP/Embedding 经 OutboundExportGate，生产 Embedding 当前为 FTS-only | 审计提交 | `OutboundDataPolicyTest` + `ProviderRetrievalRerankerTest` + ASR/MCP/Embedding gate 测试 |
| 20.7 | 敏感数据写入日志 | ✅ | — | 生产源码无 Log/println/Timber；OkHttp 仅 debug BASIC 且 Authorization 脱敏，release NONE；出站审计只存摘要元数据 | 审计提交 | `NetworkModuleTest` + `SecretRedactor` 相关测试 |
| 20.8 | 敏感数据写未加密存储 | ✅ | — | SQLCipher 保护领域数据，个人资料/提示词/旧称呼使用 Keystore 加密；长期头像文件也已加密，临时附件只落 app cache 且有清理策略 | `a33435a` + `cc4a691` + 本提交 | 20.8a–20.8c 对应设备测试 |
| 20.8a | 自定义系统提示词明文写入 DataStore | ✅ | 数据丢失 | 用户提示词可能含客户/个人信息；改存 EncryptedSharedPreferences，读取时迁移并删除旧明文 | 本提交 `fix(20.8a)` | `PreferencesManagerSecurityTest.customSystemPromptIsNotPersistedAsPlaintext` |
| 20.8d | 待确认计划正文进入长期运行日志 | ✅ | 隐私 | 确认前的消息收件人、正文、日程备注和联系人详情曾被复制到 append-only 事件日志；现改为独立 SQLCipher 短期暂存，日志只留协议字段、摘要与不可读引用，拒绝、取消、执行完成和终态均清除，旧事件保持兼容读取 | 本提交 `fix(20.8d)` | `RoomRuntimeStoreTest.sensitiveApprovalUsesEncryptedStagingAndRejectRemovesIt` + `RuntimeStoreMigrationTest.migrate38To39AddsRunBoundApprovalStaging` + `AgentRuntimeProjectionControllerTest.pending card resolves sensitive fields from encrypted approval staging` |
| 20.8b | 旧版用户称呼明文留在 SharedPreferences | ✅ | 隐私 | AgentPersonalizationStore 改用加密偏好；首次读取迁移旧称呼/风格并清除旧明文 | 本提交 `fix(20.8b)` | `AgentPersonalizationStoreSecurityTest.legacyPreferredNameMigratesWithoutPlaintextResidue` |
| 20.8c | 用户头像长期明文写入 filesDir | ✅ | 隐私 | 改为 Keystore-backed EncryptedFile；旧 `avatar.png` 首次读取后迁移并删除，UI 仅使用解密后的内存字节 | 本提交 `fix(20.8c)` | `UserProfileStoreTest`（静态加密 + 旧头像迁移） |
