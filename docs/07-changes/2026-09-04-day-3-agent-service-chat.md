# V1 Day 3：Agent Service Chat 与 Java-Python 契约

- 日期：2026-09-04
- 状态：Implemented（Pi 集中审查与测试通过）
- 范围：FastAPI、LangGraph Chat、Core API 代理与服务间契约

## 目标与范围

建立“认证用户 → 项目授权 → Java → Python graph → answer”链路。Python 3.12 服务提供 FastAPI 健康检查和内部 Chat API；LangGraph 使用显式 state、prepare/respond node 与 edge。Java 先通过 ProjectAccess，再携带 actor、project、conversation 与 request ID 调用 Python。

默认 responder 为 deterministic，便于无密钥联调；真实 LLM 接入不属于 Day 3。Java 设置连接/读取超时，把下游认证失败、网络错误和 5xx 归一为 503 Problem Detail。

## 非目标

不实现 RAG、Embedding、BM25/RRF、Tool Calling、持久化 conversation、Redis checkpoint、真实模型路由、Langfuse、LiteLLM、MCP 或前端页面。

## 安全与失败

- Python 内部接口使用 `AGENTFORGE_AGENT_INTERNAL_TOKEN`，仓库只提交占位符。
- Python 不连接业务数据库、不自行授权、不执行确定性写入。
- Java 是权限边界；Python 收到的 user/project 只作为 graph context。
- 对外错误不回显内部 token、下游正文或堆栈。

## 验证与回滚

测试全部由 Pi V4-pro 一次性执行：Python pytest、Java verify、敏感信息扫描与契约核验。回滚删除 agent-service 实现和 Java agent 模块，并恢复 Day 2 配置；不涉及数据库迁移。

## 实现清单

- Agent Service：`pyproject.toml`、配置、camelCase DTO、内部 token 校验、FastAPI 路由、LangGraph `prepare/respond` graph 与 5 项 API 测试。
- Core API：agent API/application/infrastructure 分层、项目授权、RestClient 超时与内部 token、request ID 透传、503 Problem Detail 与 4 项服务/API 测试代码。
- 配置与文档：根环境变量示例、本地启动、测试策略、功能/API/ADR 和项目入口同步更新。
- `prompts/day3-agent-service-validation.md` 定义 Pi 的一次性 Python/Java/安全与清理验证。

## Pi 第一轮集中反馈

Pi 报告 `VALIDATION_RESULT: NEEDS_FIX`，一次性确认两项阻断：Spring Web 6.2 的 `JdkClientHttpRequestFactory` 不提供 `setConnectTimeout`；本机 Python 3.14 被不必要的 `<3.14` 上限拒绝。统一修复为用 JDK `HttpClient.Builder.connectTimeout` 构造 request factory，并将支持范围调整为 Python 3.12–3.14（`<3.15`）。其余字段契约、内部 token、request ID、错误映射、授权顺序、边界与密钥扫描均通过静态核验。

Pi 第二轮复验中 Python 5 项全部通过；Java 在 testCompile 阶段发现跨包测试无法引用包私有 `SecurityProblemWriter`，同时指出错误内部 token 分支缺少测试。集中修复为公开该 Spring 安全响应组件的类型（方法仍保持包内使用），并补充错误 token 返回 401 的 Python 用例。

## 最终验证结果

- 原始 Day 3 验证报告为 `docs/08-reviews/2026-09-04-review-v1-day-3-validation-3.md`；提交后 Attempt 1 的最终整批复验报告为 `docs/08-reviews/2026-09-04-review-day3-attempt1-batch-revalidation.md`，Pi DeepSeek V4-pro 给出 `VALIDATION_RESULT: PASS`。
- Python 3.14.3：pytest 6 项全部通过，0 失败、0 跳过。
- Core API：51 项，0 失败、0 错误、0 跳过；其中真实 Java→uvicorn HTTP 契约 1 项通过，PostgreSQL 17 Testcontainers 2 项通过。
- Docker daemon 29.5.3、Java/Python JSON、内部 token、request ID、授权先于下游、400/401/403/404/503、V1 边界和敏感信息扫描均通过。
- Pi 已停止 uvicorn，确认 PostgreSQL/Ryuk 容器释放，并清理 `.venv`、Python/pytest cache 与临时测试目录；Git 状态未被测试污染。

## 已知限制与后续

Day 3 仍使用 deterministic responder，不包含 RAG、真实 LLM 或会话持久化，这些边界保持不变。此前缺失的真实跨进程联调、Boot RestClient builder、requestId 旁路兜底及 Docker/PostgreSQL 实测均已在 Attempt 1 整批修复中关闭。

## 提交后 Pi Attempt 1 集中反馈

Pi 一次性列出三项：缺少 Java 调用真实 uvicorn 的跨进程契约测试、RestClient 未复用 Spring Boot builder、requestId 对旁路调用缺少兜底。三项统一采纳：增加由环境变量显式开启的 Java HTTP 契约测试与 Pi 启停 uvicorn 的门禁；配置改为注入 `RestClient.Builder`；客户端为空 requestId 时生成 UUID 并在 header/body 中使用同一值。

## Attempt 1 整批修复验证口径

用户已确认本机 Docker daemon 可用。本轮 Pi V4-pro 复验必须一次性完成 Python pytest、真实 uvicorn 的 Java HTTP 契约测试、Core API `verify` 和 PostgreSQL/Testcontainers 集成测试。`PersistenceIntegrationTest` 的两项数据库测试及 HTTP 契约测试均不得跳过；若 Docker 不可用或任一门禁未实际执行，本轮结果必须为 `NEEDS_FIX`。Pi 还需停止本轮启动的 uvicorn、确认 Testcontainers 资源已释放，并清理 Python 隔离环境与缓存。

Pi 首次执行上述强门禁后确认 Docker 与两项 PostgreSQL 测试通过，但真实 HTTP 契约暴露出 JDK HttpClient 对明文 uvicorn 发起 h2c upgrade 时请求体为空，另确认契约测试使用裸 `RestClient.builder()`，没有覆盖 Boot 的生产序列化配置。两项作为同一整批继续修复：客户端显式固定 HTTP/1.1；契约测试以最小 Spring 上下文加载 Jackson、HTTP message converter 与 RestClient 自动配置，并注入实际配置生成的客户端。修复后由 Pi 再次统一执行原完整门禁。
