# Pi 代码审查报告：v3-04-06-critical-fixes / Attempt 2

- 日期：2026-09-30
- 审查阶段：v3-04-06-critical-fixes
- 审查对象：WORKTREE@ebafcfd（基线：ebafcfd6991436a8f561c9a31d2cd6da4afb7d26）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-04～V3-06 严重缺陷修复 —— Milestone Review 报告（第 2 轮）

- 审查阶段：v3-04-06-critical-fixes
- 审查模式：Milestone / 第 2 轮（共 3 轮）
- 审查对象：`WORKTREE@ebafcfd`（18 个文件，+695/-32）
- 审查范围：本次 diff 全部文件 + 显式提供的 Node 协议与路线图上下文 + 上一轮 attempt-1 报告
- 只读声明：未运行任何命令、未修改任何文件与 Git 状态；结论均来自 diff 与本轮上下文
- 上一轮报告：`docs/08-reviews/2026-09-30-review-v3-04-06-critical-fixes-attempt-1.md`（NEEDS_FIX）

## 一、概述与总体结论

attempt-1 的七项 finding 在本次 diff 中已被实质性处理：

- F1：`GraphProjectLifecycle` 增加五分钟租约与 `stale` 判据，`beginClear` 在超时后接管；`finishClear(projectId, generation)` 改为按 generation 条件更新，旧请求无法解除新代次的 resetting，方向正确。
- F2：`beginClear` 已改用 `FOR UPDATE`，重叠 clear 由确定性排他锁串行化，而非依赖 PG 死锁检测。
- F3：worker 候选查询 `LEFT JOIN graph_project_state ... coalesce(s.resetting,false)=false` 排除 resetting 项目，未取得精确队列行时返回 `false`。
- F4/F5/F6/F7：新增活跃 reset 503、陈旧 reset 接管、JSON 小数与数字字符串 400 断言；`core-api.md`/`entity-resolution.md` 登记了 suggestions 409；Neo4j 只读重试增加最终失败日志与 cause；finally 异常不再覆盖原始失败。

`generation` 隔离查询、canonical upsert 的 `WHERE generation<>EXCLUDED.generation`、`AND generation=?` 的 CAS 更新、`StrictVersion` 复用、候选归一化去重等实现与 ADR-0035/功能文档一致，未发现跨项目泄露、越权面扩大或 Node 越界（未触碰 V3-07 GraphRAG 检索）。

但本轮修复引入/遗留一个**必须修改**的契约与健壮性缺陷：`confirm`、`updateCanonical`、`revert` 现在**先于项目存在性与权限校验**执行 `lifecycle.lockAvailable(projectId)`，其内部 `ensure` 会向 `graph_project_state.project_id REFERENCES project(id)` 插入行；对不存在的 `projectId`，请求会在到达 `graph.entity → ProjectAccess` 之前因外键失败而终止，原先稳定的 404 语义被破坏。此外存在一小时级别的租约设计残余风险与若干测试缺口，不阻塞。

结论：**需修复后交付（NEEDS_FIX）**，F1 为阻断项。

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| F1 | 中/高 | `GraphResolutionDecisionService.java` / `GraphProjectLifecycle.java` | L44 / L158 / L172 / L33-L35 | `lockAvailable`（含 `ensure` INSERT）先于 `ProjectAccess` 执行；不存在的 projectId 触发 `graph_project_state` 外键违规，404 契约被破坏 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| F2 | 中 | `GraphProjectLifecycle.java` / `GraphService.java` | L17-L30 / L34-L45 | 五分钟租约无心跳；清理本身超过五分钟时被第二个请求接管，原清理的 Neo4j DETACH DELETE 可能与已恢复的 worker 重建产生竞态 |
| F3 | 低 | `Neo4jGraphStore.java` | L38-L55 | 只读重试对所有 `Neo4jException`（含认证/配置等永久错误）一律重试 3 次，放大最坏延迟 |
| F4 | 低 | `GraphResolutionIntegrationTest.java` | 新增用例 | 缺少“真实并发/重叠 clear 503”“resetting 期间 worker 不推进”“不存在项目语义”“canonicalSourceVersion 数字字符串 400”等契约用例；现有 503 用例为直接改库模拟 |
| F5 | 低 | `GraphResolutionService.java` | L33-L52 | CONFIRMED 成员被解析为 canonical 作为候选，但 `ruleScore` 仍由成员名称/alias 计算，返回的候选分数与展示名可能不一致 |

