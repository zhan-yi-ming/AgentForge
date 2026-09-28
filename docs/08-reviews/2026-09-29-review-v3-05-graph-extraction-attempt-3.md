# Pi 代码审查报告：v3-05-graph-extraction / Attempt 3

- 日期：2026-09-29
- 审查阶段：v3-05-graph-extraction
- 审查对象：INDEX@1160428（基线：1160428c72e6c2eeda559160bc47e1676f8dff05）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-05 Graph Extraction Pipeline — Milestone Review（第 3 轮 / Attempt 3）

- 日期：2026-09-29
- 审查阶段：v3-05-graph-extraction
- 审查对象：Commit `INDEX@1160428`（基线：1160428c72e6c2eeda559160bc47e1676f8dff05）
- 审查模式：Milestone（只读，未运行任何命令、未修改文件与 Git 状态）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash）

---

## 一、概述与总体结论

- **审查范围**：`INDEX@1160428` 的 V3-05 交付，共 29 个文件（Flyway V12、6 个新增/改动 graph Java 文件、Wiki/Task service 登记点、1 个新增集成测试、V3-04 测试隔离改动、22 份文档）。
- **节点目标核对**：将 Wiki/Task 中的显式语法行转换为带 `source_document / source_chunk / evidence(UTF-16 位置 + 逐字 excerpt) / confidence=1.0` 的 Entity/Relation，通过持久待办 + 后台 worker 覆盖新增、更新、删除来源生命周期。**已达成**；未越界实现 V3-06（消歧/merge）与 V3-07（GraphRAG 检索/回答），未引入 LLM 判关系，未改 `/wiki/graph`。
- **边界核对**：Java 独占图写入，Python/LLM 无入口；抽取逻辑位于 `graph/domain`，worker/scheduler 位于 `graph/application`，Neo4j 细节封在 `graph/infrastructure`；自动投影以 `origin='EXTRACTED'` 与 V3-04 手工投影隔离；跨模块读取已由 ADR-0033/backend-architecture 明确为「project/type/id 约束的只读 SQL 例外」。
- **前轮修复验证**：Attempt 1 的 A-1（删除“无自动重建/同步编排”残留）、A-2（跨模块只读例外声明）、A-4（整行 >2000 整源失败 + 用例）、A-6（docs/README 条目移入功能段）、A-7（文档记录抽取刷新不参与手工 CAS）已落地；Attempt 2 的 B-1（externalId 改为来源作用域稳定摘要，公共 Graph HTTP 边界用例通过）已修复。B-2（永久失败与瞬时故障不可区分）已作为限制记载；B-3（部分边界分支缺测）仍部分存在。
- **测试证据**：`GraphExtractionIntegrationTest` 覆盖重建权限、增删改生命周期、重复行去重、API 语法校验、故障保留 + 重建恢复、手工关系保留、失效 evidence 物理清理、状态统计、超长 excerpt 整源失败、externalId 边界。真实 HTTP + PostgreSQL/Neo4j Testcontainers，符合文档承诺。V3-04 用例通过 `sync.enabled=false` + `@DirtiesContext` 隔离。
- **总体结论**：**通过（可交付）**。未发现具备明确证据的可运行性、正确性、安全、权限、并发、幂等、数据一致性或契约缺陷。剩余项均为架构一致性、可观测性与测试完善类建议，不阻塞本节点。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| C-1 | 建议（中） | `wiki/application/WikiPageService.java`、`task/application/TaskService.java` ↔ `graph/application/*` | import 与构造器 | wiki/task 与 graph 之间形成双向模块依赖（wiki/task → `GraphSourceSyncQueue`，graph → wiki/task service），未在文档记录该依赖方向 |
| C-2 | 建议（中） | `graph/application/GraphSyncProcessor.java` | `processOne()` catch 分支 | 永久性失败（>50 行 / >200 目标名 / >2000 excerpt）与瞬时故障共用 `retrying` 通道，且日志只记录异常类名、不记录消息，运维不可区分 |
| C-3 | 建议（中） | `graph/application/GraphSyncProcessor.java` | `processOne()` 全流程 | PG 行锁（`FOR UPDATE SKIP LOCKED`）跨越 Neo4j 网络调用；同来源业务写入的 `mark` 会被阻塞至多约一个 Neo4j 事务超时（A-3 已记载，仍属实际放大风险） |
| C-4 | 建议（低） | `test/java/.../GraphExtractionIntegrationTest.java` | 全文件 | DELETE 来源时「关系仍有独立有效 evidence 且端点存在则保留」、Task `Modifies API` 非法路径、50 行上限分支无自动化覆盖 |
| C-5 | 建议（低） | `graph/application/GraphSyncProcessor.java` | `read()` | 以列序号 `rs.getString(2)` 读取来源正文，未按列名读取，脆弱且与既有 `entityParameters` 风格不一致 |
| C-6 | 建议（低） | `graph/infrastructure/Neo4jGraphStore.java` | `replaceSource` 孤儿关系清理 | 每次来源同步执行项目级全量 `NOT EXISTS` 关系清理，随项目关系数增长成本上升，可限定在该来源产生的 relation id 集合内 |

