# 知伴全局视觉交互收口 — 页面清单与验收矩阵

基线：main @ f94a798（2026-08-22 开工核验：HEAD/分支/工作区干净一致）
真机：三星 W24（SM_W7023，Android 折叠屏）
质量尺子 baseline：2a=0（无超1000有效行文件）；2b=7；主TAB collectAsState≤5 全绿；测试比 0.81

## 一、页面清单

### 一级页面（TAB 直达，5 个）
| # | 页面 | 路由 | Composable | 顶栏 | 状态 |
|---|---|---|---|---|---|
| 1 | 日历 | Calendar | CalendarTab.kt:130 | ZhiBanPrimaryTabHeader | ✓ |
| 2 | 关系 | Relation | RelationTab.kt:177 | ZhiBanPrimaryTabHeader | ✓ |
| 3 | 问问（全屏对话） | Home | AgentConversationScreen.kt:82 | AgentTopBar(ZhiBanTopBar) | ✓ |
| 4 | 能力 | Skill | SkillTab.kt:50 | ZhiBanPrimaryTabHeader | ✓ |
| 5 | 我的 | Profile | ProfileTab.kt:105 | ZhiBanPrimaryTabHeader | ✓ |

### 二级页面（17 个）
| # | 页面 | 路由 | Composable | 顶栏 | 状态 |
|---|---|---|---|---|---|
| 6 | 个人 CRM | CrmCapability | CrmCapabilityPage.kt:47 | ZhiBanTopBar | ✓ |
| 7 | 生活助理 | LifeAssistant | LifeAssistantPages.kt:55 | ZhiBanTopBar | ✓ |
| 8 | 一起安排 | EventPlanning | EventPlanningPages.kt:65 | ZhiBanTopBar | ✓ |
| 9 | 智能体设置 | AgentSettings | AgentSettingsPages.kt:85 | AgentHeader | ✓ |
| 10 | 自动整理 | AutoWrites | AutoWritePage.kt:87 | ZhiBanTopBar（随内容滚走⚠️） | 修 |
| 11 | 智能建议 | AgentSuggestions | AgentSuggestionPage.kt:114 | ZhiBanTopBar（随内容滚走⚠️） | 修 |
| 12 | 个人资料 | ProfileEdit | UserProfilePage.kt:217 | AgentHeader | ✓ |
| 13 | 联系人维护 | ContactMaintenance | ContactMaintenancePage.kt:92 | ZhiBanTopBar | ✓ |
| 14 | 隐私与权限 | PrivacySecurity | GeneralSettingsPages.kt:126 | SettingsPageFrame | ✓ |
| 15 | 外观 | Appearance | GeneralSettingsPages.kt:827 | SettingsPageFrame | ✓ |
| 16 | 通知 | NotificationSettings | NotificationSettingsPage.kt:108 | SettingsPageFrame | ✓ |
| 17 | 存储 | StorageSettings | GeneralSettingsPages.kt:531 | SettingsPageFrame | ✓ |
| 18 | 数据管理 | DataSettings | GeneralSettingsPages.kt:634 | SettingsPageFrame | ✓ |
| 19 | 报告问题 | ReportErrorSettings | GeneralSettingsPages.kt:712 | SettingsPageFrame | ✓ |
| 20 | 关于知伴 | AboutZhiBan | GeneralSettingsPages.kt:774 | SettingsPageFrame | ✓ |
| 21 | 问问（带上下文） | AssistantChat | AgentConversationRoute | AgentTopBar | ✓（returnTarget 断点⚠️） |
| 22 | 模型连接 | ModelConfig | ModelConfigPage.kt:84 | ZhiBanTopBar | ✓ |

