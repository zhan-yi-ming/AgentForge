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

- Pi DeepSeek V4-pro 报告 `VALIDATION_RESULT: PASS`：`docs/08-reviews/2026-09-04-review-v1-day-3-validation-3.md`。
- Python 3.14.3：pytest 6 项全部通过，0 失败、0 跳过。
- Core API：50 项，0 失败、0 错误；2 项 PostgreSQL Testcontainers 因本机 Docker 不可用跳过。
- Java/Python JSON、内部 token、request ID、授权先于下游、400/401/403/404/503、V1 边界和敏感信息扫描均通过。
- Pi 已清理 `.venv`、Python/pytest cache 与临时测试目录，Git 状态未被测试污染。

## 已知限制与后续

Day 3 没有启动真实 Python 进程做 Java HTTP 往返；当前由双方 DTO、Python API 测试和 Java service/API 测试保证契约。真实跨进程联调可在 Day 4 RAG 链路前作为批次门禁补充。RestClient 使用独立 Jackson 默认值，但 Python 接受 null conversationId；生产 requestId 由过滤器保证非空。
