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
| 3.5 | ChangeLog inverse payload 可逆 | ⚪ | — | 未复现：可见自动写入口拒绝空/`{}` inverse，领域写、ChangeLog、receipt 同事务；各首批自动写都有最终撤销回归 | — | `AutoWriteCorrectionTest.visibleAutoWriteWithoutInverseIsRejectedAtomically`、`AutoWriteAtomicityTest.injectedFailureRollsBackDomainChangeAuditAndReceiptTogether` |
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
| 5.1 | 时间解析错误("明天下午3点") | ⬜ | | | | |
| 5.2 | 改期后旧提醒仍触发 | ⬜ | | | | |
| 5.3 | 系统日历和本地日程冲突误报 | ⬜ | | | | |
| 5.4 | 重复事件最后一天取消误报冲突 | ⬜ | | | | |
| 5.5 | 标题特殊字符(emoji/换行)崩溃 | ⬜ | | | | |
| 5.6 | 提醒到期 App 被杀提醒丢失 | ⬜ | | | | |
| 5.7 | 删除日程后关联 CRM 下一步清理 | ⬜ | | | | |
| 5.8 | 系统日历权限关闭本地日程正常 | ⬜ | | | | |
| 5.9 | 跨天日程显示正确 | ✅ | 功能不可用 | 日历页、列表和检索只用 startAt 是否落在查询日判断，23:30 开始并跨午夜的日程在次日完全消失。三条查询现统一为时间区间重叠语义，结束恰在日界线的事件不会误入次日 | 本提交 `fix(5.9)` | `ScheduleObserveRangeReproTest.crossMidnightScheduleIsVisibleOnEveryOverlappedDay` + 当日插入/Flow 更新回归 |
| 5.10 | 提醒提前时间按设置触发 | ⬜ | | | | |
| 5.11 | 创建冲突检测误报 | ⬜ | | | | |
| 5.12 | 时间已过(昨天)是否警告 | ⬜ | | | | |
| 5.13 | 系统日历同步重复事件实例去重 | ⬜ | | | | |

## 维度 7 · 设置链路
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 7.1 | 个人资料保存返回数据丢失 | ⬜ | | | | |
| 7.2 | 智能体档位改后行为真变化 | ⬜ | | | | |
| 7.3 | 隐私权限关闭后数据仍发送 | ⬜ | | | | |
| 7.4 | 外观切换主题立即生效 | ⬜ | | | | |
| 7.5 | 记忆开关关后 Agent 仍读取 | ⬜ | | | | |
| 7.6 | 对话风格切换回答风格变化 | ⬜ | | | | |
| 7.7 | 工具开关关后仍调用该工具 | ⬜ | | | | |
| 7.8 | 技能开关关后仍触发该技能 | ⬜ | | | | |
| 7.9 | 自动写开关关后仍自动写入 | ⬜ | | | | |
| 7.10 | 通知分类开关关后仍发送 | ⬜ | | | | |
| 7.11 | 头像选择后立即显示 | ⬜ | | | | |
| 7.12 | 多平台账号添加后保存 | ⬜ | | | | |
| 7.13 | 职业多选后保存 | ⬜ | | | | |
| 7.14 | 给知伴指令注入 prompt | ⬜ | | | | |
| 7.15 | API Key 修改后立即生效 | ⬜ | | | | |
| 7.16 | API Key 删除后显示未配置 | ⬜ | | | | |

## 维度 8 · 导航与路由
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 8.1 | 深链跳转返回正确页面 | ⬜ | | | | |
| 8.2 | 底部 TAB 切换返回原位置 | ⬜ | | | | |
| 8.3 | 页面嵌套过深返回键混乱 | ⬜ | | | | |
| 8.4 | 路由参数缺失崩溃 | ⬜ | | | | |
| 8.5 | 旋转后导航状态丢失 | ⬜ | | | | |
| 8.6 | 通知点击进入正确页面 | ⬜ | | | | |
| 8.7 | 分享 Intent 进入正确页面 | ⬜ | | | | |
| 8.8 | CRM 建议点击进入正确页面 | ⬜ | | | | |