### 三级及更深页面（13 个）
| # | 页面 | 路由 | Composable | 顶栏 | 状态 |
|---|---|---|---|---|---|
| 23 | 回答偏好 | ConversationStyle | AgentMemoryPage.kt:396 | AgentHeader | ✓ |
| 24 | 记忆 | MemoryConfig | AgentMemoryPage.kt:138 | AgentHeader | ✓ |
| 25 | 工具 | AgentTools | AgentSettingsPages.kt:290 | AgentHeader | ✓ |
| 26 | 技能 | AgentSkills | AgentSkillsPage.kt:76 | AgentHeader | ✓ |
| 27 | 回答反馈 | AgentFeedbackImprovement | AgentSettingsPages.kt:553 | AgentHeader | ✓ |
| 28 | 运行记录 | AgentRunHistory | AgentSettingsPages.kt:619 | AgentHeader | ✓ |
| 29 | 线索池 | CrmLeads | CrmLeadListPage.kt:44 | ZhiBanTopBar | ✓ |
| 30 | 机会列表 | CrmOpportunityList | CrmOpportunityListPage.kt:35 | ZhiBanTopBar | ✓ |
| 31 | 机会看板 | CrmOpportunityBoard | CrmOpportunityBoardPage.kt:39 | ZhiBanTopBar | ✓ |
| 32 | 机会详情 | CrmOpportunityDetail | CrmOpportunityDetailPage.kt:66 | ZhiBanTopBar | ✓ |
| 33 | 生活助理列表 | LifeAssistantList | LifeAssistantPages.kt:77 | ZhiBanTopBar | ✓ |
| 34 | 生活助理详情 | LifeAssistantDetail | LifeAssistantPages.kt:119 | ZhiBanTopBar | ✓ |
| 35 | 安排列表 | EventPlanningList | EventPlanningPages.kt:90 | ZhiBanTopBar | ✓ |
| 36 | 安排详情 | EventPlanningDetail | EventPlanningPages.kt:129 | **无顶栏（返回断点）** | 修 |

### 全屏覆盖层（视觉=二级页，1 个）
| # | 页面 | 实现 | 状态 |
|---|---|---|---|
| 37 | 联系人详情 | ContactDetailDialog（ContactDetailDialogs.kt:162，ZhiBanDialogHost 全屏） | 顶行自写⚠️→ZhiBanTopBar |

### Dialog / BottomSheet 总清单（27 个）
Dialog 系 24：ContactDetailDialog（全屏）、ContactMergeReviewDialog、ContactIdentityEditorDialog、ContactFactEditorDialog、ContactImportDialog、ContactEditorDialog、ContactCompletionCard（全屏）、补全不可用提示、RelationshipEditorDialog、RelationshipEventEditorDialog、RelationshipEventDetailDialog、RelationshipEvidenceDialog、删除关系确认、CallNoteDialog、语音上云授权、NotificationCandidateDialog、通讯录回写预览、导入权限intro、权限被拒说明、删除联系人确认、标记本人确认、OwnerEmploymentEditorDialog、ScheduleEditorDialog、ScheduleCompletionDialog、取消日程确认、SystemCalendarImportDialog、日历权限说明、通知权限提示、线索转正确认、CrmConvertLeadDialog、CrmStageDialog、CreateEventPlanDialog、EventContactPicker、ResponseStatusDialog、删除安排确认
BottomSheet 2：ContactEnrichmentReviewSheet（资料核实）、ForceNodeDetailSheet（图节点详情，当前禁用）
DropdownMenu 1：CategoryDropdownMenu（关系分类）

### 空/错/加载/成功状态
- 共享：ZhiBanEmptyState（无操作按钮版）、SceneCapabilityEmptyState（三场景统一空态）、MainTabEmptyPage（一级页整页空）
- 页面自写：RelationEmpty、EmptyDay（日历）、CrmDetailPageState、各加载态 CircularProgressIndicator（无统一组件）
- 成功回执：Snackbar（各页手写）、LifeResultMessage、EventPlanningMessage、AgentOperationResultCard
- 错误：ToolResultCard（问问）、各表单内联错误

## 二、确认的真实交互断点（功能 Bug，单独登记）
1. **EventPlanningDetailPage 无顶栏**（EventPlanningPages.kt:129-169）——无返回按钮，只能手势返回 → 包 G 修
2. **AssistantChat returnTarget="RELATION" 不生效**（NavGraph.kt:228-237 只特判 "BACK"）——联系人详情"问问"执行完无法回到联系人 → 包 E 修
3. **CallNoteDialog 云 ASR 不可用时弹 Google 系统语音识别**（RelationInboxDialogs.kt:261-267）——违反 §七.5 → 包 F 修
4. **CRM 场景无结果回执**（生活助理/一起安排有回执条，CRM 没有）→ 包 G 修

## 三、验收矩阵（逐包填写）

| 包 | 页面/状态项 | 问题 | 改动文件 | 截图 | 真机结果 |
|---|---|---|---|---|---|
| A | 全局组件 | 缺统一按钮/角标/选项Sheet/紧凑空态 | （待填） | | |