### 无需修改

见第四节；未发现新增越权、跨项目泄露、Node 越界或敏感信息。

## 三、逐个 Issue 展开

### F1（必须修改，中/高）项目存在性/权限校验晚于 `graph_project_state` 写入

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionDecisionService.java` `confirm`（约 L44）、`updateCanonical`（约 L158）、`revert`（约 L172）；`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphProjectLifecycle.java` `ensure`（约 L33-L35）

**Evidence**

```java
@Transactional
public Decision confirm(UUID projectId, UUID entityId, AuthenticatedActor actor, ConfirmRequest request) {
    long generation=lifecycle.lockAvailable(projectId);   // ← 先执行，内部 ensure 会 INSERT
    var member=graph.entity(projectId,entityId,actor);    // ← 这里才通过 ProjectAccess 校验存在性/权限
    ...
}

@Transactional
public CanonicalView updateCanonical(...) {
    long generation=lifecycle.lockAvailable(projectId);   // ← 同上
    var current=canonicalView(projectId,canonicalId,actor);
    ...
}

@Transactional
public void revert(...) {
    lifecycle.lockAvailable(projectId);                   // ← 同上
    var entity=graph.entity(projectId,entityId,actor);
    ...
}
```

```java
private void ensure(UUID projectId) {
    jdbc.update("INSERT INTO graph_project_state(project_id) VALUES (?) ON CONFLICT DO NOTHING", projectId);
}
```

```sql
-- V14__graph_clear_generation.sql
project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
```

**Description**：`lockAvailable` → `ensure` 会向 `graph_project_state` 插入 `project_id`，该列对 `project(id)` 有外键约束。对路径中不存在（或已被删除）的 `projectId`：

- `INSERT ... ON CONFLICT DO NOTHING` 不覆盖外键违规，抛出 `DataIntegrityViolationException`；
- 该异常发生在 `graph.entity → projects.requireAccess` 之前，因此原 API 文档承诺的 404/403 路径无法到达，Spring 默认会返回 500（即使存在全局异常处理器，也几乎不会是 404）；
- `suggest` 仍以 `graph.entity` 开头，保持原语义，三条写路径与 `suggest` 语义不一致。

现有集成测试均先 `fixture()` 创建项目，未覆盖不存在项目，所以该回归不会被现有绿灯发现。这不是安全越权（未授权用户对已存在项目仍会在 `graph.entity` 处 403 并回滚 `ensure`），但属于真实契约回归与不佳的错误语义。

**Suggested Fix**（最小改动：先校验存在性与权限，再取生命周期锁；或让 `lockAvailable` 不负责创建行）：

```java
@Transactional
public Decision confirm(UUID projectId, UUID entityId, AuthenticatedActor actor, ConfirmRequest request) {
    projects.requireAccess(projectId, actor);   // 或在 lockAvailable 内部先 requireAccess
    long generation=lifecycle.lockAvailable(projectId);
    var member=graph.entity(projectId,entityId,actor);
    ...
}
```

（`GraphProjectLifecycle` 可注入 `ProjectService` 并在 `ensure` 前调用 `requireAccess`，使三条路径统一；这样对不存在项目恢复 404、对越权恢复 403。）

### F2（建议修改，中）五分钟租约无心跳，慢清理被接管后与恢复的 worker 竞态

**File & Line**：`GraphProjectLifecycle.java` `beginClear`/`state`（约 L17-L30、L56-L63）；`GraphService.java` `clear`（约 L34-L45）

**Evidence**

```java
if (state.resetting() && !state.stale())
    throw new ServiceUnavailableException("Graph reset is in progress.");
long generation = state.generation() + 1;   // stale 时直接接管
```

```java
long generation=lifecycle.beginClear(projectId);
try { store.clear(projectId); }
...
lifecycle.finishClear(projectId,generation);   // 新代次清理完成后恢复 resetting=false
```

**Description**：租约只以 `updated_at` 判断陈旧，没有“清理进行中”的续约或互斥机制。若某次清理在 Neo4j 上确实耗时超过五分钟（大图 `DETACH DELETE`、Neo4j 慢/抖动），第二个 `DELETE ...?confirm=true` 会以新 generation 接管并完成 `finishClear`，worker 随即恢复同步；而原请求的 `store.clear` 可能仍在运行，其 `MATCH ... DETACH DELETE` 会删除 worker 刚重建的节点，且对应 `graph_source_sync` 行已被 worker 消费，投影将持续缺失直到人工重建。图是派生数据、可重建，因此不构成阻断，但这正是“清理与同步串行化”要防的竞态在租约过期窗口下重新出现。

**Suggested Fix**：为清理增加心跳（在 `store.clear` 期间周期性 `UPDATE graph_project_state SET updated_at=now() WHERE project_id=? AND generation=?`），或让接管失败方以 generation 条件主动中止旧请求的 Neo4j 清理；至少在接受接管时验证原请求已不可能继续（例如同一 generation 的终止标记）。

### F3（建议修改，低）只读重试未区分瞬态与永久错误

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java` `transaction`（约 L38-L55）

