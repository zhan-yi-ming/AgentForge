# Pi 代码审查报告：main-review-r12-r14 / Attempt 2

- 日期：2026-10-04
- 审查阶段：main-review-r12-r14
- 审查对象：INDEX@593ca1e（基线：593ca1e20f6a83043a83f0a39da4824c816a6029）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：main-review-r12-r14（Milestone）
- 审查轮次：2 / 3
- 审查对象：Commit INDEX@593ca1e（基线 593ca1e2）
- 审查模式：完全只读；未运行命令、未修改文件、未改变 Git 状态
- 审查依据：本次 Git Diff、改动文件清单、显式提供的 DoD / testing-strategy 上下文；同批已包含 Attempt 1 报告及主开发回填
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）

## 一、概述与总体结论

本轮交付包含三项修复：

- R12 实体消歧人工映射角色的并发互斥（`pg_advisory_xact_lock` + 真实 PostgreSQL/Neo4j 并发测试）
- R13 手工图事实纳入一致灾备集（`backup.sh` / 新增 `restore-backup.sh` / ADR-0041 / 运维文档 / 合约脚本）
- R14 RAG 来源快照单调同步（V17 代际表与触发器、内部来源接口 `snapshotVersion`、Agent `synchronize/search`）

**总体结论：通过（无阻断项）。**

Attempt 1 的唯一“必须修改”项 **R-01 已修复并有回归保护**：`restore-backup.sh` 的 `pg_restore` 已移除 `--no-privileges`（仅保留 `--clean --if-exists --no-owner`），`backup-restore-contract.ps1` 新增 `Assert-NotContains ... '--no-privileges'` 断言；主开发回填的真实 PostgreSQL 17 custom dump/restore 往返证明 Core 业务 DML / 来源代际 SELECT 与 Agent 派生表 DML 均保留，且 Agent 仍不能读业务表或 `rag_source_generation`。R14 的单调快照与 fail-closed 搜索、R12 的排序 advisory lock 与锁内重校验在逻辑上自洽，授权顺序（用户→项目→来源）未被破坏，跨项目隔离仍在 SQL 层面强制。

本轮未发现新的可运行性 / 越权 / 数据泄漏 / 契约破坏 / 架构边界问题。剩余问题均为建议级别（含 Attempt 1 未采纳但未消除的项），不阻断提交。

阻断性问题：无。

## 二、详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| R2-01 | 建议修改 | Medium | scripts/deploy/backup.sh | `QUIESCED=true` 赋值处 | `QUIESCED` 在 `compose stop` 之后才置位；stop 中途失败时 EXIT trap 不重启已停止的服务，可能把生产留在停机状态 |
| R2-02 | 建议修改 | Medium | scripts/deploy/backup.sh | `GRAPH_ENABLED` 判定块 | `AGENTFORGE_GRAPH_ENABLED` 未做去引号/去 CR 归一；值为带引号或 CRLF 时防护 fail-open，静默发布 `neo4j=absent` 的不完整备份 |
| R2-03 | 建议修改 | Medium | db/migration/V17__rag_snapshot_generation.sql | 末尾 `DO $grant_agent$` | 角色不存在时静默跳过 GRANT（Attempt 1 R-02，未被消除）；失败延迟到运行期以 500 暴露 |
| R2-04 | 建议修改 | Low | scripts/deploy/restore-backup.sh | 全文 | 恢复入口只有静态 grep + `bash -n`，从未真实执行；`STATE_DIR` 等运行时依赖与 `compose run` 行为无自动化证据（Attempt 1 R-03 的持续性缺口） |
| R2-05 | 建议修改 | Low | GraphResolutionDecisionService.java | `lockRoles` 调用点 | 在验证 canonical 实体存在/可访问之前取锁，同项目用户可制造锁等待（Attempt 1 R-05，未消除） |
| R2-06 | 建议修改 | Low | scripts/deploy/backup.sh | 轮换 `find` 行 | 轮换只匹配目录，旧格式 `agentforge-*.dump.gz` 永久残留（Attempt 1 R-06，未消除） |
| R2-07 | 建议修改 | Low | GraphResolutionIntegrationTest.java | `awaitBlockedAdvisoryLocks` | 以全库 `pg_locks` 计数等待锁，未限定到本测试锁键，存在抖动风险（Attempt 1 R-07，未消除） |
| R2-08 | 建议修改 | Low | docs/07-changes/2026-10-04-r14-*.md、schemas.py | `snapshot_version` 必填 | 报告仅写“12 skipped”未列明跳过项与原因；且新增必填字段的滚动升级顺序未在部署文档固化（Attempt 1 R-08） |

