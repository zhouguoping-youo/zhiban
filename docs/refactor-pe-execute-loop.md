# 重构清单：拆分 ProviderExecutionEngine.execute()

> 目标：让 `execute()` 的 detekt LongMethod（当前 ~395 行 / 阈值 80）翻零，顺带降文件体积（dim 2a）。
> 这是最险的一块——核心 ReAct 引擎。务必**每抽一段跑一遍 38 个引擎设备测试**兜底。
> 起点：分支 `task/t31-plan-dag`，HEAD 已完成 retrieval + 网络预检两段安全抽取（commit `3152162`）。

## 背景：为什么一次性做不完
`execute()` 现在是「prep 段（~130 行）+ ReAct 流式循环（~305 行）」。**单独抽循环**后 execute() 仍剩 ~130 行 prep，还是 >80。所以**必须两段都抽**，execute() 才会降到 `状态检查 + prepareRun() + runReActLoop()` ≈ 20–30 行 < 80。

## execute() 当前结构（行号会随抽取漂移，以签名/边界为准）
- `suspend fun execute(runId, sessionId, fencingEpoch)` 开始。
- ① 状态检查（`run.status !in {ASSEMBLING_CONTEXT, INFERENCING}` → return false）。
- ② **prep 段**：`readRunInput` → `decodeInput` → 网络预检（已抽 `networkPreflightFailure`）→ `perceptionPipeline.perceive` + `SkillActivator.activate` → `profiles.load`/黑名单 → `ProviderModelPolicy.selectForInput` → `memoryPolicy()` → `performRetrieval`（已抽）→ `recallApproved`/会话上下文/feedback → `recoverySnapshot`/`startAttempt` → `events.appendPerception/appendRetrieval`。
- ③ `activeRequests[runId] = attemptId`。
- ④ `return try { withTimeout { coroutineScope { heartbeat; probe; rerank; assembleMessages; provider.stream().produceIn; while(event){Delta/Usage/Final} ; finalSeen 校验 ; saveAssistantTurn } } ; finishProviderRun ; true } catch (AutomaticToolCompleted/ApprovalPending/TimeoutCancellationException/CancellationException/Throwable) { ... }`。

## 步骤 1：抽 prep 段 → `prepareRun(...)`
新增私有 suspend：
```kotlin
private suspend fun prepareRun(
    run: RuntimeRunEntity, runId: String, sessionId: String, fencingEpoch: Long,
): PreparedRun   // sealed: Failure(code, retryable) | Ready(...)
```
- `Ready` 持有循环要的全部局部（见下「14 局部」），并**在返回前**完成 `events.appendPerception/appendRetrieval` 与 `store.startAttempt/supersedeAttempt`（这些副作用现在就在 prep 里）。
- prep 里所有 `return events.failBeforeAttempt(...)` 改成 `return PreparedRun.Failure(code, retryable)`。
- execute() 变成：状态检查 → `val prepared = prepareRun(...)` → `if (prepared is Failure) return events.failBeforeAttempt(...)` →（smart-cast 到 Ready）取字段 → `activeRequests[runId] = prepared.attemptId` → `return runReActLoop(...)`。

