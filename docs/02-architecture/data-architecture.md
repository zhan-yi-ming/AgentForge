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

Flyway V3 迁移启用 `vector` 扩展并创建 `rag_chunk`。该表可从 Wiki/Task 重建，不是业务事实。

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

## 隔离与并发

- Project owner 是权限事实；API 不能用客户端传入的 ownerId 代替认证身份。
- Wiki / Task 具体资源查询必须同时包含 `project_id` 和资源 ID，防止路径与资源错配。
- 更新和删除要求客户端提交当前 `version`。版本不一致返回 409，禁止 last-write-wins 静默覆盖。
- 数据库唯一 / 检查 / 外键约束提供并发下最终保护，应用层校验提供可读错误。
- RAG 查询和清理都必须带 `project_id`；即使来源 ID 猜测成功，也不能跨项目读取 Chunk。

## 迁移规则

- 迁移文件放在 `services/core-api/src/main/resources/db/migration/`。
- 已经进入共享分支或被其他环境执行的版本化迁移不得修改；通过新迁移修正。
- JPA 使用 `ddl-auto=validate`，启动时验证 Entity 与迁移结果一致。
- 迁移失败先查看 Flyway schema history 和数据库日志，不允许临时切回 `update` 绕过。

## 为什么使用 Flyway

数据库变化需要与代码一样可审阅、可排序、可重复执行。Flyway 会维护 schema history，并把迁移到目标版本作为显式操作；这与本项目文档和 Git 的证据链一致。[Flyway repository](https://github.com/flyway/flyway)
