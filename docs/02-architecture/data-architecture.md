# 数据架构

- 状态：Implemented
- 当前数据库：PostgreSQL
- 迁移工具：Flyway

## 原则

- PostgreSQL 是业务事实的唯一来源。
- 数据结构只能通过版本化迁移演进，禁止依赖 Hibernate 自动修改生产 schema。
- 所有业务表使用 UUID 主键，便于跨服务生成和后续数据导入。
- 时间使用带时区语义的 UTC 时间戳，由应用和数据库明确处理。
- Day 1 的 owner 隔离是 V1 起点，后续通过 workspace/project membership 扩展。

## 用户与项目模型

### `app_user`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | 用户标识 |
| `email` | VARCHAR(320) | 非空、唯一 | 规范化为小写的登录邮箱候选 |
| `display_name` | VARCHAR(100) | 非空 | 展示名称 |
| `password_hash` | VARCHAR(255) | 可空 | `{bcrypt}` 等带算法标识的单向哈希；Day 1 遗留用户为空 |
| `role` | VARCHAR(20) | 非空、检查约束 | `USER` 或 `ADMIN`，默认 `USER` |
| `created_at` | TIMESTAMPTZ | 非空 | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 非空 | 最后更新时间 |

### `project`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | 项目标识 |
| `owner_id` | UUID | 外键、非空 | 当前项目所有者 |
| `name` | VARCHAR(120) | 非空 | 项目名称 |
| `description` | VARCHAR(2000) | 可空 | 项目说明 |
| `created_at` | TIMESTAMPTZ | 非空 | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 非空 | 最后更新时间 |

同一 owner 下项目名唯一；项目表为 `owner_id` 建索引。未来 membership 迁移需要保留 owner 语义，不能静默改变权限范围。

## Day 2 Wiki 与 Task 模型

### `wiki_page`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | Wiki Page 标识 |
| `project_id` | UUID | 外键、非空 | 所属项目 |
| `title` | VARCHAR(200) | 非空 | 页面标题，同项目唯一 |
| `content` | TEXT | 非空 | Markdown 原文，最大 100,000 字符由应用和检查约束限制 |
| `version` | BIGINT | 非空、默认 0 | 乐观锁版本 |
| `created_at` | TIMESTAMPTZ | 非空 | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 非空 | 最后更新时间 |

`(project_id, title)` 唯一；为 `(project_id, updated_at DESC)` 建列表索引。删除项目时由外键级联删除页面；当前没有删除 Project API。

### `task_item`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | Task 标识 |
| `project_id` | UUID | 外键、非空 | 所属项目 |
| `title` | VARCHAR(200) | 非空 | 标题 |
| `description` | VARCHAR(10000) | 可空 | 描述 |
| `status` | VARCHAR(20) | 非空、检查约束 | `TODO` / `IN_PROGRESS` / `DONE` |
| `priority` | VARCHAR(20) | 非空、检查约束 | `LOW` / `MEDIUM` / `HIGH` |
| `version` | BIGINT | 非空、默认 0 | 乐观锁版本 |
| `created_at` | TIMESTAMPTZ | 非空 | 创建时间 |
| `updated_at` | TIMESTAMPTZ | 非空 | 最后更新时间 |

为 `(project_id, updated_at DESC)` 建列表索引。状态和优先级同时由 Java enum 与数据库检查约束保护。

## Day 4 RAG 派生索引

Flyway V3 迁移启用 `vector` 扩展并创建 `rag_chunk`。该表可从 Wiki/Task 重建，不是业务事实。V17 新增 Core 只读的 `rag_source_generation(project_id,generation)`；Wiki/Task 提交由数据库触发器按项目推进代际，使可重复读来源集合与版本来自同一快照。V17 同时新增 `rag_project_snapshot(project_id,snapshot_version,updated_at)`，与 Chunk 在同一 Python 事务中推进，拒绝旧全量快照回退或误删；它同样是可重建派生元数据，仅向 `agentforge_agent` 授予所需 DML。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | 由来源版本和 Chunk 序号确定性生成 |
| `project_id` | UUID | 外键、非空、索引 | 项目隔离键，项目删除时级联清理 |
| `source_type` | VARCHAR(16) | `WIKI` / `TASK` | 来源类型 |
| `source_id` | UUID | 非空 | Wiki Page 或 Task ID |
| `source_version` | BIGINT | 非负 | 业务来源版本 |
| `chunk_index` | INTEGER | 非负 | 来源内稳定序号 |
| `title` | VARCHAR(200) | 非空 | 用于 Context 和引用 |
| `content` | TEXT | 非空 | 原文片段，用于 BM25 与摘录 |
| `embedding` | VECTOR(384) | 非空 | 归一化 Embedding |
| `created_at` | TIMESTAMPTZ | 非空 | 本次索引写入时间 |

