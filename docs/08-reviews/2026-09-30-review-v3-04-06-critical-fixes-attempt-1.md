# Pi 代码审查报告：v3-04-06-critical-fixes / Attempt 1

- 日期：2026-09-30
- 审查阶段：v3-04-06-critical-fixes
- 审查对象：WORKTREE@ebafcfd（基线：ebafcfd6991436a8f561c9a31d2cd6da4afb7d26）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-04～06 严重缺陷修复 —— Milestone Review 报告

- 审查阶段：v3-04-06-critical-fixes
- 审查模式：Milestone / 第 1 轮（共 3 轮）
- 审查对象：`WORKTREE@ebafcfd`（16 个文件，+333/-29）
- 审查范围：本次 diff 全部文件 + 显式提供的 Node 协议与路线图上下文
- 只读声明：未运行任何命令、未修改任何文件与 Git 状态；所有结论均来自 diff 与本轮上下文
- 上一轮报告：无

## 一、概述与总体结论

本轮修复的四个方向（图清理代次失效、清理与同步串行化、候选可确认性过滤、严格整数版本反序列化）在设计与实现上总体成立：`generation` 隔离查询、member 清理、canonical generation 校验、`FOR SHARE`/排他更新的串行化思路、`StrictVersion` 复用、候选解析为 canonical 后按 ID 去重，均与 ADR-0035 和文档描述一致，未发现越权、跨项目泄露或 API 契约被破坏的新增缺陷。

但存在一个**必须修改**的问题：`resetting` 状态没有代码层恢复路径，而 `beginClear` 在 `resetting=true` 时直接返回 503，导致进程/部署在清理窗口内中断（或 `finishClear` 失败）后，该项目将**永久**无法进行消歧写入与来源同步，且与本次新增的 Accepted ADR 明确声明的“重试清理负责恢复”直接矛盾。其余为并发健壮性、worker 调度语义、契约文档与测试缺口层面的建议，不阻塞。

结论：**需修复后交付（NEEDS_FIX）**，仅 F1 为阻断项。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| F1 | 高（必须修改） | `GraphProjectLifecycle.java` / `GraphService.java` | L20-L28 / L34-L40 | `resetting=true` 时 `beginClear` 直接 503，无任何恢复/接管路径；清理中断后项目永久失去消歧写入与来源同步能力，且与 ADR-0035 的“重试可恢复”矛盾 |
| F2 | 中（建议修改） | `GraphProjectLifecycle.java` | L20-L24（`lock` L49-L52） | `beginClear` 先对同一行取 `FOR SHARE` 再 `UPDATE`，两个并发 clear 会行锁升级死锁，依赖 PG 死锁检测而非确定性串行化 |
| F3 | 中（建议修改） | `GraphSyncProcessor.java` | L29-L45 | 候选查询去掉了 `FOR UPDATE SKIP LOCKED`，命中 resetting 项目或被其他事务锁定的行时 `return true`，同一队首行会被反复选中，可能忙等并阻塞其他项目同步 |
| F4 | 中（建议修改） | `GraphResolutionIntegrationTest.java` | 全文 | 新增测试未覆盖并发/重叠 clear 的 503、resetting 恢复、清理后 canonical 编辑、resolution 端点数字字符串版本 400 等文档化契约 |
| F5 | 低（建议修改） | `docs/04-api/core-api.md` / `docs/03-features/entity-resolution.md` | V3-06 段 | `POST /suggestions` 对 canonical anchor 源实体新增 409，未在 API/功能文档登记 |
| F6 | 低（建议修改） | `Neo4jGraphStore.java` | L38-L50 | 只读重试静默吞掉原始 `Neo4jException`，且对非瞬态错误（认证/配置）同样重试 3 次，放大延迟且不可观测 |
| F7 | 低（建议修改） | `GraphService.java` | L34-L40 | `finally` 中 `finishClear` 失败会掩盖 `store.clear` 的原始失败；且该失败本身会残留 `resetting`（与 F1 同源） |

无“无需修改”类问题需要单列阻断项；`StrictVersion` 复用、generation 过滤读、候选 canonical 归一化等实现方向正确，见第四节。

## 三、逐个 Issue 展开

