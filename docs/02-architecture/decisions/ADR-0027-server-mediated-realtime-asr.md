# ADR-0027 服务端中介实时语音识别

- 状态：Accepted
- 日期：2026-09-19
- 阶段：P3-05

## Context

聊天页需要即时显示麦克风转写，同时保留用户编辑并主动发送。阿里云录音文件 HTTP API 依赖可访问的文件 URL、异步提交与轮询；浏览器直连实时 WebSocket 又无法安全设置服务端 API Key。现有 Java 边界负责认证、项目权限与额度，Python Agent Service 负责模型和外部 AI 服务。

## Decision

浏览器仅在独立 `/chat` 页采集并转换 PCM16，经同源 Bearer HTTP 分块上传并短轮询文字。Java 每次校验项目访问、在开始录音时扣除现有 AI 日额度，再以内部 token 向 Python 转发。Python 使用独立服务端 ASR Key 连接阿里云 Qwen3-ASR-Flash-Realtime WebSocket，管理短期内存会话；会话绑定 project/user，限制单用户并发、全局并发、块大小、总字节与寿命。只回传文字；用户停止后收到最终转写并自行编辑发送。音频不入历史、数据库和业务 Action 流。

## Alternatives

- 录音文件 HTTP API：需要持久文件与可访问 URL，反馈慢，且引入存储与清理。
- 浏览器直连阿里云：供应商 Key 将进入浏览器，违反凭据边界。
- 浏览器直接调用 Java WebSocket：需要额外短期握手凭据或 Cookie，扩大认证表面；当前同源 HTTP 分块已满足聊天输入的实时预览。

## Trade-offs and Consequences

每块 HTTP 与轮询有额外开销；会话驻留单个 Python 进程，重启或多实例切换会结束录音，用户可重试。供应商密钥未配置时语音入口不可用，文字聊天继续。未来部署多实例时需引入粘性路由或专用实时网关，并重新评估状态存储、成本和背压。

官方协议依据：[实时 WebSocket 交互](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-interaction-process)、[客户端事件](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-client-events)。