唯一键为 `(source_type, source_id, source_version, chunk_index)`；查询索引覆盖 `project_id`，向量使用 cosine HNSW。Python 只访问此表，不访问业务表。来源版本变化时整组替换，来源删除时删除对应 Chunk。

## V2-06 Approval 与审计

`agent_task_action` 是 Java 管理的 Approval payload，不是 Python 可写的派生数据。V2-06 在 Day 5 表上做兼容迁移，不重建已发布数据。

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | action 标识 |
| `project_id` | UUID | 外键、非空、索引 | 项目隔离键 |
| `requested_by_user_id` | UUID | 外键、非空 | 发起者 |
| `source` | VARCHAR(16) | `CHAT` / `MCP`、非空 | 可信调用来源，由 Java 设置 |
| `conversation_id` | UUID | 条件可空 | CHAT 必填；MCP 必须为空，不伪造会话 |
| `action_type` | VARCHAR(24) | `CREATE_TASK` / `UPDATE_TASK` | 白名单 Tool |
| `task_id` | UUID | update 必填 | 更新目标 |
| `title` / `description` | VARCHAR/TEXT | 可空、应用层长度校验 | 创建参数或更新补丁 |
| `task_status` / `priority` | VARCHAR(20) | Task 枚举 | 创建参数或更新补丁 |
| `expected_task_version` | BIGINT | update 必填、非负 | 确认时的乐观锁基线 |
| `status` | VARCHAR(16) | `PENDING` / `APPROVED` / `REJECTED` / `EXECUTED` / `FAILED` | 审批与执行状态 |
| `result_task_id` | UUID | executed 后填写 | 已创建/更新 Task |
| `idempotency_key` | VARCHAR(100) | requester 范围内部分唯一 | 首次决策绑定的幂等键 |
| `proposal_idempotency_key` | VARCHAR(100) | MCP 必填、CHAT 为空；project/user/source 范围唯一 | MCP Tool Call 重试复用同一 Approval 的提案幂等键 |
| `action_workflow_id` | UUID | CHAT workflow v2 必填且唯一；v1/legacy 与 MCP 为空 | 将 Java Approval 绑定到唯一 LangGraph 等待轮次；不进入公共 API |
| `action_workflow_version` | INTEGER | 可空、允许 1 或 2 | CHAT v1 兼容无 workflow ID；新 Action 为 v2 并强制校验 workflow ID；MCP 必须为空；升级前 V2-06 Action 兼容为空 |
| `version` | BIGINT | 非空 | action 乐观锁 |
| `created_at` / `approved_at` / `decided_at` | TIMESTAMPTZ | created 非空 | 生命周期时间 |

confirm/reject 在事务内悲观锁定 action。同 key 的终态请求返回既有事实，不同 key 冲突；确认前已可见的业务前置条件冲突落为 `FAILED`，flush 期乐观锁或未知基础设施异常回滚整个事务为 `PENDING` 并保持可安全重试。`(requested_by_user_id, idempotency_key)` 部分唯一索引防止同一用户跨 Approval 误用 key。Audit 的首版公共契约不承诺同一事务内事件的独立全序查询；测试验证完整事件集合与状态机约束，不依赖相同 timestamp 下的随机 UUID 排序。

`agent_action_audit_event` 是追加式审计事实，包含 `project_id`、`approval_id`、`actor_user_id`、`action_type`、`target_id`、`event_type`、`result`、`request_id`、`idempotency_key` 和 `created_at`。外键保证 Project/User/Approval 真实存在；业务状态与对应事件在同一 Java 事务提交，不记录 token、Prompt 或密码。

## V2-07 LangGraph checkpoint（目标状态）

Flyway V8 创建独立 `agent_checkpoint` schema，并为 `agent_task_action` 增加可空的 `action_workflow_version` 兼容标记；后续迁移为 workflow v2 增加唯一 `action_workflow_id`，不回填或重写既有 v1 Action。该 schema 内部的 checkpoint migrations、checkpoints、writes 与 blobs 表由 `langgraph-checkpoint-postgres` 的幂等 setup 管理，应用代码不依赖其内部列结构。它们是 Python Agent 运行态，不是 Java 业务事实。

