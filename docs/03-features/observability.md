# Agent Observability

- 状态：Implemented
- 所属阶段：V2-01
- 相关 ADR：ADR-0016

## 用户价值与使用场景

开发者和运维人员可以用一次请求的 `request_id`、`thread_id` 或 `project_id` 在 Langfuse 定位 Agent 各阶段，区分慢在检索、模型还是 Tool 规划，查看可获得的 Token usage，并确认成功或异常链路均已结束。

## 范围与非目标

V2-01 覆盖 JSON Chat 与流式 Chat 的根请求、Agent、prepare、retrieval、tool 和 LLM generation 基础观测。Langfuse 默认关闭，启用时连接用户自行提供的 Langfuse Cloud 或自托管实例。

V2 stable 后的独立运维增强额外提供单机 Grafana + Loki + Alloy 日志界面。它收集 `agentforge` Compose project 的容器标准输出/错误，预置 Loki 数据源与 `AgentForge Logs` dashboard，可按服务、关键词和 `request_id` 查询。

仍不包含 Langfuse Server 部署、复杂 Dashboard、主机/应用 metrics、告警、成本预算、跨服务 W3C context 或日志系统重构。日志界面不替代 Langfuse Trace，两者通过 `request_id` 人工关联。

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

Grafana 仅通过现有 HTTPS gateway 的 `/grafana/` 暴露，不发布独立主机端口；匿名访问和用户自助注册关闭，管理员密码来自服务器上权限为 `0600`/`0400` 的环境文件。Loki 与 Alloy 仅在内部 Compose 网络可达。Alloy 只采集 Compose project `agentforge`，但读取 Docker socket 仍属于高权限边界，具体取舍见 ADR-0023。日志中可能含用户/项目标识等运维元数据，禁止把完整日志公开或转发给第三方。

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
- 日志界面增强通过生产 Compose 渲染与安全边界 contract、Nginx `/grafana/` 子路径、Loki/Alloy 配置、Grafana provisioning 及容器 smoke 验收；确认只有 gateway 发布主机端口，未登录不能查询日志。

## 已知限制与后续计划

V2-01 不传播 Langfuse/Otel trace id 到 Java，也不保存聊天 history；同一 thread 的请求通过 session/thread 字段聚合，但 Agent state 持久化属于 V2-07。Context composition、预算和 Eval 分别属于 V2-02/V2-03/V2-08，不在本节点实现。

单机 Loki 使用 filesystem storage，只面向当前低日志量 Demo，不提供高可用或按磁盘剩余空间自动停止写入。默认 retention 为 7 天；观测栈故障不影响业务，但故障期间的集中日志可能缺失，原 Docker bounded logs 仍是短期兜底。
