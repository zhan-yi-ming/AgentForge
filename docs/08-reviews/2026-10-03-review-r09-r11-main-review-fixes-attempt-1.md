# Pi 代码审查报告：r09-r11-main-review-fixes / Attempt 1

- 日期：2026-10-03
- 审查阶段：r09-r11-main-review-fixes
- 审查对象：INDEX@c9cf05b（基线：c9cf05b1079f72fc125c70d3af71880a1e630d71）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立审查报告

- 阶段：r09-r11-main-review-fixes
- 模式：Milestone Review
- 轮次：1 / 3
- 审查目标：INDEX@c9cf05b（工作树）
- 审查范围：R09 Entity Resolution 建议配额、R10 PostgreSQL 服务身份分离、R11 ASR 限流与前端批次
- 只读声明：本轮仅依据提供的 Git Diff、文件清单、显式上下文与 Codex 验证回填完成审查；未运行测试、未修改任何文件或 Git 状态，也不认可/改写 Codex 的测试结论。

## 一、概述与总体结论

**结论：需修复后交付（NEEDS_FIX）。**

R09 与 R11 的实现与文档、测试三者一致性良好，未发现可确认的正确性/契约缺陷。R10 的**目标方向正确、SQL 与 Compose 结构基本合理**，但该节点最关键的验收路径——**“Core 运行连接使用 `agentforge_core`、Flyway 使用初始化管理员、Agent 使用 `agentforge_agent`”在真实启动链路上没有任何端到端证据**，且迁移账号覆盖仅依赖 `spring.flyway.user/password` 而在 `application.yml` 中未提供 `spring.flyway.url`，存在被 Spring Boot 忽略、进而使 Flyway 以无 DDL 权限的 `agentforge_core` 运行并导致 Core 启动失败的实质风险。这一点对 L3 数据库安全边界属于阻断性验证缺口，因此判为 NEEDS_FIX。

- R09：PASS（实现正确，测试覆盖候选/无候选/配额耗尽/未认证四个分支）
- R10：NEEDS_FIX（核心边界未端到端验证 + 迁移凭据配置存在歧义）
- R11：PASS（批次、偶数字节、独立限流区、顺序队列均正确）

## 二、详细发现清单

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| F1 | High（必须修改） | `services/core-api/src/main/resources/application.yml`；`infra/compose.prod.yaml` / `infra/compose.yaml` | flyway 配置块（约 L18-L20）；core-api environment | R10 的“Flyway 管理员 / Core 运行角色”分离未在真实启动路径验证；仅设 `spring.flyway.user/password` 而不设 `spring.flyway.url`，Spring Boot 可能复用应用 DataSource，使迁移仍以 `agentforge_core` 执行 |
| F2 | Medium（建议修改） | `services/core-api/src/main/resources/db/migration/V16__separate_database_service_roles.sql` | 全文件 | 既有卷中 `agent_checkpoint` 对象由管理员所有，V16 只给 Agent `GRANT` 而不转移所有权；后续 langgraph 迁移若执行 DDL 会因非 owner 失败 |
| F3 | Low（建议修改） | `scripts/validation/day4-e2e.ps1` / `day5-e2e.ps1` / `v2-07-resume-e2e.ps1` / `v3-release-regression.ps1` | Core/Agent 运行环境赋值 | 这些门禁仍以管理员账号启动 Core/Agent，新加的 `AGENTFORGE_*_DB_PASSWORD` 仅用于满足 Compose 插值，未真正校验权限边界，无法发现边界回退 |
| F4 | Low（建议修改） | `infra/nginx/production.conf.template` | ASR location 块 | ASR location 未显式声明 `proxy_read_timeout`（默认 60s）与 `client_max_body_size`，`finish` 在 ASR 上游抖动时可能触发默认超时，和 Chat 路径显式预算的写法不一致 |
| F5 | Info（无需修改） | `GraphResolutionService.java`、`VoiceInput.tsx` | — | R09 配额时机、空候选短路、R11 批次/字节对齐/独立限流区经逐行核对正确，无需改动 |

## 三、逐个 Issue 展开

### F1（必须修改）迁移凭据与应用数据源分离未端到端验证，且 `spring.flyway.url` 缺失

- Severity：High
- File & Line：
  - `services/core-api/src/main/resources/application.yml`（flyway 块，diff 新增两行）
  - `infra/compose.prod.yaml` core-api environment（`AGENTFORGE_DB_USERNAME=agentforge_core` + `AGENTFORGE_DB_MIGRATION_USERNAME=${POSTGRES_USER}`）
  - `infra/compose.yaml` 同位置

