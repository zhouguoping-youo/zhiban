# 知伴 Agent 运行时审计整改进度（R1）

> 基线：`bab3ac3`  
> 整改分支：`fix/agent-runtime-round1`  
> 审计日期：2026-08-14  
> 原则：结论以当前代码、自动化测试和真机设备测试为准；审计描述与当前代码不一致时，以复核结果为准。

## 整改清单

| 项目 | 复核结论与处理 | 提交 / 本地动作 | 回归测试 | 真机设备验证 |
|---|---|---|---|---|
| P0-2：SSE 无 `[DONE]` 丢失工具调用 | 成立。仅在干净 EOF 且工具参数 JSON 完整时补发工具调用与唯一终态；半截帧按固定协议错误失败，禁止执行不完整工具调用。 | `907834a` | `completeToolCallIsFlushedWhenStreamEndsAtCleanEofWithoutDone`；`plainContentAtCleanEofEmitsExactlyOneFinal`；`malformedSseChunkReturnsFixedProtocolFailure` | Provider 回归链已在设备测试闸内执行；真实弱网中途断流仍需人工网络条件复验。 |
| P0-2.1：通话备注启动失败泄漏 `MediaRecorder` | 成立。资源构造与启动分离；启动失败、运行异常和取消均释放已拥有资源。 | `1dec47e` | `failedResourceStartReleasesTheConstructedResourceExactlyOnce`；`cancellationDuringResourceStartReleasesAndPropagatesCancellation` | `failedRecorderInitializationReleasesItsOwnedResourceOnAndroid`。 |
| P0-2.2：实时语音释放吞取消 | 成立。资源清理显式透传 `CancellationException`，普通释放失败只记录固定降级码。 | `ca2f98a` | `resource release reports a fixed degradation instead of swallowing a runtime failure`；`resource release always propagates cancellation` | `twentyRealtimeSessionCleanupsReleaseEveryOwnedResourceOnAndroid`。 |
| P0-3：推理档位文案与实际状态不一致 | 成立。默认档位统一为“深入”；附件面板显示“深入思考”，选中状态由真实推理档位驱动。 | `32d4a39` | Compose 交互回归 | `attachmentPickerReflectsTheActualReasoningLevel`。 |
| P0-4：ASR 未配置无直达设置入口 | 成立。转写状态保留安全错误码；未配置或鉴权失败时显示“去设置”，复用现有模型设置导航。 | `61eb0a7` | 转写状态与安全码回归 | `unconfiguredCloudAsrLinksDirectlyToModelSettings`。 |
| P1-5：敏感服务缺少用途说明 | 成立。通知监听、发出消息感知服务和敏感权限补齐准确的用户可见用途说明。 | `53fe9cb` | Manifest 合规断言 | `sensitiveServicesExposeAccurateLabelsAndDescriptions`。正式隐私政策及应用商店申报仍属于发布资料，不由 Manifest 文案替代。 |
| P1-6.1：通话导入与 CRM 建议非原子 | 成立。导入记录与关联 CRM 建议纳入同一 Room 事务，任一写入失败全部回滚。 | `39b3cd0` | 事务成功/失败注入回归 | `failedCrmSuggestionRollsBackTheImportedCall`。 |
| P1-6.2：记忆提交与运行终态非原子 | 成立。候选批准、记忆/索引/事实、工具尝试、事件和运行终态统一纳入同一 Room 事务。 | `89410d0` | 事务成功/失败注入回归 | `memoryCommitRollsBackWhenRuntimeFinalizationFails`。 |
| P1-7：审批执行参数过多 | 审计所称“构造参数 12 个”已过时；当前真实问题是 11 参数的 suspend 回调。改为 `ApprovedToolExecutionRequest`，不改变 ActionPolicy、DomainWriter 和 ChangeLog 边界。 | `1ffbffa` | `constructorKeepsDependenciesBoundedAndUsesARequestObjectForCompletion` | 记忆审批与远程 MCP 审批设备测试通过。 |
| P1-8：本机遗留百度 ASR 凭据 | 成立，仅存在于被 Git 忽略的 `local.properties`。已在本机删除三项，复核计数为 0；不会把本地配置强行纳入版本库。 | 本地安全清理（无 Git 提交） | `local_baidu_key_count=0` | 不适用。 |
| P1-9：Provider 健康缓存陈旧 | “无 TTL/无强制刷新测试”的描述已过时；真实缺口是凭据或模型轮换后旧 profile digest 快照仍残留。配置验证成功后现在会替换旧快照，失败不污染缓存。 | `19261d5` | `verified credential rotation removes the previous profile health snapshot`；既有 TTL、强制刷新和失败恢复测试 | `providerHealthCachePersistsOnlyNonSecretSnapshotAndExpiresAfterOneHour`。 |

## 边界裁定

- 工具流在完整工具参数后的干净 EOF 可以安全收口；半截 JSON、协议错误或普通网络异常必须失败关闭，不能为了“看起来成功”而执行不完整写操作。
- 出站、确认、审计、幂等和回滚边界未被绕过。
- 本轮不把用户密钥、手机号、邮箱、消息正文或异常原文写入代码、测试、文档与日志。
- 审计原文要求“断网后仍创建日程”不安全：断网发生在工具调用完整到达之前时，正确行为是明确失败且会话可继续；只有已完整接收并通过治理链的工具调用才允许执行。

## 最终验收

- `./gradlew check`：通过（最终代码提交 `45ba96b` 前执行）。
- 仓库外一次性验证签名 `:app:assembleRelease`：通过；R8、资源压缩、lint vital 和 APK 签名链均完成，验证 keystore 随后删除且未进入仓库。
- `SM-W7023 connectedDebugAndroidTest`：通过；JUnit XML 记录 487 项、0 失败、1 项受控跳过。跳过项为需要真实公网条件的 `liveAndroidHandshakeAcceptsEveryPinnedProviderChain`。
- JVM：547 项、0 失败（Debug 口径，包含 app 与 agent 子模块）。
- `scripts/measure.sh`：0 个超过 1000 有效行的生产文件；600–1000 行预警由既有 7 个降到 6 个；测试/生产文件比 0.79；硬编码密钥、未加密日程标题和裸网络调用均为 0。当前 21 条非阻断 Detekt 结构警告均在 app 既有代码，Provider 模块为 0，本轮未新增警告。
- 真机安装后验证：问问可提交消息；设备测试卸载了原调试包及其本机凭据，发送后正确显示未配置状态与“去设置”，点击可直达“大模型连接”。云端真实提示词链路未在重装后的设备复跑，原因是不能把用户密钥写进 adb 命令、日志或自动化脚本；该项明确为 `NOT VERIFIED`，不伪造通过结论。