### F1（必须修改，高）`resetting` 无恢复路径，与 ADR-0035 矛盾

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphProjectLifecycle.java` L20-L28；`GraphService.java` `clear` L34-L40（行号为按 diff 估算）

**Evidence**

```java
@Transactional
public long beginClear(UUID projectId) {
    ensure(projectId);
    var state = lock(projectId);
    if (state.resetting()) throw new ServiceUnavailableException("Graph reset is in progress."); // ← 无法重入
    long generation = state.generation() + 1;
    jdbc.update("UPDATE graph_project_state SET generation=?,resetting=true,...", generation, projectId);
    jdbc.update("DELETE FROM graph_resolution_member WHERE project_id=?", projectId);
    jdbc.update("DELETE FROM graph_source_sync WHERE project_id=?", projectId);
    return generation;
}

@Transactional
public void finishClear(UUID projectId) {
    jdbc.update("UPDATE graph_project_state SET resetting=false,... WHERE project_id=?", projectId);
}
```

```java
public void clear(UUID projectId, AuthenticatedActor actor, boolean confirm) {
    ...
    lifecycle.beginClear(projectId);          // 独立事务提交 resetting=true
    try { store.clear(projectId); }           // Neo4j 清理，跨库窗口
    finally { lifecycle.finishClear(projectId); }
}
```

同轮新增并被 Accept 的 `docs/02-architecture/decisions/ADR-0035-graph-clear-generation.md` 明确写：

> 清理失败返回依赖错误且旧合并依旧失效，重试可完成投影清理。
> 故障可能留下 resetting 状态，重试清理负责恢复；运行手册需可观测该状态。

**Description**：`resetting=true` 由 `beginClear` 的独立事务提交，之后才执行 `store.clear`。只要进程被 kill/重启、部署中断，或 `finishClear` 自身的事务失败，`resetting` 会永久为 true。此后：

- `beginClear` 永远抛 `ServiceUnavailableException` → **清理永远无法重试**，与 ADR 的“重试清理负责恢复”直接矛盾；
- `lockAvailable` 永远抛 503 → 该项目 `confirm`、`updateCanonical`、`revert` 全部不可用；
- `lockForSync` 永远返回 false → 该项目来源同步永久停滞，且 `graph_source_sync` 行持续堆积；
- `canonical()` 因 `NOT s.resetting` 恒为空 → 该项目全部规则映射读取变为 `UNMAPPED`/404。

代码中没有任何启动自愈、超时接管或管理员复位路径，唯一出路是人工改库，而 ADR 声称可自恢复。这是运行手册与 Accepted 架构决策均无法覆盖的可用性/数据一致性缺陷。

**Suggested Fix**（最小修复，保持既有“并发 clear 返回 503”语义的同时提供恢复能力）：

```java
// 迁移中补充 resetting_at，或复用 updated_at 作为判据
private static final Duration RESET_TAKEOVER = Duration.ofMinutes(5);

@Transactional
public long beginClear(UUID projectId) {
    ensure(projectId);
    var state = lockForUpdate(projectId);           // 见 F2，改为 FOR UPDATE
    if (state.resetting() && !state.stale(RESET_TAKEOVER)) {
        throw new ServiceUnavailableException("Graph reset is in progress.");
    }
    // resetting 且已超时：视为上一次清理中断，直接接管重做（clear 幂等）
    long generation = state.generation() + 1;
    ...
}
```

或退一步：在应用启动时扫描 `resetting=true` 的行并给出可观测告警 + 显式 resume 入口（`POST .../graph/cleanup/resume`），确保 ADR 的“重试可恢复”真实成立。**注意**：若选择接管方案，需同步修订 ADR-0035 与 `docs/03-features/graph-domain-model.md` 中“重试清理负责恢复”的措辞与判据。

### F2（建议修改，中）`beginClear` 的 `FOR SHARE → UPDATE` 行锁升级导致并发 clear 死锁

**File & Line**：`GraphProjectLifecycle.java` `beginClear` L20-L24、`lock` L49-L52

**Evidence**

```java
private State lock(UUID projectId) {
    return jdbc.queryForObject("SELECT generation,resetting FROM graph_project_state WHERE project_id=? FOR SHARE", ...);
}
```

`beginClear` 在取到共享锁后，紧接着对**同一行**执行 `UPDATE ... SET generation=?,resetting=true`（需要排他元组锁）。

**Description**：两个并发的 `DELETE /graph?confirm=true`（双击、重试）都会先成功拿到 `FOR SHARE`（共享锁互相兼容），随后各自尝试对同一行取排他锁并互相等待，触发 PostgreSQL 死锁检测（`deadlock_timeout` 1s）后其中一个事务被强制回滚。后果：

- 失败方不会执行到 `finishClear`（异常发生在 `beginClear` 返回前），抛出的是 `DeadlockLoserDataAccessException`（`TransientDataAccessException` 子类），能否映射为文档承诺的“重叠 clear 返回 503”取决于全局异常处理器是否覆盖该类，diff 内无证据；
- 即便映射正确，也把“并发 clear 的串行化”交给了数据库死锁检测，而非确定性加锁。

若 `LockAvailable`/`lockForSync` 使用 `FOR UPDATE` 反而会破坏共享语义，因此只需修改 clear 路径。

**Suggested Fix**：

```java
private State lockForUpdate(UUID projectId) {
    return jdbc.queryForObject(
        "SELECT generation,resetting FROM graph_project_state WHERE project_id=? FOR UPDATE",
        (rs, row) -> new State(rs.getLong("generation"), rs.getBoolean("resetting")), projectId);
}

