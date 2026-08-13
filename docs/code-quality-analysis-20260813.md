# 知伴代码质量复核与整改报告

> 日期：2026-08-13  
> 原始复核基线：`5e10577`  
> 整改实现 HEAD：`02be61a`  
> 范围：`app/src/main/java/**/*.kt` 与 `agent/*/src/main/kotlin/**/*.kt`，不含测试和生成产物

## 1. 结论

原报告对“代码责任集中”的方向判断成立，但部分数字和方法级判断已过时，不能直接当作当前代码事实。本轮已按最终调用点重新核验并完成高价值结构整改：

- 统一了文件体积、构造参数和安全扫描的计量口径，根 `check` 与 `scripts/measure.sh` 不再互相矛盾。
- `AgentDataRepository` 和 `RelationViewModel` 的依赖收口为领域参数对象，生产类构造参数超过 8 的数量为 0。
- 关系一级页改为聚合页面快照，联系人详情状态只在打开详情时订阅；主页 `collectAsState` 由原核验的 25 个收敛为 4 个。
- `ProviderExecutionEngine` 的感知、记忆上下文、运行初始化和 ReAct 收尾阶段已拆出明确的小步骤，有效行数降到 998，越过 1000 红线的生产文件为 0。
- `AgentDatabase` 从 2056 物理行降到约 200 行，36 个历史迁移按版本段物理分离，对外的 `AgentDatabase.MIGRATION_*` 稳定入口保持不变。
- 存储页的数据库、附件、缓存递归统计和缓存删除已移出 Compose 组合与主线程点击回调。

当前已经不存在发布阻断级的代码质量问题。仍有 20 条非阻断 Detekt 结构警告和 7 个 600–1000 有效行的预警文件，它们是后续可维护性债务，不应被表述为“零风险”。

## 2. 统一测量口径

唯一可复现的手工测量入口是：

```bash
bash scripts/measure.sh
```

严格阻断入口是：

```bash
./gradlew check
```

文件体积按 `gradle/quality.gradle.kts` 定义的“有效行”计算：600–1000 为预警，超过 1000 为错误。物理行只用于衡量阅读成本，不再与阻断口径混用。

### 当前实测

| 指标 | 结果 |
| --- | ---: |
| 生产 Kotlin 文件 | 302 |
| 生产物理行 | 66,827 |
| 生产有效行 | 40,470 |
| 超过 1000 有效行 | 0 |
| 600–1000 有效行 | 7 |
| 构造参数超过 8 | 0 |
| 20 行跨文件精确克隆 | 0 |
| 可见的 suspend 取消语义风险 | 0 |
| 硬编码密钥 | 0 |
| 裸网络调用 | 0 |
| `remember` 内同步 IO | 0 |
| 主 TAB `collectAsState` | 关系 4 / 日历 4 / 能力 0 / 我的 4 |
| JVM/设备测试源文件 | 116 / 116 |
| Detekt 非阻断警告 | 20 |

## 3. 对原报告的重要更正

### 3.1 规模和超长文件

原报告的“283 个文件 / 6.1 万有效行 / 6 个超过 1000 有效行”不是当前统一尺子的结果。当前为 302 个生产文件、66,827 物理行、40,470 有效行，超过 1000 有效行的文件为 0。

### 3.2 `ProviderExecutionEngine`

原报告所述的“20 个构造参数”和“443/609 行的 `execute`/`launchApprovedTool`”在 `5e10577` 已无法复现：当时构造参数已是 7，两个入口也已被过往整改拆分。本轮没有按过时描述重做，而是处理了当前真正的复杂点：ReAct 循环、运行准备、观察上下文和超时分支。

### 3.3 `RelationTab`

“顶层 25 个状态订阅导致广泛重组”在本轮开始时属实。修复后主页只消费聚合快照和必要的局部状态，联系人详情的数据放到 `RelationDetailOverlay` 内按需订阅。这是结构性降低重组面，但宏基准和重组计数仍需在真实大通讯录上持续补充。

### 3.4 `AgentDatabase`

原文将它视为低收益的声明型巨型文件，方向基本正确；但迁移文本对阅读、评审和冲突的影响已足以值得物理分离。本轮只做语义不变的搬迁和重复 FTS DDL 复用，并由数据库迁移设备测试验收。

### 3.5 “无死代码 / 无重复 / 无取消风险”的边界

- 精确克隆扫描为 0，仅证明没有跨文件的 20 行规范化文本复制，不证明没有语义重复。
- 取消安全扫描为 0，仅证明已扫描的 suspend 泛化 catch 有可见的取消保留，不代替运行时压力测试。
- 3 个 `@Suppress("unused")` 已确认为 roadmap 占位：`PlanLifecycle`、`PlanValidator`、`ContactEnrichmentProvider`，已明确标注，不当作待删死代码。
- 6 个 `UNCHECKED_CAST` 仍集中在 CRM UI 演示/模型转换边界，属于后续类型安全债务，本轮未为了消告警而扩大业务改动。

