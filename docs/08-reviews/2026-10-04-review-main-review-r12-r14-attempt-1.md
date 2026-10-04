# Pi 代码审查报告：main-review-r12-r14 / Attempt 1

- 日期：2026-10-04
- 审查阶段：main-review-r12-r14
- 审查对象：INDEX@593ca1e（基线：593ca1e20f6a83043a83f0a39da4824c816a6029）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- 审查阶段：main-review-r12-r14（Milestone）
- 审查轮次：1 / 3
- 审查目标：Commit INDEX@593ca1e（`593ca1e2..593ca1e`）
- 审查模式：完全只读，未运行命令、未修改文件、未改变 Git 状态
- 审查依据：本次 Git Diff、改动文件清单、显式提供的 DoD / 测试策略上下文；无上一轮报告

## 一、概述与总体结论

本轮交付包含三个修复：

- R12 实体消歧角色并发互斥（Core API advisory lock + 真实 PostgreSQL/Neo4j 并发测试）
- R13 手工图事实纳入一致灾备集（backup.sh / restore-backup.sh / ADR-0041 / 运维文档）
- R14 RAG 来源快照单调同步（V17 代际表与触发器、内部来源接口 `snapshotVersion`、Agent 同步/检索）

**总体结论：需修复后交付。**

R14 的单调快照设计与 fail-closed 搜索、R12 的排序 advisory lock 与事务内复核在逻辑上是自洽的，授权顺序（先鉴权后读来源）也未被破坏，未发现明显的越权或数据泄漏路径。R13 的备份脚本逻辑基本正确，但**恢复路径存在一个会直接导致恢复后生产不可用的权限缺陷**：`restore-backup.sh` 使用 `pg_restore --no-privileges`，在 `--clean` 重建对象的同时丢弃了由数据库角色迁移（V16/V17）建立的 `agentforge_core` / `agentforge_agent` 表级授权，且恢复流程未重新执行授权迁移或角色权限引导。这是本轮唯一进入“必须修改”的问题，触发 NEEDS_FIX。

阻断性问题：无（未发现不可运行、越权、数据泄漏、契约破坏或架构边界破坏）。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- |
| R-01 | 必须修改 / High | scripts/deploy/restore-backup.sh | `pg_restore` 调用处（约 49–52 行） | `--no-privileges` 丢弃 V16/V17 建立的角色表级授权，恢复后 Core/Agent 无法访问数据库 |
| R-02 | 建议修改 / Medium | services/core-api/src/main/resources/db/migration/V17__rag_snapshot_generation.sql | 文件末尾 DO 块 | 角色不存在时静默跳过 GRANT，后续运行期才以 500 暴露 |
| R-03 | 建议修改 / Medium | scripts/validation/backup-restore-contract.ps1 | 全文 | R13 仅做静态 grep + `bash -n`，未自动验证恢复后数据与角色权限往返，R-01 因此漏检 |
| R-04 | 建议修改 / Low | scripts/deploy/update.sh、rollback.sh（未在本次 diff 中） | — | 备份产物由单文件改为目录，需确认调用方未解析旧的 `*.dump.gz` 路径 |
| R-05 | 建议修改 / Low | GraphResolutionDecisionService.java | `lockRoles` 调用点 | 在验证 canonical 实体存在/可访问之前即取锁，可被同项目用户制造锁竞争 |
| R-06 | 建议修改 / Low | scripts/deploy/backup.sh | 轮换 `find` 行 | 轮换只清理目录，旧布局的 `agentforge-*.dump.gz` 永久残留 |
| R-07 | 建议修改 / Low | GraphResolutionIntegrationTest.java | `awaitBlockedAdvisoryLocks` | 以全库 `pg_locks` 计数等待锁，作用域过宽，存在抖动风险 |
| R-08 | 建议修改 / Low | schemas.py / core_client.py | `snapshot_version` 必填字段 | 滚动升级期间旧 Core + 新 Agent 会因缺少字段导致 RAG 503，需明确部署顺序 |

## 三、逐个 Issue 展开

### R-01（必须修改 / High）恢复丢弃数据库角色授权

- Severity：High（恢复后应用不可用 / 权限边界失效）
- File & Line：`scripts/deploy/restore-backup.sh`，PostgreSQL 恢复块（约 49–52 行）
- Evidence：