## 维度 9 · 数据与持久化
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 9.1 | 数据库迁移失败崩溃 | 🧪 | 数据丢失风险 | 1→34 每条迁移均有 MigrationTestHelper 覆盖且最新全链迁移通过；真正失败时 Room 会拒绝打开以避免破坏数据。恢复界面需先定义“保留损坏库/导出/重置”产品策略，不能用 destructive fallback 冒充修复 | — | `FactIndexMigrationTest` 全链 + 各版本专项迁移测试；需专用损坏数据库安装包验收恢复 UX |
| 9.2 | SQLCipher 密钥丢失打不开 | 🧪 | 数据丢失风险 | Keystore 包裹密钥丢失时当前明确 fail-closed，数据库不会被静默重建；密钥不可恢复，需产品裁断灾难恢复/导出策略后才能做 UI 闭环，禁止在用户真机删除密钥试验 | — | `AgentDatabaseEncryptionTest` 覆盖稳定密钥与重开；密钥丢失需隔离测试包 |
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
| 9.13 | 备份恢复后数据完整 | ⚪ | — | 产品明确禁用 Android 备份：Manifest `allowBackup=false`，cloud backup 与 device transfer 均排除 database；加密库和设备 Keystore 不跨设备恢复，因此该场景不受支持，也不会产生“恢复了库却没有密钥”的半恢复 | 审计提交 | Manifest + `backup_rules.xml` + `data_extraction_rules.xml` 代码审查 |
| 9.14 | 清除后 WAL 文件清理 | 🖐 | 数据丢失（测试风险） | 系统“清除数据”应移除整个应用数据目录，包含 db/wal/shm、SharedPreferences、缓存和 Keystore 归属；当前真机含用户数据，不执行破坏性验证。一次性安装清除后检查沙箱目录与重新启动建库 | — | SM-W7023 人工步骤（仅一次性测试数据） |
| 9.15 | 真机测试清空生产用户资料 | ✅ | 数据丢失 | 两个设备测试直接复用 `user_profile_secure` 并调用 `clear()`；为 UserProfileStore 增加测试命名空间，测试结束只删除隔离数据 | 本提交 `fix(9.15)` | `UserProfileStoreTest` + `UserProfilePageTest` |
| 9.16 | 覆盖安装后数据库版本/密钥兼容 | 🖐 | 数据丢失（测试风险） | 普通设备回归只证明全新测试安装；覆盖升级必须先在一次性构建写入唯一联系人/日程并记录 DB 版本，再 `adb install -r` 新 APK，确认数据、wrapped key 与 34 版迁移均保留。不得用当前用户资料做破坏性升级演练 | — | SM-W7023 人工升级矩阵 |
| 9.17 | 清除数据重装后是否重建 | 🖐 | 数据丢失（测试风险） | Android 清除数据/卸载会删除应用沙箱；需一次性测试安装执行清除→启动→确认创建全新加密 34 版库、旧唯一数据不存在。当前用户设备不自动执行 | — | SM-W7023 人工步骤（一次性数据） |
| 9.18 | 数据库文件权限被改后是否崩溃 | 🧪 | 功能不可用 | 非 root 生产设备不能修改应用私有数据库权限；需 root 模拟器把 DB 设为不可读并验证 fail-closed/恢复界面。SM-W7023 非 root，无法安全复现 | — | root 模拟器专用场景 |

## 维度 10 · 网络与 Provider
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 10.1 | StepFun 超时卡住 | ⬜ | | | | |
| 10.2 | 返回错误码显示错误不崩溃 | ⬜ | | | | |
| 10.3 | 返回非法 JSON 崩溃 | ⬜ | | | | |
| 10.4 | 返回空响应显示空不崩溃 | ⬜ | | | | |
| 10.5 | 网络断开离线提示 | ⬜ | | | | |
| 10.6 | 网络恢复自动重试 | ⬜ | | | | |
| 10.7 | API Key 过期明确提示 | ⬜ | | | | |
| 10.8 | 证书锁定失败明确提示 | ⬜ | | | | |
| 10.9 | 流式中断显示已接收部分 | ⬜ | | | | |
| 10.10 | 流式重复去重 | ⬜ | | | | |
| 10.11 | 响应>100KB OOM | ⬜ | | | | |
| 10.12 | 响应特殊字符崩溃 | ⬜ | | | | |

