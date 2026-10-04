# Pi 代码审查报告：main-review-r15 / Attempt 2

- 日期：2026-10-04
- 审查阶段：main-review-r15
- 审查对象：INDEX@ba98302（基线：ba98302a4475bc97b2b936c8534a6c1e48f8823a）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：main-review-r15 / Attempt 2

- 日期：2026-10-04
- 审查阶段：main-review-r15
- 审查模式：Milestone（2 / 3）
- 审查对象：INDEX@ba98302（基线：ba98302a4475bc97b2b936c8534a6c1e48f8823a）
- 审查依据：本次 Diff、文件清单、DoD、测试策略、Day 4 / V3 质量门槛、Attempt 1 报告与变更记录
- 前置声明：本报告完全只读。未运行任何命令、测试或 Git 操作；文中“机器证据”均为转述 `docs/07-changes/2026-10-04-r15-rag-query-bounds.md` 与 Attempt 1 报告的记录，未改写为 Pi 执行结果。

---

## 一、概述与总体结论

本次 Attempt 2 在 Attempt 1 的基础上完成了 R15 容量修复，并处置了上一轮 finding：

1. **Core 来源代际握手**（`knownSnapshotVersion` + `sourcesChanged`）：授权顺序正确（用户存在 → 项目访问 → 代际读取），匹配时短路返回空正文，不读取 Wiki/Task；不匹配/缺省返回完整快照，保留删除传播。兼容方向（新 Core × 旧 Agent）已在 API 与运维文档明确。
2. **PostgreSQL 内有界候选**：V18 生成列 `to_tsvector('simple', title||content)` + GIN，向量路与词法路各自 `LIMIT candidate_k`，Python 只加载两路候选并集，再对**有界**词法候选计算 BM25。不再搬运全项目 Chunk。
3. **Graph evidence 有界匹配**：改为在相同代际索引上空 `load_graph_evidence` 验证，`stable 代际` 下不再依赖全量来源正文；同步后始终按同代际 Chunk 验证，消除了 Attempt 1 S1 的“变化/未变化路径语义不一致”。

Attempt 1 的阻断项 **M1 已由真实 pgvector 集成测试闭环**（命中、source_version 错配、代际错配、跨 Chunk excerpt 负例），S1 的行为不一致已消除；其余建议项以“文档显式记录 + 不引入新排序语义”方式处置，判断合理。

代码层面未发现越权、契约错配、并发回退、数据一致性缺陷或不可运行问题。剩余均为中等/低风险的健壮性与覆盖建议，不构成交付阻断。

**总体结论：通过（PASS）。** 无“必须修改”项；Attempt 1 阻断项已关闭。

---

## 二、详细发现清单

| ID | 分组 | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| S1 | 建议修改 | 中 | `services/agent-service/src/agentforge_agent/rag_store.py` | `load_graph_evidence`（约 197–245 行） | 结果字典按 `(source_type, source_id, source_version)` 作键；同一来源、不同 Chunk 的多条 relation 会互相覆盖，`_graph_chunks_from_index` 随后只保留最后一条可命中关系 |
| S2 | 建议修改 | 低 | `services/agent-service/src/agentforge_agent/rag_store.py` | `load_graph_evidence` 匹配循环 | 每条 match 一次 `cursor.execute`，最多 40 次数据库往返；可合并为单条 `VALUES/unnest` 查询 |
| S3 | 建议修改 | 低 | `RagSourcesRequest.java` / `RagSourcesApiTest` / `core_client.py` | 请求校验与回归测试 | 缺少边界断言：负 `knownSnapshotVersion` → 400；Core 返回 `sourcesChanged=false` 但 `sources` 非空时 Python 抛 `RagDependencyError` |
| S4 | 建议修改 | 低 | `V18__rag_lexical_search.sql` / `rag_store.py` | 词法预选 | `simple` + `ts_rank_cd` 预选后交由 BM25 重排，是已记录的有界近似；建议在集成测试中固定“无空格 CJK 仅走向量路/空词法候选”的可观察行为 |
| S5 | 建议修改 | 低 | `docs/07-changes/2026-10-04-r15-rag-query-bounds.md`、`docs/07-changes/README.md` | 状态与收尾 | 变更记录状态仍为 `Pi Re-review Pending`，README 仍标 `In Progress`；DoD 要求最终敏感扫描与状态闭环 |

无“必须修改”项。无需修改项见第四节。

---

## 三、逐个 Issue 展开

### S1（建议修改，中）`load_graph_evidence` 按来源作键会覆盖同源多 relation

