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

## Day 1 模型

### `app_user`

| 字段 | 类型 | 约束 | 含义 |
| --- | --- | --- | --- |
| `id` | UUID | 主键 | 用户标识 |
| `email` | VARCHAR(320) | 非空、唯一 | 规范化为小写的登录邮箱候选 |
| `display_name` | VARCHAR(100) | 非空 | 展示名称 |
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

## 迁移规则

- 迁移文件放在 `services/core-api/src/main/resources/db/migration/`。
- 已经进入共享分支或被其他环境执行的版本化迁移不得修改；通过新迁移修正。
- JPA 使用 `ddl-auto=validate`，启动时验证 Entity 与迁移结果一致。
- 迁移失败先查看 Flyway schema history 和数据库日志，不允许临时切回 `update` 绕过。

## 为什么使用 Flyway

数据库变化需要与代码一样可审阅、可排序、可重复执行。Flyway 会维护 schema history，并把迁移到目标版本作为显式操作；这与本项目文档和 Git 的证据链一致。[Flyway repository](https://github.com/flyway/flyway)
