# Pi 代码审查报告：r09-r11-main-review-fixes / Attempt 2

- 日期：2026-10-04
- 审查阶段：r09-r11-main-review-fixes
- 审查对象：INDEX@c9cf05b（基线：c9cf05b1079f72fc125c70d3af71880a1e630d71）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：r09-r11-main-review-fixes / Attempt 2

- 日期：2026-10-03
- 审查阶段：r09-r11-main-review-fixes
- 模式：Milestone Review
- 轮次：2 / 3
- 审查目标：INDEX@c9cf05b（基线：c9cf05b1079f72fc125c70d3af71880a1e630d71 .. INDEX@c9cf05b）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 只读声明：本轮仅依据提供的 Git Diff、文件清单、显式上下文与 Codex 验证回填完成审查；未运行任何命令、未修改文件或 Git 状态，也不改写 Codex 的测试结论。

---

## 一、概述与总体结论

**结论：通过（PASS）。** 无“必须修改”项。

Attempt 1 的阻断项已被可运行证据关闭，R09/R11 保持正确，且修复过程未引入新的可确认缺陷：

- **F1（迁移凭据与应用数据源分离的证据缺口）已关闭。** 新增 `DatabaseIdentitySeparationIntegrationTest` 是真实 Spring Boot 上下文测试：应用 datasource 使用受限 `agentforge_core`，Flyway 仅通过 `spring.flyway.user/password` 使用管理员凭据；断言 `current_user = agentforge_core`、`flyway_schema_history` 已到 V16、`app_user` 的 owner 为管理员、运行角色无 `public` CREATE 且有业务 DML。该断言同时覆盖“迁移以管理员执行”和“运行连接为受限角色”两个方向，能真实捕获“Flyway 复用运行 datasource”的失败模式（V1 建表即因无 CREATE 而失败）。
- **F2（既有卷 checkpoint 所有权）已修复。** V16 追加 `ALTER SCHEMA agent_checkpoint OWNER TO agentforge_agent` 与表/序列 owner 转换 DO 块；`database-role-boundary.ps1` 以“管理员预建 checkpoint 表 → V16 → Agent 执行 ALTER TABLE/建表”验证，覆盖既有卷升级路径。
- **F3（边界回退门禁）已以专用门禁覆盖**，避免扩大改动历史阶段脚本的运行模型，取舍合理。
- **F4（ASR location 超时/体积）经复核为非缺陷**：默认 60s 与 1m body 对 ≤64 KB 的音频块无影响，`finish` 超时无复现证据，未放宽任何既有 Java/Python 超时与包大小边界。
- **R09 / R11** 逐行核对无回归：配额仍在“有候选”时单次消费、空候选短路；前端批次/偶数字节/顺序队列与独立 `asr_per_ip` 限流区与文档一致。

| 目标 | 结论 |
| --- | --- |
| R09 实体消歧建议纳入 AI 日配额 | PASS |
| R10 PostgreSQL 服务身份最小权限隔离 | PASS（Attempt 1 阻断项已由真实启动/权限证据关闭） |
| R11 ASR 请求频率与 Nginx 限流预算 | PASS |

---

## 二、详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Low | `services/core-api/src/main/resources/db/migration/V16__separate_database_service_roles.sql`、`infra/postgres/provision-roles.sh` | V16 owner 转换段；provision 的 `GRANT CONNECT ON DATABASE` | `ALTER SCHEMA agent_checkpoint OWNER TO agentforge_agent` 未显式授予新 owner 数据库级 `CREATE`。真实 smoke 在 pg17 上通过，此建议仅为消除对不同 PG 版本/执行者权限细节的依赖（非阻塞）。 |
| S2 | Low | `services/core-api/src/test/java/com/agentforge/core/DatabaseIdentitySeparationIntegrationTest.java` | `isEqualTo(16)` | 断言硬编码最新 Flyway 版本；后续任何新迁移都会使该测试失败，需人工同步。建议改为“迁移已应用且 ≥16 / 分离不变量成立”。 |
| S3 | Low | `infra/nginx/production.conf.template` | ASR `location` 块 | 延续 Round 1 F4：ASR location 未显式声明 `proxy_read_timeout` 与 `client_max_body_size`，与 Chat 路径显式声明预算的写法不一致。无复现证据，仅一致性/容量建议。 |