**Evidence**

```java
int attempts=write ? 1 : 3;
for(int attempt=1;attempt<=attempts;attempt++) {
    try(var session=driver().session()) {
        return write ? session.executeWrite(work::apply, TX) : session.executeRead(work::apply, TX);
    } catch (Neo4jException failure) {
        if(attempt==attempts) { log.warn(...); throw new ServiceUnavailableException("Graph service is unavailable.",failure); }
    } catch (IllegalArgumentException failure) {
        throw new ServiceUnavailableException("Graph service is unavailable.");
    }
}
```

**Description**：相比 attempt-1，日志与 cause 已补齐，方向正确；但认证失败、配置错误等不可恢复的 `Neo4jException` 同样会重试三次，叠加 `withConnectionTimeout(2s)`/`withConnectionAcquisitionTimeout(3s)`，最坏情况显著放大故障延迟。对只读查询而言，这会使依赖故障时的响应变慢。

**Suggested Fix**：仅对可重试子类（如 `ServiceUnavailableException`、`TransientException`、`SessionExpiredException`）重试，其余立即映射为 503：

```java
catch (Neo4jException failure) {
    boolean retryable = failure instanceof org.neo4j.driver.exceptions.TransientException
        || failure instanceof org.neo4j.driver.exceptions.SessionExpiredException
        || failure instanceof org.neo4j.driver.exceptions.ServiceUnavailableException;
    if (attempt == attempts || !retryable) {
        log.warn("Graph transaction failed, attempt={}, write={}, failure={}", attempt, write, failure.getClass().getSimpleName());
        throw new ServiceUnavailableException("Graph service is unavailable.", failure);
    }
}
```

### F4（建议修改，低）新增测试仍缺多条已登记契约

**File & Line**：`services/core-api/src/test/java/com/agentforge/core/graph/GraphResolutionIntegrationTest.java` 新增用例

**Description**：本轮新增了严格版本 400、清理后失效与重新确认、候选过滤，覆盖核心修复。但以下已写入文档/ADR 的行为仍无自动化证据：

1. 真实并发/重叠 `DELETE graph?confirm=true` 的 503（当前用例直接 `UPDATE graph_project_state SET resetting=true` 模拟，未覆盖 `FOR UPDATE` 串行化路径）；
2. resetting 期间 worker 不再处理该项目、也不阻塞其他项目（F3 修复方向）；
3. 不存在 projectId 的请求语义（对应 F1，可暴露当前外键异常路径）；
4. `canonicalSourceVersion` 的数字字符串（如 `"0"`）在 resolution 端点返回 400（当前只覆盖了 `expectedVersion` 字符串与两个小数）。

**Suggested Fix**：补 1 个真实两请求并发 clear 的 503 用例、1 个“不存在项目返回 404 而非 500”的用例；并把 `resolutionVersionsRequireJsonIntegers` 扩展到 `canonicalSourceVersion:"0"`。

### F5（建议修改，低）canonical 候选的 `ruleScore` 与展示名不匹配

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionService.java` `suggest`（约 L33-L52）

**Evidence**

```java
if(decision.status().equals("CONFIRMED")) {
    for(String alias:decision.aliases())
        score=Math.max(score,similarity(normalized,normalize(alias)));
    try { current=graph.entity(projectId,decision.canonicalEntityId(),actor); }
    catch (ResourceNotFoundException expired) { continue; }
} else if(!decisions.canBeAnchor(projectId,current.id())) continue;
...
byId.merge(candidate.entityId(),candidate,
    (left,right) -> left.ruleScore()>=right.ruleScore() ? left : right);
