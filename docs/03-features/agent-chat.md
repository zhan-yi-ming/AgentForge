# Agent Chat

- 状态：Implemented
- 阶段：V1 Day 3

认证用户在项目内提交 message 与可选 conversationId。Core API 校验 owner/admin 权限并调用 Agent Service；LangGraph 把请求规范化后生成回答，Java 返回 answer、conversationId 和 requestId。

Graph 为 `START -> prepare -> respond -> END`。State 包含 project_id、user_id、message、conversation_id、request_id、normalized_message 与 answer。message 去空白后 1–8000 字符；conversationId 缺失时 Python 生成 UUID。

未认证 401；无项目权限 403；无效输入 400；Agent Service 不可用 503。不保存聊天记录，不检索 Wiki/Task，不产生 Tool 意图。

生产入口由 RequestIdFilter 保证 requestId；客户端对未来旁路调用仍会在空值时生成 UUID，确保 header、请求体与响应关联一致。跨语言契约由 Pi 启动真实 uvicorn 后执行 Java HTTP 集成测试。

Java 到当前明文 uvicorn 服务固定使用 HTTP/1.1，避免 JDK HttpClient 发起 uvicorn 不支持的 h2c 升级并丢失请求体；未来启用 TLS/HTTP2 时必须另行记录架构决定并执行端到端验证。契约测试通过 Spring 测试上下文取得 Boot 自动配置的 `RestClient.Builder`，覆盖生产 JSON 序列化配置。

跨进程门禁覆盖成功响应、错误内部 token 和下游不可达三类真实传输路径；出站请求契约另用同一个 Boot builder 精确断言 null 可选字段被省略、自动生成的 requestId 是 UUID，并且 header 与 JSON body 使用同一值。Python 的内部 401 对 Core API 调用方统一表现为 503，不向外暴露服务间认证细节。