public long beginClear(UUID projectId) {
    ensure(projectId);
    var state = lockForUpdate(projectId);   // 第二个 clear 在此阻塞，提交后读到 resetting=true → 503
    if (state.resetting()) throw new ServiceUnavailableException("Graph reset is in progress.");
    ...
}
```

保留 `lockAvailable`/`lockForSync` 的 `FOR SHARE`（与 clear 的排他 UPDATE 互斥、彼此兼容），即可让“在途写入被清理等待”与“重叠 clear 确定性 503”同时成立。

### F3（建议修改，中）worker 候选查询去掉 `SKIP LOCKED` 且跳过分支返回 `true`

**File & Line**：`GraphSyncProcessor.java` `processOne` L29-L45

**Evidence**

```java
var candidates=jdbc.query("""
    SELECT project_id, source_type, source_id FROM graph_source_sync
    WHERE next_attempt_at<=now() ORDER BY next_attempt_at, updated_at LIMIT 1
    """, ...);                                  // ← 不再带 FOR UPDATE SKIP LOCKED
if(candidates.isEmpty()) return false;
var candidate=candidates.getFirst();
if (!lifecycle.lockForSync(candidate.projectId())) return true;   // resetting → return true
var rows=jdbc.query("... WHERE project_id=? AND ... FOR UPDATE SKIP LOCKED", ...);
if (rows.isEmpty()) return true;            // 行被其他事务锁走 → return true
```

**Description**：候选选取现在不感知行锁与项目状态，命中以下两种“未处理任何工作”的情形时仍返回 `true`（语义为“可能还有工作”）：

1. 队首行所属项目 `resetting=true`（`lockForSync` 返回 false）；
2. 队首行被另一实例/另一调度事务锁走（`SKIP LOCKED` 后为空）。

由于 `ORDER BY next_attempt_at, updated_at LIMIT 1` 结果稳定，下一次调用会**选中同一行**。含义有二：

- 若调用方是 `while (processOne())` 形式，将形成忙等（不断 `ensure` + 两次查询）；
- 即使调用方是固定间隔调度，`resetting` 项目的队首行也会在清理期间持续占据队首，使**其他项目**的来源同步在这段时间内得不到推进（head-of-line blocking）。结合 F1，若某项目永久卡在 `resetting`，其新产生的待办行会长期占用候选名额。

**Suggested Fix**：候选查询直接排除 resetting 项目，并且“未实际处理”时返回 `false`：

```java
var candidates=jdbc.query("""
    SELECT q.project_id, q.source_type, q.source_id
    FROM graph_source_sync q
    JOIN graph_project_state s ON s.project_id=q.project_id AND NOT s.resetting
    WHERE q.next_attempt_at<=now()
    ORDER BY q.next_attempt_at, q.updated_at
    LIMIT 1
    """, ...);
...
if (rows.isEmpty()) return false;   // 该行已被其他 worker 处理，本轮视为无进展
```

（若保留 `return true`，需在调用方提供退避，否则请一并说明调度模型。）

### F4（建议修改，中）新增测试未覆盖本次修复的多条文档化契约

**File & Line**：`GraphResolutionIntegrationTest.java` 新增三个用例

**Evidence**：本 diff 中新增/被引用的验证为 `resolutionVersionsRequireJsonIntegers`、`clearInvalidatesResolutionAfterStableIdsAreRebuilt`、`suggestionExcludesMappedMembersThatCannotBeAnchors`，以及被提及但未在本 diff 修改的 `GraphApiIntegrationTest#concurrentCleanupNeverTurnsNeighborReadIntoServerError`。