```

**Description**：当命中 CONFIRMED 成员时，`score` 由成员名称/alias 计算，但候选对象使用 `current.displayName()`（canonical 的名称）。排序与 `ruleScore` 反映的是成员与源实体的相似度，展示的却是 canonical 名称，可能推荐出一个“分数很高但名称并不相近”的候选，影响人工判读（不影响确认接口的正确性）。

**Suggested Fix**：在替换为 canonical 后，用 `similarity(normalized, normalize(current.displayName()))` 重新计算（或同时保留成员分数作为 alias 命中说明），避免分数与展示名脱节。

## 四、无需修改（确认正确的部分）

- `canonical()` 通过 `JOIN graph_project_state s ON s.project_id=c.project_id AND s.generation=c.generation ... AND NOT s.resetting` 实现代次隔离与清理期隐藏；旧 canonical 行保留供审计，旧 member 在 `beginClear` 删除，符合 ADR-0035 与数据架构文档。
- `graph_canonical_entity` 的 `ON CONFLICT ... DO UPDATE ... WHERE generation<>EXCLUDED.generation` + 后续一致性校验与 CAS，正确解决稳定 ID 重建后旧合并复活；`updateCanonical`/REFRESH_SOURCE 的 UPDATE 均补 `AND generation=?`。
- `beginClear` 使用 `FOR UPDATE`、`lockAvailable`/`lockForSync` 使用 `FOR SHARE`；clear 与 worker/写路径的加锁顺序均为“先 project_state、后业务行”，未发现死锁方向。
- `finishClear(projectId, generation)` 的 generation 条件保证旧请求不能解除新接管代次，符合 ADR-0035“旧请求只能解除自己 generation 的状态”。
- `GraphService.clear` 失败路径保留原始异常、`finishClear` 失败作为 suppressed；成功路径正常收敛。
- `StrictVersion` 复用于 `ConfirmRequest` 三字段与 `UpdateCanonicalRequest` 两字段，与 V3-04 既有严格版本约定一致。
- 新增 `isCanonicalAnchor`/`canBeAnchor` 仅在已通过 `graph.entity` 的调用链内使用，未新增 HTTP 入口；未授权用户对已存在项目会触发 403 且回滚 `ensure`。
- V14 迁移：`graph_project_state` 回填 `project`、`graph_canonical_entity.generation` 默认 1 与代码默认一致，CHECK/索引无问题。
- 未发现 Node 越界（未实现 V3-07 检索/回答）、未发现敏感信息、未发现跨项目数据泄露。

## 五、主开发（Codex）评估回填区（预留）

| ID | 是否成立 | 事实判断与理由 | 处理决定（修复/不修复） | 关联修复提交 |
| --- | --- | --- | --- | --- |
| F1 | 是 | lifecycle.ensure 在项目检查前触发外键冲突，HTTP 实测由 404 回归为 409。 | 已修复：三条写路径先调用 ProjectAccess，再取生命周期共享锁，保持清理串行化顺序；新增 HTTP 回归由 409 红灯转为 404 绿灯。 | 本次提交 |
| F2 | 是，非阻断 | 无心跳的五分钟租约在单次清理超过租约时可能被接管；图是派生投影，可重建。 | 本次不引入后台心跳；作为已知运行限制记录，后续结合真实大图清理时长再确定租约续期方案。 | 不修复 |
| F3 | 部分成立，非阻断 | 永久错误也会进行最多三次只读尝试，存在有限延迟放大；当前并发异常缺少稳定子类证据。 | 保留三次有界重读、最终结构化日志与 cause，不基于未证实类型缩窄。 | 不修复 |
| F4 | 部分成立 | 不存在项目是本轮阻断回归；其余为覆盖增强建议。 | 已补不存在项目返回 404 的 HTTP 用例；现有用例覆盖 active/stale reset、旧待办删除、重建后失效与并发读清理。 | 部分修复 |
| F5 | 是，非阻断 | alias 命中分数可能高于 canonical 展示名的直接相似度，但该分数仍表达映射别名证据。 | 保留 alias 证据参与候选排序；不影响锚点合法性、确认 CAS 或数据一致性。 | 不修复 |

## 六、复审建议

修 F1 时请给出“不存在项目返回 404 而非 500”的 HTTP 级机器证据，并重跑 `GraphResolutionIntegrationTest`、`GraphExtractionIntegrationTest` 与 `clean verify`；若一并处理 F2/F4，请补真实并发 clear 与 resetting 期间 worker 行为的用例。