### 无需修改（确认项）

| 检查项 | 结论与证据 |
|--------|-----------|
| A-1 文档残留 | 已修复：`graph-domain-model.md` 不再含「无自动重建/同步编排」，改为指向 V3-05 待办/同步/重建入口 |
| A-2 跨模块读取边界 | 已修复：ADR-0033 与 backend-architecture 均声明 worker 的 project/type/id 只读 SQL 为例外 |
| A-4 excerpt 上限 | 已修复：`line.length() > 2000` 整源失败，`oversizedExcerptKeepsProjectionRetryableWithoutPartialPublish` 覆盖，失败前无部分投影 |
| A-6 文档索引位置 | 已修复：`docs/README.md` 条目移入 `03-features/` 段落 |
| A-7 实体 CAS 语义 | 已在 `graph-extraction.md` 明确自动刷新不参与手工 CAS 版本序列 |
| B-1 externalId 契约 | 已修复：externalId 改为 `extract:<type>:<id>:<EntityType>:<stable(key)>` 稳定摘要（约 ≤92 字符），`maximalTargetNameKeepsExternalIdWithinGraphContract` 通过 |
| 关系白名单 | `allowed = Wiki ? !modifies : modifies`，仅产出 Wiki→DESCRIBES / Task→MODIFIES，未越出 V3-04 白名单 |
| Evidence 对齐 | `excerpt = text.substring(offset, lineEnd)`，`start/end` 为 UTF-16 码元且 CRLF 排除 `\r`，与 V3-04 逐字校验语义一致 |
| 重复关系与多证据 | 相同目标 `computeIfAbsent` 去重为 1 关系，按独立位置生成多条 evidence（断言 1 关系 / 2 evidence / start=0,17） |
| 删除/更新生命周期 | 删除清空该来源全部 evidence（含手工）与以该对象为端点的关系；更新删旧版本证据、保留独立来源证据，`refreshingOneSourcePreservesManualRelationWithIndependentEvidence` 覆盖 |
| 权限与越权 | `syncStatus`/`rebuild` 均先 `requireAccess`，跨项目 403 有测试；`replaceSource` 所有 Cypher 以 `projectId` 约束 |
| 幂等与并发 | `graph_source_sync` 三元主键 + `ON CONFLICT DO UPDATE`；`FOR UPDATE SKIP LOCKED` 防多实例重复处理；Neo4j 项目锁串行化 |
| 迁移安全 | V12 为新增迁移，`CHECK (source_type IN ('WIKI','TASK'))`，回填既有 Wiki/Task，未改既有迁移或业务表 |
| API 契约 | `rebuild` 202 空体、`status` `{pending,retrying}` 与 `core-api.md`/`graph-extraction.md` 一致 |
| 未越界 | 无 GraphRAG 检索/回答、无实体 merge、无 Python/LLM 图写入口、未改 `/wiki/graph` |

---

## 三、逐 Issue 展开

### C-1（建议，中）wiki/task 与 graph 形成双向模块依赖