Evidence：
```yaml
  flyway:
    enabled: true
    user: ${AGENTFORGE_DB_MIGRATION_USERNAME:${AGENTFORGE_DB_USERNAME:agentforge}}
    password: ${AGENTFORGE_DB_MIGRATION_PASSWORD:${AGENTFORGE_DB_PASSWORD:agentforge_local_only}}
```
```yaml
  core-api:
    environment:
      AGENTFORGE_DB_USERNAME: agentforge_core
      AGENTFORGE_DB_PASSWORD: ${AGENTFORGE_CORE_DB_PASSWORD}
      AGENTFORGE_DB_MIGRATION_USERNAME: ${POSTGRES_USER}
      AGENTFORGE_DB_MIGRATION_PASSWORD: ${POSTGRES_PASSWORD}
```

Description：
1. R10 的核心承诺是“运行时 DataSource = `agentforge_core`（无 DDL/角色管理能力），Flyway = 初始化管理员”。这要求 Spring Boot 的 Flyway 必须**另建**一个使用管理员凭据的连接。
2. Spring Boot 的 Flyway 自动配置在**未提供 `spring.flyway.url` / `@FlywayDataSource`** 时通常复用应用 DataSource；`spring.flyway.user/password` 是否覆盖该 DataSource 的凭据在 Spring Boot 版本间存在差异，属于必须实测确认的语义，而不是可以通过配置字符串断言证明的行为。
3. 若该覆盖被忽略，Flyway 将以 `agentforge_core` 连接执行迁移；而 `agentforge_core` 只有 `USAGE` on schema `public`（V16 还 `REVOKE CREATE ... FROM PUBLIC`），**没有任何表创建权限**，迁移会在第一条 DDL 即失败，Core 无法启动——这是生产阻断级后果。
4. 现有证据链未覆盖该路径：
   - `database-role-boundary.ps1`：只验证角色能力（core DML / agent 受限），不启动 Core。
   - `test_database_role_boundary.py`、`v1-1-production-config.ps1`：只做 Compose/配置字符串断言。
   - `clean verify` 的 Testcontainers 迁移使用容器超级用户单一身份，`AGENTFORGE_DB_MIGRATION_USERNAME` 未设置（回退到 `AGENTFORGE_DB_USERNAME`），**不构成分离凭据证据**。
   - Codex 回填中列出的是“Compose 渲染、生产配置验证、角色能力 smoke”，未列出以分离凭据真实启动 Core（例如 `v1-1-production-smoke.ps1`）的结果。
5. 这属于“关键测试范围不充分”，且是 R10 唯一未被证明的关键声明，因此判为必须修改。

Suggested Fix：
```yaml
  flyway:
    enabled: true
    # 显式给出迁移 URL，确保 Flyway 使用独立的管理员凭据连接，而不是复用运行 DataSource
    url: ${AGENTFORGE_DB_URL:jdbc:postgresql://localhost:5432/agentforge}
    user: ${AGENTFORGE_DB_MIGRATION_USERNAME:${AGENTFORGE_DB_USERNAME:agentforge}}
    password: ${AGENTFORGE_DB_MIGRATION_PASSWORD:${AGENTFORGE_DB_PASSWORD:agentforge_local_only}}
```
并新增一条端到端测试（Testcontainers 或 prod Compose 均可）：
- 在数据库中先创建 `agentforge_core`（仅 `USAGE` on `public` + 在测试 DDL 后 `GRANT DML`）与管理员；
- 以 `AGENTFORGE_DB_USERNAME=agentforge_core`、`AGENTFORGE_DB_MIGRATION_USERNAME=admin` 启动 Core；
- 断言 Core 健康、Flyway 迁移成功落地、运行期连接确实是 `agentforge_core`（如 `SELECT current_user`）。

### F2（建议修改）既有卷 `agent_checkpoint` 对象所有权未转移

- Severity：Medium
- File & Line：`services/core-api/src/main/resources/db/migration/V16__separate_database_service_roles.sql`

Evidence：
```sql
GRANT USAGE, CREATE ON SCHEMA agent_checkpoint TO agentforge_agent;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA agent_checkpoint TO agentforge_agent;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA agent_checkpoint TO agentforge_agent;
```