## 步骤 2：抽循环段 → `runReActLoop(...)`
新增私有 suspend：
```kotlin
private suspend fun runReActLoop(
    input: DecodedInput,
    queryContext: QueryContext,
    retrieval: ContextRetrievalResult,
    approvedMemories: List</* ApprovedMemoryRecallResult.items 元素，见类型表 */>,
    conversationContext: SessionConversationContext,
    feedback: List<String>,
    activatedSkills: List<SkillActivation>,
    profile: ProviderProfile,
    config: com.zhiban.rebuild.runtime.config.AgentDynamicConfig,
    currentNetwork: NetworkQuality,
    attemptId: String, runId: String, sessionId: String, fencingEpoch: Long,
): Boolean
```
- 把当前 `return try { … } catch(…){…} … `（从 `return try {` 到 execute 最后的 `}`）**整段 verbatim** 搬进 `runReActLoop`，方法体就是 `return try { … } catch { … }`。
- `retrieval` 在循环里被重赋值（rerank 段：`retrieval = if(...) rerankRetrieval(...) else retrieval.copy(...)`）——循环内改成局部 `var retrieval = retrieval`（参数是 val），后续 `assembleMessages` 用局部即可；循环结束后 execute 不再读 retrieval，**不用返回**。
- 控流原样保留：`throw ProviderFailure(...)`、`throw ApprovalPending`、各 `catch` 分支（含 `NonCancellable` 里的取消判定）。
- 私有方法 14 参数不触发 dim 1a（只管构造函数）；detekt 未开 LongParameterList。

## 14 个局部的类型表（两个跨文件类型已钉，其余待实现时确认）
| 局部 | 类型 | 来源 |
|---|---|---|
| input | `DecodedInput` | `ProviderExecutionDomainLogic.kt:261` `fun decodeInput(raw): DecodedInput`（同包，免 import） |
| queryContext | `QueryContext` | 已 import |
| retrieval | `ContextRetrievalResult` | 已 import |
| approvedMemories | `List<…>` | `ApprovedMemoryRecallResult.items`（tool 包，元素类型实现时看类定义确认） |
| conversationContext | `SessionConversationContext` | `store.conversationContext()` 返回 |
| feedback | `List<String>` | `store.recentFeedback()` |
| activatedSkills | `List<SkillActivation>` | `agent/skills SkillContracts.kt:18 data class SkillActivation` |
| profile | `ProviderProfile` | `ProviderModelPolicy.selectForInput` 返回 |
| config | `AgentDynamicConfig` | `dynamicConfig()`（FQN 或短名，已 import NetworkQuality） |
| currentNetwork | `NetworkQuality` | 已 import（本轮加的） |
| attemptId / runId / sessionId | `String` | — |
| fencingEpoch | `Long` | — |

## 验证（每步都跑，不绿不进下一步）
1. `./gradlew :app:compileDebugKotlin :app:ktlintMainSourceSetCheck`——签名的类型、ktlint（expression-body / multiline-if-else / when 空行这几条会在长 FQN 上挑刺，必要时加 import 用短名）。
2. `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.zhiban.rebuild.runtime.store.RuntimeInputProcessorTest`——**38 个引擎设备测试**全过（覆盖 timeout/cancel/recover/网络/工具确认/重试）。
3. `./gradlew :app:check` 绿。
4. `./gradlew :app:installDebug` 把验证后的版本装回真机（connectedAndroidTest 会卸载）。
5. 复测：`bash scripts/measure.sh` 看 execute() LongMethod 是否归零、PE 文件是否 <1000。

## 易错点（前车之鉴）
- **不要**用「局部嵌套函数」偷懒——闭包捕获局部虽免参数，但 detekt LongMethod 仍算外层方法，**不降指标**。必须抽成成员方法。
- ktlint 在长全限定名上反复挑 function-signature / multiline-if-else / when 空行——优先 `import` 用短名让签名单行能放下。
- 循环里有 `return try { … } catch { … }` 整体返回 Boolean，`throw` 控流；搬动时一字不改，只把外层 `execute` 的 `return` 换成 `runReActLoop(...)` 调用。
- `observeToolResult`（~345 行）有**近似但不完全相同**的 retrieval 块（无 `currentNetwork==NORMAL` 门），不要强行复用 `performRetrieval`/`prepareRun`——会改变其行为。它若也要降 dim 2c，单独按同法处理。

## 完成判据
- `execute()` < 80 有效行（detekt LongMethod 归零）。
- ProviderExecutionEngine.kt < 1000 物理行（dim 2a）。
- 38 引擎设备测试 + `:app:check` 全绿，行为零变更。
