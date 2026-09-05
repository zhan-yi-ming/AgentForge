# Day 4 Hybrid RAG 集中验证

请一次性审查当前完整 Day 4 工作区并执行下列验证。禁止修改源码、文档、配置或 Git 状态；所有问题在一份报告中集中列出，最多十项并按严重度排序。

1. 在仓库根运行 `docker info`，必须成功；记录 Docker 版本。Docker 不可用时结果为 `NEEDS_FIX`，不得跳过容器测试。
2. 在 `services/agent-service` 确认 Python 为 3.12–3.14，创建 `.venv`，执行 `python -m pip install -e ".[test]"`，再执行 `python -m pytest -q`。必须覆盖普通 API/单元测试和 `pgvector/pgvector:pg17` 的真实索引集成测试，0 失败、0 错误、0 跳过。
3. 为旧 Java→Python HTTP 契约先以 `AGENTFORGE_AGENT_RAG_ENABLED=false` 在 `127.0.0.1:18000` 启动 uvicorn；同时设置两个测试内部 token 和测试 DSN 占位值。设置 `AGENTFORGE_AGENT_CONTRACT_TEST=true` 后在 `services/core-api` 执行 `mvnw.cmd verify`。`AgentServiceHttpContractIntegrationTest` 至少 4 项、`PersistenceIntegrationTest` 至少 2 项且均 0 跳过；Flyway 必须启用 vector 扩展并创建 384 维 `rag_chunk`。
4. 检查 Java 单元/Web 测试实际覆盖：内部来源缺失/错误 token 401、Bearer 不能替代内部 token、用户存在、owner/admin、跨用户拒绝、授权先于 Wiki/Task 读取、来源 DTO、`actorAdmin` 出站字段，以及公共 Chat `sources` 透传。
5. 执行真实 Day 4 闭环：用独立 Compose project 和空闲端口启动 `pgvector/pgvector:pg17`；以匹配的测试环境变量在隐藏进程启动 Core API（例如 18080）和 RAG enabled 的 Agent Service（18000）。通过公共 HTTP 注册用户、创建项目、Wiki、Task，再调用 Chat。断言 Wiki 查询命中 Wiki、Task 查询命中 Task、响应含 `conversationId/answer/requestId/sources`，并确认 `rag_chunk` 已写入。
6. 在同一闭环创建第二用户和第二项目以及唯一内容，证明第一项目响应和数据库查询都不包含第二项目来源。更新一个 Wiki 后再次 Chat，证明旧 `source_version` 被替换；删除一个来源后再次 Chat，证明对应 Chunk 被清除。无匹配查询不得伪造其他项目来源。
7. 只读核验算法与边界：Markdown/Task Chunk 稳定；Embedding 固定 384 维；BM25 只保留正分；向量查询带 `project_id`；RRF 不直接比较异构分数；Context 字符预算和来源去重生效；Python 不查询或修改 `app_user/project/wiki_page/task_item`。
8. 只读核验错误与契约：Python→Java 使用 `X-AgentForge-Core-Internal-Token`，Java→Python 使用不同的 `X-AgentForge-Internal-Token`；request ID 跨两跳一致；Core/数据库/provider 失败对公共调用方归一为 503，不泄漏 token、DSN、API key 或下游正文。
9. 执行 `git diff --check`，扫描当前差异中的真实密钥、私钥、Bearer/JWT、生产连接串；测试占位值不得误报。核对 `.env.example`、本地教程、API、数据文档、ADR、迁移和实际字段一致，且未引入 Tool Calling、HITL、Redis 状态、Langfuse、GraphRAG、LiteLLM 或 MCP。
10. 在 `finally` 中按精确 PID 停止本轮 Core/uvicorn，删除只由本轮创建的隔离 Compose project 及卷，确认 Testcontainers 资源释放；删除 `.venv`、Python/pytest cache、`*.egg-info` 和临时测试目录。Maven `target` 可保留且被 Git 忽略。记录全部清理结果和最终 Git 状态。

输出首行必须精确为 `VALIDATION_RESULT: PASS` 或 `VALIDATION_RESULT: NEEDS_FIX`。报告必须列出每条实际命令、退出码、测试数量、跳过数、跨进程断言、容器/进程清理和任何未运行项；任何要求的测试或闭环未执行都不得判定通过。
