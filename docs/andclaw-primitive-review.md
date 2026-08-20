# Andclaw 操作原语对照评审

日期：2026-08-21  
范围：`RuntimeToolCatalog` 与本轮新增的智能转发无障碍流程  
结论：不把 UI 自动化原语暴露为 Agent 工具。

## 结论

当前生产目录实际注册 34 个 `RuntimeToolSpec`（不是旧任务描述中的 30 个）。它们是日历、联系人、关系、记忆、CRM、通信、位置和公开搜索等领域操作；Andclaw 的 `click`、`swipe`、`text_input`、`wait`、`observe`、`back`、`scroll`、`long_press` 等是目标 App 内的 UI 操作原语。两者抽象层不同，因此没有需要合并的重复工具，也不应为了“对齐数量”增加工具。

本轮智能转发在专用的 `SmartForwardController` 内部使用受限的 observe / click / scroll / text-input 能力，且有一次性用户触发、单步 8 秒超时、最多 3 屏、可中断、永不点击发送和失败回退分享面板等边界。它不是 RuntimeToolCatalog 的工具，避免模型或后台任务获得通用 UI 操控能力。

## 对照表

| Andclaw 原语 | 知伴当前对应 | 评审结论 |
|---|---|---|
| observe / wait | 智能转发控制器的事件轮询与单步超时 | 已有内部实现；不向 Agent 暴露 |
| click | 仅定位会话并进入会话 | 受限于一次性流程；不新增通用 click 工具 |
| scroll | 最多三屏查找联系人 | 已有上限；失败即回退 |
| text_input | 只向输入框预填草稿 | 保留“停在发送前”；不允许代发 |
| back / abort | 无障碍服务销毁、用户返回和控制器 `abort` | 已有中断路径 |
| intent | 拉起微信和现有分享面板 | 属于应用边界，不是领域 ToolSpec |
| screenshot / OCR | 分享图片的本地 OCR，低结构结果再走显式开启的视觉解析 | 走现有出站策略与候选确认链 |
| generic long_press / drag / key | 无生产需求 | 不增加，避免扩大自动化权限 |

## 粒度与安全判断

`communication.message.compose`、`calendar.schedule.create`、联系人和关系写入工具保持领域级粒度，并由 ActionPolicy、确认链、幂等和 ChangeLog 约束。把它们拆成 click/swipe 等低级指令不会提高可验证性，反而可能绕过“用户最后确认”和写后读回。

因此本项不做行为重构，也不新增 `wait`/`observe` 等目录工具。若未来需要更多自动化场景，应继续采用“场景专用控制器 + 明确上限 + 执行前停止点 + 执行后验证”，而不是开放通用 UI Agent。

## 验证

- `RuntimeToolCatalog` 当前注册数量：34。
- 智能转发默认关闭；用户显式开启后才执行一次流程。
- 图片视觉解析默认关闭；开启后仍先本地 OCR，并通过现有 Provider/出站策略。
- 本文只记录评审结论，不改变运行时行为。
