All validation steps are complete. Here is my final report.

---

```
VALIDATION_RESULT: NEEDS_FIX
```

## 执行摘要

模型：`deepseek/deepseek-v4-pro`（本会话）。Docker、Python、Java 工具链均满足前置条件。

### 1. Docker 可用性 — PASS
- `docker info` 退出码 `0`。Client/Server 版本 **29.5.3**（Docker Desktop, WSL2, x86_64）。Compose v5.1.4。

### 2. Python 测试 — PASS
- `python --version` = **3.14.3**（满足 3.12–3.14）。
- `python -m venv .venv`（退出码 0）→ `python -m pip install -e ".[test]"`（退出码 0）。
- `python -m pytest -q`（退出码 0）：**12 passed, 0 failed, 0 errors, 0 skipped**（含 `pgvector/pgvector:pg17` 真实 Testcontainers 集成测试 `test_rag_store_integration.py`）。

### 3. Java 旧契约测试 — PASS
- 以 `AGENTFORGE_AGENT_RAG_ENABLED=false` 在 `127.0.0.1:18000` 启动 uvicorn（健康检查 `{"status":"UP",...}`），并设置两个测试内部 token 与测试 DSN 占位值。
- `AGENTFORGE_AGENT_CONTRACT_TEST=true` 下执行 `.\mvnw.cmd verify`（退出码 0）：**BUILD SUCCESS，Tests run: 60, Failures: 0, Errors: 0, Skipped: 0**。
  - `AgentServiceHttpContractIntegrationTest`：**4** 项，0 跳过。
  - `PersistenceIntegrationTest`：**2** 项，0 跳过。
- Flyway 核验（真实库）：`V1/V2/V3` 全部 `success=t`，`pg_extension.vector=1`，`rag_chunk.embedding` 类型为 **`vector(384)`**。

### 4. Java 单测/Web 覆盖 — PASS（只读核验）
已逐项映射到测试：内部来源缺失/错误 token→401（`RagSourcesApiTest`）、Bearer 不能替代内部 token（同）、用户存在（`RagSourceServiceTest.listHidesUnknownInternalActor...`）、owner/admin（`ProjectServiceTest.getProjectRejectsCrossUserButAllowsAdmin` + `AgentChatServiceTest.chatForwardsAdministratorFlagToPython`）、跨用户拒绝（同 ProjectServiceTest）、授权先于 Wiki/Task 读取（`RagSourceServiceTest` 的 InOrder + `verifyNoInteractions`）、来源 DTO（`listAuthorizesBeforeReadingAndMapsWikiAndTask`）、`actorAdmin` 出站字段（`AgentChatServiceTest` + 契约测试断言 `actorAdmin=false`）、公共 Chat `sources` 透传（`AgentChatApiTest`）。

### 5. 真实 Day 4 闭环 — PASS
- 独立 Compose project `af-val-day4`（`pgvector/pgvector:pg17`，端口 15432，健康）＋ Core API（18080）＋ RAG-enabled Agent Service（18000）。
- 公共 HTTP：注册用户→创建项目→创建 Wiki/Task→Chat。
  - Wiki 查询 `"durable storage postgresql embeddings"` → 命中 **WIKI** 来源；Task 查询 `"hybrid retrieval bm25 vector fusion"` → 命中 **TASK** 来源。
  - 响应含 `conversationId / answer / requestId / sources`（全部验证）。
  - `rag_chunk` 已写入（2 行：WIKI+Task）。

### 6. 隔离/更新/删除闭环 — PASS
- 第二用户＋第二项目＋唯一内容；项目一查询项目二内容返回 **"No relevant project context"**（空 sources）；DB 查询项目一 `foreign_chunks=0`。
- 更新 Wiki（version 0→1）后再 Chat：`rag_chunk` 仅存 `source_version=1`，旧版本被替换。
- 删除 Task（204）后再 Chat：对应 Chunk 被清除，仅剩 WIKI。

### 7. 算法与边界（只读）— PASS
Markdown/Task Chunk 稳定（`chunk_source`）；Embedding 固定 384 维（`HashEmbeddingProvider(384)` + `Literal[384]`）；BM25 仅 `score > 0` 入榜；向量查询带 `project_id`；RRF 只按排名（id 列表）融合、不比较异构分数；Context 字符预算与来源去重（`_build_context`/`_deduplicate_sources`）；Python 生产代码仅访问 `rag_chunk`（grep 确认无 `app_user/project/wiki_page/task_item` 访问）。

