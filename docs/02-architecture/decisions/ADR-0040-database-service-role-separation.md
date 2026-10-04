# ADR-0040：PostgreSQL 管理、Core 与 Agent 服务身份分离

- 状态：Accepted
- 日期：2026-10-03
- 决策者：项目维护者
- 前置：[ADR-0004](ADR-0004-versioned-database-migrations.md)、[ADR-0009](ADR-0009-java-to-python-agent-boundary.md)、[ADR-0022](ADR-0022-postgres-langgraph-action-checkpoints.md)

## 背景

PostgreSQL 官方镜像的 `POSTGRES_USER` 是初始化管理员。生产 Compose 过去把同一账号交给 Core API 与 Agent Service，使“Java 管业务事实、Python 只管理派生 RAG/checkpoint”没有数据库权限保护；Agent 进程被攻破时理论上可以改写用户、Task、Approval 与审计事实。既有服务器已有持久卷，方案不能依赖删卷或重新 initdb。

## 备选方案

1. 继续共享管理员账号，仅依赖代码约定：部署简单，但不能形成最小权限边界，拒绝。
2. 为 RAG/checkpoint 建独立数据库：隔离更强，但引入额外备份、一致性和运维负担，超出本次修复范围。
3. 在同一数据库中分离管理员、Core 运行角色与 Agent 运行角色：可兼容现有数据与备份，同时由 grants 强制既有服务边界。

## 决策

采用方案 3。`POSTGRES_USER` 仅用于容器初始化、幂等角色引导与 Flyway；Core datasource 固定使用 `agentforge_core`，Agent DSN 固定使用 `agentforge_agent`。角色引导在 Core 启动前运行，对既有角色只更新密码与权限，不删除数据。Flyway 仍由管理员执行，运行 datasource 不拥有 DDL 或角色管理能力。

Core 角色获得 `public` schema 中业务表/序列的运行期权限，并通过管理员 default privileges 接收未来 Flyway 对象的相同权限。Agent 角色只获得 `rag_chunk` 所需 DML；由于 `langgraph-checkpoint-postgres` 的幂等 setup 会独立演进 checkpoint DDL，`agentforge_agent` 必须拥有 `agent_checkpoint` schema 及其中既有表/序列，新对象也由该角色创建。`public` schema 的 PUBLIC CREATE 被撤销。角色名固定，只有密码来自环境，避免迁移 SQL 与可配置角色名产生漂移。

Core 启动门禁必须用同一真实 PostgreSQL 同时配置受限运行 datasource 与管理员 Flyway 凭据，验证迁移完成后 `current_user = agentforge_core` 且运行连接没有 `public` schema CREATE 权限；单身份 Testcontainers 或 Compose 字符串断言不能替代该证据。

## 结果

Python 无法由数据库连接修改 `app_user`、`task_item`、`agent_task_action` 或审计事实；Core 运行连接也不再是超级用户。代价是部署增加两个独立服务密码和一次幂等角色引导，Core 同时需要独立 Flyway 管理凭据。既有卷必须按“PostgreSQL → 角色引导 → Core/Flyway → Agent”的顺序升级；禁止以删除 volume 代替迁移。

## 取代关系

不取代既有 ADR；补强 ADR-0009/0010/0022 已声明的 Java/Python 数据职责边界。
