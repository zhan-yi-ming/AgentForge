# Pi 代码审查报告：v3-05-graph-extraction / Attempt 1

- 日期：2026-09-28
- 审查阶段：v3-05-graph-extraction
- 审查对象：INDEX@1160428（基线：1160428c72e6c2eeda559160bc47e1676f8dff05）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-05 Graph Extraction Pipeline — Milestone Review（第 1 轮）

## 一、概述与总体结论

- **审查范围**：Commit `INDEX@1160428` 的 V3-05 交付，含 27 个文件（1 个 migration、5 个新增 Java 文件、4 个改动 Java 文件、1 个新增集成测试、22 个文档）。
- **节点目标核对**：把 Wiki/Task 显式语法行转换为带 `source_document / source_chunk / evidence(position+excerpt) / confidence` 的 Entity/Relation，并覆盖新增、更新、删除的生命周期与可追溯 evidence。**已达成**，且未越界实现 V3-06（消歧/merge）或 V3-07（GraphRAG 检索/回答），未引入 LLM 判关系、未改 `/wiki/graph`。
- **边界核对**：Java 独占图写入，Python/LLM 无入口；抽取逻辑位于 `graph/domain`，worker 位于 `graph/application`，Neo4j 细节封在 `infrastructure`；与 V3-04 的手工 Graph API 通过 `origin` 隔离，自动投影按来源替换，手工证据在来源更新时保留、来源删除时按 ADR-0033 清理。
- **测试证据**：新增 `GraphExtractionIntegrationTest`（重建权限、生命周期、重复行去重、API 语法校验、故障保留+重建恢复、手工关系保留、手工失效 evidence 物理清理、状态统计）；V3-04 用例通过 `sync.enabled=false` + `@DirtiesContext` 与新特性隔离。真实 HTTP + PostgreSQL + Neo4j Testcontainers 覆盖新增/更新/删除与项目隔离，符合文档承诺。
- **总体结论**：**通过（可交付）**。未发现具备明确证据的可运行性、正确性、安全、权限、并发、幂等、数据一致性或契约缺陷。存在若干文档一致性与运行期效率方面的改进点，均归入“建议修改”，不阻塞本节点。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| A-1 | 建议（中） | `docs/03-features/graph-domain-model.md` | “API 与失败语义”段末 | 残留“无自动重建/同步编排”措辞，与 V3-05 已实现的自动来源同步直接矛盾 |
| A-2 | 建议（中） | `GraphSyncProcessor.java` / `GraphSourceSyncQueue.java` / `ADR-0033` / `backend-architecture.md` | 全文件 | ADR 声明“来源读取端口”，实现直接对 `wiki_page`/`task_item` 执行 SQL，与 V3-04 段落“不读取其他模块持久化实现”的边界声明不一致 |
| A-3 | 建议（中） | `GraphSyncProcessor.java` | `processOne()` 19–35 | 在 PostgreSQL 行锁（`FOR UPDATE SKIP LOCKED`）内执行 Neo4j 网络调用；同一来源的 `mark` 会被阻塞至多一个 Neo4j 事务超时 |
| A-4 | 建议（低） | `GraphExtraction.java` | `extract` 55–66 | 抽取 evidence 的 `excerpt` 未限制 1..2000；首/尾空白可绕过 200 字符上限，产出超出 V3-04 evidence 契约的值 |
| A-5 | 建议（低） | `GraphExtractionIntegrationTest.java` | 全文件 | 50 行 / 200 字符上限的失败分支无自动化覆盖，且失败仅表现为无限 retry，缺少可观测的终态语义验证 |
| A-6 | 建议（低） | `docs/README.md` | `01-product/` 列表 | `03-features/graph-extraction.md` 条目被放入“01-product”小节，位置与分类错误 |
| A-7 | 建议（低） | `Neo4jGraphStore.java` | `replaceSource` 实体 MERGE | 抽取 MERGE 仅 `ON CREATE SET n.version=1`，刷新不递增实体版本，手工 CAS 无法感知抽取侧并发更新 |

> 无“必须修改”项；以下逐条展开建议修改与确认无需修改项。

---

## 三、逐 Issue 展开

### A-1（建议，中）残留“无自动重建/同步编排”表述

- **File & Line**：`docs/03-features/graph-domain-model.md`，“API 与失败语义”段落（本次 diff 未触及该句）。
- **Evidence（当前文档原文）**：
  > 显式 DELETE graph?confirm=true 清理整个项目投影并保留 schema/project lock，用于人工重建；**无自动重建/同步编排**。
