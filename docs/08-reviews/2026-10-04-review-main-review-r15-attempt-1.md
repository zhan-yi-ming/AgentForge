# Pi 代码审查报告：main-review-r15 / Attempt 1

- 日期：2026-10-04
- 审查阶段：main-review-r15
- 审查对象：INDEX@ba98302（基线：ba98302a4475bc97b2b936c8534a6c1e48f8823a）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# R15 RAG 查询路径容量边界 — Pi Milestone 只读审查报告

- 审查阶段：main-review-r15
- 审查模式：Milestone（1 / 3）
- 审查对象：`INDEX@ba98302`（25 文件，+488/−58）
- 审查依据：本 Diff、文件清单、DoD、测试策略、V3/Day 4 质量门槛
- 前置声明：本报告完全只读，未运行任何命令、测试或 Git 操作；所有“机器证据”均转述变更记录，未改写为 Pi 执行结果。

---

## 一、概述与总体结论

本次变更实现了一个清晰且方向正确的契约演进：

1. **Core 来源代际握手**：`knownSnapshotVersion` + `sourcesChanged`，在授权与代际读取后短路返回空正文，消除稳定项目的重复正文传输，保留变化时的完整快照删除语义。
2. **PostgreSQL 内有界候选**：V18 生成列 + GIN，向量与词法各自 `candidate_k`，Python 只搬运候选并集，不再全量加载项目 Chunk。
3. **Graph evidence 有界匹配**：按来源身份/版本/excerpt 在相同代际索引中匹配，不再依赖全量来源正文。

代码层面未发现越权、契约错配、并发回退或明显可运行性缺陷：授权顺序（用户 → 项目访问 → 代际 → Wiki/Task）保持正确；`sourcesChanged=false` 的响应一致性与失败关闭校验到位；`Long == long` 为数值比较，无装箱引用陷阱；旧 Agent × 新 Core 与新 Agent × 旧 Core 的兼容方向已在文档明确。

**总体结论：需修复后交付（NEEDS_FIX）。** 阻断点为 1 项：新增的真实 SQL 查询 `RagStore.load_graph_evidence` 没有真实 PostgreSQL 集成测试，且该路径在现有测试中被 fake 替换，违反本仓库 Day 4 / 测试策略对数据库 SQL 的强制要求。其余为建议项，不阻塞。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| M1 | 必须修改（高） | `services/agent-service/src/agentforge_agent/rag_store.py` | `load_graph_evidence`（约 197–245 行） | 新增 SQL 仅被 fake boundary 覆盖，无真实 pgvector 集成测试；违反测试策略“SQL 用真实 PostgreSQL 验证” |
| S1 | 建议修改（中） | `services/agent-service/src/agentforge_agent/retrieval.py` | `_graph_chunks_from_sources` / `_graph_chunks_from_index`（约 205–250 行） | 两条路径 excerpt 匹配语义不同（整源 vs 单 Chunk），同一状态在变化/未变化请求间可能产出不同 Graph 引用 |
| S2 | 建议修改（中） | `V18__rag_lexical_search.sql` / `rag_store.py` search | V18；`search` 词法查询（约 158–178 行） | `simple` 配置无法切分 CJK，中文查询的词法候选可能恒为空，词法召回较原进程内 BM25 存在退化风险 |
| S3 | 建议修改（低） | `services/agent-service/src/agentforge_agent/retrieval.py` / `rag_store.py` | `retrieve`（约 66 行）/ `snapshot_version`（约 30–40 行） | 握手代际在独立连接读取，与搜索事务不在同一快照；每次 Chat 增加一次数据库往返 |
| S4 | 建议修改（低） | `RagSourcesRequest.java` / 测试 | 请求校验与 `RagSourcesApiTest` / `test_graphrag_retrieval.py` | 缺少负 `knownSnapshotVersion` 400 与 `sourcesChanged=false` 但 `sources` 非空的校验测试 |
| S5 | 建议修改（低） | `docs/07-changes/2026-10-04-r15-rag-query-bounds.md` | 全文（状态：Pi Review Pending） | 记录自述“最终 gitleaks 复核待重跑”，DoD 要求提交前扫描通过并回填；当前未闭环 |

无需修改项见第四节。

---

## 三、逐个 Issue 展开

### M1（必须修改）新增 `load_graph_evidence` SQL 无真实数据库验证

- **Severity**：High
- **File & Line**：`services/agent-service/src/agentforge_agent/rag_store.py`，`load_graph_evidence`（约 197–245 行）
- **Evidence**：

```python
for match in matches[:40]:
    evidence = match.evidence
    cursor.execute(
        """
        SELECT id, project_id, source_type, source_id, source_version,
               chunk_index, title, content
        FROM rag_chunk
        WHERE project_id = %s
          AND source_type = %s
          AND source_id = %s
          AND source_version = %s
          AND strpos(content, %s) > 0
        ORDER BY chunk_index
        LIMIT 1
        """,
        (project_id, evidence.source_type, evidence.source_id,
         evidence.source_version, evidence.excerpt),
    )
```

