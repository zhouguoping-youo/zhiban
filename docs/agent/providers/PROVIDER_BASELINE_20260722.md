# Provider 统一基线（2026-07-22）

## 用户可见原则

- 页面只展示服务商、API Key、连接状态和当前最新多模态模型。
- 每个服务商只展示一个由知伴维护的模型，不让用户理解或选择模型版本。
- “语义检索”属于长期记忆内部能力，不出现在 Provider 页面，也不要求普通用户填写第二套 Key。
- “系统提示词”属于个性化模块，不出现在 Provider 页面。
- 保存前必须真实访问服务商官方接口验证 Key 和模型，不允许只做本地格式检查。

## 当前模型基线

| 服务商 | 唯一模型 | 输入能力 | Agent 工具 |
|---|---|---|---|
| 阿里百炼 | `qwen3.7-plus` | 文本、图片、视频 | 支持 |
| 火山方舟 | `doubao-seed-2-0-pro-260215` | 文本、图片 | 支持 |
| MiniMax | `MiniMax-M3` | 文本、图片 | 支持 |
| 智谱 BigModel | `glm-5v-turbo` | 文本、图片、视频、文件 | 支持 |
| 腾讯 TokenHub | `youtu-vita` | 文本、图片、视频 | 官方未声明 Function Calling；Runtime 不向其发送工具参数 |

## 官方依据

- 阿里百炼视觉理解与模型能力：https://help.aliyun.com/zh/model-studio/vision-model
- 阿里百炼模型更新：https://help.aliyun.com/zh/model-studio/newly-released-models
- 火山豆包 2.0：https://developer.volcengine.com/articles/7610285824933445675
- MiniMax 文本与多模态：https://platform.minimaxi.com/docs/guides/text-generation
- 智谱 GLM-5V-Turbo：https://docs.bigmodel.cn/cn/guide/models/vlm/glm-5v-turbo
- 腾讯 TokenHub 模型列表：https://cloud.tencent.com/document/product/1823/130051

## 已落地的协议保护

- 官方 Endpoint 白名单与证书 SPKI 固定。
- Bearer Key 由 Android Keystore 加密保管。
- 探测和对话网络请求固定在 IO 线程。
- 流式响应大小、单帧大小、工具参数大小、上下文和输出上限均 fail-closed。
- 统一解析 OpenAI-compatible 错误体，覆盖无效 Key、欠费、限流、上下文超限、内容安全和参数错误。
- MiniMax 额外解析 HTTP 200 中的 `base_resp` 业务错误。
- Provider 不支持工具时，Runtime 不发送 `tools`，避免“配置成功、实际 Work 必然报错”。

## 真实 Key 验证状态

| 服务商 | 配置实现 | 模拟器真实 Key |
|---|---|---|
| MiniMax | 完成 | 已通过：模型探测 200、流式对话 200 |
| 阿里百炼 | 完成 | 等待对应 Key |
| 火山方舟 | 完成 | 等待对应 Key |
| 智谱 BigModel | 完成 | 等待对应 Key |
| 腾讯 TokenHub | 完成 | 等待对应 Key |

没有真实 Key 的服务商不得标记为“真实联调通过”。获得 Key 后，逐家执行：模型探测、纯文本流式、图片理解、错误 Key、限流/欠费安全提示，以及官方支持时的工具调用。