## 维度 11 · 权限与隐私
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 11.1 | 通知监听撤销后仍采集 | ⬜ | | | | |
| 11.2 | 无障碍撤销后崩溃 | ⬜ | | | | |
| 11.3 | 通话记录撤销后崩溃 | ⬜ | | | | |
| 11.4 | 录音撤销后崩溃 | ⬜ | | | | |
| 11.5 | 通讯录撤销后崩溃 | ⬜ | | | | |
| 11.6 | 日历撤销后崩溃 | ⬜ | | | | |
| 11.7 | 敏感数据脱敏后发送 | ⬜ | | | | |
| 11.8 | 敏感数据写入日志 | ⬜ | | | | |
| 11.9 | 敏感数据写未加密存储 | ⬜ | | | | |
| 11.10 | 无障碍截图 Bitmap 释放 | ⬜ | | | | |
| 11.11 | 通知含验证码过滤 | ⬜ | | | | |
| 11.12 | 通知含银行卡号过滤 | ⬜ | | | | |
| 11.13 | 通话含敏感号码(10086)过滤 | ⬜ | | | | |
| 11.14 | 语音上传前明确授权 | ⬜ | | | | |
| 11.15 | 联系人上传前明确授权 | ⬜ | | | | |
| 11.16 | 消息内容上传前明确授权 | ⬜ | | | | |

## 维度 12 · 性能与内存
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 12.1 | 1000+联系人搜索卡顿 | ⬜ | | | | |
| 12.2 | 500+日程日历卡顿 | ⬜ | | | | |
| 12.3 | 100+商机看板卡顿 | ⬜ | | | | |
| 12.4 | 100+待确认候选箱卡顿 | ⬜ | | | | |
| 12.5 | 流式回复卡顿 | ⬜ | | | | |
| 12.6 | 500+图谱节点卡顿 | ⬜ | | | | |
| 12.7 | 图片加载过多 OOM | ⬜ | | | | |
| 12.8 | Activity/Fragment 泄漏 | ⬜ | | | | |
| 12.9 | ViewModel 泄漏 | ⬜ | | | | |
| 12.10 | 协程未取消泄漏 | ⬜ | | | | |
| 12.11 | 监听器未注销泄漏 | ⬜ | | | | |
| 12.12 | 广播接收器未注销泄漏 | ⬜ | | | | |
| 12.13 | 服务未停止泄漏 | ⬜ | | | | |

## 维度 13 · 兼容性与边界
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 13.1 | 屏幕密度 420/560dpi UI 变形 | ⬜ | | | | |
| 13.2 | 手机/平板 UI 变形 | ⬜ | | | | |
| 13.3 | 深色模式颜色正确 | ⬜ | | | | |
| 13.4 | 字体放大2倍 UI 溢出 | ⬜ | | | | |
| 13.5 | 横屏 UI 变形 | ⬜ | | | | |
| 13.6 | Android 26/30/33/35 功能一致 | ⬜ | | | | |
| 13.7 | 2GB 低端设备卡顿 | ⬜ | | | | |
| 13.8 | 中英文混合输入处理 | ⬜ | | | | |
| 13.9 | 特殊字符输入崩溃 | ⬜ | | | | |
| 13.10 | 超长文本(>1000字)卡顿 | ⬜ | | | | |
| 13.11 | 快速连续点击重复触发 | ⬜ | | | | |
| 13.12 | 快速滑动卡顿 | ⬜ | | | | |

## 维度 14 · 状态与生命周期
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 14.1 | 杀后重启会话状态恢复 | ⬜ | | | | |
| 14.2 | 杀后重启草稿保留 | ⬜ | | | | |
| 14.3 | 杀后重启未发消息保留 | ⬜ | | | | |
| 14.4 | 杀后重启未完成确认保留 | ⬜ | | | | |
| 14.5 | 杀后重启自动写回执保留 | ⬜ | | | | |
| 14.6 | 覆盖安装 API Key 丢失 | ⏭ | | 上一轮 #22 已修+真机验证 | `c685d9b` | 真机 |
| 14.7 | 覆盖安装用户数据保留 | ⬜ | | | | |
| 14.8 | 覆盖安装设置保留 | ⬜ | | | | |
| 14.9 | 清除数据后真清空 | ⬜ | | | | |
| 14.10 | 清除后残留文件 | ⬜ | | | | |
| 14.11 | 清除后残留数据库 | ⬜ | | | | |
| 14.12 | 清除后残留缓存 | ⬜ | | | | |
| 14.13 | 卸载重装数据清空 | ⬜ | | | | |

