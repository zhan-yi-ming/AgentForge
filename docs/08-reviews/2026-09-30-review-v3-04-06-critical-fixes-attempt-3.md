# Pi 代码审查报告：v3-04-06-critical-fixes / Attempt 3

- 日期：2026-09-30
- 审查阶段：v3-04-06-critical-fixes
- 审查对象：WORKTREE@ebafcfd（基线：ebafcfd6991436a8f561c9a31d2cd6da4afb7d26）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-04～06 严重缺陷修复 —— Milestone Review 报告（第 3 轮）

- 审查阶段：v3-04-06-critical-fixes
- 审查模式：Milestone / 第 3 轮（共 3 轮）
- 审查对象：`WORKTREE@ebafcfd`（19 个文件，+958/-32）
- 审查范围：本次 diff 全部文件 + 显式提供的 Node 协议/路线图上下文 + attempt-1 / attempt-2 报告
- 只读声明：未运行任何命令、未修改任何文件与 Git 状态；结论均来自 diff 与本轮上下文
- 上一轮报告：attempt-1（NEEDS_FIX）、attempt-2（NEEDS_FIX）

## 一、概述与总体结论

attempt-1、attempt-2 的全部阻断项在本轮 diff 中已被实质性、可验证地处理：

- **attempt-2 F1（阻断）已修复**：`confirm` / `updateCanonical` / `revert` 三条写路径现在均以 `projects.requireAccess(projectId, actor)` 开头，再调用 `lifecycle.lockAvailable(projectId)`，随后才走到 `graph.entity`。不存在项目不会再先触发 `graph_project_state` 外键写入；新增 `resolutionWriteForMissingProjectReturnsNotFound` 断言 404。
- **attempt-1 F1（阻断）已修复**：`beginClear` 引入 5 分钟 `stale` 判据，陈旧 `resetting` 由新 generation 接管；`finishClear(projectId, generation)` 以 generation 条件更新，旧请求无法解除新代次状态。
- **attempt-1 F2 已修复**：`beginClear` 直接使用 `FOR UPDATE`，重叠 clear 确定性串行化，不再依赖 PG 死锁检测；`lockAvailable`/`lockForSync` 保持 `FOR SHARE`。
- **attempt-1 F3 已修复**：worker 候选查询 `LEFT JOIN graph_project_state ... coalesce(s.resetting,false)=false` 排除 resetting 项目，且未取得精确队列行时返回 `false`，消除忙等与队首阻塞。
- F4～F7：严格版本输入、suggestions 409 文档、Neo4j 最终失败日志与 cause、finally 不覆盖原始异常，均已落实。

`generation` 隔离读（`canonical()` JOIN `graph_project_state ... AND NOT s.resetting`）、`ON CONFLICT (project_id,id) DO UPDATE ... WHERE generation<>EXCLUDED.generation`、`AND generation=?` 的 CAS 更新、`StrictVersion` 复用、候选人 canonical 归一化去重等实现与 ADR-0035、数据架构、Core API、Entity Resolution 文档一致。未发现越权、跨项目泄露、API 契约回归、Node 越界（未触碰 V3-07 检索/回答）或敏感信息。

**结论：通过（PASS）。** 无“必须修改”项；以下均为建议性观察，不阻塞交付。

## 二、详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | 中 | `GraphProjectLifecycle.java` / `GraphService.java` | L17-L30 / L34-L45 | 5 分钟租约无心跳，单次清理超时被接管后，原请求的 Neo4j `DETACH DELETE` 仍可能在接管方恢复 worker 后删除其重建节点 |
| S2 | 低 | `Neo4jGraphStore.java` | L38-L55 | 只读重试对所有 `Neo4jException`（含认证/配置等永久错误）一律重试 3 次，放大最坏延迟 |
| S3 | 低 | `GraphResolutionService.java` | L33-L52 | CONFIRMED 成员解析为 canonical 后，`ruleScore` 仍由成员名称/alias 计算，返回的分数与展示的 canonical 名称可能不一致 |
| S4 | 低 | `GraphResolutionIntegrationTest.java` | 新增用例 | 缺“真实两请求并发 clear 503”“`canonicalSourceVersion:"0"` 数字字符串 400”“`updateCanonical`/`revert` 不存在项目语义”等已登记契约用例 |
| S5 | 低 | `GraphProjectLifecycle.java` | `beginClear` | 失败清理后重试会删除失败窗口内新登记的 `graph_source_sync`，使这些来源不再自动重填（可经 `extraction/rebuild` 恢复） |

### 无需修改

