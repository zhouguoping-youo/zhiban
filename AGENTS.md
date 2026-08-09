# 知伴 Android — Codex 执行纪律(简版)

> 完整规范见 `docs/知伴代码规范.md`(R1-R21)+ `docs/代码质量基线.md`。开工前先读这两个文件。

## 项目
- Android:Kotlin + Jetpack Compose + Room(**SQLCipher 加密**)+ Hilt。
- 真机:三星 SM-W7023(`adb devices` 可见),包名 `com.zhiban.rebuild.debug`,StepFun key 已配好。
- 构建:`./gradlew check`(必须绿才提交)、`./gradlew connectedDebugAndroidTest`、`bash scripts/measure.sh`(指标不许退化)。

## 当前任务
全面破坏者测试。主权审计表 = `docs/adversarial-audit.md`(20 维度 ~270 检查点已登记)。
你的交付物 = **把该表逐项填满**(状态/严重度/根因/commit/测试名),并把更新后的表随修复一起提交。
状态图例见表头:✅已修复 ❌已复现 ⚪无法复现 🖐需人工 🧪需环境。

## 不要重复测:维度 6 对话链路
对话链路已在 `907fb05..HEAD` 11 个 commit 全部修复并验证,本轮**跳过**,聚焦其余 19 个维度。

## 硬纪律
1. **单工作区串行提交**:本 worktree 有人在用,禁止并行 agent 同目录提交;一次只改+提交一个 bug。
2. **每 bug 单独 commit**,且 `./gradlew check` 绿了才提交。
3. **修 bug 必补回归测试**(不只修不加测试)。测试名描述被测行为。
4. **不改对外行为**,只修 bug 不加新功能。
5. commit message 带 bug 编号(如 `1.6`)+ 修复内容,结尾加:
   `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## 规范红线(最易踩)
- R10 多表写必须同一 `withTransaction`,禁各自独立事务(防半成品)。
- R15 suspend 链路用 `runSuspendCatching`(禁 `runCatching` 吞 CancellationException)。
- R17 外部输入(通知/模型输出/MCP)入口校验,禁盲信。
- R18 敏感数据(手机号/邮箱/地址/消息内容)出境前必须过 OutboundDataPolicy,禁裸发。
- R19 资源(Cursor/Stream/Bitmap/AudioRecord)必须 finally/use 释放。
- R20 禁空 catch,catch 至少记降级原因码。
- R21 禁硬编码密钥,凭据走 KeystoreCredentialVault。
- 质量红线:单文件 ≤1000 有效行(`ProviderExecutionEngine.kt` 已压线,改它先拆);函数 ≤80 行、参数 ≤5、构造参数 ≤8。

## 修复优先级
崩溃 > 数据丢失 > 功能不可用 > 体验 > 文案

## 真机注意
反复 `adb install -r` 会概率性丢 Keystore key;App 报"未配置大模型"时多半是 key 丢了(非新 bug),
进 我的 → 智能体设置 → 大模型连接 重连一次即可。
