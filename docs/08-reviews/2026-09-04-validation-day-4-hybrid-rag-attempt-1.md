I have completed the full validation. Here is my report.

---

**VALIDATION_RESULT: NEEDS_FIX**

## 执行记录（命令 / 退出码 / 测试数量 / 跳过）

| # | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 1 | `docker info` | 0 | Client 29.5.3 / Server 29.5.3, Docker Desktop, x86_64, 12 CPU, 7.64 GiB |
| 2 | `python --version`（agent-service） | 0 | Python 3.14.3（符合 3.12–3.14） |
| 3 | `python -m venv .venv` | 0 | 创建成功 |
| 4 | `.venv\Scripts\python.exe -m pip install -e ".[test]"` | 0 | 成功（pgvector 0.5.0 / psycopg 3.3.5 / testcontainers 4.15.0） |
| 5 | `.venv\Scripts\python.exe -m pytest -q` | **1** | **12 项：1 failed, 11 passed, 0 skipped**（详见问题 1） |
| 6 | uvicorn（`AGENTFORGE_AGENT_RAG_ENABLED=false`，127.0.0.1:18000） | 0 | 健康检查 UP |
| 7 | `mvnw.cmd verify`（`AGENTFORGE_AGENT_CONTRACT_TEST=true`） | 0 | BUILD SUCCESS；**59 项：0 failed / 0 error / 0 skipped** |
| 8 | 契约测试明细 | — | `AgentServiceHttpContractIntegrationTest` **4 项 0 跳过**；`PersistenceIntegrationTest` **2 项 0 跳过** |
| 9 | 真实闭环：注册/项目/Wiki/Task 创建 | 0 | 全部成功；`rag_chunk` 已写入 2 条，`vector_dims=384` |
| 10 | 真实闭环：公共 Chat（Wiki/Task 查询） | — | **503 Service Unavailable**（详见问题 2，闭环无法完成） |
| 11 | `git diff --check` | 0 | 无空白错误 |
| 12 | 敏感信息扫描（diff 新增行 + 新文件） | 0 | 无真实密钥/私钥/Bearer/生产连接串，测试占位值未误报 |

**Flyway 核验**：`flyway_schema_history` success=3；`pg_extension` 含 `vector`；`rag_chunk.embedding` 为 `USER-DEFINED/vector`，实测 `vector_dims=384`（2 条数据）。✅

**Java 单测/Web 覆盖核验（步骤 4）**：内部来源缺失/错误 token→401、Bearer 不能替代内部 token（`RagSourcesApiTest`）、用户存在、owner/admin、跨用户拒绝、授权先于 Wiki/Task 读取、来源 DTO（`RagSourceServiceTest`/`ProjectServiceTest`/`ResourceApiTest`）、`actorAdmin` 出站（false 侧）、公共 Chat `sources` 透传（`AgentChatApiTest`）均已覆盖；**缺口**：无 `actorAdmin=true` 出站的直接断言（见问题 3）。

**只读算法/契约核验（步骤 7/8）**：Chunk 稳定、Embedding 默认 384、BM25 仅正分、向量查询带 `project_id`、RRF 基于排名不比较异构分数、Context 预算+来源去重、Python 运行时只访问 `rag_chunk`（不碰 `app_user/project/wiki_page/task_item`）；Python→Java 用 `X-AgentForge-Core-Internal-Token`、Java→Python 用 `X-AgentForge-Internal-Token`（两个不同 token）、request ID 两跳一致、失败归一为 503 且不泄漏敏感信息——均符合。✅

**文档一致性（步骤 9）**：`.env.example`/本地教程/API 文档/数据文档/ADR-0010/迁移与实际字段一致；未引入 Tool Calling、HITL、Redis 状态、Langfuse、GraphRAG、LiteLLM 或 MCP（`compose.yaml` 的 redis 为 Day 1 预留、本次未改动）。✅

---

## 可确认问题（按严重度排序）