## 4. 本轮已完成的提交

| Commit | 内容 | 主要验收 |
| --- | --- | --- |
| `b854253` | 统一质量尺子与安全扫描规则 | `check`，手工测量与根闸口同口径 |
| `d6ccbe0` | 收口数据仓库与关系域依赖 | `AgentDataRepositoryTest` 及全量 JVM 测试 |
| `1298d1a` | 聚合关系页状态，分离详情按需订阅 | 关系 UI/数据仓库设备测试 |
| `7af7013` | 为 KSP/重构编译稳定 Kotlin/Gradle 内存 | 从干净重编译通过 |
| `87d439a` | 拆分 Provider 执行阶段和超时策略 | `CalendarTimeResolutionTest.reactTimeoutKeepsMultimodalAndWeakNetworkBudgets` |
| `5599004` | 物理分离 36 个 Room schema migration | 全量迁移设备测试，包括 31→32、35→36、36→37 |
| `02be61a` | 将存储统计/清理移到 IO 调度器并消除测量误报 | `GeneralSettingsCacheTest`，`remember` 内 IO 计数 0 |

## 5. 最终验证

### 静态、JVM 与构建

- `./gradlew --no-daemon --max-workers=2 check`：通过，232 个 Gradle 任务。
- JVM：Debug 429 + Release 429 + 子模块 83 = 941 次测试执行，0 失败、0 错误、0 跳过。Debug/Release 会重复运行同一批 app 用例，因此不将 941 表述为“941 个不同用例”。
- Release：无签名执行在 `verifyReleaseSigningConfiguration` 按设计失败关闭；注入仓库外一次性验证 keystore 后，R8、lint vital、资源压缩、签名和 `assembleRelease` 全部通过。临时 keystore 已删除，未进入仓库。

### 真机

- 设备：Samsung SM-W7023 / Android 13，ADB 序列 `R5CT20QKT9D`。
- `ANDROID_SERIAL=R5CT20QKT9D ./gradlew connectedDebugAndroidTest`：发现 458 项，执行 457 项，0 失败，1 项受控跳过。
- 受控跳过：`ProviderCertificatePinsControlledTest.liveAndroidHandshakeAcceptsEveryPinnedProviderChain`。该用例需要显式打开真实公网证书链握手验收，不是测试失败。

## 6. 仍需继续治理的债务

### 6.1 600–1000 有效行预警文件

| 有效行 | 文件 | 建议 |
| ---: | --- | --- |
| 998 | `ProviderExecutionEngine.kt` | 下次新增执行分支前先下沉协作者，不要再在引擎内堆积 |
| 974 | `RoomRuntimeStore.kt` | 按 command / approval / event 存储职责渐进分离，保留 facade 和事务边界 |
| 889 | `ContactAgentDataRepository.kt` | 优先分离系统通讯录导入与联系人智能整理 |
| 759 | `AgentDataRepository.kt` | 继续作为 facade，新逻辑必须进领域子仓库 |
| 682 | `ContactDetailDialogs.kt` | 按资料、关系、回写通讯录继续拆 UI 区块 |
| 662 | `RelationTab.kt` | 继续保持聚合快照，用 Compose 宏基准验证大通讯录性能 |
| 607 | `GeneralSettingsPages.kt` | 按权限、存储、数据管理分页物理分文件 |

### 6.2 Detekt 警告

当前 20 条非阻断警告：

- 15 条位于检索、ASR、命令处理、日历参数规范化、CRM/联系人工具、系统通讯录、社交消息解析和联系人导入。这些是当前真正的逻辑复杂度热点。
- 5 条位于历史 Room migration / schema DDL。本轮拆文件后暴露了长方法，不是新增的运行时复杂度。历史迁移以稳定和可回归为先，不为清告警做高风险 SQL 改写。

### 6.3 不应被静态结果代替的验收

- Compose 重组次数、首屏时间、1000+ 联系人滚动和关系图帧率需 Macrobenchmark/性能追踪，源码订阅计数不是性能结论。
- `scripts/measure.sh` 列出的 25 个 `runCatching` 可疑文件是粗扫候选；根取消安全审计为 0，但新增 suspend 路径仍必须逐调用链复核。
- 本轮证明“当前自动化闸口全绿”，不代表用户不可能再遇到业务或 OEM 特有问题。

## 7. 后续执行原则

1. 红线已由根 `check` 强制；不允许用 baseline 、全局 suppress 或放宽阈值换取绿灯。
2. 上表预警文件如需新增逻辑，先下沉职责再改业务，避免再次越过 1000 线。
3. 每次数据库迁移、Agent 状态机、对外数据或自动写入改动，必须有能抓回回归的测试和真机验收。
4. 所有结论继续分为“静态已验证”、“构建已验证”、“设备已验证”和“仍未验证”，不用配置或推断代替实际回执。