- **Severity**：建议（中）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/wiki/application/WikiPageService.java`、`.../task/application/TaskService.java` 的 import 与 4 参构造器；对照 `graph/application/GraphService.java`（依赖 `WikiPageService`/`TaskService`）。
- **Evidence**：
  ```java
  import com.agentforge.core.graph.application.GraphSourceSyncQueue;   // wiki/task → graph
  ...
  public GraphService(ProjectService projects, WikiPageService wiki, TaskService tasks,
      GraphStore store, GraphSourceSyncQueue syncQueue) { ... }        // graph → wiki/task
  ```
- **Description**：V3-04 依赖方向为 `graph → wiki/task`（只读公开查询）。V3-05 让 wiki/task 在同一业务事务内登记待办，新增 `wiki/task → graph` 的反向依赖，形成模块级双向依赖。由于本项目只采用 Spring Modulith 的分包思想、未引入模块校验运行时（backend-architecture 参考节明确），不会导致启动失败或编译错误；但未在架构文档中记录该方向反转的取舍。
- **Suggested Fix**：二选一并保持文档一致：① 将「登记待办」抽象为被 wiki/task 依赖的中立接口（如 `GraphSourceSyncRegistrar`），graph 模块实现它，使依赖方向单向；或 ② 在 `docs/02-architecture/backend-architecture.md` 的 V3-05 段明确记录「wiki/task → graph 的登记待办为新引入的允许依赖方向」，避免边界声明与代码漂移。

### C-2（建议，中）永久失败与瞬时故障不可区分且日志缺少原因

- **Severity**：建议（中）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphSyncProcessor.java`，`processOne()` catch 分支。
- **Evidence**：
  ```java
  } catch (RuntimeException failure) {
      log.warn("Graph source sync deferred for project={}, type={}, source={}, failure={}",
          row.projectId(), row.type(), row.sourceId(), failure.getClass().getSimpleName());
      jdbc.update("""
          UPDATE graph_source_sync
          SET attempts=attempts+1, next_attempt_at=now()+interval '30 seconds'
          WHERE project_id=? AND source_type=? AND source_id=?
          """, row.projectId(),row.type().name(),row.sourceId());
  }
  ```
  `GraphExtraction` 对 >50 行 / >200 目标名 / >2000 excerpt 抛出 `IllegalArgumentException`，属确定性永久失败；Neo4j 抖动是瞬时失败。两者都只表现为 `attempts` 递增与 `retrying` 计数。
- **Description**：文档已承认「无限重试的错误文档仍显示 retrying」，因此不构成契约破坏；但日志仅记录异常类名、不记录消息，维护者无法从日志判断是内容问题还是依赖问题，排障信息不足。B-2 在本轮未变化。
- **Suggested Fix**：至少在 `log.warn` 中附加 `failure.getMessage()`（`GraphExtraction` 的异常消息不含 excerpt 正文，不会泄露内容），或在 `graph_source_sync` 增加失败分类标记并将永久失败计数暴露为 `failed`；若不改，保持文档中「需人工检查内容语法」的限制说明。

### C-3（建议，中）PG 行锁跨越 Neo4j 网络调用

- **Severity**：建议（中）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphSyncProcessor.java`，`processOne()`（`SELECT … FOR UPDATE SKIP LOCKED` → `store.replaceSource(…)` → `DELETE`）。
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
  同一来源的业务写入经 `GraphSourceSyncQueue.mark` 的 `ON CONFLICT … DO UPDATE` 需要同一行锁。
- **Description**：worker 在持有待办行锁的整个 PG 事务期间执行 Neo4j 事务（文档标注 5 秒超时）。Neo4j 故障/慢响应时，同来源的用户 Wiki/Task 写入会被阻塞至多约一个 Neo4j 事务时长。ADR-0033 已把「持锁跨库处理有短时资源占用」列为取舍（A-3 已接受），故不构成契约破坏，但仍属运行期风险。
- **Suggested Fix**：若将来以负载证据决定优化，可改为「先提交领取、后处理」两段式：短事务 `UPDATE … SET attempts=attempts+1, next_attempt_at=now()+interval '30 seconds' RETURNING` 后提交释放行锁，再在事务外读取来源与替换 Neo4j，最后短事务删除待办。

### C-4（建议，低）删除分支与边界语法缺少自动化覆盖

- **Severity**：建议（低）
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/graph/GraphExtractionIntegrationTest.java`（全文件）。
- **Evidence**：现有用例覆盖重建权限、增删改、重复行、API 语法、故障保留 + 重建、手工关系保留、失效 evidence 清理、状态统计、超长 excerpt、externalId 边界；未见对「删除来源时，仍有独立有效 evidence 且端点存在的关系被保留」、Task `Modifies API: nonsense` 非法路径、`++matched > 50` 上限分支的断言。
- **Description**：删除来源时的关系保留语义是文档承诺（「关系若仍有其他有效 evidence 且端点仍存在，则保留」），当前仅有 UPDATE 路径的等价用例；若删除实现被改动，可能静默破坏该承诺。B-3 在本轮部分收口。
- **Suggested Fix**：补一个参数化用例：① 两个来源 W1/W2 各提供一条 evidence 支持同一关系，删除 W1 后断言关系与 W2 证据保留；② 构造 Task `Modifies API: nonsense` 断言不产生 API 实体；③ 构造 51 条有效行断言 `retrying` 增长且无部分投影。

