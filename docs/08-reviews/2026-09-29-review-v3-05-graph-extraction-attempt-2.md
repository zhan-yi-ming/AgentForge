# Pi 代码审查报告：v3-05-graph-extraction / Attempt 2

- 日期：2026-09-29
- 审查阶段：v3-05-graph-extraction
- 审查对象：INDEX@1160428（基线：1160428c72e6c2eeda559160bc47e1676f8dff05）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-05 Graph Extraction Pipeline — Milestone Review（第 2 轮 / Attempt 2）

## 一、概述与总体结论

- **审查对象**：`INDEX@1160428`，28 个文件（V12 迁移、6 个新增/改动 graph Java 文件、Wiki/Task service 登记点、1 个新增集成测试、文档）。
- **本轮定位**：验证 Attempt 1 的 7 项建议（A-1～A-7）修复，并识别修复引入的新问题。除只读审查外未运行任何命令。
- **节点目标核对**：Wiki/Task 显式语法行 → 带 `source_document / source_chunk / evidence(UTF-16 位置 + 逐字 excerpt) / confidence=1.0` 的 Entity/Relation，覆盖新增、更新、删除生命周期与持久待办恢复。**达成**；未越界实现 V3-06 消歧/merge 与 V3-07 检索/回答，未引入 LLM 判关系，未改 `/wiki/graph`。
- **Attempt 1 修复验证**：A-1（删除“无自动重建/同步编排”残留）、A-2（ADR-0033 与 backend-architecture 明确跨模块受约束只读 SQL 为例外）、A-4（`line.length() > 2000` 整源失败）、A-6（docs/README 条目移入功能段）、A-7（文档记录抽取刷新不参与手工 CAS）均已落地；A-3 作为已记载取舍保留；A-5 部分收口（补了超长 excerpt 用例，50 行/200 字符分支仍缺测）。
- **总体结论**：**通过（可交付）**。未发现具备明确证据的可运行性、正确性、安全、权限、并发、幂等或数据一致性缺陷。剩余项均为文档/测试完善类建议，不阻塞收口。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| B-1 | 建议（中） | `graph/domain/GraphExtraction.java`；对照 `docs/03-features/graph-domain-model.md`、`docs/04-api/core-api.md` | `extract` 目标 externalId 构造处（约 55–66） | 抽取生成的 `externalId` 可超出文档声明的 1..200 上限（结构前缀约 54 字符 + 目标名 ≤200） |
| B-2 | 建议（低） | `graph/application/GraphSyncProcessor.java` | `processOne()` catch 分支 | 永久性失败（>50 行 / >200 字符）与瞬时故障共用同一无限重试路径，状态无法区分原因，运维不可判定 |
| B-3 | 建议（低） | `test/java/.../GraphExtractionIntegrationTest.java` | 全文件 | 50 行上限、200 字符目标上限、`Issue:`/`Modifies API` 非法路径、CRLF 行尾等分支无自动化覆盖 |

### 无需修改（确认项）

| 检查项 | 结论与证据 |
|--------|-----------|
| A-1 文档残留 | 已修复：`graph-domain-model.md` 不再含“无自动重建/同步编排”，改为指向 V3-05 待办/同步/重建入口 |
| A-2 跨模块读取边界 | 已修复：ADR-0033 与 backend-architecture 均声明 worker 以 project/type/id 约束的只读 SQL 为例外 |
| A-3 PG 行锁跨 Neo4j 调用 | 已作为 ADR 取舍记载（5 秒 Neo4j 超时、每轮 20 条），本轮不阻塞 |
| A-4 excerpt 上限 | 已修复并有 `oversizedExcerptKeepsProjectionRetryableWithoutPartialPublish` 覆盖，失败前无部分投影 |
| A-6 文档索引位置 | 已修复：`docs/README.md` 条目移入 `03-features/` 段落 |
| A-7 实体 CAS 语义 | 已在 `graph-extraction.md` 明确“自动刷新不参与手工 CAS 版本序列” |
| 关系白名单 | `allowed` 仅放行 Wiki→DESCRIBES / Task→MODIFIES，未越出 V3-04 白名单；非法行静默跳过 |
| Evidence 对齐 | `excerpt=substring(offset,lineEnd)`、`start/end` 为 UTF-16 码元且 CRLF 排除 `\r`，与 V3-04 逐字校验一致 |
| 重复关系与多证据 | 相同目标去重为 1 关系，按独立位置生成多条 evidence（测试断言 1 关系 / 2 evidence / start=0,17） |
| 删除/更新生命周期 | 删除清空该来源全部 evidence（含手工）与以该对象为端点的关系；更新清旧版本证据并保留独立来源证据，测试覆盖 |
| 权限与越权 | `syncStatus`/`rebuild` 均先 `requireAccess`，跨项目 403 有测试；`replaceSource` 所有 Cypher 均以 `projectId` 约束 |
| 幂等与并发 | `graph_source_sync` 三元主键 + `ON CONFLICT DO UPDATE`；`FOR UPDATE SKIP LOCKED` 防多实例重复处理；Neo4j 项目锁串行化 |
| 迁移安全 | V12 为新增迁移，`CHECK (source_type IN ('WIKI','TASK'))`，回填既有 Wiki/Task，未改既有迁移或业务表 |
| 未越界 | 无 GraphRAG 检索/回答、无实体 merge、无 Python/LLM 图写入口、未改 `/wiki/graph` |