对应测试仅为 fake：

```python
def load_graph_evidence(self, _project_id, _snapshot_version, matches):
    assert matches == [match]
    return {(...): StoredChunk("graph-source", ...)}
```

而唯一真实 pgvector 测试 `test_rag_store_integration.py` 只覆盖 `synchronize` / `search`（含新增有界词法），未触达该方法。

- **Description**：这是本次新增的唯一真实 SQL 读路径，包含列名、`strpos` 语义、UUID/bigint 参数绑定、`LIMIT 1` 选中哪个 Chunk 等实现细节。fake 测试只能验证 RetrievalService 的分支接线，无法发现 SQL 拼写、类型或匹配错误。测试策略明确“Repository 集成测试：使用真实 PostgreSQL/Testcontainers 验证 SQL、约束和查询”，Day 4 门槛亦明确禁止 mock 后宣称数据库路径通过。变更记录中的“真实 pgvector 定向”只证明 `search` 有界词法，未证明该方法。
- **Suggested Fix**：在 `test_rag_store_integration.py` 中新增真实 pgvector 用例：同步含 Wiki/Task 的项目后，构造与某 Chunk 内容子串一致的 `GraphMatch`，断言 `load_graph_evidence` 返回正确 source 三元组；再构造 excerpt 跨 Chunk 边界、source_version 不匹配、代际不匹配三类负例，断言返回 `{}`。

### S1（建议修改）Graph evidence 双路径 excerpt 匹配语义不一致

- **Severity**：Medium
- **File & Line**：`services/agent-service/src/agentforge_agent/retrieval.py`，`_graph_chunks_from_sources`（约 205–220 行）、`_graph_chunks_from_index`（约 222–250 行）
- **Evidence**：

```python
# sources 路径：对整源 content 匹配
source = authorized.get((evidence.source_type, evidence.source_id, evidence.source_version))
if source is None or evidence.excerpt not in source.content:
    continue
```

```python
# index 路径：对单个 Chunk content 匹配（SQL strpos）
AND strpos(content, %s) > 0
```

- **Description**：同一条 Graph 证据，在“来源代际变化”请求走整源匹配，在“代际未变化”请求走单 Chunk 匹配。若 excerpt 跨 Chunk 边界或长于 chunk 窗口，则首次请求可产生 Graph 引用，后续同状态请求静默丢失，导致引用结果随请求序列变化。这是可复现的行为不一致，而非风格问题。
- **Suggested Fix**：统一语义。建议两条路径都以“来源身份 + 版本 + 单 Chunk 子串”为唯一判定，或在 sources 路径复用同一 Chunk 级匹配逻辑，并在文档/测试中固定该行为。

### S2（建议修改）`simple` 配置对 CJK 的词法召回归零风险

- **Severity**：Medium
- **File & Line**：`V18__rag_lexical_search.sql`；`rag_store.py` `search` 词法查询
- **Evidence**：

```sql
to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, ''))
...
AND search_document @@ plainto_tsquery('simple', %s)
```

- **Description**：`simple` 配置不做分词，中文整段会成为单一词元，查询子串无法命中该词元，词法候选可能恒为空。若原进程内 BM25 对中文按字符/子串切分，则本次改动会把中文项目的词法召回降为仅依赖向量路。这不是未证实的猜测，而是 `simple` 的确定性行为；但是否构成“净退化”取决于原 BM25 tokenizer，本次 Diff 未包含，故仅作建议并要求证据。
- **Suggested Fix**：用一段真实中文 Wiki/Task 语料在集成测试中断言词法候选非空；若确为空，评估改用 `pg_trgm` 子串索引或保留中文专用候选来源，并在功能文档“已知限制”中明确中文词法边界。

### S3（建议修改）握手代际读取与搜索不在同一快照

- **Severity**：Low
- **File & Line**：`retrieval.py` `retrieve`（`known_snapshot_version = self.store.snapshot_version(project_id)`）；`rag_store.py` `snapshot_version`
- **Description**：`snapshot_version()` 使用独立连接读取，随后 `search` 在 REPEATABLE READ 事务中再次校验代际。二者之间存在窗口，但搜索侧失败关闭（代际不等返回空），不会泄漏或返回他快照内容，因此仅为一致性与性能提示：每次 Chat 新增一次连接/往返。
- **Suggested Fix**：可在文档标注该读取的“提示值”性质；若关注延迟，可复用同一连接或在同一事务内读取后再握手。

### S4（建议修改）边界与校验测试缺口