```bash
if ! compose exec -T postgres pg_restore -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --clean --if-exists --no-owner --no-privileges <"${SOURCE}/postgres.dump"; then
    echo "PostgreSQL restore failed; application services remain stopped." >&2
    exit 1
fi
```

- Description：
  - `pg_dump -Fc` 会把表级 ACL（`GRANT ... TO agentforge_core / agentforge_agent`）写入归档；`pg_restore --no-privileges`（`-x`）明确指示恢复时**不执行**这些 GRANT/REVOKE。
  - 同一命令带 `--clean --if-exists`，会先 `DROP` 再按 dump 重建对象。旧对象连同其 ACL 一起被删除，新对象只带默认（owner）权限，因此授权在“先删后建 + 跳过授权”下被彻底丢弃。
  - 项目的角色权限边界由迁移而非部署引导建立：`docs/02-architecture/data-architecture.md` 明确“权限变化由新 Flyway 迁移完成”，`scripts/validation/database-role-boundary.ps1` 也是先建表再导入 `V16` 才让 `agentforge_core` 获得 DML，说明表级 GRANT 位于 V16/V17。恢复后 `flyway_schema_history` 已恢复到 V17，Flyway 不会重放这些 GRANT；`restore-backup.sh` 也未调用 `database-roles`/`provision-roles.sh` 重新授予。
  - 后果：恢复“显示成功”，但以 `agentforge_core` 运行的 Core API 与以 `agentforge_agent` 运行的 Agent Service 缺少业务表 / `rag_chunk` / `rag_project_snapshot` / checkpoint 权限，运行期大面积 500；`agentforge_agent` 的 `rag_project_snapshot` 权限同样丢失，RAG 完全不可用。这是一次灾备恢复中最不可接受的失败模式。
  - 变更记录 R13 的“真实 PostgreSQL custom dump/restore 演练”只核验了 canonical 实体/成员数据（以管理员连接），未验证恢复后 Core/Agent 角色仍可访问，属证据缺口。

- Suggested Fix：
  - 首选：恢复时保留 dump 中的授权。角色在恢复前已存在，撤销 `--no-privileges` 后由对象属主（`--no-owner` 下为恢复连接的管理员）执行 GRANT 是允许的：

```bash
if ! compose exec -T postgres pg_restore -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --clean --if-exists --no-owner <"${SOURCE}/postgres.dump"; then
    echo "PostgreSQL restore failed; application services remain stopped." >&2
    exit 1
fi
```

  - 或（若不希望依赖 dump ACL）在 `pg_restore` 成功后、重启应用前显式重放权限引导，并在失败时保持写入面停机：

```bash
compose run --rm --no-deps database-roles   # 仅当该入口确实包含 V16/V17 表级授权时
```

  - 无论采用哪种方式，恢复验收必须新增“以 `agentforge_core` / `agentforge_agent` 身份各执行一次代表性读写”的断言，防止同类回归。

### R-02（建议修改 / Medium）V17 授权按角色存在性静默跳过

- Severity：Medium
- File & Line：`services/core-api/src/main/resources/db/migration/V17__rag_snapshot_generation.sql`，末尾 `DO $grant_agent$` 块
- Evidence：

```sql
IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_core') THEN
    GRANT SELECT ON TABLE rag_source_generation TO agentforge_core;
END IF;
```

- Description：若迁移执行时 `agentforge_core`（或 `agentforge_agent`）尚不存在，授权被静默跳过且无任何报错，问题直到运行期以权限错误暴露。生产部署顺序（先 `database-roles` 再 Flyway）可避免，但缺少显式失败保护，且与 R-01 同属权限面脆弱点。
- Suggested Fix：在跳过分支 `RAISE EXCEPTION`（或至少 `RAISE WARNING`）要求角色必须存在；或将授权放在角色引导之后的独立迁移中。

### R-03（建议修改 / Medium）R13 缺少自动化恢复往返验证

- Severity：Medium
- File & Line：`scripts/validation/backup-restore-contract.ps1`
- Evidence：

```powershell
Assert-Contains $restore 'sha256sum --check' ...
Assert-Contains $restore 'neo4j-admin database load neo4j' ...
& $bash.Source -n $restoreBashPath
```