- **Severity**：Medium
- **File & Line**：`services/agent-service/src/agentforge_agent/rag_store.py`，`load_graph_evidence`（约 220–243 行）；`retrieval.py` `_graph_chunks_from_index`（约 190–215 行）
- **Evidence**：

```python
# rag_store.load_graph_evidence
for match in matches[:40]:
    evidence = match.evidence
    cursor.execute(
        """
        ... FROM rag_chunk
        WHERE project_id = %s AND source_type = %s AND source_id = %s
          AND source_version = %s AND strpos(content, %s) > 0
        ORDER BY chunk_index LIMIT 1
        """,
        (project_id, evidence.source_type, evidence.source_id,
         evidence.source_version, evidence.excerpt),
    )
    row = cursor.fetchone()
    if row is not None:
        result[(evidence.source_type, evidence.source_id, evidence.source_version)] = _stored_chunk(row)
```

```python
# retrieval._graph_chunks_from_index
source = authorized.get((evidence.source_type, evidence.source_id, evidence.source_version))
if source is None or evidence.excerpt not in source.content:
    continue
identity = f"graph:{match.relation_id}:{evidence.source_id}"
```

- **Description**：字典键只含来源身份与版本，不含 Chunk/evidence。若图检索对同一 Wiki 来源返回两条 relation，且两条 excerpt 分属不同 Chunk（例如同一长页面的不同句子），循环中后一条会覆盖前一条；随后 `_graph_chunks_from_index` 对第一条 relation 执行 `evidence.excerpt not in source.content` 为真而跳过，第二条正常保留。结果是同一来源的多条关系会静默丢失一部分，图上下文引用数随 relation 顺序变化。此前 `_graph_chunks_from_sources` 用整源 content 匹配不存在该覆盖，故这是本次统一到索引路径后引入的行为收缩。当前两条 GraphRAG/集成测试均只放一条 match，未覆盖同源多 relation。
- **Suggested Fix**：让返回值保留 per-match 粒度，例如键改为 `(source_type, source_id, source_version, chunk_index)`，或在 `load_graph_evidence` 内通过 `unnest` 一次返回 `(match_index, chunk)` 列表，由 `_graph_chunks_from_index` 按 match 取值；并补一条“同一来源两条 relation、excerpt 分属两个 Chunk，均在 context 中出现”的真实 pgvector 用例。

### S2（建议修改，低）`load_graph_evidence` 逐 match 查询产生 N 次往返

- **Severity**：Low
- **File & Line**：`rag_store.py` `load_graph_evidence`（约 220–241 行）
- **Description**：一次 Chat 内最多 40 条 match，每条一次 `cursor.execute` 与一次网络往返，在 RAG 热路径上会放大延迟。语义正确，属性能健壮性建议。
- **Suggested Fix**：用 `unnest(%s::text[], %s::uuid[], %s::bigint[], %s::text[])` 生成匹配表后单条 `JOIN rag_chunk` 查询，配合 `DISTINCT ON` 保留每 match 的首个 Chunk。

### S3（建议修改，低）边界与失败关闭的测试缺口

- **Severity**：Low
- **File & Line**：`RagSourcesRequest.java`（`@PositiveOrZero Long knownSnapshotVersion`）；`core_client.py`（`unchanged source response is inconsistent` 分支）；`RagSourcesApiTest.java`
- **Description**：`@PositiveOrZero` 已实现负值拒绝，`core_client.fetch_sources` 已实现 `sourcesChanged=false` 时版本必须匹配且 sources 必须为空，但两者均无断言测试。Attempt 1 S4 相同，仍属覆盖补强而非行为缺陷。
- **Suggested Fix**：在 `RagSourcesApiTest` 增加 `"knownSnapshotVersion":-1` → 400 的用例；在 `test_core_source_contract_...` 增加 `sourcesChanged=false` 且 `sources` 非空 → `pytest.raises(RagDependencyError)`。

### S4（建议修改，低）词法预选语义需用测试固定

- **Severity**：Low
- **File & Line**：`V18__rag_lexical_search.sql`；`rag_store.search` 词法查询
- **Description**：词法候选由 `plainto_tsquery('simple', ...)` + `ts_rank_cd` 截断到 `candidate_k`，再交 BM25 重排；`simple` 对无空格 CJK 的确定性行为、以及预选与 BM25 打分函数不同导致的候选差异，已在功能文档“已知限制”披露。为避免未来静默改变排序语义，建议用可观察断言固定该边界。
- **Suggested Fix**：在真实 pgvector 集成测试中补一条无空格中文语料断言“词法候选为空、向量候选仍可返回”，作为文档限制的可执行证据；若后续引入 `pg_trgm` 或分词需新变更记录。