### 8. 错误与契约（只读＋实测）— 发现 1 项问题
- token：Python→Java `X-AgentForge-Core-Internal-Token`；Java→Python `X-AgentForge-Internal-Token`（两 token 独立）— PASS。
- request ID 跨两跳一致：实测 `X-Request-Id: closed-loop-req-001` → 响应头与 body `requestId` 一致 — PASS。
- 失败归一化实测：Agent Service 宕机→**503**（problem+json，无泄漏）；RAG DB（DSN）宕机→**503**；Embedding provider 宕机→**503**（均不泄漏 token/DSN/API key）。Core 回调失败与 provider 失败路径经代码核验同为 `RagDependencyError→503`。

### ❌ 发现的问题（按严重度排序，共 1 项）

1. **（中）共享 PostgreSQL 宕机时公共 Chat 返回 500 而非 503，且不是 problem+json**
   - 复现：`docker stop af-val-day4-postgres-1` 后调用 `POST /api/v1/projects/{id}/agent/chat`，返回 **HTTP 500**，body 为 Spring 默认错误格式 `{"timestamp":...,"status":500,"error":"Internal Server Error","path":"..."}`，而非契约要求的 503 + `application/problem+json`（含 `requestId`）。
   - 根因：`ProjectAccess.requireAccess → JPA findProject` 在 DB 不可用时抛出 `CannotCreateTransactionException`/`DataAccessException`，`ApiExceptionHandler` 未将其映射为 `ServiceUnavailableException(503)`，落入 `BasicErrorController`。
   - 影响：与 `docs/07-changes/2026-09-04-day-4-hybrid-rag.md` 中“PostgreSQL 不可用 → 归一为 503”的声明，以及 `core-api.md` 的“错误统一 problem+json”契约不符（好在未泄漏 token/DSN/API key，`X-Request-Id` 头仍存在）。
   - 建议（由 Codex 修改，本次未改）：在 `ApiExceptionHandler` 增加对 `DataAccessException`/`CannotCreateTransactionException`/`TransactionSystemException` 的统一处理，映射为 503 problem+json（type=`service-unavailable`），并补一条 DB 宕机→503 的集成/Web 测试。

### 9. `git diff --check` 与敏感信息扫描 — PASS
- `git diff --check` 退出码 0（无空白错误）。
- 密钥扫描：diff（含未跟踪源码）中无真实私钥/Bearer/JWT/生产连接串；命中项均为测试占位值（`test-only-internal-token`、`test-only-core-token`、`MDEy...`）与文档说明文字。`.env`（含本地随机值）已被 `.gitignore` 忽略，不在 diff 中。
- 文档一致性：`.env.example`、`application.yml`、`config.py`、API/数据/本地教程/ADR/迁移字段一致。未引入 Tool Calling、HITL、Redis 状态、Langfuse、GraphRAG、LiteLLM、MCP（grep 无命中；compose 中的 `redis` 仅为预留 infra，无代码引用）。

### 10. 清理 — PASS
- 按精确 PID 停止本轮进程：Core API `6032`、Agent Service 父进程 `12068`/`5852`（及契约轮 `24328`/`31204`、中间轮 `7116`/`23716`、`35196`）；端口 18000/18080 已释放。
- `docker compose -p af-val-day4 down -v`：容器/卷 `af-val-day4_af-val-day4-pgdata`/网络全部移除（`EXIT 0`）。
- Testcontainers：无残留容器（Ryuk 已回收）；现存 `agentforge-postgres-1`（`postgres:17-alpine`）为本轮之前已运行容器，未触碰。
- 已删除：`.venv`、`.pytest_cache`、`src/agentforge_agent_service.egg-info`、`__pycache__`、临时目录 `af-val-day4/` 及 `af-*.log/.pid/state` 等本轮临时文件。
- 最终 `git status` 与开始时一致（未新增/删除任何文件）；Maven `target/` 保留且被 Git 忽略。

### 未运行项说明
- 无。全部 10 项均已执行；未跳过任何容器/契约/闭环测试。