checkpoint state 只保存 schema version、完整 Memory Namespace、workflow ID、proposal 指纹、等待/恢复状态、action/decision/idempotency/request 元数据。workflow v2 的 ID 必须与 Java Action 唯一列一致；v1 checkpoint 仅用于已有 Action 的兼容恢复。禁止保存 JWT、内部 token、密码、完整 Prompt、回答正文和检索正文。生产与本地 Compose 由 [ADR-0040](decisions/ADR-0040-database-service-role-separation.md) 强制分离数据库身份：初始化管理员执行 Flyway；`agentforge_core` 只承担业务运行期 DML；`agentforge_agent` 只获 `rag_chunk` DML，并拥有 `agent_checkpoint` schema 及既有表/序列以执行后续幂等 setup DDL。Python 对 `app_user`、`task_item`、`agent_task_action` 与 audit 表的写入必须由 PostgreSQL 拒绝，而不是只靠代码约定。

## 隔离与并发

- Project owner 是权限事实；API 不能用客户端传入的 ownerId 代替认证身份。
- Wiki / Task 具体资源查询必须同时包含 `project_id` 和资源 ID，防止路径与资源错配。
- 更新和删除要求客户端提交当前 `version`。版本不一致返回 409，禁止 last-write-wins 静默覆盖。
- 数据库唯一 / 检查 / 外键约束提供并发下最终保护，应用层校验提供可读错误。
- RAG 查询和清理都必须带 `project_id`；即使来源 ID 猜测成功，也不能跨项目读取 Chunk。

## V2-05 持久化会话历史

### `agent_conversation`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | conversationId |
| `project_id` | UUID | 外键、非空 | 项目作用域 |
| `user_id` | UUID | 外键、非空 | 发起用户作用域 |
| `preview` | VARCHAR(240) | 非空 | 首条用户消息的受限摘要 |
| `message_count` | INTEGER | 非空、非负 | 已完成消息数 |
| `deleted_at` | TIMESTAMPTZ | 可空 | 删除展示历史的 tombstone 时间；存在时禁止列表、详情和继续写入 |
| `created_at` / `updated_at` | TIMESTAMPTZ | 非空 | 生命周期 |

列表索引为 `(project_id, user_id, updated_at DESC)`。conversation ID 一旦绑定 project/user 后不能重绑。

P3-02 删除历史时先检查同一 project/user/conversation 下没有 `PENDING`/`APPROVED` Action，然后在事务内锁定会话、清除 `agent_message`、清空 preview/message_count 并写入 `deleted_at`。终态 Action 和追加式审计事实不删除，Python checkpoint 属独立运行态，不由展示历史 HTTP 操作清理。

### `agent_message`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | 消息 ID |
| `conversation_id` | UUID | 外键、非空 | 所属会话 |
| `request_id` | VARCHAR(100) | 新记录非空；遗留记录可空 | 完成 exchange 的幂等身份，同一 request 的 USER/ASSISTANT 共用 |
| `sequence` | BIGINT | 非空、非负 | 会话内稳定顺序 |
| `role` | VARCHAR(16) | `USER` / `ASSISTANT` | 展示角色 |
| `content` | TEXT | 非空 | 完整消息正文 |
| `sources_json` | TEXT | 非空 | assistant 的公开来源 JSON；user 为 `[]` |
| `created_at` | TIMESTAMPTZ | 非空 | 创建时间 |

`(conversation_id, sequence)` 唯一；非空 requestId 还要求 `(conversation_id, request_id, role)` 唯一。一次完成 exchange 在同一事务追加 USER 与 ASSISTANT；同 requestId 重试直接复用已提交事实，不增加 message_count。错误或未收到内部流 complete 时不写半截 assistant，公共 SSE complete 只在事务提交后发送。

## 迁移规则

- 迁移文件放在 `services/core-api/src/main/resources/db/migration/`。
- 已经进入共享分支或被其他环境执行的版本化迁移不得修改；通过新迁移修正。
- 数据库服务角色由部署引导幂等创建，权限变化由新 Flyway 迁移完成；既有卷不得删除重建。
- JPA 使用 `ddl-auto=validate`，启动时验证 Entity 与迁移结果一致。
- 迁移失败先查看 Flyway schema history 和数据库日志，不允许临时切回 `update` 绕过。

