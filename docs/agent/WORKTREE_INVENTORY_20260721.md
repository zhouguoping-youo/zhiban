# Claude worktree 盘点（2026-07-21）

## 结论

`zhiban-t31` 是当前唯一集成主线。它包含问问 UI、Runtime v2、Provider、计划 DAG，以及尚未提交的工具/记忆整合。其他 worktree 暂时冻结为实现和证据来源。

## 清单

| 目录 | 分支/状态 | 用途 |
|---|---|---|
| `zhiban` | detached `4252d67`，有 5 个文档改动 | 历史集成点，不继续开发 |
| `zhiban-t25` | `task/t25-production-runtime`，干净 | Runtime v2 生产入口基线 |
| `zhiban-t31` | `task/t31-plan-dag`，有 30 个整合改动 | 唯一主线 |
| `zhiban-t35` | `task/t35-provider-bridge`，干净 | Provider 流式执行与取消证据；已在 t31 祖先中 |
| `zhiban-t38` | `task/t38-memory-atomic`，干净 | 原子记忆实现参考 |
| `zhiban-t38-integration` | `task/t38-memory-integration`，干净 | 记忆集成基线；已在 t31 祖先中 |
| `zhiban-t39` | `task/t39-memory-retrieval`，干净 | 受界限约束的记忆检索，尚未进入 t31 |
| `zhiban-t41` | `task/t41-mic-permission`，干净 | 麦克风永久拒绝处理参考 |
| `zhiban-t41-slice2` | `task/t41-slice3-work-state`，干净 | 弹性输入框；已合并 t31 |
| `zhiban-t41-sliceF` | `task/t41-slice-f-voice`，有截图改动 | Provider ASR/语音全链路，尚有 4 个分叉提交需审查 |

## 禁止事项

- 不以目录名称判断“最新版”。
- 不直接把某个 worktree 整体覆盖到主线。
- 不删除未跟踪文件和用户截图。
- 不在多个 worktree 同时修改同一功能。
- 不把 UI 出现、弹 Toast 或打开说明框当作功能闭环。

## 待吸收项

1. 审查并迁移 `t39` 的记忆检索边界与重验证。
2. 审查并迁移 `t41-sliceF` 的 Provider ASR bridge、录音状态链和测试。
3. 将 t31 未提交的 Tool Catalog、Memory Tool、UI/Runtime 整合拆成可审查提交。
4. 对照架构补齐 CapabilityRouter、Domain Writer、Audit/Undo、Contact Tool、检索流水线。