Description：
- 既有生产卷在此之前由管理员 DSN 运行 `langgraph-checkpoint-postgres` 幂等 setup，因此 `agent_checkpoint` 的 schema/表/索引归**管理员**所有，而 Agent 现在只拿到 `GRANT` 而非所有权。
- 日常读写（SELECT/INSERT/UPDATE/DELETE + `CREATE TABLE IF NOT EXISTS` 于已有 CREATE 权限的 schema）通常可用；但若 `langgraph` 的 setup 在版本升级时执行 `CREATE INDEX` / `ALTER TABLE` 等 DDL，PostgreSQL 会要求表 owner 权限，Agent 将失败。ADR-0040 明确该 schema 由 Python Agent 负责，所有权应与之对齐，而当前只靠 ACL。
- `database-role-boundary.ps1` 只测 Agent 自建表的读写与三张业务表的拒绝，未覆盖“既有管理员所有 checkpoint 表 + 真实 langgraph setup”的既有卷升级路径。

Suggested Fix（迁移内追加，注意先在既有卷上可重复执行）：
```sql
ALTER SCHEMA agent_checkpoint OWNER TO agentforge_agent;
DO $own$
DECLARE r record;
BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='agent_checkpoint' LOOP
    EXECUTE format('ALTER TABLE agent_checkpoint.%I OWNER TO agentforge_agent', r.tablename);
  END LOOP;
  FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname='agent_checkpoint' LOOP
    EXECUTE format('ALTER SEQUENCE agent_checkpoint.%I OWNER TO agentforge_agent', r.sequencename);
  END LOOP;
END
$own$;
```
并在 `database-role-boundary.ps1` 中加入“以管理员预建 checkpoint 表 → 运行 V16 → 以 Agent 执行一次幂等 setup/DDL”的断言。

### F3（建议修改）既有回归门禁仍以管理员运行 Core/Agent 运行期连接

- Severity：Low
- File & Line：`scripts/validation/day4-e2e.ps1`、`day5-e2e.ps1`、`v2-07-resume-e2e.ps1`、`v3-release-regression.ps1`

Evidence：
```powershell
$env:AGENTFORGE_DB_USERNAME = "agentforge"
$env:AGENTFORGE_DB_PASSWORD = $databasePassword
...
$env:AGENTFORGE_CORE_DB_PASSWORD = "..."
$env:AGENTFORGE_AGENT_DB_PASSWORD = "..."
```

Description：
- 这些脚本直接 `java -jar` 启动 Core，并显式用管理员账号作为运行 DataSource；新增的两个密码变量只是为了让 `docker compose up -d postgres` 通过插值校验（Compose 会解析整份文件），并不改变 Core/Agent 的运行身份。
- 结果是：若将来有人误把 Core 运行账号改回管理员（R10 想防的回退），这些门禁仍会全绿，无法守边界。

Suggested Fix：在 `database-roles` 服务可用的真实 Compose 场景下新增/改造一条门禁（可复用 `v1-1-production-smoke.ps1` 的路径），以 `AGENTFORGE_DB_USERNAME=agentforge_core` + 管理员迁移凭据启动 Core，并断言 Agent 对 `app_user`/`task_item`/`agent_task_action` 写入被数据库拒绝。

### F4（建议修改）ASR location 缺少显式超时/体积预算

- Severity：Low
- File & Line：`infra/nginx/production.conf.template` ASR location

Evidence：
```nginx
    location ~ ^/api/v1/projects/[^/]+/agent/asr/sessions(?:/[^/]+(?:/audio|/finish)?)?$ {
        limit_req zone=asr_per_ip burst=30 nodelay;
        proxy_pass http://core-api:8080;
        proxy_http_version 1.1;
        ...
    }
```

Description：该 location 未显式设置 `proxy_read_timeout` 与 `client_max_body_size`。音频块 ≤64,000 字节没有问题，但 `finish` 需要等待最终句（受 ASR 上游影响），默认 60s 可能偏紧，且 Chat 路径都显式声明了预算，此处风格/预算不一致。

Suggested Fix：显式声明 `proxy_read_timeout 120s;` 与 `client_max_body_size 128k;`，并让 `asr-nginx-rate-limit.ps1` 的模板断言同时检查这两个指令存在。

### F5（无需修改）核对确认正确的实现