---

## 三、逐 Issue 展开

### B-1（建议，中）抽取目标 externalId 可能超出文档声明上限

- **Severity**：建议（中）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/domain/GraphExtraction.java`，`extract` 中目标 externalId 生成（约 55–66）；对照 `docs/03-features/graph-domain-model.md`（“externalId 1..200”语义）与 `docs/04-api/core-api.md`（`externalId（1..200）`）。
- **Evidence**：
  ```java
  String external = "extract:"+document.type()+":"+document.id()+":"+key;
  ...
  var target = targets.computeIfAbsent(key, ignored -> new Target(
      stable(document.projectId()+":entity:"+type+":"+external),type,external,name));
  ```
  结构前缀 `"extract:" + WIKI/TASK(4) + ":" + UUID(36) + ":" + SERVICE/API/ISSUE + ":"` 约 54 字符，加上允许的 `name` ≤200，最大值约 258。
- **Description**：抽取路径绕过 V3-04 `PUT /entities` 的 `externalId 1..200` 输入校验，直接写 Neo4j。生成的实体经 `GET /entities` 返回时 `externalId` 可超过文档声明上限。由于是服务端生成、读取路径不校验，且无索引长度约束，当前不产生运行时错误，属文档/数据契约与生成值不一致。
- **Suggested Fix**：二选一并保持单一事实来源：① 在抽取侧对 `external` 增加 `length() <= 200` 校验（超限按现有 `IllegalArgumentException` 整源失败）；或 ② 将 externalId 改为定长稳定键（如 `"extract:" + SourceType + ":" + sourceId + ":" + type + ":" + stable(name)`），并在文档中明确“抽取实体 externalId 为服务端哈希键，不沿用 1..200 输入约束”。

### B-2（建议，低）永久失败与瞬时故障不可区分

- **Severity**：建议（低）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphSyncProcessor.java`，`processOne()` 异常分支。
- **Evidence**：
  ```java
  } catch (RuntimeException failure) {
      log.warn("Graph source sync deferred for project={}, type={}, source={}, failure={}",
          row.projectId(), row.type(), row.sourceId(), failure.getClass().getSimpleName());
      jdbc.update("""
          UPDATE graph_source_sync
          SET attempts=attempts+1, next_attempt_at=now()+interval '30 seconds'
          WHERE ...
          """, ...);
  }
  ```
- **Description**：`GraphExtraction` 对 >50 行 / >200 字符 / >2000 excerpt 抛 `IllegalArgumentException`，属确定性永久失败；而 Neo4j 抖动是瞬时失败。两者都只表现为 `attempts` 递增与 `retrying` 计数，状态端点无法让运维判断“是否需要修内容”。文档已承认该限制，但日志仅记录异常类名未记录消息，排障信息不足。
- **Suggested Fix**：至少在 `log.warn` 中附加 `failure.getMessage()`（避免记录 excerpt 正文），或在 `graph_source_sync` 增加失败分类标记并将永久失败计数暴露为 `failed`；若不改，建议在 `graph-extraction.md` 明确“永久失败同样只进入 retrying，需人工检查内容语法”。

### B-3（建议，低）失败/边界分支缺少自动化覆盖

- **Severity**：建议（低）
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/graph/GraphExtractionIntegrationTest.java`（全文件）。
- **Evidence**：现有 12 项覆盖重建权限、增删改生命周期、重复行、API 语法、故障保留+重建、手工关系保留、手工失效 evidence 物理清理、状态统计与超长 excerpt。未见对 `++matched > 50`、`name.length() > 200`、`Issue:` 目标、Task `Modifies API` 非法路径、CRLF 行尾的断言。
- **Description**：这些分支属于永久失败路径，行为（整源失败、无部分投影、`retrying` 增长）未被回归保护，未来重构易静默改变语义。A-5 在本轮仅部分收口。
- **Suggested Fix**：补一个参数化用例：分别构造 51 条有效行、201 字符目标名、`Modifies API: nonsense`，断言 `GET /extraction/status` 的 `retrying` 增长且 `pending` 不清零、`GET /entities` 不出现部分投影；可另加 CRLF 用例断言 `start/end` 排除 `\r`。

---

## 四、主开发 (Codex) 评估回填区

| ID | Codex 接受/拒绝 | 处理说明 | 关联提交/文件 |
|----|-----------------|----------|----------------|
| B-1 | 接受并修复 | 200 字符目标名使原 externalId 达 258，公共 HTTP 测试先红；改为来源作用域稳定摘要后绿。 | GraphExtraction.java / graph-extraction.md / GraphExtractionIntegrationTest.java |
| B-2 | 接受限制并记载 | 永久性失败仍标 retrying；状态接口不含原文，维护者须检查来源内容并修正重建。避免将失败消息写入日志泄露正文。 | graph-extraction.md |
| B-3 | 暂不实施 | 现有真实集成测试覆盖成功语法、无效 API 与整源失败；新增外部 ID 边界红绿用例。其余路径无当前缺陷信号。 | GraphExtractionIntegrationTest.java |

---

**结论**：`REVIEW_RESULT: PASS`。Attempt 1 的 A-1/A-2/A-4/A-6/A-7 修复已验证，A-3 取舍已记载，A-5 部分收口；本轮新增 3 项非阻塞建议（1 项契约一致性、1 项可观测性、1 项测试完善），可在本节点收口或并入 V3-06 前技术债清理，无需返工。