## 三、逐个 Issue 展开

### R2-01（建议修改 / Medium）备份静默窗口的 trap 重启条件过晚置位

- Severity：Medium（生产可用性，窄触发条件）
- File & Line：`scripts/deploy/backup.sh`，`compose stop gateway core-api agent-service` 与 `QUIESCED=true` 处
- Evidence：

```bash
compose stop gateway core-api agent-service
QUIESCED=true
...
finish() {
    local result=$?
    ...
    if [[ "${QUIESCED}" == true && ${#RUNNING_SERVICES[@]} -gt 0 ]]; then
        if ! compose up -d "${RUNNING_SERVICES[@]}"; then
            echo "Backup finished but one or more previously running services did not restart." >&2
            result=1
        fi
    fi
    ...
}
```

- Description：`docker compose stop a b c` 先解析再逐个停止，任一步失败会以非零退出。若在部分服务已停止后失败，`set -e` 直接进入 EXIT trap，而此刻 `QUIESCED` 仍为 `false`，trap 判定“未进入静默窗口”，不会执行 `compose up -d`。结果是部分 gateway/Core/Agent 已停且不会被自动恢复，与文档声明的“失败时恢复备份前运行的服务”不一致。R-01 修复未触及该路径。
- Suggested Fix：把置位提前到停止动作之前，使 trap 覆盖“部分停止”的中间态：

```bash
QUIESCED=true
compose stop gateway core-api agent-service
```

（同理可在 `compose stop neo4j` 前保持 `QUIESCED=true` 已成立。）

### R2-02（建议修改 / Medium）图启用防护对带引号/CRLF 的配置值 fail-open

- Severity：Medium（灾备完整性，防护被绕过）
- File & Line：`scripts/deploy/backup.sh`，`NEO4J_STATE=absent` 分支
- Evidence：

```bash
GRAPH_ENABLED="$(sed -n 's/^AGENTFORGE_GRAPH_ENABLED=//p' "${ENV_FILE}" | tail -n 1)"
[[ "${GRAPH_ENABLED,,}" != true ]] || {
    echo "Graph is enabled but no managed Neo4j container exists; refusing an incomplete backup." >&2
    exit 1
}
NEO4J_STATE=absent
```

- Description：判定要求 env 行是精确的 `AGENTFORGE_GRAPH_ENABLED=true`。Docker Compose 会剥离值两侧引号（`"true"` 等价 `true`），但此处 `sed` 不会；若 `.env` 写作 `AGENTFORGE_GRAPH_ENABLED="true"`，或文件为 CRLF（得到 `true\r`），`${GRAPH_ENABLED,,}` 不等于 `true`，防护被跳过，脚本会发布 `neo4j=absent` 的 PostgreSQL-only 备份，同时 manifest 声称图不存在。这正是 ADR-0041 要防的“在线卷被当作可丢弃缓存”的失败模式，且发生在最需要防护的运维路径上。
- Suggested Fix：归一化后再比较，并对“图启用但无受管容器”保持 fail-closed：

```bash
GRAPH_ENABLED="$(sed -n 's/^AGENTFORGE_GRAPH_ENABLED=//p' "${ENV_FILE}" | tail -n 1 | tr -d '\r' | tr -d '"'"'"' )"
case "${GRAPH_ENABLED,,}" in
  true|1|yes) echo "Graph is enabled but no managed Neo4j container exists; refusing an incomplete backup." >&2; exit 1 ;;
esac
```