## 为什么使用 Flyway

数据库变化需要与代码一样可审阅、可排序、可重复执行。Flyway 会维护 schema history，并把迁移到目标版本作为显式操作；这与本项目文档和 Git 的证据链一致。[Flyway repository](https://github.com/flyway/flyway)

## V3-04 Neo4j 派生图（Implemented）

Neo4j 保存项目图。自动抽取部分可由 PostgreSQL Wiki/Task 重建；V3-04 手工 Graph API 选择的 externalId、显示名、关系类型和多 evidence 没有完整 PostgreSQL 副本，属于必须与 PostgreSQL 规范映射一起备份的持久图事实。GraphEntity/GraphRelation/GraphEvidence/GraphProjectLock 唯一 id 约束为 schema v1；source/version 与原文证据由 Java 验证。详见 ../03-features/graph-domain-model.md、decisions/ADR-0032-neo4j-derived-graph.md 和 decisions/ADR-0041-manual-graph-disaster-recovery.md。无 PostgreSQL 业务 schema 变更。

## V3-05 图来源同步待办（已实现）

Flyway V12 建 `graph_source_sync(project_id, source_type, source_id, updated_at)`，三元组主键，`source_type` 限定 WIKI/TASK。业务 CRUD 同事务 upsert；迁移从既有 Wiki/Task 回填。行代表“需要按当前业务事实重算”，不保存旧正文或 Token。worker 锁行后处理，成功删除；失败保留，Neo4j 为可重建派生投影。详见 ../03-features/graph-extraction.md 与 decisions/ADR-0033-durable-graph-source-sync.md。

## V3-06 人工实体消歧事实（已实现）

新增 Flyway 迁移保存项目内规范实体（锚点图实体 ID、类型、canonical_name、受限 metadata、CAS version）、来源作用域的 alias/成员映射（成员图实体 ID、规范 ID、确认时成员来源类型/ID/version、规范锚点来源版本、confidence、确认人、CAS version）及追加式确认/撤销审计事件。唯一约束保证一个项目成员同一时刻最多属于一个规范实体；所有查询按 project_id 范围执行。原始 Neo4j 节点、关系与证据维持 V3-04/V3-05 结构，规范映射仅作为可撤销覆盖层。模型建议不作为事实写入；来源失效由 Java 读时重验并隐藏；锚点来源更新需人工确认触发 CAS 刷新，旧成员仍因记录的锚点版本不匹配而保持隐藏，直至逐一重确认。V13 使用 `graph_canonical_entity`、`graph_resolution_member`（含 REVERTED tombstone）与 `graph_resolution_event` 与规范实体名称/metadata 更新审计 `graph_canonical_event`；实体/成员主键均含 project_id，规范锚点/成员指向 Neo4j 稳定 ID，事件只追加。具体约束和索引见 V13 迁移，详见 ../03-features/entity-resolution.md 和 decisions/ADR-0034-reviewable-entity-resolution.md。
## V3-07 GraphRAG 只读数据路径（Implemented）

本节点不新增持久化表。Neo4j 的自动 GraphEntity/GraphRelation/GraphEvidence 是可重建投影，手工 Graph API 的不可重建选择按 ADR-0041 纳入离线灾备；PostgreSQL 的 Wiki/Task 为引用事实，V3-06 人工规范映射保存在 PostgreSQL。GraphRAG 读取时重验项目、实体来源、映射状态与 evidence 原文/版本，向 Agent Service 只返回有界 DTO。在线读取跨库无原子快照承诺；来源竞争时关系可被隐藏，不能以旧图证据作为当前事实。参见 [ADR-0035](decisions/ADR-0035-evidence-bounded-graphrag.md)。

## V3-08 Repository Context 数据路径（Implemented）

本节点不新增 PostgreSQL 表或 RAG Chunk 持久化来源类型。部署映射只存在 Agent Service 配置；Git HEAD 的 tree/blob 与提交摘要按请求读取。仓库候选的 `sourceId` 由 project UUID、HEAD SHA 和逻辑路径确定性生成，HEAD 改变即生成不同引用身份。Wiki/Task 的业务事实仍由 Java 管理；Repository Context 不参与业务写入或图 evidence 的来源重验。参见 [ADR-0036](decisions/ADR-0036-project-scoped-repository-context.md)。