- Severity：Info
- Evidence 与结论：
  - `GraphResolutionService.suggest`：`candidates` 为空时 `Advice(null,0)` 直接返回，**不消费配额、不调用 advisor**；非空时在调用 advisor 前 `aiUsageQuota.consume(actor.userId())` 恰好一次，异常直接向上抛出形成 429，未被 fail-open 吞掉。与 `docs/03-features/entity-resolution.md`、`docs/04-api/core-api.md` 描述一致。
  - `VoiceInput.flush`：`pcm16` 恒为偶数长度，`bytes.slice` 以 64,000 为步长切分（64000、16000 均为偶数），停止时先 `flush` 再 `await current.queue`，顺序保持；轮询 1,500ms、上传阈值 48,000 字节与文档一致，测试 `[48000, 64000, 16000]` 的期望可由代码精确推导。
  - Nginx：ASR 为正则 location，位于 `location /api/` 前缀之前，命中 ASR 时优先生效且使用独立 `asr_per_ip` 区（600 r/m、burst 30），不消耗普通 API 区；稳态约 1.33 req/s，远低于上限。
  - R09/R11 的 `docs/07-changes/*`、`ADR-0040`、`data-architecture.md` 与代码行为一致，无文档-实现漂移。

## 四、主开发（Codex）评估回填区

| Finding | 是否接受 | 修复方案 / 拒绝理由 | 修复证据（命令与结果） | 备注 |
| --- | --- | --- | --- | --- |
| F1 | 部分接受 | 接受 L3 分离身份缺少真实启动证据；不接受“缺少 `spring.flyway.url` 会复用运行 DataSource”的配置故障假设。新增真实 Spring Boot 3.5.16 集成测试，在不设置 Flyway URL 时同时配置受限 Core datasource 与管理员 Flyway 凭据。 | `mvnw.cmd -Dtest=DatabaseIdentitySeparationIntegrationTest test`：1/1 通过；V1–V16 由管理员完成，业务 `JdbcTemplate` 的 `current_user=agentforge_core`，业务表 owner 为管理员，Core 无 `public` CREATE 且有业务 DML。修复后 `mvnw.cmd clean verify`：238 tests，0 failures/errors，12 skipped。 | 当前版本用 `spring.flyway.user/password` 会创建独立 Flyway datasource 并从主 datasource 推导 URL；无需加入重复 URL 配置。证据缺口已关闭。 |
| F2 | 接受并修复 | V16 将 `agent_checkpoint` schema 及既有表/序列 owner 转给 `agentforge_agent`；ADR/数据架构同步明确所有权。 | 强化 smoke 先得到 `must be owner of table checkpoints` 红灯；修复后 Agent 对既有 checkpoint 表 `ALTER TABLE` 成功，业务表写入与 `public` DDL 仍被拒绝。`clean verify` 与跨进程 13/13 均通过。 | 覆盖既有卷后续 LangGraph setup DDL。 |
| F3 | 接受目标，不改历史门禁 | 历史阶段脚本继续验证各自旧契约；新增专用 `DatabaseIdentitySeparationIntegrationTest` 与 `database-role-boundary.ps1` 防止 R10 边界回退。 | 分离身份 Spring 启动 1/1、真实 PostgreSQL 权限/所有权 smoke、Core 238 tests、跨进程 13/13 均通过。 | 避免扩大修改多个既有阶段脚本的运行模型。 |
| F4 | 不接受为当前缺陷 | 未提供 `finish` 超过 Nginx 默认 60s 或 64 KB 上传触及 body 限制的复现；本批目标是解除频率限流，现有 Java/Python 超时与包大小边界未放宽。 | Web 80/80、生产构建、真实 Nginx 60 秒双会话 smoke 与 TLS/Nginx 契约均已通过。 | 记录为未来容量预算建议，不触发复审或本批扩项。 |
| F5 | 确认 | R09/R11 无需追加实现。 | 定向、全量与真实网关 smoke 均通过。 | — |

## 五、审查边界说明

- 本轮未将 R09 的顾问失败后配额已消费、R10 的 `agentforge_core` DML 范围过宽等属于产品/设计取舍的项列为阻塞；仅当具备可运行性、安全边界、契约或关键验证证据时才计入。
- 未提出任何引入 V2/V3 阶段外组件（Neo4j 扩展、Langfuse、LiteLLM、MCP 新能力）的建议；F1–F4 均为当前阶段范围内的最小修正。
- 未运行任何命令；F1 的“可能被忽略”为基于 Spring Boot Flyway 自动配置语义的风险判定，其最终结论必须以 Codex 提供的分离凭据真实启动证据为准。
