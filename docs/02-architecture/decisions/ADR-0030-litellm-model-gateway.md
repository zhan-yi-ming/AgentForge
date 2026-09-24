# ADR-0030：在 Agent Service 内使用 LiteLLM SDK 统一模型调用

- 状态：Accepted
- 日期：2026-09-24
- 取代：ADR-0012 的客户端选型与 OpenAI 服务禁用结论；保留其 Java/Python 信任边界

## Context

ADR-0012 为 V1 选择 `ChatOpenAI` 兼容客户端，只开放三家非 OpenAI 服务。V3-02 路线明确要求统一 GPT、DeepSeek、GLM 等 provider 的 timeout、fallback、cost 和 usage。现有客户端把 provider 差异与模型调用分散在 responder 中，无法形成稳定的 Gateway 契约。

## Decision

在 Python Agent Service 内引入 LiteLLM Python SDK，不部署独立 Proxy。单个 `ModelGateway` 统一同步、流式和 JSON 意图调用；LangGraph、RAG 和 Tool 只依赖 responder/Gateway 的既有公共能力。部署配置可选择 `openai`、`deepseek`、`zhipu`、`qwen`，GPT 不默认启用。开启 OpenAI 服务需显式提供对应 provider 与 key；现有部署默认继续 `disabled`。配置允许一个静态 fallback，且仅在输出开始前的暂时性失败触发一次。无效配置在首次模型请求时失败关闭；没有有效 usage/价格时成本标为未知。

## Alternatives

- 继续 `ChatOpenAI` 加自写兼容分支：依赖较少，但每家重试、usage、成本和参数差异会继续增长。
- 独立 LiteLLM Proxy：提供集中式运维能力，但为当前单 Agent 部署增加服务、鉴权和持久化边界。
- LiteLLM Router：适合负载均衡和多部署；V3-02 只需要有界的静态 fallback，避免提前实现 V3-03 路由。

## Trade-offs

新增 SDK 依赖及 provider 适配变化风险。显式 GPT 支持改变 ADR-0012 的旧限制，需要由部署者自行提供凭据并接受相应数据目的地；不会自动迁移现有用户或默认流量。成本依赖模型价格数据且只能作为估算。模型输出始终不可信；Java 的权限、审批和业务写入不变。

## Consequences

后续 V3-03 可在 Gateway 上方实现确定性任务类型路由。本节点不允许 LLM 选 LLM、无限 fallback、流中切换或让 Gateway 直接执行业务 Tool。