- 三条消歧写路径的鉴权/存在性校验先于 lifecycle 锁（attempt-2 F1 修复正确）。
- `beginClear` 的 `FOR UPDATE`、`lockForSync`/`lockAvailable` 的 `FOR SHARE`，加锁顺序统一为“先 project_state、后业务行”，无死锁方向。
- `generation` 隔离与 canonical upsert/update 的代次条件、旧 member 清理、旧 canonical 留审计，符合 ADR-0035。
- `StrictVersion` 复用于 `ConfirmRequest` 三字段与 `UpdateCanonicalRequest` 两字段。
- V14 迁移回填、默认值、CHECK 与索引正确。
- 未发现越权、跨项目泄露、Node 越界或敏感信息。

## 三、逐个 Issue 展开

### S1（建议修改，中）清理租约无心跳，慢清理与接管后 worker 竞态

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphProjectLifecycle.java` `beginClear`/`state`（约 L17-L30、L56-L63）；`GraphService.java` `clear`（约 L34-L45）

**Evidence**

```java
if (state.resetting() && !state.stale())
    throw new ServiceUnavailableException("Graph reset is in progress.");
long generation = state.generation() + 1;   // stale 后直接接管
```

```java
long generation=lifecycle.beginClear(projectId);
try { store.clear(projectId); }              // Neo4j DETACH DELETE，无续约
...
lifecycle.finishClear(projectId,generation);
```

**Description**：租约仅以 `updated_at` 判断，没有清理期间续约或对原请求的中止。若单次 Neo4j 清理超过 5 分钟，第二个 clear 会以新 generation 接管并 `finishClear` 恢复 worker，而原请求的 `DETACH DELETE` 可能仍在运行，删除 worker 刚重建的节点；对应 `graph_source_sync` 行已被消费，投影会缺失直至人工重建。图是可重建派生数据，且代码已通过 generation 条件避免旧 finish 解除新状态，因此不构成阻断，但“清理与同步串行化”在租约过期窗口下出现缺口。

**Suggested Fix**：清理期间按 generation 条件周期性更新 `updated_at` 作为心跳，或让接管方以 generation 条件终止旧清理；至少将“单次清理超过租约”作为文档化运行限制（变更记录已记录该限制，建议同步到 ADR-0035/运行手册）。

### S2（建议修改，低）只读重试未区分瞬态与永久错误

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java` `transaction`（约 L38-L55）

**Evidence**

```java
int attempts=write ? 1 : 3;
...
} catch (Neo4jException failure) {
    if(attempt==attempts) { log.warn(...); throw new ServiceUnavailableException("Graph service is unavailable.",failure); }
}
```

**Description**：日志与 cause 已补齐（较 attempt-1 有改进），但认证/配置等永久性 `Neo4jException` 同样重试 3 次，叠加 `withConnectionTimeout(2s)`/`withConnectionAcquisitionTimeout(3s)`，最坏延迟被放大。

**Suggested Fix**：仅对 `TransientException`、`SessionExpiredException`、驱动 `ServiceUnavailableException` 等可重试子类重试，其余立即映射 503。

### S3（建议修改，低）canonical 候选 `ruleScore` 与展示名可能不一致

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionService.java` `suggest`（约 L33-L52）

**Evidence**

```java
if(decision.status().equals("CONFIRMED")) {
    for(String alias:decision.aliases())
        score=Math.max(score,similarity(normalized,normalize(alias)));
    try { current=graph.entity(projectId,decision.canonicalEntityId(),actor); }
    catch (ResourceNotFoundException expired) { continue; }
} ...
var candidate=new Candidate(current.id(),current.displayName(),score);
```

**Description**：命中 CONFIRMED 成员时，分数由成员名称/alias 计算，但候选展示的是 canonical 名称，可能给出“分数高但名称不相近”的候选，影响人工判读。不影响确认接口正确性与数据一致性。

**Suggested Fix**：替换为 canonical 后用 `similarity(normalized, normalize(current.displayName()))` 重算，或同时保留 alias 命中说明。

### S4（建议修改，低）新增测试仍缺若干已登记契约

**File & Line**：`services/core-api/src/test/java/com/agentforge/core/graph/GraphResolutionIntegrationTest.java` 新增用例

**Description**：本轮已覆盖严格版本 400（含小数与 `expectedVersion:"0"`）、清理失效与重新确认、候选锚点过滤、不存在项目 404。以下文档化行为仍缺自动化证据：

1. 真实两请求并发 `DELETE graph?confirm=true` 的 503（现有 503 用例直接改库模拟，未覆盖 `FOR UPDATE` 串行化路径）；
2. `canonicalSourceVersion:"0"` 等数字字符串在 resolution 端点的 400；
3. `updateCanonical` / `revert` 对不存在项目返回 404；
4. resetting 期间 worker 不处理该项目、也不阻塞其他项目。

**Suggested Fix**：补 1 个真实并发 clear 503 用例、扩展严格版本用例到 `canonicalSourceVersion` 字符串分支、补 `revert`/`PUT canonicals` 的 404 用例。

### S5（建议修改，低）失败清理重试会删除失败窗口内新登记的待办

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphProjectLifecycle.java` `beginClear`