### 无需修改

| ID | 文件 | 结论 |
| --- | --- | --- |
| N1 | `GraphResolutionService.java` | 配额时机、空候选短路、advisor 不吞配额错误均正确 |
| N2 | `VoiceInput.tsx`、`voice-input.test.tsx` | 48,000 触发、≤64,000 且偶数字节切分、顺序队列、1500 ms 轮询均正确 |
| N3 | `V16`、`provision-roles.sh`、两份 compose | 管理/Core/Agent 三角色分离、DML 范围与 checkpoint 所有权符合 ADR-0040 |
| N4 | `DatabaseIdentitySeparationIntegrationTest` | 真实双身份启动证据有效，能捕获 F1 失败模式 |

---

## 三、逐个 Issue 展开

### S1（建议修改）schema owner 变更未显式授予数据库级 CREATE

- Severity：Low
- File & Line：
  - `services/core-api/src/main/resources/db/migration/V16__separate_database_service_roles.sql`（`ALTER SCHEMA agent_checkpoint OWNER TO agentforge_agent;`）
  - `infra/postgres/provision-roles.sh`（`GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO agentforge_core, agentforge_agent;`）

Evidence：
```sql
GRANT USAGE, CREATE ON SCHEMA agent_checkpoint TO agentforge_agent;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA agent_checkpoint TO agentforge_agent;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA agent_checkpoint TO agentforge_agent;

ALTER SCHEMA agent_checkpoint OWNER TO agentforge_agent;
```

Description：
- 部分 PostgreSQL 版本对 `ALTER SCHEMA ... OWNER TO` 要求**新 owner**具备数据库级 `CREATE`；当前只在 provision 中授予 `CONNECT`，V16 也只授予 schema 级 `CREATE`。
- Codex 的真实 PostgreSQL smoke（`database-role-boundary.ps1`）在同一数据库中先以管理员建 schema/表、再执行 V16，并以 Agent 成功 `ALTER TABLE`/建表，证明当前 pg17 与执行者权限组合下可行。因此这是可移植性硬化建议，不构成阻断。

Suggested Fix（可选）：
```sql
GRANT CREATE ON DATABASE "${POSTGRES_DB}" TO agentforge_agent;
```
并在 `database-role-boundary.ps1` 中保留现有“V16 → Agent 可 ALTER 既有 checkpoint 表”的断言。

### S2（建议修改）集成测试硬编码 Flyway 最新版本号

- Severity：Low
- File & Line：`services/core-api/src/test/java/com/agentforge/core/DatabaseIdentitySeparationIntegrationTest.java`（`... isEqualTo(16)`）

Evidence：
```java
assertThat(jdbcTemplate.queryForObject(
        "select max(version::integer) from flyway_schema_history where success", Integer.class))
        .isEqualTo(16);
```

Description：该断言把测试与“当前最后迁移恰为 V16”强绑定。新增 V17 后即使分离语义完全正确，测试也会红灯，属维护噪声。

Suggested Fix：
```java
assertThat(jdbcTemplate.queryForObject(
        "select count(*) from flyway_schema_history where success", Integer.class))
        .isGreaterThan(0);
// 或保留 >= 16 并在注释中说明这是本节点的最低基线
```

### S3（建议修改）ASR location 未显式声明超时与体积预算

- Severity：Low
- File & Line：`infra/nginx/production.conf.template` ASR location 块

Evidence：
```nginx
location ~ ^/api/v1/projects/[^/]+/agent/asr/sessions(?:/[^/]+(?:/audio|/finish)?)?$ {
    limit_req zone=asr_per_ip burst=30 nodelay;
    proxy_pass http://core-api:8080;
    proxy_http_version 1.1;
    ...
}
```

Description：本批目标为解除频率限流；`client_max_body_size` 默认 1m 远大于 64 KB 单块，`proxy_read_timeout` 默认 60s 对当前包大小无影响，且无 `finish` 超时复现。仅建议与 Chat 路径保持显式预算风格一致。