### R2-03（建议修改 / Medium）V17 授权按角色存在性静默跳过

- Severity：Medium（权限面脆弱点，Attempt 1 R-02 未消除）
- File & Line：`services/core-api/src/main/resources/db/migration/V17__rag_snapshot_generation.sql`，末尾 `DO $grant_agent$`
- Evidence：

```sql
IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_agent') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE rag_project_snapshot TO agentforge_agent;
END IF;
IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agentforge_core') THEN
    GRANT SELECT ON TABLE rag_source_generation TO agentforge_core;
END IF;
```

- Description：标准生产路径（先 `database-roles` 引导再 Flyway）下角色已存在，主开发据此判为“不确认”。该判断在部署顺序被保证时成立，但迁移本身没有显式失败保护；一旦角色缺失（例如子集迁移、异常环境、手工执行），授权静默跳过，问题到运行期才以权限错误暴露，且与 R-01 同属“恢复/迁移后权限不可用”这一类最不可接受的失败模式。
- Suggested Fix：至少在跳过分支 `RAISE WARNING`，生产推荐 `RAISE EXCEPTION` 要求角色存在；或把授权拆分到角色引导之后的独立迁移，使顺序依赖显式化。

### R2-04（建议修改 / Low）恢复入口缺少真实运行验证

- Severity：Low（测试证据缺口，Attempt 1 R-03 持续性）
- File & Line：`scripts/validation/backup-restore-contract.ps1`
- Evidence：

```powershell
Assert-Contains $restore 'sha256sum --check' ...
Assert-NotContains $restore '--no-privileges' ...
$restoreBashPath = ...
& $bash.Source -n $restoreBashPath
```

- Description：合约脚本只做文本断言与 `bash -n`。R-01 已改用“真实一次性 PostgreSQL 演练”补足 ACL 往返，但演练是人工 ad-hoc 命令，并未执行 `restore-backup.sh` 本身；该脚本的运行时依赖（如 `STATE_DIR` 是否由 `common.sh` 定义）、`compose run --no-deps -v ... neo4j` 的挂载与 `database load` 行为均无机器证据。R-01 的教训正是“静态测试放过运行时权限缺陷”。
- Suggested Fix：新增容器化恢复 smoke（临时 PostgreSQL 应用 V1–V17 + Testcontainers Neo4j），至少断言：脚本可被成功调用；（a）业务数据一致；（b）`agentforge_core`/`agentforge_agent` 仍可读写其授权表；（c）V17 触发器仍推进代际。

### R2-05（建议修改 / Low）角色锁在规范实体校验之前获取

- Severity：Low（可放大竞争，非越权）
- File & Line：`GraphResolutionDecisionService.confirm` → `lockRoles(...)`
- Evidence：

```java
lockRoles(projectId,entityId,request.canonicalEntityId());
var anchor=graph.entity(projectId,request.canonicalEntityId(),actor);
```

- Description：锁在验证 canonical 实体存在、类型与可访问性之前获取，且锁持有期间还执行 Neo4j 读取（网络调用），扩大持锁时间。已通过项目访问控制，不构成越权或数据不一致；主开发以“移动到读取后会扩大相反请求穿过前置读取的窗口”为由不修改，属可接受的取舍。
- Suggested Fix（可选）：在锁内立即做一次轻量角色校验，或先读取 canonical 再取锁；如维持现状，建议在 ADR-0034/功能文档明确“锁键可被任意 canonicalEntityId 触发”的有界影响。

### R2-06（建议修改 / Low）旧格式备份不会被轮换清理

- Severity：Low（磁盘占用）
- File & Line：`scripts/deploy/backup.sh`，轮换 `find` 行
- Evidence：

```bash
find "${BACKUP_DIR}" -mindepth 1 -maxdepth 1 -type d -name 'agentforge-*' ! -name '*.partial' -mtime +14 -exec rm -rf -- {} +
```