## 维度 15 · 特殊场景（多需人工/环境）
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 15.1 | 双卡通话识别 SIM 卡 | 🧪 | | 需双卡设备 | | |
| 15.2 | 飞行模式降级不崩溃 | 🖐 | | | | |
| 15.3 | 低电量后台任务受限 | 🖐 | | | | |
| 15.4 | 充电/不充电行为一致 | 🖐 | | | | |
| 15.5 | 耳机插拔语音中断 | 🧪 | | 需耳机 | | |
| 15.6 | 蓝牙连接/断开语音中断 | 🧪 | | 需蓝牙设备 | | |
| 15.7 | 锁屏后台采集继续 | 🖐 | | | | |
| 15.8 | 解锁 UI 正常恢复 | 🖐 | | | | |
| 15.9 | 分屏 UI 变形 | 🖐 | | | | |
| 15.10 | 弹出窗口 UI 变形 | 🖐 | | | | |
| 15.11 | 系统字体繁体 UI 变形 | 🖐 | | | | |
| 15.12 | 系统语言英文 UI 变形 | 🖐 | | | | |
| 15.13 | 系统时间未来日程异常 | 🖐 | | | | |
| 15.14 | 系统时间过去日程异常 | 🖐 | | | | |

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

## 维度 17 · 检索与上下文
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 17.1 | 检索空显示"没有找到"不卡住 | ⬜ | | | | |
| 17.2 | 检索过多截断不 OOM | ⬜ | | | | |
| 17.3 | 检索超时降级不卡住 | ⬜ | | | | |
| 17.4 | 检索含敏感信息脱敏 | ⬜ | | | | |
| 17.5 | 上下文 token 超限截断 | ⬜ | | | | |
| 17.6 | 上下文含敏感信息脱敏 | ⬜ | | | | |
| 17.7 | 上下文含错误信息过滤 | ⬜ | | | | |
| 17.8 | LLM 重排失败降级 FTS | ⬜ | | | | |

## 维度 18 · 工具执行与确认
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 18.1 | 参数缺失拒绝不崩溃 | ⬜ | | | | |
| 18.2 | 参数非法拒绝不崩溃 | ⬜ | | | | |
| 18.3 | 执行超时显示错误 | ⬜ | | | | |
| 18.4 | 执行失败显示错误不卡住 | ⬜ | | | | |
| 18.5 | 确认后执行失败显示错误 | ⬜ | | | | |
| 18.6 | 确认后执行成功显示权威结果 | ⬜ | | | | |
| 18.7 | 拒绝后能否再提议 | ⬜ | | | | |
| 18.8 | 执行后 safeResult 权威结果 | ⬜ | | | | |
| 18.9 | 执行后 ChangeLog 记录 | ⬜ | | | | |
| 18.10 | 执行后 ToolAudit 记录 | ⬜ | | | | |
| 18.11 | 目标 App 启动失败后立即重试被误判已打开 | ✅ | 功能不可用 | 去重占位在解析/启动前写入且失败不释放；改为可释放 reservation，失败路径立即回收 | 本提交 `fix(18.11)` | `RecentHandoffLaunchGuardTest.failedLaunchReservationCanBeReleasedForImmediateRetry` |

## 维度 19 · 记忆与个性化
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 19.1 | 记忆保存后 Agent 能读取 | ⬜ | | | | |
| 19.2 | 记忆删除后 Agent 还读取 | ⬜ | | | | |
| 19.3 | 记忆过期后被清理 | ⬜ | | | | |
| 19.4 | 记忆冲突时合并 | ⬜ | | | | |
| 19.5 | 记忆含敏感信息脱敏 | ⬜ | | | | |
| 19.6 | 个性化修改后立即生效 | ⬜ | | | | |
| 19.7 | 个性化删除后恢复默认 | ⬜ | | | | |
| 19.8 | 对话风格修改后风格变化 | ⬜ | | | | |
| 19.9 | 对话风格删除后恢复默认 | ⬜ | | | | |