- **Description**：V3-05 新增 `GraphSyncScheduler`/`GraphSyncProcessor` 与 `Wiki/Task` CRUD 内的 `mark`，正是“自动来源同步编排”，且新文档 `graph-extraction.md`、ADR-0033、`backend-architecture.md` 均描述该能力。同一交付内两处描述互相矛盾，违背本项目“文档先行、文档与实现一致”的门禁口径。
- **Suggested Fix**：将该句改为“用于人工重建；自动来源同步编排见 [Graph Extraction Pipeline](../03-features/graph-extraction.md) 与 ADR-0033，`DELETE` 仅清理投影并登记人工重建入口”，保持与 V3-05 文档一致。

### A-2（建议，中）来源读取“端口”声明与实现/边界声明不一致

- **File & Line**：`services/core-api/.../graph/application/GraphSyncProcessor.java#read`、`GraphSourceSyncQueue.java#rebuild`；对照 `docs/02-architecture/decisions/ADR-0033-durable-graph-source-sync.md` 与 `docs/02-architecture/backend-architecture.md`（V3-04 段）。
- **Evidence**：
  ```java
  String sql=row.type()==SourceType.WIKI
      ? "SELECT title, content, version FROM wiki_page WHERE project_id=? AND id=?"
      : "SELECT title, description, version FROM task_item WHERE project_id=? AND id=?";
  ```
  ```java
  INSERT INTO graph_source_sync(project_id, source_type, source_id)
  SELECT project_id, 'WIKI', id FROM wiki_page WHERE project_id=?
  ```
  对照 ADR-0033：
  > worker 使用仅内部的、project/type/id 约束的 PostgreSQL 来源读取端口；该端口不暴露 HTTP…
  对照 backend-architecture（V3-04）：
  > graph/api → graph/application → graph/domain port → graph/infrastructure Neo4j driver。应用层复用 ProjectAccess 与 Wiki/Task/Project 公开查询；**不读取其他模块持久化实现**。
- **Description**：实现以 `JdbcTemplate` 直接读 `wiki_page`/`task_item` 物理表，跨过了 wiki/task 模块的公开查询边界，也未形成 ADR 所称的“端口”。该做法可解释（避免与 `WikiPageService` 形成循环依赖），但当前文档同时声称“有端口”与“不读取其他模块持久化”，与代码三方不一致。
- **Suggested Fix**：二选一并保持文档一致：① 在 wiki/task 模块内定义仅内部可见的只读端口（如 `WikiSourceReader`/`TaskSourceReader`），由 graph worker 依赖；或 ② 若接受直接读取，修改 ADR-0033 与 backend-architecture 措辞，明确“以受限 SQL 只读端口跨模块读取当前业务事实”的取舍，删除“不读取其他模块持久化实现”的冲突表述。

### A-3（建议，中）PG 行锁跨越 Neo4j 网络调用，可能阻塞业务写入

- **File & Line**：`services/core-api/.../graph/application/GraphSyncProcessor.java`，`processOne()`（`SELECT … FOR UPDATE SKIP LOCKED` → `store.replaceSource(…)` → `DELETE`）。
- **Evidence**：
  ```java
  @Transactional
  public boolean processOne() {
      ...
      var rows=jdbc.query("""
          SELECT project_id, source_type, source_id FROM graph_source_sync
          WHERE next_attempt_at<=now()
          ORDER BY next_attempt_at, updated_at
          LIMIT 1 FOR UPDATE SKIP LOCKED
          """, ...);
      var row=rows.getFirst();
      try {
          var document=read(row);
          store.replaceSource(row.projectId(),row.type(),row.sourceId(), ...); // 外部 Neo4j 调用
          jdbc.update("DELETE FROM graph_source_sync ...");
      } ...
  }
  ```
  同时 `GraphSourceSyncQueue.mark`（在 Wiki/Task 业务事务内）对同一主键执行 `ON CONFLICT … DO UPDATE`：
  ```java
  INSERT INTO graph_source_sync(...) VALUES (?,?,?)
  ON CONFLICT (project_id, source_type, source_id)
  DO UPDATE SET updated_at=now(), next_attempt_at=now(), attempts=0
  ```
- **Description**：worker 在持有该待办行锁的整个 PG 事务期间执行 Neo4j 事务（文档标注 5 秒超时）。同一来源的用户 Wiki/Task 写入会因 `mark` 的 upsert 需要同一行锁而被阻塞至多约一个 Neo4j 事务时长。ADR-0033 已把“持锁跨库处理有短时资源占用”列为取舍，故不构成契约破坏，但在 Neo4j 故障/慢响应时会把图同步延迟放大到用户写入路径。
- **Suggested Fix**：建议改为“先提交领取、后处理”的两段式：在同一短事务内 `UPDATE … SET attempts=attempts+1, next_attempt_at=now()+interval '30 seconds' RETURNING`（或写入 processing 标记）并提交释放行锁，再在事务外执行业务读取与 Neo4j 替换，最后用短事务删除待办；失败时由退避时间自然重试。若无改动，建议在文档中把“短时资源占用”的量级明确为“可叠加至一次 Neo4j 事务超时”。

