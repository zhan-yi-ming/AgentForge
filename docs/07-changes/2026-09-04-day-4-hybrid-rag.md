# V1 Day 4：Wiki 与 Task 混合 RAG

- 日期：2026-09-04
- 状态：Implemented
- 阶段：V1 / Day 4
- 交付目标：`origin/main`

## 背景

Day 3 已打通认证用户经 Java Core API 调用 Python LangGraph Chat 的链路，但回答仍由 deterministic responder 直接回显输入，不读取项目 Wiki 或 Task。Day 4 需要在不削弱 Java 权限边界的前提下，把项目业务对象转成可检索 Chunk，并用 Embedding 与 BM25 两路召回、RRF 融合后构建 Retrieved Context。

## 目标

- Agent Chat 在回答前同步当前项目的 Wiki 与 Task 来源，并只检索已授权项目的数据。
- Markdown Wiki 按标题和自然段切分，Task 按标题、状态、优先级和描述形成 Chunk；索引记录来源类型、来源 ID、版本和 Chunk 序号。
- 使用 384 维 Embedding 进行向量召回，使用 BM25 进行词法召回，并用 RRF 合并排名。
- Chat 响应新增结构化 `sources`，每条来源可追溯到 Wiki 或 Task；无匹配时明确返回未找到上下文，而不是伪造引用。
- 派生索引保存在 PostgreSQL `rag_chunk`；Python 不读取或修改 Wiki/Task 业务表。

## 非目标

- 不接入真实生成式 LLM；Day 4 responder 仍为确定性输出，但会展示检索摘要和来源。
- 不实现 Tool Calling、Task 写回、人工确认或 LangGraph interrupt；这些属于 Day 5。
- 不实现 Redis 会话、持久化 checkpoint、Langfuse、评测平台、GraphRAG、LiteLLM 或 MCP。
- 不实现后台队列和增量事件总线；V1 在 Chat 请求中按来源版本同步派生索引。

## 受影响文档

- `docs/01-product/roadmap.md`、`docs/01-product/product-overview.md`：把当前焦点和交付边界推进到 Day 4。
- `docs/02-architecture/system-overview.md`、`docs/02-architecture/data-architecture.md`：描述 RAG 数据流、派生索引与 pgvector。
- `docs/02-architecture/decisions/ADR-0010-day-4-rag-boundary-and-ranking.md`：记录 Java/Python 双向内部调用、索引所有权和检索策略。
- `docs/03-features/rag-retrieval.md`、`docs/03-features/agent-chat.md`：定义用户行为、来源同步、检索与引用。
- `docs/04-api/core-api.md`、`docs/04-api/agent-service.md`：定义公共 Chat 增量字段和内部来源接口。
- `docs/05-development/local-development.md`、`docs/05-development/testing-strategy.md`：补充运行配置、体验步骤和 Pi 门禁。

## 设计决定

采用 ADR-0010：Java 仍是身份、项目权限与业务事实边界；Python 使用独立 Core 内部 token 回调只读来源接口。Python 仅写可重建的 `rag_chunk` 派生索引，在每次 Chat 前按 `source_type + source_id + source_version` 对齐当前来源，删除已失效 Chunk。

默认 `hash` Embedding 是无需密钥、可重复测试的 384 维词项特征向量；配置 `openai` provider 后可调用 OpenAI-compatible Embeddings API，并仍固定输出 384 维。BM25 与向量各取候选，RRF 常数为 60，融合后最多返回 6 个 Chunk。默认模式适合本地闭环和词项相关性演示，语义改写能力受限是已知约束。

## 实现

- 数据与基础设施：`infra/compose.yaml` 切换为 `pgvector/pgvector:pg17`；Flyway `V3__add_rag_chunks.sql` 启用 vector、创建 384 维 `rag_chunk`、来源约束、项目外键和 HNSW cosine 索引。
- Core API：新增 `rag` application/api/infrastructure 包、Core 内部 token 配置和 `/internal/v1/rag/sources`；来源服务复用 UserDirectory、ProjectAccess、WikiPageService 与 TaskService。Chat 内部请求增加 `actorAdmin`，公共/内部响应增加 `sources`。
- Agent Service：新增 `chunking.py`、`embeddings.py`、`core_client.py`、`rag_store.py`、`ranking.py` 与 `retrieval.py`；LangGraph 加入 `retrieve` 节点。默认 hash provider 与可选 OpenAI-compatible provider 都固定 384 维。
- 测试代码：新增 Python RAG 单元与 pgvector Testcontainers 测试；新增 Java 来源 service/Web 测试，扩展 Chat API、真实 HTTP 契约与 Flyway/PostgreSQL 测试。
- 配置与体验：新增 Core callback、RAG DSN、provider 和第二个内部 token；本地教程更新为 Day 1–Day 4；集中验证 Prompt 为 `scripts/agent-bridge/prompts/day4-hybrid-rag-validation.md`。
- 可重复验收：新增 `scripts/validation/day4-e2e.ps1`，在隔离 pgvector 与专用端口上通过公共 HTTP 验证两项目、多来源、版本替换、删除同步和无匹配行为，并在 `finally` 中清理进程、容器、卷与临时日志。

## 验证结果

当前工作树改由 Codex 亲自执行验证，不复用旧 Pi 报告作为通过证据。验证口径沿用功能/API/测试策略文档，覆盖 Python Chunk/Embedding/BM25/RRF、来源同步与 API；Java 内部 token、项目授权、来源映射与公共响应；pgvector/Flyway/Testcontainers；真实 Core API 与 Agent Service 跨进程 RAG 闭环；敏感信息扫描和清理。