### C-5（建议，低）来源正文按列序号读取

- **Severity**：建议（低）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphSyncProcessor.java`，`read()`。
- **Evidence**：
  ```java
  String sql=row.type()==SourceType.WIKI
      ? "SELECT title, content, version FROM wiki_page WHERE project_id=? AND id=?"
      : "SELECT title, description, version FROM task_item WHERE project_id=? AND id=?";
  var docs=jdbc.query(sql,(rs,i)->new GraphExtraction.Document(row.projectId(),row.type(),
      row.sourceId(),rs.getLong("version"),rs.getString("title"),rs.getString(2)),
      row.projectId(),row.sourceId());
  ```
- **Description**：`title`/`version` 按列名读取，正文却按 `rs.getString(2)` 位置读取。功能上因两条 SQL 正文都在第 2 列而正确，但列序一旦调整即静默错读，且与既有 `entityParameters` 风格不一致。
- **Suggested Fix**：为两条查询统一别名（如 `SELECT title, content AS body, version …` / `SELECT title, description AS body, version …`），改以 `rs.getString("body")` 读取。

### C-6（建议，低）孤儿关系清理为项目级全量扫描

- **Severity**：建议（低）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java`，`replaceSource` 中的孤儿关系清理。
- **Evidence**：
  ```java
  tx.run("""
      MATCH (r:GraphRelation {projectId:$project})
      WHERE NOT EXISTS { MATCH (e:GraphEvidence)-[:SUPPORTS]->(r) }
      DETACH DELETE r
      """,base).consume();
  ```
- **Description**：每次来源同步都对本项目全部关系执行 `NOT EXISTS` 清理；项目关系数增长后，单来源同步成本线性上升。当前规模无性能问题，属可扩展性建议，不影响正确性（同一项目锁事务内串行，不会误删并发新建的关系）。
- **Suggested Fix**：将清理限定在该来源本次替换涉及的 relation id 集合（或 `fromId/toId` 属于该来源实体集合）内执行，避免全量扫描；或保留全量清理但在文档注明其成本特性。

---

## 四、主开发 (Codex) 评估回填区

| ID | Codex 接受/拒绝 | 处理说明 | 关联提交/文件 |
|----|-----------------|----------|----------------|
| C-1 | 接受并记录 | 同事务登记引入的双向模块依赖已明确写入后端架构；保持当前单体边界。 | backend-architecture.md |
| C-2 | 接受限制 | 状态仅显示 retrying，功能文档要求人工检查来源；避免将异常消息或内容写日志。 | graph-extraction.md |
| C-3 | 接受已记录取舍 | ADR-0033 规定 5 秒 Neo4j 事务和 20 条批次，待真实负载证据再优化。 | ADR-0033 |
| C-4 | 暂不扩展 | 已有真实集成测试覆盖删除、独立来源证据更新保留及失败边界；无本轮缺陷信号。 | GraphExtractionIntegrationTest.java |
| C-5 | 接受建议，留后续整理 | 当前两条查询正文均为第 2 列，测试真实数据库覆盖；本节点不为风格统一再动源码。 | GraphSyncProcessor.java |
| C-6 | 接受规模限制 | 项目级关系清理保证当前正确性；后续按真实项目规模评估局部清理。 | Neo4jGraphStore.java |

---

**结论**：`REVIEW_RESULT: PASS`。V3-05 的显式语法抽取、持久待办驱动的来源生命周期、权限边界与证据 provenance 均与文档一致，测试证据真实充分，前两轮契约问题（A-4、B-1）已验证修复并补红绿回归。本轮 6 项均为非阻塞建议（模块依赖记录 1 项、可观测性 1 项、运行期锁粒度 1 项、测试完善 1 项、实现健壮性 2 项），可在本节点收口或并入 V3-06 前技术债清理，无需返工。