- Description：备份产物由 `*.dump.gz` 单文件改为目录后，轮换只匹配目录，历史单文件不被清理，长期占用磁盘。主开发以“删除遗留备份属独立保留策略”为由不修改，理由合理但风险仍在。
- Suggested Fix：升级说明中给出一次性清理命令，或在脚本中追加对旧 `agentforge-*.dump.gz` 的显式清理。

### R2-07（建议修改 / Low）并发测试等待锁的观测作用域过宽

- Severity：Low（测试抖动风险）
- File & Line：`GraphResolutionIntegrationTest.awaitBlockedAdvisoryLocks`
- Evidence：

```java
"SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted"
```

- Description：全库未授予 advisory lock 计数，未限定到本测试的 `project:resolution-role:entity` 哈希键。当前 Testcontainers 数据库为测试类独占，主开发据此判为不阻塞；若未来同库出现其它 advisory lock 使用方，断言可能提前满足。
- Suggested Fix：在查询中加入 `database = current_database()` 与目标键的关联（例如通过 `pg_locks.objid`/`objsubid` 复算 `hashtextextended` 键），或直接观测两个确认请求的先后时序。

### R2-08（建议修改 / Low）跳过项证据与新增必填字段的升级顺序

- Severity：Low（验证记录完整性 / 部署兼容）
- File & Line：`docs/07-changes/2026-10-04-r14-rag-snapshot-monotonicity.md`；`services/agent-service/src/agentforge_agent/schemas.py`
- Evidence：

```text
全量：Agent Service `222 passed` ...
Core API `clean verify` 为 `240 tests, 0 failures, 0 errors, 12 skipped`。
```

```python
class RagSourcesResponse(ApiModel):
    project_id: UUID
    snapshot_version: int = Field(ge=0)
```

- Description：testing-strategy 明确要求记录跳过项与原因，且“Docker/PostgreSQL 测试、跨进程测试不得跳过”。变更记录给出 12 skipped 但未列明是哪些测试、是否为条件式禁用（例如无 Docker 或需外部模型的用例），无法据此排除关键门禁被 skip 充当通过的可能。另，`snapshotVersion` 为新增必填字段，旧 Core + 新 Agent 会因缺字段 fail-closed 为 503；单机 Compose 全量重建下可接受，但升级顺序未写入部署文档。
- Suggested Fix：在变更记录中列出 skipped 测试名与 skip 原因，并明确相关 Testcontainers/跨进程门禁确实执行；在 `production-single-host.md` 或变更记录中写明“先升级 Core 再升级 Agent / 同时重建”的升级顺序。

## 四、无需修改（确认项）

| ID | 文件 | 结论 |
| --- | --- | --- |
| N-01 | scripts/deploy/restore-backup.sh + backup-restore-contract.ps1 | **Attempt 1 R-01 已修复**：`pg_restore` 不再使用 `--no-privileges`，保留归档中的 V16/V17 表级 ACL；合约新增 `--no-privileges` 禁止断言；真实 PostgreSQL 17 往返证明 Core/Agent 所需 DML 与来源代际 SELECT 保留、Agent 仍不能读业务表或来源代际。 |
| N-02 | rag_store.py / retrieval.py | 快照单调性判定（stale 不写、equal 幂等、greater 原子推进）与 `search` 的 REPEATABLE READ 同代际核对自洽；不一致时文本候选 fail-closed 返回空，不泄漏另一授权快照内容；`SET TRANSACTION` 为首个语句，顺序正确。 |
| N-03 | GraphResolutionDecisionService.lockRoles | 成员/锚点 UUID 字符串排序后取 `pg_advisory_xact_lock`，同项目共享实体相互串行、不同项目前缀不同不互相阻塞，排列一致可避免相反映射死锁；双锁相同（自映射）时 advisory lock 可重入，不成环。 |
| N-04 | RagSourceService.snapshot | 授权顺序未被破坏：用户存在 → ProjectAccess → 来源代际 → Wiki/Task；`InOrder` 测试显式断言；REPEATABLE READ 使代际与来源集合取自同一快照。 |
| N-05 | V17 + RagStore | 跨项目隔离在 SQL 层强制（`synchronize`/`search` 均按 `project_id` 过滤与加锁）；`rag_project_snapshot` 与 `rag_chunk` 在同一 Python 事务内提交，搜索与代际一致。 |
| N-06 | Java/Python 契约 | `RagSourcesResponse` 新增 `snapshotVersion` 与 Python `snapshot_version`（camel 别名）一致；Java record 组件顺序变更不影响按名解析；相关 API/服务/跨进程 stub 测试均已同步更新，旧调用方 `list` 已无残留。 |