- **Severity**：Low
- **File & Line**：`RagSourcesRequest.java`（`@PositiveOrZero Long knownSnapshotVersion`）；`RagSourcesApiTest`；`test_core_source_contract_...`
- **Description**：代码已用 `@PositiveOrZero` 处理负值、`CoreApiClient` 已校验 `sourcesChanged=false` 时 sources 为空，但缺少断言测试：负 `knownSnapshotVersion` 返回 400；`sourcesChanged=false` 且 `sources` 非空时 Python 抛 `RagDependencyError`。属于覆盖补强，非阻断。
- **Suggested Fix**：补两个断言用例即可。

### S5（建议修改）最终敏感扫描尚未闭环

- **Severity**：Low
- **File & Line**：`docs/07-changes/2026-10-04-r15-rag-query-bounds.md`
- **Evidence**：“加入运维兼容说明和本验证回填后将重跑最终扫描。”
- **Description**：DoD 要求“公开仓库敏感信息扫描已通过”，当前记录只有提交前首次扫描，最终内容（含运维说明与回填）扫描未回填。属流程闭环项，编码无问题。
- **Suggested Fix**：提交前对最终 staged diff 重跑 gitleaks 并回填退出码与结论。

---

## 四、无需修改（已确认正确）

| ID | 文件 | 结论 |
| --- | --- | --- |
| N1 | `RagSourceService.java` | 授权顺序正确：用户存在 → 项目访问 → 代际读取；短路路径不读取 Wiki/Task 正文，`verifyNoInteractions(wiki, tasks)` 已覆盖 |
| N2 | `RagSourceService.java` | `knownSnapshotVersion == snapshotVersion` 为 Long 与 long 的数值比较，无装箱引用比较缺陷 |
| N3 | `core_client.py` | `sourcesChanged=false` 的一致性校验（版本匹配、sources 为空）与 `ValidationError(ValueError)` 捕获正确，失败关闭 |
| N4 | `RagSourceService` / 文档 | 旧 Agent × 新 Core 向后兼容、新 Agent × 旧 Core 不支持并给出 Core-first 升级说明，契约方向自洽 |
| N5 | `rag_store.py` search | 向量/词法各自 `candidate_k`、候选并集去重、BM25 仅作用于有界词法候选，与文档声明一致，无跨项目泄漏 |
| N6 | `DatabaseIdentitySeparationIntegrationTest.java` | `max(version)=18` 与 V18 迁移一致 |

---

## 五、主开发（Codex）评估回填区

| Finding ID | 是否认可 | 处理决定 | 证据（命令/退出码/测试数/清理） | 备注 |
| --- | --- | --- | --- | --- |
| M1 | 认可并已修复 | 在真实 pgvector 集成测试中覆盖命中、版本错配、代际错配与跨 Chunk excerpt；SQL 本身无需修改 | `test_rag_store_integration.py -q`：`1 passed`，退出 0 | 阻塞项关闭，触发 Attempt 2 |
| S1 | 认可并已修复 | 新增公共 seam 断言先得到 `graph_loads == 0` 红灯；变化与未变化快照统一在同步后按同代际 Chunk 验证 | `test_graphrag_retrieval.py -q`：`5 passed`；Agent 全量 `224 passed` | 消除请求序列导致的引用差异 |
| S2 | 认可为已知质量限制，不修改 | 现有 Python tokenizer 按 CJK 单字，PostgreSQL `simple` 对无空格 CJK 可能不给候选；本批不引入新扩展或静默改排序，功能文档已明确只走向量路的可能性 | 文档差异；向量路与候选上界测试保持通过 | 留给独立检索质量切片，不触发复审 |
| S3 | 不作为缺陷修改 | `snapshot_version` 只提供握手提示；`search` 在 REPEATABLE READ 内重读代际并在不一致时返回空，安全与一致性失败关闭 | Agent 全量 `224 passed`；跨进程 `13/13` | 一次额外往返是低风险性能取舍 |
| S4 | 建议成立，不修改 | Bean Validation 已拒绝负值；Python 已实现未变化响应一致性检查。纯覆盖补强不改变当前行为，避免扩大 Pi 修复输入 | 既有 Java API/服务定向与 Agent 全量通过 | 后续可补边界矩阵 |
| S5 | 认可，提交前闭环 | Attempt 1 前完整暂存差异已两次 Gitleaks 0 命中；Pi 报告/处置回填后还会对最终 index 再扫 | 最终结果回填 R15 记录 | 流程项，不是代码缺陷 |

---

## 六、结论

- 阻断项仅 **M1**：新增 SQL `RagStore.load_graph_evidence` 必须补真实 pgvector 集成测试后方可交付。
- S1/S2 为可复现的风险与行为一致性提示，建议在本轮或后续记录中明确处理方式，但不作为提交阻断。
- S3–S5 为低风险补强与流程闭环。
- 在 M1 修复并回填真实证据后，本 Milestone 可进入 PASS。
