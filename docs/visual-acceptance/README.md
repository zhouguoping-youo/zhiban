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
| A | 全局组件 | 缺统一按钮/角标/选项Sheet/紧凑空态；SegmentedControl 44dp | ZhiBanControls.kt(新)、ZhiBanVisual.kt | after/A-01~05 | ✅ 5 L1 无回归（b7a8338） |
| B | 关系首页 | 维护常驻大卡/行第二行露手机号/空态大白卡 | RelationshipAttention.kt、RelationContactRows.kt、RelationTab.kt | after/B-02 | ✅ 角标27、无手机号、关注区按需出现（653d958） |
| C | 联系人详情 | 手写顶行/空态正文号/私有52dp主按钮 | ContactDetailDialogs.kt | after/C-08（对 before/08） | ✅ 统一TopBar+小字空态+共享按钮（229a6b0）；灰带为存量系统窗背景非回归 |
| D | 关系图 | 介绍链伪造直达边/禁用sheet死代码/空态无操作 | ForceRelationshipGraph.kt、RelationshipGraphSection.kt、RelationGraphInference.kt | after/D-09 | ✅ 空态一状态一主操作；介绍链=路径（3a7b33e）；真机 0 边为真实空态 |
| E | 问问 | 确认条笼统/长文无折叠/表格扭曲/返回断点 | AgentConversationScreen.kt、AgentResponseFormatting.kt、NavGraph.kt(e95c6eb 单独修) | after/E-02、E-03-long-* | ✅ 长文折叠+表格真机验证；returnTarget 真机验证；感知条无候选未截图（e173b16） |
| F | 日历 | 点行进编辑器/行内多入口/空态大白卡/完成无撤销/来源缺失 | CalendarTab.kt、ScheduleCompletionDialog.kt、AgentEntities.kt、AgentDaos.kt | after/F-01~F-10 | ✅ 真机全生命周期：建→行→统一状态框→编辑→完成→撤销→查看结果→取消（13c0e8e）；「知伴记录」来源标注仅代码级验证（真机无工具链日程）；断点③系统语音弹窗已删（5579c6d，启动器代码移除） |
| G | 三能力场景 | 依据/来源占详情首屏/按钮体系不一/CRM 无回执/安排详情无顶栏/参与人行内多按钮 | CrmIntelligenceComponents.kt、CrmCapabilityPage.kt、CrmLeadListPage.kt、CrmOpportunityDetailPage.kt、CrmUiModels.kt、LifeAssistantPages.kt、EventPlanningPages.kt、EventPlanningDialogs.kt、SceneCapabilityComponents.kt、ZhiBanControls.kt | after/G-01~G-13 | ✅ 能力首页2列卡/三场景紧凑空态/CRM工作台+详情回执/查看依据收起展开/阶段Sheet勾选/参与人Sheet(状态+移除)/动作栈主-次-危险全真机验证（46db193+6777aee）；断点①安排详情TopBar（8c3a11d）与断点④CRM回执（9fdc8e0）真机确认；生活助理详情「查看依据」真机无真实数据未验（与CRM同款组件已验+JVM测试覆盖）；测试计划已删除清理 |
| H | 我的/设置 | L1 入口杂（智能体设置直出+存储数据两行）/隐私页7段无结构/行话(大模型请求可出云等)/TopBar 随内容滚走/20dp 硬编码/密钥眼睛 44dp/死代码 | ProfileTab.kt、NavGraph.kt、NavGraphRoutes.kt、GeneralSettingsPages.kt、AgentSettingsPages.kt、ModelConfigPage.kt、AutoWritePage.kt、AgentSuggestionPage.kt、AgentProjectionUiMapper.kt、AgentConversationRoute.kt、AgentConversationMessageComponents.kt | after/H-01~H-08 | ✅ L1 新IA真机核验（模型连接状态/手机权限/存储和数据/高级组）；隐私三段+行话清零真机核验；模型连接直达页/存储二级进入/智能体设置/运行记录真机核验（3ef99f6）；自动整理与智能建议真机均为真实空态，TopBar 常驻为结构改动（与已验页面同构）；E2E 断言同步未回退数量 |
| I | 缩放/键盘/形态/可达性 | —（验证包，无预登记缺陷） | 无代码改动 | after/I-01~I-16 | ✅ 字体 100/130/150/200% 四档三张 L1 无裁切（TABBAR 纯图标不变）；搜狗全高键盘下问问输入条+发送+最新消息可见、日程编辑器字段+保存可见；横屏自动改左侧导航轨、空态主操作滚动可达；可达性用语义树审计（可点父节点的命名子节点齐全：回到今天/选择日期/我的等），TalkBack 本体未在日用机开启（侵入式）；分屏与折叠态外屏需实体操作，adb 无法驱动，登记未验证。字体已还原 0.9、输入法已还原 ADB、误触的号码开关已还原开启 |
