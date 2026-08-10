# 知伴（ZhiBan）

知伴是一款以联系人和关系为中心的 Android 个人 Agent。它将关系网络、日程、个人 CRM、生活安排和对话式工作流联系起来，帮助用户整理信息、跟进重要关系并推进下一步行动。

## 核心能力

- 联系人档案、多层关系和可交互关系图谱
- 日程、提醒、下一步行动与系统日历联动
- 面向个人的 CRM 线索、机会、活动和候选线索流程
- 通知、发出消息、通话记录与用户主动分享的本地化采集
- “问问”对话与 Agent 工具链，支持确认、审计、幂等和可撤销写入
- 敏感数据分级、出站策略和对外操作的最终用户确认

## 技术栈

- Kotlin + Jetpack Compose Material 3
- Room + SQLCipher
- Hilt + Coroutines + Flow + WorkManager
- OkHttp + kotlinx.serialization
- `minSdk 26` / `compileSdk 35` / `targetSdk 35`

## 工程结构

```text
app/                  Android 应用、界面、系统集成与数据层
agent/contracts/      Agent 领域契约
agent/provider/       模型 Provider 和出站收口
agent/context/        上下文检索与组装
agent/tools/          工具契约与执行绑定
agent/governance/     风险分级、审批、审计与撤销
agent/skills/         场景技能
agent/mcp/            MCP 集成
agent/memory/         记忆子系统
agent/runtime/        Agent 运行时
agent/feature-ask/    “问问”功能模块
```

## 本地开发

环境要求：Android Studio（兼容 AGP 8.9）、JDK 17、Android SDK 35，以及 API 26 或更高版本的模拟器或真机。

```bash
./gradlew check
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

`check` 包含 JVM 测试、Android Lint、Detekt、Ktlint、模块边界、协程取消安全和密钥泄漏检查。

## 配置和安全

模型连接在 App 内通过“我的 → 智能体设置 → 大模型连接”配置。不要把 API Key、签名密钥库或密码提交到仓库。

发布构建必须使用仓库外的签名资产，并提供：

```text
ZHIBAN_RELEASE_STORE_FILE
ZHIBAN_RELEASE_STORE_PASSWORD
ZHIBAN_RELEASE_KEY_ALIAS
ZHIBAN_RELEASE_KEY_PASSWORD
```

详细方式见 [发布签名说明](docs/release-signing.md)。

## 文档

- [产品定义](PRODUCT.md)
- [知伴开发手册](docs/知伴开发手册.md)
- [知伴代码规范](docs/知伴代码规范.md)
- [代码质量基线](docs/代码质量基线.md)
- [通话采集设计](docs/通话采集设计.md)
- [低风险自动写入与回滚设计](docs/低风险自动写入与回滚设计.md)

## 边界说明

知伴受 Android 系统权限和第三方应用沙箱限制，无法承诺完整读取所有平台的历史消息。来自无障碍、通知或启发式推断的信息会保留来源和置信度；对外发消息等不可撤销操作始终需要用户最后确认。