1. **[严重] 向量检索 SQL 类型不匹配，RAG 运行时整体失效。** `services/agent-service/src/agentforge_agent/rag_store.py` 的 `search()` 将 Python `list[float]` 直接传入 `embedding <=> %s`。pgvector-python 的 `register_vector` 只为 `Vector` 与 `numpy.ndarray` 注册 dumper，普通 list 被序列化为 `double precision[]`，PostgreSQL 报 `UndefinedFunction: operator does not exist: vector <=> double precision[]`。`synchronize` 的 INSERT 仅因赋值隐式转换侥幸成功（数据 384 维正确写入），但 `<=>` 算子解析失败，`search()` 在 BM25 之前抛 `RagDependencyError` → 每个 RAG enabled 的 Chat 都归一为 503。**建议**：对 `query_embedding`（及 INSERT 中的 embedding）用 `pgvector.psycopg.Vector(...)` 包装或在 SQL 中 `%s::vector` 显式转换，并让集成测试真正走到 `search()`。

2. **[严重] Python 集成测试本身失败，且未真正覆盖向量检索路径。** `services/agent-service/tests/test_rag_store_integration.py` 第 54 行调用 `connection.executemany(...)`，psycopg3 的 `Connection` 没有 `executemany`（应为 `cursor.executemany`），导致 `AttributeError`——`pytest -q` 结果为 **1 failed, 11 passed**，不满足“0 失败/0 错误/0 跳过”。更严重的是它失败在 setup 阶段，从未执行到 `synchronize/search`，因此既未验证 pgvector 索引，也漏掉了问题 1 的运行时 Bug。**建议**：改用游标 `executemany`，并补充对 `store.search` 向量召回结果的断言。

3. **[低] `actorAdmin=true` 出站字段缺少直接测试覆盖。** 现有断言只覆盖 `actorAdmin=false` 的透传（`AgentChatServiceTest` 与契约测试 body 校验），没有测试 ADMIN actor 将 `actorAdmin=true` 转发到 Python 的服务层或契约层。

4. **[低/提示] `embedding_dimensions` 可配置为 32–3072 而 schema 固定 `VECTOR(384)`。** 若配置非 384 的 openai provider，向量写入/查询会静默失败。默认与 `.env.example` 均为 384，当前非阻断，但属潜在契约风险，建议在 Settings 校验中限定为 384 或在文档中显式禁止。

5. **[低/提示] 工作区存在非交付的未跟踪文件。** `AgentForge_产品规划与三阶段迭代路线.docx` 及 Word 锁文件 `~$entForge_...docx` 位于仓库根且未被 `.gitignore` 忽略，非 Day 4 交付物，属工作区卫生问题。

---

## 未执行项（因问题 1/2 阻断）

步骤 5–6 的真实闭环断言**未能完成**：公共 Chat 因向量检索 Bug 恒定返回 503，故“Wiki 查询命中 Wiki / Task 查询命中 Task / 响应含 conversationId·answer·requestId·sources”、第二用户/第二项目隔离证明、Wiki 更新后旧 `source_version` 替换、来源删除后 Chunk 清除、无匹配不伪造来源等跨进程断言均未达成。已通过直接回调与数据库查询部分验证了 `rag_chunk` 写入、384 维与迁移正确性，但端到端闭环未通过，按验证口径不得判定 PASS。

---

## 清理结果（步骤 10）

- 按精确 PID 停止：Core API（15176）、Agent Service（31368/28264），停止后 18000/18080 无监听。
- 隔离 Compose project `day4val` 的容器、卷 `day4val_day4val-pgdata`、网络已 `down -v` 全部移除。
- Testcontainers ryuk/临时容器已自动释放，无残留。
- 已删除 `.venv`、`.pytest_cache`、`src/**/__pycache__`、`tests/__pycache__`、临时脚本/日志/PID/state/compose 文件（`%TEMP%`）；无 `*.egg-info` 残留。
- Maven `target/` 保留（被 `.gitignore` 的 `**/target/` 覆盖）。
- 最终 `git status --short` 与验证开始时完全一致（本验证未改动源码/文档/Git 状态）；预存容器 `agentforge-postgres-1` 未被触碰。

结论：Day 4 交付存在 1 个运行时阻断 Bug 与 1 个测试自身 Bug，Python 测试未达 0 失败、真实闭环未通过，判定 **NEEDS_FIX**。
