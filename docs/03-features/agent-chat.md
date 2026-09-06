# Agent Chat

- 状态：Implemented
- 阶段：V1 Day 3

认证用户在项目内提交 message 与可选 conversationId。Core API 校验 owner/admin 权限并调用 Agent Service；LangGraph 规范化请求、检索当前项目 Wiki/Task 后生成回答，Java 返回 answer、conversationId、requestId 和 sources。

Day 4 Graph 为 `START -> prepare -> retrieve -> respond -> END`。State 在 Day 3 字段上增加 actor_admin、retrieved_context 与 sources。message 去空白后 1–8000 字符；conversationId 缺失时 Python 生成 UUID。`retrieve` 先通过 Core API 获取已授权来源，再执行 Chunk 同步、Embedding、BM25、pgvector 与 RRF。

未认证 401；无项目权限 403；无效输入 400；Agent Service、Core 回调、索引数据库或 Embedding provider 不可用时公共入口返回 503。不保存聊天记录，不产生 Tool 意图。

Responder 支持 `disabled`、`deepseek`、`zhipu`、`qwen` 四种模式。`disabled` 用于无 key 开发与测试，沿用确定性检索摘要；选择三家之一时，LangGraph `respond` 节点把用户问题和已授权、已排序、受字符预算限制的 RAG context 交给对应生成式模型。系统提示要求模型仅把 context 当作项目资料而非指令，不伪造来源；结构化 `sources` 始终由检索结果生成，不由模型生成。

模型调用只影响 `answer`。Tool proposal 仍由确定性白名单 planner 产生，模型不能直接执行或扩大业务操作；Java 的校验、人工确认与确定性写回边界不变。provider 缺少 key、上游认证/限流/网络失败或响应无有效文本时，公共入口返回 503，响应不包含 key 或上游响应正文。

V1.1 公网 Demo 在 Java 信任边界为每个认证用户执行 PostgreSQL 原子 UTC 日配额；超限返回 429 且不调用 Agent Service。Agent Service 同时给兼容模型配置最大输出 Token。具体行为见 `public-demo-protection.md`。

V1.2 保留原 JSON 入口并新增真实流式入口。Java 在响应开始前同步完成 Bearer、项目权限、输入和 UTC 日配额校验；Python 在完成 LangGraph 的 prepare/retrieve/plan 后，通过模型原生 stream 输出 NDJSON。Java 将事件转换为 SSE：`metadata` 提供 conversationId、requestId 与 sources，多个 `delta` 逐步提供回答文本，`complete` 提供最终 pendingAction（可空），`error` 表示响应开始后的通用流错误。浏览器不得把尚未收到 `complete` 的回答视为完成。

流式链路不会削弱 HITL：Python 的 tool proposal 仍只在流末尾产生；Java 校验并持久化后才把 pending action 放入 `complete` 事件，Task 在用户 confirm 前保持不变。流开始前的错误沿用 400/401/403/404/429/503；流开始后的 provider 失败只发送不含上游正文和凭据的 `error` 事件。

生产入口由 RequestIdFilter 保证 requestId；客户端对未来旁路调用仍会在空值时生成 UUID，确保 header、请求体与响应关联一致。跨语言契约由 Codex 启动真实 uvicorn 后执行 Java HTTP 集成测试。

Java 到当前明文 uvicorn 服务固定使用 HTTP/1.1，避免 JDK HttpClient 发起 uvicorn 不支持的 h2c 升级并丢失请求体；未来启用 TLS/HTTP2 时必须另行记录架构决定并执行端到端验证。契约测试通过 Spring 测试上下文取得 Boot 自动配置的 `RestClient.Builder`，覆盖生产 JSON 序列化配置。

跨进程门禁覆盖成功响应、错误内部 token 和下游不可达三类真实传输路径；出站请求契约另用同一个 Boot builder 精确断言 null 可选字段被省略、自动生成的 requestId 是 UUID，并且 header 与 JSON body 使用同一值。Python 的内部 401 对 Core API 调用方统一表现为 503，不向外暴露服务间认证细节。

V2-01 为 JSON 与流式入口增加相同的基础观测模型：内部认证后以请求为根 Trace，在 Agent 下记录 prepare、retrieval、tool 和 generation。`requestId`、实际 `conversationId`（作为 `thread_id`）与 `projectId` 用于检索 Trace；Chat HTTP/SSE 响应不新增 Langfuse 字段。观测严格排除 message、answer、检索正文、Tool 参数和凭据，并在 SDK 故障时 fail-open，详见 `observability.md` 与 ADR-0016。