Suggested Fix：
```nginx
proxy_read_timeout 120s;
client_max_body_size 128k;
```
并让 `asr-nginx-rate-limit.ps1` 同时断言这两条存在（若采纳）。

### N1–N4（无需修改）核对确认正确的实现

- `GraphResolutionService.suggest`：`candidates` 为空时 `Advice(null,0)` 直接返回，不消费配额、不调用 advisor；非空时在 advisor 前 `aiUsageQuota.consume(actor.userId())` 恰好一次，异常向上抛出形成 429，未被 fail-open 吞掉。`GraphResolutionIntegrationTest` 新增的候选/空候选/额度耗尽/未认证四个分支与实现一致。
- `VoiceInput.flush`：`pcm16` 恒为偶数字节，`bytes.slice` 以 64,000 为步长（64000/16000 均偶数），停止时先 `flush` 再 `await current.queue`，顺序保持；轮询 1500 ms、阈值 48,000 与文档一致，测试 `[48000, 64000, 16000]` 可由代码精确推导。
- Nginx：ASR 正则 location 位于 `location /api/` 之前，命中时使用独立 `asr_per_ip`（600 r/m、burst 30），不消耗普通 API 区；`test_nginx_asr_uses_an_independent_rate_limit_zone` 断言了分区声明、burst 与顺序。
- R10：`V16` 收窄 `public`、只给 Agent `rag_chunk` DML 并转移 checkpoint schema/表/序列所有权；`provision-roles.sh` 幂等引导 + 管理员 default privileges 保证未来 Flyway 对象对 `agentforge_core` 可用；`database-role-boundary.ps1` 覆盖既有角色 `BYPASSRLS` 降权与业务表拒绝写入。

---

## 四、主开发（Codex）评估回填区

| Finding | 是否接受 | 修复方案 / 拒绝理由 | 修复证据（命令与结果） | 备注 |
| --- | --- | --- | --- | --- |
| Round1 F1 | 已接受并关闭 | 由 `DatabaseIdentitySeparationIntegrationTest` 证明分离凭据真实启动 | 定向 1/1、Core clean verify 238 tests 均通过 | 保持 |
| Round1 F2 | 已接受并修复 | V16 转移 checkpoint schema/表/序列所有权 | 所有权 DDL smoke、Core clean verify、跨进程 13/13 均通过 | 保持 |
| Round1 F3 | 已接受目标 | 新增专用分离身份门禁，未改历史脚本 | 分离身份启动与权限 smoke 均通过 | 合理取舍 |
| Round1 F4 | 维持不阻断 | 无复现，未放宽既有边界 | Web 80/80、构建及真实 Nginx 60 秒 smoke 通过 | 见 S3 建议 |
| S1 | 不采纳 | pg17 真实升级 smoke 已证明迁移可转移 owner；Agent 后续在其已拥有的 schema 内建表只需 schema CREATE，额外数据库级 CREATE 会扩大能力且无当前需要。 | 既有管理员表转 owner 后 Agent ALTER/CREATE 成功。 | 纯硬化建议。 |
| S2 | 不采纳 | `>=16`/固定 V16 是本边界出现的最低迁移基线；新增迁移导致显式同步可避免测试在未审查新 schema 时静默放行。 | 当前 V1–V16 与分离不变量同场通过。 | 有意的维护守卫。 |
| S3 | 不采纳 | 默认 body 上限显著高于 64 KB，默认读超时无失败复现；本批只修复请求频率预算。 | 真实 Nginx 60 秒双会话与普通 API smoke 通过。 | 记录为未来容量建议。 |

---

## 五、审查边界说明

- 本轮以“验证 Attempt 1 修复 + 识别修复引入的新问题”为主，未重审 V2/V3 阶段外组件，未提出任何引入 Neo4j 扩展、Langfuse 完整 Trace、LiteLLM 或 MCP 新能力的建议。
- 未把 R09 的 advisor 失败后配额已消费、R10 的 `agentforge_core` DML 范围等产品/设计取舍列为阻塞；仅当存在可运行性、安全边界、契约或关键验证证据缺口时才计入“必须修改”。
- S1/S2/S3 均为非阻塞建议，不触发 NEEDS_FIX；当前无“必须修改”项，故本轮判定 PASS。