### Attempt 1：NEEDS_FIX

Pi 共执行 Python 12 项测试（11 passed、1 failed）和 Java 59 项测试（全部通过、无跳过）。Flyway V1–V3、vector 扩展、384 维列和内部接口权限测试通过，但真实 Chat 闭环返回 503，因此本轮不通过。集中修复范围如下：

- `rag_store.search()` 把 `list[float]` 直接传给 `<=>`，PostgreSQL 将其识别为 `double precision[]`；查询与写入统一显式使用 pgvector `Vector` 类型。
- pgvector 集成测试错误调用 psycopg3 `Connection.executemany()`；改由 cursor 批量写入，并明确断言向量召回结果，确保测试真正经过 `search()`。
- 增加管理员身份将 `actorAdmin=true` 传给 Python 的直接单元测试。
- 数据库列固定为 `VECTOR(384)`，因此配置层也固定 `embedding_dimensions=384`，避免允许无法写入的维度。
- 根目录两个未跟踪 Word 文件属于用户资料，不纳入 Day 4 提交；锁文件问题不改变产品实现。

该旧报告只作为问题线索保留，不能证明当前工作树通过。修复采用 `diagnosing-bugs` 与 `tdd` skills：以 `RagStore.synchronize/search` 的真实 pgvector 集成测试为紧密失败信号，先证明测试能捕获 `vector <=> double precision[]`，再应用最小修复并回归；管理员出站字段通过 HTTP adapter 对外部服务边界测试，不新增绑定内部协作者调用顺序的 mock 测试；Testcontainers 使用当前 `community.postgres` 入口避免已弃用警告。随后由 Codex 执行 Day 1–4 clean 全量验证并回填机器证据。

首次真实 E2E 还证明 `Literal[384]` 不能接受环境变量来源的字符串 `"384"`，导致 Agent Service 健康端点返回 500。配置改用 `int` 并把 `ge/le` 同时限定为 384，既保留环境变量解析，也拒绝与数据库列不一致的维度；同一 E2E 作为 red/green 回归。

### Codex 最终验证：PASS

- 工具版本：Java Temurin 21.0.12.1、Python 3.14.3、Docker Client/Server 29.5.3、Docker Compose v5.1.4。
- Red：临时恢复旧查询参数后执行 `.venv\\Scripts\\python.exe -m pytest -q tests/test_rag_store_integration.py`，退出码 1，1 failed，稳定捕获 `operator does not exist: vector <=> double precision[]`。
- Green：恢复 `Vector(query_embedding)` 后执行同一命令，退出码 0，1 passed；切换到 `testcontainers.community.postgres` 后再次执行仍为 1 passed、无弃用警告。
- Python 全量：`.venv\\Scripts\\python.exe -m pytest -q --cache-clear`，退出码 0，12 passed、0 failed、0 error、0 skipped；有 2 条来自 Starlette/AnyIO 的第三方弃用警告。
- Python 依赖：`.venv\\Scripts\\python.exe -m pip check`，退出码 0，`No broken requirements found`。
- Java 全量：启动真实 RAG-disabled uvicorn 并设置契约开关后执行 `mvnw.cmd clean verify`，退出码 0，60 tests、0 failures、0 errors、0 skipped；其中 HTTP 契约 5 项和 pgvector/Flyway 集成 2 项均真实运行，Flyway 从空库应用 V1–V3。
- 跨进程：`scripts/validation/day4-e2e.ps1`，退出码 0；Wiki/Task 各命中 1 个来源，项目一初始 2 个 Chunk，Wiki 版本替换为 1，Task 删除后 Chunk 为 0，无匹配 sources 为 0，跨项目错误归属行数为 0。
- 静态与安全：`git diff --check` 退出码 0；新增差异敏感模式扫描通过；无 `[DEBUG-*]` 调试标记；服务和基础设施中未发现 Neo4j、Langfuse、LiteLLM、GraphRAG、MCP、Tool Calling 或 LangGraph interrupt。
- Pi 停用验证：`review-loop.ps1`、`run-validation.ps1`、`run-review.ps1`、`Start-BridgeMonitor.ps1`、`bridge-monitor.ps1` 均直接返回 `DISABLED`，未调用外部模型。
- 清理：测试端口 18000/18080 listener 为 0；隔离 E2E 容器和卷均为 0；本轮临时日志为 0；`.venv`、`.pytest_cache`、`__pycache__` 与 egg-info 已删除。`target/` 是本轮 clean verify 产物并受 Git 忽略。`%TEMP%/agentforge-day4-route-review` 是 2026-09-04 已存在的产品文档读取目录，不属于本轮测试，未删除。

已知限制：默认 hash Embedding 主要提供词项相关性；OpenAI provider 因无真实 key 未做在线调用，仅验证了请求/响应适配代码与固定 384 维契约。Python 的 2 条第三方弃用警告和 Java Mockito 动态 agent 的未来兼容警告不影响本轮结果，后续依赖升级时处理。

## 风险与回滚

- Core API 或 PostgreSQL 不可用时，Agent Service 返回受控失败，Java 对外归一为 503，并用 request ID 排查。
- 索引是派生数据，回滚可删除 Day 4 Python 模块、内部来源接口与 `rag_chunk`；迁移已执行的环境通过后续迁移删除表/索引，不改写 V3 迁移历史。
- OpenAI provider 失败不得泄漏 key 或下游正文；切回默认 `hash` provider 可恢复无密钥本地运行。