**Description**：以下本轮新增或被文档明确承诺的行为没有对应的自动化证据：

1. 重叠/并发 `DELETE graph?confirm=true` 返回 503（变更记录与实体解析文档均如此声明，但无该用例）；
2. `resetting` 残留后的恢复路径（对应 F1，当前无测试也无法通过）；
3. 清理后 `PUT /canonicals/{id}` 的行为（旧 generation canonical 应 404/409，不应误改新代次）；
4. 三个阶段字段的**数字字符串**（如 `"0"`、`"0.5"`）在 resolution 端点返回 400 —— 新测试只覆盖了 JSON 小数，文档同时声明拒绝数字字符串；
5. worker 在 `resetting` 期间不处理该项目、且清理开始后的新待办在完成后按新事实同步（当前只验证了“旧待办被删”）。

**Suggested Fix**：补 1～2 个可观测 HTTP 级用例，例如新增 `clearConflictWhileResettingReturns503`（用 `JdbcTemplate` 预置 `resetting=true` 后调用 clear 断言 503，并在 F1 修复后改为“超时接管”语义），以及把 `resolutionVersionsRequireJsonIntegers` 扩展 `"0"` 字符串分支断言 400。

### F5（建议修改，低）`POST /suggestions` 新增 409 未登记到 API/功能文档

**File & Line**：`GraphResolutionService.java` `suggest`（约 L36）；`docs/04-api/core-api.md` V3-06 段

**Evidence**

```java
if (decisions.isCanonicalAnchor(projectId,entityId))
    throw new ConflictException("A canonical anchor cannot become a member.");
```

**Description**：`POST /suggestions` 对“源实体本身已是当前 generation 的 canonical anchor”新增 409，行为合理（确认接口必然拒绝其成为 member），但 `docs/04-api/core-api.md` 的 409 语义仅列出“来源或决策版本变化”，`docs/03-features/entity-resolution.md` 也只描述了候选过滤。契约文档与实现不一致。

**Suggested Fix**：在 `core-api.md` V3-06 的 409 说明中补充“`suggestions` 的源实体已是当前代次规范锚点”，或在 `entity-resolution.md` 的“公共接口与权限”段落登记一句。

### F6（建议修改，低）Neo4j 只读重试静默吞异常且对非瞬态错误重试

**File & Line**：`Neo4jGraphStore.java` `transaction` L38-L50

**Evidence**

```java
int attempts=write ? 1 : 3;
for(int attempt=1;attempt<=attempts;attempt++) {
    try(var session=driver().session()) { return write ? ... : session.executeRead(work::apply, TX); }
    catch (Neo4jException failure) {
        if(attempt==attempts) throw new ServiceUnavailableException("Graph service is unavailable.");
    } catch (IllegalArgumentException failure) { throw new ServiceUnavailableException(...); }
}
```

**Description**：重试方向正确（瞬时清理冲突 → 有界重读），但：(1) 原始 `Neo4jException` 被完全丢弃，无任何日志，清理并发问题在生产上不可观测；(2) 认证/配置/永久性错误同样重试 3 次，叠加 `withConnectionTimeout(2s)/withConnectionAcquisitionTimeout(3s)`，最坏延迟显著放大。

**Suggested Fix**：

```java
} catch (Neo4jException failure) {
    if (attempt == attempts) {
        log.warn("Graph read transaction failed after {} attempts: {}", attempts, failure.getClass().getSimpleName());
        throw new ServiceUnavailableException("Graph service is unavailable.");
    }
}
```

并可选：仅对 `TransientException`/`SessionExpiredException` 等可重试子类重试。

### F7（建议修改，低）`finally` 中的 `finishClear` 掩盖原始失败

**File & Line**：`GraphService.java` `clear` L34-L40

**Evidence**

```java
lifecycle.beginClear(projectId);
try { store.clear(projectId); }
finally { lifecycle.finishClear(projectId); }
```

**Description**：若 `store.clear` 失败（例如 Neo4j 不可用）而 `finishClear` 也失败（PostgreSQL 连接问题），`finally` 抛出的异常会替换原始异常，客户端只能看到后者；更重要的是 `finishClear` 失败会直接导致 `resetting` 残留（与 F1 同源）。

**Suggested Fix**：对 `finishClear` 做容错，保留原始失败为首要错误：