**Evidence**

```java
jdbc.update("DELETE FROM graph_source_sync WHERE project_id=?", projectId);
```

**Description**：清理失败后 `finishClear` 会置回 `resetting=false`。若此后业务产生新的 `graph_source_sync` 行，用户重试清理时 `beginClear` 会一并删除这些新行；重试成功后对应来源不会自动重填，需人工 `extraction/rebuild`。图是派生投影，可重建，故不阻断。

**Suggested Fix**：无须强制改动；可在运行手册确认“清理后需显式 rebuild”的语义，或将删除范围限定为清理开始时间之前的待办。

## 四、无需修改（确认正确的部分）

- `confirm`/`updateCanonical`/`revert` 均先 `projects.requireAccess` 再 `lifecycle.lockAvailable`，保持“先 project_state 共享锁、后业务行”的加锁顺序，且修复了 attempt-2 F1 的外键先行写入问题。
- `beginClear` 的 `FOR UPDATE` + `lockForSync`/`lockAvailable` 的 `FOR SHARE`：clear 与在途 worker/写路径互斥，重叠 clear 由 `resetting && !stale` 确定性返回 503。
- `finishClear(projectId, generation)` 的 generation 条件保证旧请求不能解除新接管状态，符合 ADR-0035。
- `canonical()` 的 generation 隔离 + `NOT s.resetting`；canonical upsert 的 `WHERE generation<>EXCLUDED.generation`；`updateCanonical`/`REFRESH_SOURCE` 的 `AND generation=?`。
- `suggest` 对当前代次 canonical 源实体返回 409，并把已确认成员解析为 canonical、按 ID 去重、过滤不可作为锚点的成员；`isCanonicalAnchor`/`canBeAnchor` 仅在已通过 `graph.entity` 的鉴权链内使用。
- `StrictVersion` 复用于 `ConfirmRequest` 三字段与 `UpdateCanonicalRequest` 两字段。
- `GraphService.clear` 失败路径保留原始异常、`finishClear` 失败作为 suppressed。
- V14 迁移对既有 `project` 回填 state、对既有 canonical 默认 generation=1，与代码默认一致。
- 未发现越权/跨项目泄露、Node 越界（未实现 V3-07 检索/回答）或敏感信息。

## 五、主开发（Codex）评估回填区（预留）

| ID | 是否成立 | 事实判断与理由 | 处理决定（修复/不修复） | 关联修复提交 |
| --- | --- | --- | --- | --- |
| S1 | 是，非阻断 | 超过五分钟的单次清理可能与接管后的同步重叠，派生投影可通过 rebuild 恢复。 | 已同步 ADR、运行手册和变更记录；本次无真实超时证据，不新增后台心跳。 | 文档修复 |
| S2 | 部分成立，非阻断 | 永久错误存在有限重试延迟；并发清理异常缺少稳定可重试子类证据。 | 保留三次有界只读重试、最终日志和 cause。 | 不修复 |
| S3 | 是，非阻断 | 分数可来自成员 alias，而展示 canonical 名称；该分数表达映射别名证据。 | 保留 alias 证据参与排序，不影响合法性或一致性。 | 不修复 |
| S4 | 是，非阻断 | 所列为覆盖增强，当前核心缺陷均已有 HTTP/集成回归。 | 不继续增加同义或低收益测试。 | 不修复 |
| S5 | 是，非阻断 | 重试会清除失败窗口内待办，显式 rebuild 可恢复派生投影。 | 运行手册明确成功清理及失败重试后执行 rebuild。 | 文档修复 |

## 六、复审建议

本轮无阻断项。若后续采纳 S1，请为清理增加心跳或以 generation 条件中止旧请求，并补“单次清理超过租约”的可观测断言；重跑 `GraphResolutionIntegrationTest`、`GraphExtractionIntegrationTest` 与 `mvnw clean verify`。若仅采纳测试增强（S4），重跑相关图集成套件即可。
