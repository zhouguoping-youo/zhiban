# MiniMax Provider 落地审计（2026-07-22）

## 结论

MiniMax 已完成官方配置核对、代码修正、自动化测试和 Android 模拟器真实 Key 联调。Key 仅进入模拟器应用的 Android Keystore 加密凭据仓，不进入源码、测试、日志或本文档。

## 官方依据

- 文本生成与模型能力：https://platform.minimaxi.com/docs/guides/text-generation
- OpenAI 兼容模型列表：https://platform.minimaxi.com/docs/api-reference/models/openai/list-models
- Chat Completions 参数：https://platform.minimaxi.com/docs/api-reference/text-post
- M3 工具调用：https://platform.minimaxi.com/docs/guides/text-m3-function-call
- 错误码：https://platform.minimaxi.com/docs/api-reference/errorcode
- 限流：https://platform.minimaxi.com/docs/guides/rate-limits

## 配置核对

| 项目 | 当前实现 | 状态 |
|---|---|---|
| 中国区 Base URL | `https://api.minimaxi.com/v1` | 通过 |
| 模型探测 | `GET /v1/models` + Bearer | 通过 |
| 对话接口 | `POST /v1/chat/completions` | 通过 |
| 默认模型 | `MiniMax-M3` | 通过 |
| M3 上下文 | 1,000,000 tokens | 通过 |
| M2.x 上下文 | 204,800 tokens | 通过 |
| 输出参数 | `max_completion_tokens` | 通过 |
| 流式输出 | `stream=true` | 通过 |
| 流式用量 | `stream_options.include_usage=true` | 通过 |
| 工具协议 | OpenAI-compatible `tools` / `tool_choice=auto` / `tool_calls` | 通过（协议与单测） |
| 多模态声明 | 仅 M3 开放图片；M2.x 收紧为文本 | 通过 |
| 服务端业务错误 | 解析 `base_resp.status_code`，包括 HTTP 200 业务失败 | 通过 |
| 请求追踪 | 优先读取官方 `trace_id` | 通过 |
| Key 存储 | Android Keystore AES-GCM；不落普通设置 | 通过 |
| 地址安全 | 内置官方地址白名单 + SPKI 证书固定 | 通过 |

## 本次发现并修复

1. 默认模型仍为 M2.7，未纳入最新 M3。
2. M2.x 被错误声明为图片模型，现仅 M3 开启图片能力。
3. 未请求流式 usage，现加入 `stream_options.include_usage=true`。
4. 未识别 MiniMax `base_resp` 业务错误，现完成错误码安全映射。
5. 未优先使用官方 `trace_id`，现已修正。
6. 模型探测曾在 Android 主线程执行，导致真实配置固定失败并抛出 `NetworkOnMainThreadException`；现强制在 `Dispatchers.IO` 执行。

## 真实模拟器证据

- 设备：Android Emulator `emulator-5556`
- `GET https://api.minimaxi.com/v1/models`：HTTP 200，返回 M3、M2.7、M2.5、M2.1、M2 等官方模型。
- 设置页：MiniMax / MiniMax-M3 显示“已连接”“已保存”。
- 问问页：实际发起 M3 Chat Completions 流式请求，HTTP 200，收到目标回复 `MINIMAX_OK`。
- 凭据：仅保存在模拟器应用私有的 Keystore 加密仓，审计材料不包含 Key。

## 后续边界

本文件只判定 MiniMax Provider。本轮完成后再以同一清单逐家核对阿里百炼、火山方舟、智谱 BigModel、腾讯混元 TokenHub；不得用“OpenAI 兼容”推定各家的模型、错误码和能力完全相同。