- Description：该测试只对脚本文本做正则断言并执行 `bash -n` 语法检查，未启动真实 PostgreSQL/Neo4j 验证“dump→restore→数据+权限+触发器可用”。R-01 正是被这种静态测试放过的缺陷。变更记录中的真实演练为人工执行且未覆盖角色权限。（本条为 R-01 的配套测试缺口，不单独阻断。）
- Suggested Fix：新增容器化恢复 smoke：临时 PostgreSQL 应用 V1–V17、以 Core/Agent 角色写入代表性数据后 dump，恢复后断言（a）业务数据一致；（b）`agentforge_core`/`agentforge_agent` 仍可读写其授权表；（c）V17 触发器仍推进代际。

### R-04（建议修改 / Low）备份产物格式变更需确认下游脚本

- Severity：Low
- File & Line：`scripts/deploy/backup.sh`（`TARGET` 由 `<stamp>.dump.gz` 变为 `<stamp>/` 目录）；`scripts/deploy/update.sh`、`rollback.sh` 未在本次 diff 中
- Description：备份产物由单文件改为目录且不再 gzip，若 `update.sh`/`rollback.sh` 依赖旧路径或 `.dump.gz` 命名，可能无法定位备份。文档已更新，但脚本调用方未在 diff 中体现。
- Suggested Fix：人工确认调用方仅调用 `backup.sh` 而不解析产物路径；如存在引用，改为使用脚本输出行 `Backup created: <dir>`。

### R-05（建议修改 / Low）消歧角色锁在目标实体校验前获取

- Severity：Low
- File & Line：`GraphResolutionDecisionService.confirm` → `lockRoles(projectId, entityId, request.canonicalEntityId())`
- Evidence：

```java
lockRoles(projectId,entityId,request.canonicalEntityId());
var anchor=graph.entity(projectId,request.canonicalEntityId(),actor);
```

- Description：锁在验证 canonical 实体存在、类型与可访问性之前获取。项目 owner/admin 可用任意 `canonicalEntityId` 对同一 `projectId+entityId` 组合制造锁等待（锁在事务结束释放，影响有界）。不构成越权或数据不一致，但可放大竞争。
- Suggested Fix：在读取并校验 canonical 实体之后再获取角色锁，或在锁内立即校验并不做昂贵的 Neo4j 调用。

### R-06（建议修改 / Low）备份轮换遗留旧格式文件

- Severity：Low
- File & Line：`scripts/deploy/backup.sh` 轮换行
- Evidence：

```bash
find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d -name 'agentforge-*' ! -name '*.partial' -mtime +14 -exec rm -rf -- {} +
```

- Description：轮换只匹配目录，旧布局的 `agentforge-*.dump.gz` 文件不会被清理，长期占用磁盘。
- Suggested Fix：追加一条针对旧文件的清理（或在升级说明中一次性手工清理）。

### R-07（建议修改 / Low）并发测试等待锁计数作用域过宽

- Severity：Low
- File & Line：`GraphResolutionIntegrationTest.awaitBlockedAdvisoryLocks`
- Evidence：

```java
"SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted"
```

- Description：以全库未授予 advisory lock 计数，未限定到本测试使用的 project/entity 哈希键；若同库存在其他 advisory lock 使用方（如 `RagStore`），断言可能提前满足或抖动。
- Suggested Fix：在查询中加入 `database = current_database()` 并对同一测试项目的锁做更精确匹配，或改用直接观测两个确认请求的时序。

### R-08（建议修改 / Low）新增必填字段的滚动升级顺序

- Severity：Low
- File & Line：`services/agent-service/src/agentforge_agent/schemas.py`（`snapshot_version: int = Field(ge=0)`）
- Description：内部来源接口新增必填 `snapshotVersion`，旧 Core + 新 Agent 的组合会因缺少该字段触发校验失败并被归一为 503。单机 Compose 全量重建下风险有限，但滚动/分阶段升级需要明确先升 Core 再升 Agent。
- Suggested Fix：在变更记录或部署文档中记录升级顺序；如需兼容，可将其标记为可选并显式拒绝缺失值（当前 fail-closed 可接受）。

## 四、无需修改（确认项）