### A-4（建议，低）抽取 evidence 未强制 1..2000 长度上限

- **File & Line**：`services/core-api/.../graph/domain/GraphExtraction.java`，`extract`（校验仅覆盖目标名 200 字符）。
- **Evidence**：
  ```java
  if (allowed && !name.isEmpty()) {
      if (name.length() > 200) throw new IllegalArgumentException("Graph extraction target exceeds 200 characters.");
      ...
      var evidence = new Evidence(evidenceId, document.projectId(), source, lineNumber, offset, lineEnd, line, 1.0, 1);
  }
  ```
  正则允许任意首/尾空白：`^\\s*(?:[-*]\\s*)?(Tag):\\s*(.*?)\\s*$`，`excerpt = line` 为整行。
- **Description**：`graph-domain-model.md` 规定 evidence `excerpt` 长度 1..2000，该约束由 V3-04 的 `GraphService` 写路径校验。抽取路径经 `GraphStore.replaceSource` 直接写 Neo4j，绕过该校验；含大量首/尾空白（`name` 仍 ≤200）的行可产出 >2000 的 `excerpt`，使派生图出现超出 V3-04 证据契约的数据。当前读路径按来源版本校验并逐字比对，不会因此报错，故影响有限。
- **Suggested Fix**：在抽取侧对整行 `lineEnd - offset` 增加 `1..2000` 校验（超限按现有 `IllegalArgumentException` 处理并保持“不发布部分投影”），或在 `graph-extraction.md` 显式声明“excerpt 上限沿用 V3-04 的 2000，超限整源失败”，使契约单一化。

### A-5（建议，低）上限失败分支缺少自动化测试与终态语义

- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/graph/GraphExtractionIntegrationTest.java`（全文件）。
- **Evidence**：现有 12 项用例覆盖重建权限、新增/更新/删除、重复行、API 语法、故障保留+重建、手工关系保留、手工 evidence 清理、状态统计；未见对 `name.length()>200` 与 `matched>50` 抛错的覆盖。
- **Description**：这两个分支属于永久性失败，当前实现只会 `attempts+1` 并无限重试（30s 退避），状态永远是 `retrying`。文档已承认“无限重试的错误文档仍显示 retrying 且不发布部分投影”，但缺少测试。这不影响正确性，但使“失败可观测/可区分永久错误”的语义未被回归保护。
- **Suggested Fix**：补充一个用例，构造 >50 条有效行（或 >200 字符目标名），断言 `GET /extraction/status` 的 `retrying` 增长、`pending` 不消失、且图 API 不返回部分投影；未来可据此判定是否需要死信标记。此为非阻塞测试完善建议。

### A-6（建议，低）`docs/README.md` 条目分类位置错误

- **File & Line**：`docs/README.md`，`### 01-product/：做什么与何时做` 列表末尾。
- **Evidence**：
  ```diff
  - `v3-04-development-plan.md`：V3-04 图领域模型开发计划（Implemented；GraphRAG 仍 Planned）。
  +- `03-features/graph-extraction.md`：V3-05 显式证据抽取与可恢复来源生命周期（Implemented）。
  ```
- **Description**：该条目是功能文档，应位于 `03-features/` 语义区，且路径前缀与其他同级条目（相对本文件所在目录）不一致。相对链接虽可解析（`docs/README.md` → `docs/03-features/graph-extraction.md`），但分类错误。`docs/03-features/README.md` 已正确新增对应条目。
- **Suggested Fix**：把该行移动到 `### 03-features/` 相关说明处，或直接删除（`03-features/README.md` 已有索引），避免重复且错位。

### A-7（建议，低）抽取刷新不递增实体版本，手工 CAS 感知不到抽取更新

- **File & Line**：`services/core-api/.../graph/infrastructure/Neo4jGraphStore.java`，`replaceSource` 中实体 MERGE。
- **Evidence**：
  ```cypher
  MERGE (n:GraphEntity {id:$id})
  ON CREATE SET n.origin='EXTRACTED',n.version=1
  SET n.projectId=$project,n.type=$type,n.externalId=$external,n.displayName=$name,
      n.sourceType=$type,n.sourceId=$source,n.sourceVersion=$version
  ```
