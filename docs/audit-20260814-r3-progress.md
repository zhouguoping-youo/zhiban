# R3 修复进度

> 基线：`c711f43`。本表只记录脱敏的验证结论，不记录凭据、用户输入或模型原文。

| 任务 | 状态 | 修复提交 | 自动化验证 | 设备验证 |
|---|---|---|---|---|
| HIGH-1 向量检索回填死锁 | 已修复 | `00cd8bd` | `./gradlew check` 全绿（239 任务）；`EmbeddingIndexIntegrationTest` 新增 2 用例：legacy `NORMAL` 事实以 `PERSONAL` 提供给网关并被索引、单个被隐私闸拦截的事实不再中断整批 | SM-W7023 覆盖安装；534 项完成、0 失败、1 项受控公网握手跳过；最终以修复后代码重跑目标类 9/9 通过 |
| M1 原生 web search 默认开启 | 已修复 | `a547320` | `./gradlew check` 全绿；`RuntimeInputProcessorTest.nativeWebSearchRequiresExplicitOptIn`：web_search 能力模型默认 `allowWebSearch=false`、显式开启后 `=true` | SM-W7023 覆盖安装；535 项完成、0 失败、1 项受控公网握手跳过；工具页新增"联网搜索"开关默认关 |