| ID | 文件 | 结论 |
| --- | --- | --- |
| N-01 | `rag_store.py` / `retrieval.py` | 快照单调性判定（stale 拒绝、equal 幂等、greater 原子推进）与 `search` 的 REPEATABLE READ 同代际校验逻辑自洽；不匹配时 fail-closed 返回空候选，避免跨授权快照泄漏 |
| N-02 | `GraphResolutionDecisionService.lockRoles` | 对成员与锚点 UUID 字符串排序后取 `pg_advisory_xact_lock`，全局字典序一致，可避免相反映射成环死锁；锁键含 projectId，跨项目不互相阻塞 |
| N-03 | `RagSourceService.snapshot` | 授权（用户存在、ProjectAccess）先于 `rag_source_generation` 与 Wiki/Task 读取，测试 `InOrder` 明确断言，未破坏既有权限边界 |
| N-04 | `core_client.fetch_sources` | 返回值改为 `RagSourcesResponse` 后仍校验 `projectId`/`requestId` 关联，契约未放松 |
| N-05 | `backup.sh` | `finish` trap 在失败时清理 `.partial` 并恢复备份前运行集合、成功才原子发布，路径校验使用 `BACKUP_DIR` 前缀防止越界删除 |

## 五、主开发（Codex）评估回填区

| 发现 ID | Codex 结论（确认/不确认/已修复） | 处理说明与证据（命令、退出码、测试数量） | 提交引用 |
| --- | --- | --- | --- |
| R-01 | 已确认并修复 | `pg_restore --clean` 会重建对象，`--no-privileges` 确会跳过归档 ACL。新增禁止该组合的合约断言先红灯；移除 `--no-privileges` 后合约绿灯。一次性 PostgreSQL 17 custom dump/restore 往返证明 Core 的业务 DML/来源代际读取和 Agent 的两张派生表 DML 保留，Agent 仍不能读取业务表/来源代际。 | R13 提交（待创建） |
| R-02 | 不确认，不修改 | V17 并非首个角色迁移：严格前置的 V16 会在角色缺失时创建 `agentforge_core` 与 `agentforge_agent`，因此标准 Flyway 路径不会静默跳过。条件块兼容非生产的迁移子集执行，不构成当前运行期缺陷。 | — |
| R-03 | 建议部分采纳 | 静态合约新增 `--no-privileges` 回归断言；本次另执行真实 PostgreSQL ACL 往返并通过。将完整双数据库灾备演练固化为常规 CI 会显著扩大环境/耗时，作为后续增强，不阻塞本批。 | R13 提交（待创建） |
| R-04 | 不成立 | `rg` 核对 `scripts/deploy/update.sh` 仅调用 `backup.sh`，不解析输出或旧 `*.dump.gz`；`rollback.sh` 无相关引用。 | — |
| R-05 | 低风险建议，不修改 | 调用者已通过项目访问控制；任意 UUID 只产生当前事务内、项目隔离的短期 advisory lock，不造成越权或持久数据问题。移动到 Neo4j 校验后还会扩大相反请求穿过前置读取的窗口，当前一致性优先。 | — |
| R-06 | 建议成立，不修改 | 旧单文件不会被新目录轮换清理，但删除遗留备份属于独立运维保留策略，不能在本次未经迁移窗口确认时自动扩大删除范围。 | — |
| R-07 | 低风险测试建议，不修改 | 当前 Testcontainers 数据库为测试类独占，红灯又由独立连接精确占用目标 key 取得；全库计数在该隔离环境不会被产品 RAG 锁污染。可在后续测试工具化时细化观测。 | — |
| R-08 | 建议成立，不修改 | 单机 Compose 是本项目声明的生产形态，部署为同一版本集合；不支持混合版本滚动。缺字段会 fail closed 为 503，不会使用无代际索引。 | — |

## 六、审查结论

- `REVIEW_RESULT: NEEDS_FIX`
- 阻断项：R-01（恢复后角色授权丢失，生产不可用）。
- 其余为建议项，不单独阻塞；但 R-02、R-03 与 R-01 同属权限/灾备可信度问题，建议在同一修复批次内一并处理。
- 本轮为 Milestone Review：R14 的公共契约（内部来源接口新增 `snapshotVersion`）、Schema（V17）与 R12 状态机并发修复方向与文档声明一致，未发现方向偏离或超出声明范围；下一节点前置风险集中在“灾备恢复的真实可用性”与“权限恢复证据”上。
