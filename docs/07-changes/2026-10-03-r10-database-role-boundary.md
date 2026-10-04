# R10 PostgreSQL 服务身份最小权限隔离

- 日期：2026-10-03
- 状态：Completed
- 风险：L3（数据库权限、安全边界、部署迁移）
- 影响域：PostgreSQL、Core API、Agent Service、Compose、部署与 Docs

## 问题确认

深层审查 R10 成立。当前生产与本地 Compose 把 `POSTGRES_USER` 初始化管理员及其密码同时交给 Java 和 Python；因此 Python 的“只写派生索引/checkpoint”仅是代码约定，不是数据库能力边界。

## 目标与边界

- 保留 `POSTGRES_USER` 作为初始化/迁移管理员，只交给 PostgreSQL 和 Core 的 Flyway，不作为 Core 运行 datasource 或 Python DSN。
- 固定运行角色为 `agentforge_core` 与 `agentforge_agent`，密码分别由 `AGENTFORGE_CORE_DB_PASSWORD`、`AGENTFORGE_AGENT_DB_PASSWORD` 注入。
- 可重复角色引导在 Core 启动前创建/更新两个 LOGIN 角色，并让 Core 对现有及后续 `public` 业务表拥有运行期 DML；不删除卷、不重建数据库。
- 新 Flyway 迁移收窄公共 schema，并只给 Python `rag_chunk` DML、`agent_checkpoint` schema 使用/建表及既有 checkpoint 对象权限。Python 不得更新 `app_user`、`task_item`、`agent_task_action` 或审计事实。
- 既有持久卷通过同一幂等引导与迁移升级；不修改已发布迁移。

## 约定测试 seam

公共 seam 为生产 Compose 渲染及真实 PostgreSQL 角色能力：Core 角色可读写业务表；Agent 角色可同步 `rag_chunk`、在 `agent_checkpoint` 建表并读写，但更新三个代表性业务表均被 PostgreSQL 拒绝。配置测试同时证明两个应用不再接收管理员密码。

## 受影响文档

- `docs/02-architecture/decisions/ADR-0040-database-service-role-separation.md`
- `docs/02-architecture/data-architecture.md`
- `docs/02-architecture/system-overview.md`
- `docs/05-development/local-development.md`
- `docs/06-operations/production-single-host.md`

## 验证计划

先建立会因当前单身份 Compose 失败的配置/真实角色门禁，再实现引导、迁移和配置。运行真实 PostgreSQL 权限 smoke、Compose/部署/TLS 配置回归、Core clean verify、Agent 数据库相关测试与本批 Pi Milestone Review。

## 验证回填

- TDD 红灯：`pytest -q tests/test_database_role_boundary.py` 的 local/prod 两个参数均因缺少 `database-roles` 引导服务失败；实现后 2/2 通过。
- 真实 PostgreSQL 权限 smoke：`database-role-boundary.ps1` 通过。Core 角色可执行代表性业务 DML；Agent 角色可读写 `rag_chunk`、在 `agent_checkpoint` 建表和读写；Agent 对 `app_user`、`task_item`、`agent_task_action` 的更新及在 `public` 建表均被拒绝；临时容器已清理。
- 人工影响审查补充了既有角色升级场景：先预置两个带 `BYPASSRLS` 的固定服务角色，旧引导产生“service role is privileged”红灯；引导显式设置 `NOBYPASSRLS` 后同一 smoke 转绿，并拒绝不安全的数据库标识符。
- Pi 处置后的 Core 全量 `clean verify` 通过：238 tests，0 failures/errors，12 skipped；Flyway 在真实 pgvector Testcontainers 上从空库应用 V1–V16。
- Agent 全量在工作树 `PYTHONPATH` 且仅对测试进程设置 `NO_PROXY=127.0.0.1,localhost` 后通过：222 passed、4 warnings。首次未设置 `NO_PROXY` 时，宿主 `HTTP_PROXY/HTTPS_PROXY` 误代理 LiteLLM 的本机回环测试，得到 221 passed、1 failed；单测复现并确认环境根因后未改产品代码。
- local/prod Compose 渲染、生产配置验证、PowerShell/ Bash 解析、`tls-public-host-nginx.ps1` 与 WSL `tls-public-host-contract.sh` 均通过；Java↔Python 跨进程门禁 13/13 通过。临时 `.venv` Junction 经 LinkType 核验后仅删除链接，原目标虚拟环境仍存在。
- `plan-change-gates.ps1` 判定本批为 L3 / Milestone Review；`git diff --check` 通过。
- Pi Attempt 1：`NEEDS_FIX`。F1 的真实问题是分离凭据启动证据缺口；新增 Spring Boot 3.5.16 集成测试证明无需 `spring.flyway.url` 也会以管理员执行 V1–V16，而运行连接为 `agentforge_core`、无 schema CREATE、保有业务 DML。F2 的既有 checkpoint owner 风险成立，先取得 `must be owner of table checkpoints` 红灯，再由 V16 转移 schema/表/序列所有权并转绿。F3 由专用分离身份门禁覆盖；F4 为无失败复现的低风险容量建议，不扩项。真实阻断项已修复，进入 Attempt 2。
- Pi Attempt 2：`PASS`，确认 F1/F2 已关闭且没有新阻断项。S1–S3 均为无当前缺陷复现的 Low 建议：不扩大 Agent 数据库能力，不弱化 V16 最低基线守卫，不为未复现的 ASR 超时扩项；不触发 Attempt 3。

## 风险与回滚

最大风险是既有卷尚未创建运行角色便切换应用凭据，导致服务启动失败。部署顺序必须先启动 PostgreSQL、完成幂等角色引导，再启动以管理员身份执行 Flyway 的 Core，最后启动 Agent。回滚保留新增角色和 grants（无业务数据破坏），恢复上一版本应用配置；禁止删卷。