- **Description**：`displayName`/`sourceVersion` 会被刷新覆盖，但 `n.version` 只在创建时置 1，刷新不递增。对 Wiki/Task 源实体（与 V3-04 手工实体共享稳定 ID）而言，手工 `expectedVersion` CAS 可能无法感知抽取侧的并发属性变化。属边界场景，当前无证据表明会导致实际错误。
- **Suggested Fix**：如需严格 CAS 语义，刷新源实体时递增 `n.version`；否则在 `graph-extraction.md` 明确“抽取刷新不参与实体 CAS 版本序列，仅更新来源属性”。

---

## 四、确认无需修改项（负向确认）

| 检查项 | 结论与证据 |
|--------|-----------|
| 关系白名单 | 抽取只产出 `DESCRIBES`(Wiki→Service/API/Issue) 与 `MODIFIES`(Task→Service/API)，通过 `allowed = Wiki ? !modifies : modifies` 过滤跨向语法，未越出 V3-04 白名单 |
| Evidence 对齐 | `excerpt = text.substring(offset, lineEnd)`，`start=offset`、`end=lineEnd` 均为 UTF-16 码元区间，CRLF 下排除 `\r`；与 V3-04 逐字校验语义一致 |
| 重复关系与多证据 | `targets.computeIfAbsent` 去重目标，`claims` 按行生成独立 `evidenceId`；测试断言 1 关系 / 2 evidence 且 start=0,17 |
| 删除生命周期 | `replaceSource(null)` 先删该来源全部 evidence（含手工），再删以源实体为端点的关系与证据，最后删源实体；测试经真实 Bolt 断言残留为 0 |
| 更新保留手工证据 | 更新路径仅删 `origin:'EXTRACTED'` 证据，手工证据保留；`refreshingOneSourcePreservesManualRelationWithIndependentEvidence` 通过 |
| 持久待办/幂等 | `graph_source_sync` 三元主键 + `ON CONFLICT DO UPDATE`；worker `FOR UPDATE SKIP LOCKED`、成功删行、失败退避保留；`rebuild`/`mark` 幂等 |
| 权限 | `syncStatus`/`rebuild` 复用 `projects.requireAccess`，与 V3-04 图入口一致；非项目用户返回 403（测试覆盖） |
| API 契约 | `rebuild` 202 空体、`status` `{pending,retrying}`、`retrying=attempts>0` 与 `core-api.md` 描述一致 |
| 未越界 | 无 GraphRAG 检索/回答、无实体消歧/merge、无 Python/LLM 图写入口、未改 `/wiki/graph` |
| 迁移安全 | `V12` 新增而非修改既有迁移，`CHECK (source_type IN ('WIKI','TASK'))`，回填既有 Wiki/Task；无业务 schema 破坏 |

---

## 五、主开发 (Codex) 评估回填区

| ID | Codex 接受/拒绝 | 处理说明 | 关联提交/文件 |
|----|-----------------|----------|----------------|
| A-1 | 接受并修复 | 删除过时同步描述，指向 V3-05 功能契约。 | graph-domain-model.md |
| A-2 | 接受并修复 | ADR 将跨模块受约束只读 SQL 明确为唯一例外，后端架构同步更新。 | ADR-0033、backend-architecture.md |
| A-3 | 接受风险，维持实现 | 5 秒 Neo4j 事务超时和 20 条批次上限已在 ADR 记录；以负载证据决定后续优化。 | ADR-0033 |
| A-4 | 接受并修复 | 明确整行 1..2000 约束，越界保持待办、无部分投影；补真实 HTTP/数据库测试。 | graph-extraction.md、GraphExtraction.java、集成测试 |
| A-5 | 部分接受 | 超长 excerpt 失败重试已覆盖；50 行和 200 字符沿用同一整源失败路径，暂不追加同义反复测试。 | GraphExtractionIntegrationTest.java |
| A-6 | 接受并修复 | 入口移动至功能目录段落。 | docs/README.md |
| A-7 | 接受限制并记载 | 自动刷新不参与图实体手工 CAS；手工写入仍需当前业务来源版本。待实际并发错误再调整策略。 | graph-extraction.md |

---

**结论**：`REVIEW_RESULT: PASS`。本节点功能正确、边界清晰、测试证据真实充分；上述 7 项均为非阻塞改进建议（文档一致性 2 项、运行期效率 1 项、契约边界与测试完善 4 项），可在本节点收口或并入 V3-06 前的技术债清理，无需在本轮返工。

Codex 另发现并修复：来源更新后旧版本手工 evidence 只在读时隐藏、物理仍残留。真实 Neo4j 回归先红（1 条），修复后清理旧版本并保留独立来源证据。Pi 报告的“更新保留手工证据”负向确认仅适用于独立来源或当前版本证据。