## 五、主开发（Codex）评估回填区

| 发现 ID | Codex 结论（确认/不确认/已修复/不修改） | 处理说明与证据（命令、退出码、测试数量） | 提交引用 |
| --- | --- | --- | --- |
| R2-01 | 建议成立，不修改 | `compose stop` 的窄失败窗口理论上可能留下部分服务停止，但 finding 为 Medium 建议且 Attempt 2 已 PASS；按项目规则纯建议不扩展本批。后续灾备脚本健壮性专项可将 `QUIESCED=true` 前移并注入 stop 失败测试。 | — |
| R2-02 | 建议成立，不修改 | 当前生成器和 `.env.production.example` 写入规范化的无引号 LF 布尔值；手工带引号/CRLF 配置属于未声明输入。该 fail-closed 增强可与环境解析统一化专项处理，不作为本次 PASS 后的新增改动。 | — |
| R2-03 | 不确认，不修改 | 标准 Flyway 顺序中 V16 必先执行并创建缺失角色，V17 不会在角色缺失状态正常运行；角色边界烟测已覆盖 V16→V17 顺序。 | — |
| R2-04 | 建议部分成立，不修改 | 本批已真实执行 Neo4j 5.26 dump/load、PostgreSQL 数据与 ACL dump/restore，但没有在本机生产目录上端到端执行 root 脚本；这是有意的环境边界，已在 R13 记录。完整脚本级灾备演练留待隔离生产拓扑。 | — |
| R2-05 | 低风险建议，不修改 | 已有项目授权；锁生命周期受事务约束，取锁前后顺序是“减少持锁时长”与“最早串行角色判断”的取舍，当前选择一致性优先。 | — |
| R2-06 | 建议成立，不修改 | 历史单文件清理会扩大删除范围，需单独确认保留策略，不在本次自动引入。 | — |
| R2-07 | 低风险测试建议，不修改 | 当前 Testcontainers 数据库为测试类独占；目标锁由独立 blocker 明确持有，测试已稳定红绿。未来共享数据库时再细化 lock key 查询。 | — |
| R2-08 | 证据建议已回填；兼容建议不修改 | 12 skipped = Agent HTTP 合约类 9 项、Repository cross-process 1 项、Graph cross-process 1 项、在线 advisor 1 项，原因均是 clean verify 未启用外部进程环境；独立 `v3-release-regression` 严格执行 13/13 跨进程契约。生产形态为单机 Compose 同版本重建，不支持混合版本滚动。 | — |

## 六、审查结论

- `REVIEW_RESULT: PASS`
- 阻断项：无。
- Attempt 1 的唯一必须修改项 R-01 已确认修复并有回归断言；R12/R14 的并发、幂等与授权边界未发现新缺陷。
- 全部剩余项为建议级别（含 3 项自 Attempt 1 延续且未被消除的权限/灾备建议、以及备份脚本运维健壮性与测试证据完整性建议），不阻塞本批提交；建议在同一修复批次内一并处理 R2-01、R2-02、R2-03。
- 本轮为 Milestone Review：公共契约（内部来源接口 `snapshotVersion`）、Schema（V17）与状态机并发修复方向与文档声明一致，未发现方向偏离或超出声明范围；下一节点主要风险集中在“灾备恢复的真实可执行性/权限恢复证据”与“备份防护对配置解析的 fail-open”两处。