## 维度 20 · 错误处理与恢复（静态扫描）
| ID | 检查点 | 状态 | 严重度 | 根因/说明 | commit | 测试 |
|---|---|---|---|---|---|---|
| 20.1 | suspend 全用 runSuspendCatching | ✅ | 功能不可用 | 启动协程曾用 `runCatching` 包裹配置迁移、健康检查、维护和通话设置读取，会吞取消；统一走传播取消的 `runStartupAction` | 本提交 `fix(20.1)` | `ZhiBanAppCancellationTest.startupActionPropagatesCoroutineCancellation` + `auditCancellationSafety` |
| 20.2 | 资源(Cursor/Stream/WS/Bitmap)finally/use 释放 | ✅ | 数据丢失 | Cursor/Stream/Bitmap/实时语音资源均有 use/finally；会话工件原子写入失败会遗留 `.part`，现失败路径删除临时文件 | 本提交 `fix(20.2)` | `SessionWorkspaceAtomicWriteTest.failedAtomicRenameDeletesPartialArtifact` |
| 20.3 | 异常捕获并记录降级原因 | ⬜ | | | | |
| 20.3a | Embedding 维护失败被空 catch 静默吞掉 | ✅ | 可调试性 | 本地 FTS 仍可用时保留韧性，但返回固定 `embedding_backfill:failure`；不记录异常详情，取消仍上抛 | 本提交 `fix(20.3a)` | `AgentMaintenanceDegradationTest`（失败原因码 + 取消传播） |
| 20.4 | 空 catch 块存在 | ✅ | — | 全量 Kotlin 正则扫描无字面空 catch；Embedding 维护原“仅注释 catch”已在 20.3a 改为可见降级原因 | 审计提交 | `detekt` + `rg -U 'catch...{\\s*}'` |
| 20.5 | 硬编码密钥存在 | ✅ | — | 仓库扫描无硬编码 key/token/password，凭据走 Keystore vault；根 check 已包含秘密扫描 | 审计提交 | `verifyNoCommittedSecrets` |
| 20.6 | 敏感数据脱敏后发送 | ✅ | — | LLM/重排统一经 PolicyEnforcingProviderAdapter；ASR/MCP/Embedding 经 OutboundExportGate，生产 Embedding 当前为 FTS-only | 审计提交 | `OutboundDataPolicyTest` + `ProviderRetrievalRerankerTest` + ASR/MCP/Embedding gate 测试 |
| 20.7 | 敏感数据写入日志 | ✅ | — | 生产源码无 Log/println/Timber；OkHttp 仅 debug BASIC 且 Authorization 脱敏，release NONE；出站审计只存摘要元数据 | 审计提交 | `NetworkModuleTest` + `SecretRedactor` 相关测试 |
| 20.8 | 敏感数据写未加密存储 | ✅ | — | SQLCipher 保护领域数据，个人资料/提示词/旧称呼使用 Keystore 加密；长期头像文件也已加密，临时附件只落 app cache 且有清理策略 | `a33435a` + `cc4a691` + 本提交 | 20.8a–20.8c 对应设备测试 |
| 20.8a | 自定义系统提示词明文写入 DataStore | ✅ | 数据丢失 | 用户提示词可能含客户/个人信息；改存 EncryptedSharedPreferences，读取时迁移并删除旧明文 | 本提交 `fix(20.8a)` | `PreferencesManagerSecurityTest.customSystemPromptIsNotPersistedAsPlaintext` |
| 20.8b | 旧版用户称呼明文留在 SharedPreferences | ✅ | 隐私 | AgentPersonalizationStore 改用加密偏好；首次读取迁移旧称呼/风格并清除旧明文 | 本提交 `fix(20.8b)` | `AgentPersonalizationStoreSecurityTest.legacyPreferredNameMigratesWithoutPlaintextResidue` |
| 20.8c | 用户头像长期明文写入 filesDir | ✅ | 隐私 | 改为 Keystore-backed EncryptedFile；旧 `avatar.png` 首次读取后迁移并删除，UI 仅使用解密后的内存字节 | 本提交 `fix(20.8c)` | `UserProfileStoreTest`（静态加密 + 旧头像迁移） |
