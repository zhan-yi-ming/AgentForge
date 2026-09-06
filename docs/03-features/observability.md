# Agent Observability

- 状态：Implemented
- 所属阶段：V2-01
- 相关 ADR：ADR-0016

## 用户价值与使用场景

开发者和运维人员可以用一次请求的 `request_id`、`thread_id` 或 `project_id` 在 Langfuse 定位 Agent 各阶段，区分慢在检索、模型还是 Tool 规划，查看可获得的 Token usage，并确认成功或异常链路均已结束。

## 范围与非目标

V2-01 覆盖 JSON Chat 与流式 Chat 的根请求、Agent、prepare、retrieval、tool 和 LLM generation 基础观测。Langfuse 默认关闭，启用时连接用户自行提供的 Langfuse Cloud 或自托管实例。

不包含 Langfuse Server 部署、复杂 Dashboard、Eval、告警、成本预算、跨服务 W3C context、日志系统重构、聊天历史或 V2-02 Context Manager。

## 关键流程

1. Java 完成 JWT、项目权限和配额校验，沿用同一个 `request_id` 调用 Python。
2. Python 内部 token 校验通过后，确定 `thread_id`：已有 `conversationId` 复用，否则生成 UUID。
3. observability adapter 创建 `agent-chat-request` 根观测并传播 `request_id`、`thread_id`、`project_id`。
4. 根观测下创建 `agent`；Graph 的 `prepare`、`retriever`、`tool` 以及 responder 的 `generation` 自动继承同一 Trace。
5. 节点只写白名单摘要与 Token 计数。异常由对应观测记录脱敏错误类别并正常结束；流式响应开始前的未知异常也必须闭合 request/agent 观测并继续上抛。原业务异常继续按既有 422/503、流式 `error` 或未知异常传播语义处理。
6. SDK 异常被 adapter 隔离；业务继续运行。进程关闭时尽力 flush 未发送事件。

## 接口

不新增公共 HTTP 字段。关联字段复用内部 Chat body 的 `requestId`、`conversationId` 和 `projectId`；公共响应仍返回现有 `requestId` 与 `conversationId`。

配置：

- `AGENTFORGE_AGENT_LANGFUSE_ENABLED`：默认 `false`。
- `AGENTFORGE_AGENT_LANGFUSE_PUBLIC_KEY`、`AGENTFORGE_AGENT_LANGFUSE_SECRET_KEY`：启用时必填，不记录、不回显。
- `AGENTFORGE_AGENT_LANGFUSE_HOST`：Langfuse endpoint，默认官方 Cloud endpoint，可指向自托管实例。
- `AGENTFORGE_AGENT_LANGFUSE_ENVIRONMENT`：环境名，默认 `local`。

## 数据

Langfuse 保存的是外部观测数据，不是业务事实；数据库 schema 不变化。允许字段：三个关联 ID、节点名/类型、provider/model、Token 计数、source count、proposal/tool 类型、结果状态和脱敏错误类别。延迟由 observation 的 start/end 计算。

## 权限与安全

只有内部认证成功的 Chat 才建立 Trace。禁止写入 message、answer、retrieved context、source excerpt、Tool arguments/title/description、JWT、密码、内部 token、provider key、DSN、Cookie、请求/响应 headers 或原始异常文本。Langfuse Trace 不公开；public/secret key 只从运行时环境读取。

## 失败与排查

- 没有 Trace：检查 enabled、public/secret key、host 和 Agent Service 安全日志；业务仍应正常。
- Trace 断链：用 `request_id` 检查根与子节点是否一致，确认 `thread_id` 是响应中的 conversationId。
- Token 为缺失而非 0：当前 provider/stream 没有返回 usage metadata；不得估算或伪造。
- Langfuse 不可达：adapter fail-open，修复网络/证书/host 后发送新请求；不会补传进程已丢失的数据。
- 业务 503：先用 `request_id` 对照 Java/Python 日志和已结束的 ERROR observation；Langfuse 故障本身不应产生 503。

## 测试与验收

- 通过 FastAPI 公共内部入口验证根/Agent/prepare/retriever/tool/generation 层级、关联 ID 和安全摘要。
- 验证 retrieval、LLM 以及流式响应开始前的未知异常会标记 ERROR 并闭合，原 HTTP/流式错误契约不变。
- 验证同步与流式模型 usage metadata 能写入 generation；缺失 usage 时不伪造。
- 验证 disabled 与 observer 自身故障均不影响 Chat。
- 运行 Python 全量 pytest、Java `clean verify`、Web 回归、核心跨进程 smoke、配置检查、敏感扫描和 Pi Milestone Review。

## 已知限制与后续计划

V2-01 不传播 Langfuse/Otel trace id 到 Java，也不保存聊天 history；同一 thread 的请求通过 session/thread 字段聚合，但 Agent state 持久化属于 V2-07。Context composition、预算和 Eval 分别属于 V2-02/V2-03/V2-08，不在本节点实现。