### S5（建议修改，低）变更记录状态与最终敏感扫描闭环

- **Severity**：Low
- **File & Line**：`docs/07-changes/2026-10-04-r15-rag-query-bounds.md`、`docs/07-changes/README.md`
- **Description**：DoD 要求提交前敏感扫描通过并回填、文档状态与实际一致。当前记录状态为 `Pi Re-review Pending`，README 链接标 `In Progress`；Attempt 1 的 S5（最终 Gitleaks 未闭环）需在提交前完成。
- **Suggested Fix**：提交前对最终 staged diff 重跑 Gitleaks 并回填退出码与结论，将记录状态更新为 Implemented、README 条目同步。

---

## 四、无需修改（已确认正确）

| ID | 文件 | 结论 |
| --- | --- | --- |
| N1 | `RagSourceService.java` | 授权顺序正确：`requireUserExists` → `requireAccess` → 读取代际；短路分支不读取 Wiki/Task，`verifyNoInteractions(wiki, tasks)` 已覆盖 |
| N2 | `RagSourceService.java` | `knownSnapshotVersion == snapshotVersion` 为 `Long` 对 `long` 的数值比较，无装箱引用比较缺陷；`null` 已由判空保护 |
| N3 | `core_client.py` | `sourcesChanged` 一致性校验（版本匹配、sources 为空）失败关闭，异常归一为 `RagDependencyError`，不泄漏下游正文 |
| N4 | `RagSourcesRequest/Response` | `knownSnapshotVersion` 可选非负；`sourcesChanged` 新增为必填响应字段，旧 Agent 忽略新字段仍可工作，新 Agent 需新 Core，方向已在 API/运维文档写明 Core-first |
| N5 | `rag_store.search` | 向量/词法各自 `candidate_k`、候选并集去重、仅对有界词法候选算 BM25，`project_id` 过滤保持，无跨项目泄漏；空候选返回空而不报错 |
| N6 | `retrieval.py` | 同步仅对 `sourcesChanged=true` 执行；搜索/图证据在 REPEATABLE READ 内二次校验代际，不一致 fail closed，代际竞态不返回他快照内容 |
| N7 | `DatabaseIdentitySeparationIntegrationTest.java` | `max(version)=18` 与 V18 迁移一致 |
| N8 | `database-role-boundary.ps1` | 显式列插入与读取生成列 `search_document` 的边界校验与 V18 一致，Agent 仍不能读取业务表 |

---

## 五、主开发（Codex）评估回填区

| Finding ID | 是否认可 | 处理决定 | 证据（命令/退出码/测试数/清理） | 备注 |
| --- | --- | --- | --- | --- |
| S1 | 认可为检索完整性建议，不修改 | 同源多 relation 跨 Chunk 时可能只保留一个 Graph context，但不影响来源授权、业务写入、快照一致性或可运行性；Attempt 2 已 PASS，按规则不为中风险纯建议扩大本批 | 真实 pgvector 单 relation 正负例 `1 passed`；Agent 全量 `224 passed`；跨进程 `13/13` | 后续可用 per-match key 的独立纵向切片处理 |
| S2 | 认可为性能建议，不修改 | 查询次数由 Core 的 40 match 上限约束；批量 `unnest` 会扩大 SQL 与返回契约，留给独立优化 | 当前有界与真实 SQL 测试通过 | 不触发复审 |
| S3 | 建议成立，不修改 | 生产行为已有 Bean Validation 与 Python fail-closed 校验；仅缺两条边界断言，不是当前缺陷 | Java定向/全量与 Agent 全量均通过 | 后续覆盖矩阵处理 |
| S4 | 建议成立，文档已固定 | 功能文档明确无空格 CJK 可能仅走向量路；本批不引入新扩展或改写排序 | 文档与现有 V18 实现一致 | 后续检索质量切片再给可执行边界 |
| S5 | 认可并已闭环 | 变更记录与索引更新为 Implemented；最终完整 index 在提交前执行 diff check 与 Gitleaks | `git diff --cached --check` 退出 0；Gitleaks 退出 0、`no leaks found` | 流程项关闭 |

---

## 六、结论

- **必须修改项：无。** Attempt 1 的 M1（`load_graph_evidence` 缺真实 PostgreSQL 证据）已由命中/版本错配/代际错配/跨 Chunk excerpt 负例闭环；S1 的路径语义不一致已通过统一到索引路径消除。
- 剩余 S1–S5 为中等/低风险健壮性与覆盖建议，按规则不阻塞交付，可在提交前或后续小切片处理。
- 在 Codex 完成 S5 的提交前敏感扫描复扫并回填后，本 Milestone 可进入 PASS 交付。