```java
try { store.clear(projectId); }
finally {
    try { lifecycle.finishClear(projectId); }
    catch (RuntimeException cleanupFailure) {
        log.error("Graph reset flag not cleared for project={}", projectId, cleanupFailure);
    }
}
```

（该修复不替代 F1 的恢复路径，仅避免错误掩盖与可观测缺失。）

## 四、无需修改（确认正确的部分）

- `canonical()` 通过 `JOIN graph_project_state ... AND NOT s.resetting` 实现代次隔离与清理期隐藏，读路径一致；旧 canonical 行保留供审计、旧 member 在清理开始删除，符合 ADR-0035 与数据架构文档。
- `graph_canonical_entity` 的 `ON CONFLICT ... DO UPDATE ... WHERE generation<>EXCLUDED.generation` + 后续 CAS 校验，正确解决了“稳定 ID 重建后旧合并复活”，且 `updateCanonical`/REFRESH_SOURCE 的 UPDATE 都补了 `AND generation=?`。
- `GraphResolutionService.suggest` 将已确认 member 解析为其 canonical 并按 ID 去重、过滤不可作为锚点的成员，方向正确；`byId` 为 `LinkedHashMap`，去重不破坏排序。
- `@JsonDeserialize(using=GraphModel.StrictVersion.class)` 复用于 `ConfirmRequest` 三个字段与 `UpdateCanonicalRequest` 两个字段，与 V3-04 既有严格版本输入约定一致。
- 未引入任何越权面：新增 `isCanonicalAnchor`/`canBeAnchor` 仅在已通过 `graph.entity(...)` → `ProjectAccess` 的调用链内使用，未暴露新的 HTTP 入口；`lockAvailable` 的 `ensure` 副作用位于 `@Transactional` 内，鉴权失败会回滚。
- V14 迁移对既有 `project` 回填 state、对既有 canonical 默认 generation=1，与代码默认值一致，未发现 NOT NULL/CHECK 与并发 DDL 问题。
- 未发现 Node 越界（未触碰 V3-07 GraphRAG 检索）、未发现敏感信息、未发现跨项目数据泄露。

## 五、主开发（Codex）评估回填区（预留）

| ID | 是否成立 | 事实判断与理由 | 处理决定（修复/不修复） | 关联修复提交 |
| --- | --- | --- | --- | --- |
| F1 | 是 | 活跃 reset 缺少失效接管会永久阻塞项目。 | 已修复：五分钟租约后可接管，finish 按 generation 条件更新，避免旧请求解除新接管状态。 | 本次提交 |
| F2 | 是 | clear 使用共享锁后升级排他锁会造成并发死锁。 | 已修复：beginClear 直接使用 FOR UPDATE，重叠请求确定性读取 resetting 并返回 503。 | 本次提交 |
| F3 | 是 | resetting 队首和被占用队列行可能造成忙等或阻塞其他项目。 | 已修复：候选查询排除 resetting 项目，未取得精确队列行时返回 false。 | 本次提交 |
| F4 | 部分成立 | 活跃/失效 reset 与数字字符串属于本次契约和 F1 回归；其余为建议性扩展。 | 已补 active reset 503、stale reset 接管及数字字符串 400；既有重建、旧待办、canonical 失效和重新确认覆盖核心清理契约。 | 本次提交 |
| F5 | 是 | suggestions 的 canonical source 409 未写入契约。 | 已修复：Core API 与 Entity Resolution 文档补充该 409 语义。 | 本次提交 |
| F6 | 部分成立 | 缺少最终失败日志和 cause 成立；当前并发失败的稳定驱动子类尚无可靠证据。 | 已增加结构化最终失败日志并保留 cause；只读重试仍有界为三次，以覆盖清理并发的驱动异常。 | 本次提交 |
| F7 | 是 | finally 异常会覆盖 Neo4j 原始失败。 | 已修复：保留原始异常，finish 失败作为 suppressed；无原始失败时仍正常传播 finish 失败。 | 本次提交 |

## 六、复审建议

修 F1 时请同时给出恢复正常路径的机器证据（例如预置 `resetting=true` 后 clear 的行为断言，或启动自愈断言），并重跑 `GraphResolutionIntegrationTest`、`GraphExtractionIntegrationTest` 与 `clean verify`；若 F2/F3 一并修复，请增补并发 clear 与 resetting 期间 worker 行为的可观测用例。
