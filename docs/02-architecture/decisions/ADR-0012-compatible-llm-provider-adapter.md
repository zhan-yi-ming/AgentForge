# ADR-0012：LangGraph 通过兼容适配器调用国内模型

- 状态：Accepted
- 日期：2026-09-05

## 背景

Agent Service 已使用 LangGraph 编排 Chat，但 `respond` 节点没有生成式模型。DeepSeek、智谱与阿里云百炼均提供 OpenAI-compatible Chat Completions 协议。项目明确不接入 OpenAI 服务，同时需要用最小改动让 V1 可真实体验。

## 决策

在 Python Agent Service 内使用 LangChain `ChatOpenAI` 作为协议客户端，并在 LangGraph `respond` 节点注入 responder。客户端只接受 `deepseek`、`zhipu`、`qwen` 三种 provider；各 provider 有明确的非 OpenAI 默认 base URL 和默认模型，API key 从运行环境读取。允许用环境变量覆盖 base URL 与模型，以适配百炼地域/业务空间或厂商模型升级。

`disabled` 是无密钥开发与测试回退模式，沿用确定性 responder。RAG Embedding 继续使用本地 hash provider；本次不向任一外部服务发送整库数据，只发送当前请求经授权、排序和字符预算截断后的检索上下文及用户问题。

本决定同时取代 ADR-0010 中“可选 OpenAI-compatible Embedding provider”的当前实现选择；历史 ADR 保留原文用于追溯，V1 运行配置只接受 `hash` Embedding。

## 安全与信任边界

- 浏览器和 Java Core API 不接触模型 key；只有 Python Agent Service 进程读取所选 provider 的 key。
- `.env.example` 只含空值/占位说明，`.env` 被 Git 忽略。
- provider adapter 不提供 OpenAI 选项，默认映射中不存在 `api.openai.com`。
- 上游认证、限流、网络和响应格式错误统一为不泄漏响应正文、URL 查询或 key 的 503。
- 模型输出仍是不可信文本。Tool proposal 继续由既有确定性 planner 产生，并由 Java 白名单校验、持久化和人工确认后执行。

## 后果

优点是复用一个成熟的兼容客户端和现有 LangGraph 节点，三家切换只改环境变量。代价是依赖包名称仍为 `langchain-openai`/`openai`，但它们只实现协议与传输；实际服务目的地由 provider base URL 决定。三家兼容差异通过最小公共参数集控制，暂不使用专有 thinking、工具调用或流式字段。

厂商接口和模型版本会演进，因此默认值只是可运行起点；模型名和 base URL 必须可配置，并在本地运行文档中注明以厂商控制台实际可用值为准。
