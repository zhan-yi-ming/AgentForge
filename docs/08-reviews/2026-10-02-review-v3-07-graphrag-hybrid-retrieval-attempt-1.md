# Pi 代码审查报告：v3-07-graphrag-hybrid-retrieval / Attempt 1

- 日期：2026-10-02
- 审查阶段：v3-07-graphrag-hybrid-retrieval
- 审查对象：INDEX@ebafcfd（基线：ebafcfd6991436a8f561c9a31d2cd6da4afb7d26）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 代码审查报告

## 1. 概述与总体结论

- 审查阶段：`v3-07-graphrag-hybrid-retrieval`
- 审查模式：Milestone（V3-07 节点收口）
- 审查轮次：1 / 3
- 审查范围：`INDEX@ebafcfd` 全量 23 个改动文件（Java 内部图检索入口、Python 融合与契约客户端、测试、文档）
- 审查基线：完全只读；未运行命令、未修改任何文件或 Git 状态

**总体结论：PASS（建议项不影响交付，可进入 Node Close Gate）。**

判定依据：

1. **节点边界正确**：仅实现 V3-07 GraphRAG Hybrid Retrieval（Java 只读图检索 + Python 图/文融合），无 V3-08 Git 仓库上下文、无 V3-09 Release Gate、未越界改动 V3-04/V3-05/V3-06 写入与人工消歧逻辑；未引入任何写入路径，未改动公共 Chat JSON/SSE 字段。
2. **信任边界完好**：内部入口沿用 `X-AgentForge-Core-Internal-Token`，Service 层在读取图之前重新执行 `requireUserExists` + `ProjectAccess.requireAccess`；Python 不能直连 Neo4j，Java 不做概率排序、Python 不做授权，符合“AI 决策、确定性系统执行”。
3. **证据约束落地**：Java 只返回读取时重验过的实体/关系/evidence；Python 再用**当次**已授权 `RagSource` 的版本与原文二次过滤（`source.version != evidence.source_version || excerpt not in content` 即丢弃），无 evidence 边不进入 Context。
4. **有界性成立**：候选 500、根 4、跳数 2、邻居 12、关系/匹配 40，均与文档声明一致；不存在无限扩散或 Context 爆炸路径。
5. **降级/失败关闭语义正确**：503 与传输类异常降级为纯文本 RAG；401/403/404、其它非成功状态与响应关联不匹配抛出 `RagDependencyError` 失败关闭。
6. **测试证据自洽**：Java 覆盖关系问答、内部鉴权/越权/过期证据、两跳路径、人工 alias 与 mapping 失效；Python 覆盖图证据引用、503 降级 vs 403/关联失败关闭、图被双路文本排名挤出时的保位回退。变更记录明确跨进程验证是契约 smoke 而非完整 Chat/LLM 断言，未夸大。

未发现“必须修改”级别问题。

## 2. 详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| S-1 | 建议修改 | Low–Medium | `services/core-api/.../graph/application/GraphRetrievalService.java` | 58、94 | 直接注入并调用 `GraphStore`，绕过 `GraphService` 的 enabled/可用性检查；需确认图关闭/Neo4j 不可用时仍被映射为 503 而非 500，否则 Python 会失败关闭而无法降级 |
| S-2 | 建议修改 | Low | `GraphRetrievalService.java` | 67–76、121–127 | 规范映射查询抛 `ResourceNotFoundException` 时整个实体/关系被丢弃（含仅依赖自身 displayName 的合法命中），比 V3-06“仅隐藏 alias/映射”更保守，建议在文档显式声明该取舍 |
| S-3 | 建议修改 | Low | `services/core-api/src/test/java/.../GraphApiIntegrationTest.java` | 新测试段 | 文档明确承诺 400（空/超长 query、缺失 id）与 503（图关闭/故障），但新入口无对应自动化测试 |
| S-4 | 建议修改 | Low | `services/agent-service/src/agentforge_agent/retrieval.py` | 96、146、156–174 | 新增代码依赖 `chunk.id` 为 `str`（`startswith("graph:")`）；若未来 chunk id 类型变化，会在**每次**检索时抛 `AttributeError`，建议加类型保护 |
| S-5 | 建议修改 | Low | `docs/02-architecture/system-overview.md` | V3-03 段落后 | 新增 `## V3-07 GraphRAG 读取边界（Implemented）` 标题前缺少空行，部分 Markdown 渲染器会吞掉标题 |
| S-6 | 建议修改 | Low | `README.md` | 当前状态与真实证据段 | Milestone Review 尚未 PASS，README 已标 ✅ Implemented；建议与变更记录“Milestone Review 待收口”的措辞保持一致 |
| S-7 | 建议修改 | Low | `services/agent-service/src/agentforge_agent/retrieval.py` / 测试 | 163、`test_graphrag_retrieval.py` | Task 来源图证据的 `excerpt in content` 未获端到端覆盖（跨进程 smoke 只用 Wiki），建议确认 Java 的 Task `RagSource.content` 逐字包含 `description`，并补一个 Task evidence 的 Python 用例 |

## 3. 逐个 Issue 展开

### S-1 `GraphRetrievalService` 直接使用 `GraphStore` 绕过可用性检查

- **Severity**：Low–Medium（若确认会退化为 500，则升级为必须修改）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphRetrievalService.java:58`、`:94`
- **Evidence**：

```java
42  public GraphRetrievalService(UserDirectory users, ProjectAccess projects, GraphService graph,
43      GraphStore store, GraphResolutionDecisionService decisions) { ... }

57  while(scanned<MAX_SCANNED) {
58      var page=store.entities(projectId,after,Math.min(100,MAX_SCANNED-scanned));
...
94  try { page=graph.neighbors(projectId,node.id(),actor,"",MAX_NEIGHBORS); }
```

- **Description**：V3-04 文档约定“图功能默认关闭，关闭或 Neo4j 不可用返回通用 503”。该保证通常由 `GraphService` 或统一异常处理施加。本服务注入了 `store` 并用于根候选扫描（第 58 行），这是整条读取链的第一步，早于任何 `graph.*` 调用。若 `GraphStore` 本身不做 enabled/依赖检查，则图关闭时可能抛出未映射到 503 的驱动异常（如 500），而 Python 侧对 500 不降级（`fetch_graph` 仅对 503 与 `TransportError` 返回 `[]`），会导致整轮 Chat 失败。若该异常类型统一映射为 503，则本条不成立。
- **Suggested Fix**：优先通过 `GraphService` 暴露带 enabled/可用性判定的候选枚举方法；或确认 `GraphStore` 抛出的依赖异常已被全局映射为 503，并补一个“图关闭时该入口返回 503”的测试（与 S-3 合并）。

### S-2 规范映射失效时整条关系被丢弃

- **Severity**：Low
- **File & Line**：`GraphRetrievalService.java:64–76`、`:106–112`、`:121–127`
- **Evidence**：

```java
64  try {
65      int score=score(normalized,entity.displayName());
66      if(resolvable(entity.type())) {
67          var decision=decisions.decision(projectId,entity.id(),actor);
...
74  } catch(ResourceNotFoundException expiredDuringResolution) {
75      // The source changed between candidate validation and resolution lookup.
76  }
...
121 private EntityView view(UUID projectId, Entity entity, AuthenticatedActor actor) {
122     if(!resolvable(entity.type())) return new EntityView(...displayName...,null);
123     var decision=decisions.decision(projectId,entity.id(),actor);   // 可能抛出
...
126     return new EntityView(entity.id(),entity.type(),entity.displayName(),null);
```

- **Description**：当某实体的规范映射来源失效时，`decisions.decision` 抛出的异常会（a）在根扫描中使该实体完全不被当作根候选，（b）在 `view` 中经第 112 行 catch 使整条关系被丢弃——即使该实体自身来源有效、关系 evidence 有效、且仅凭自身 `displayName` 就可命中。V3-06 的语义是“失效成员/alias 隐藏”，并非隐藏底层有效图证据。这是 fail-closed 的保守取舍（召回降低，非越权），新测试 `graphRetrievalUsesOnlyCurrentHumanConfirmedAlias` 断言 `matches` 为空，说明该行为已被作者接受，但与文档表述不完全对齐。
- **Suggested Fix**：在 `view` 与根扫描中，将映射查询失败降级为 `UNMAPPED`（使用自身 `displayName`/`canonicalEntityId=null`），仅在映射本身有效时替换为 canonical 名称；同时在 `graphrag.md` 明确“映射失效 -> 按未映射实体回退”或“关系整体隐藏”的既定语义，避免实现与文档漂移。

### S-3 缺少 400 / 503 契约测试

- **Severity**：Low
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/graph/GraphApiIntegrationTest.java`（新增 `internalGraphRetrieval*` 测试函数）
- **Evidence**：新增测试覆盖 200（关系问答、两跳、alias）、401（缺 token/错 token）、403（跨项目 actor）、空匹配；未见 `query` 为空/超 1000 字符、`projectId`/`userId` 缺失（期望 400）以及图关闭/不可用（期望 503）的用例。文档 `docs/04-api/core-api.md` 明确写出“输入错误 400；图关闭或故障返回通用 503”。
- **Description**：新入口的 Bean Validation（`@NotNull/@NotBlank/@Size(max=1000)`）与图不可用 503 映射是公开契约的一部分，属于可回归的行为分支；缺失测试使 S-1 的风险无法被门禁捕获。
- **Suggested Fix**：增加两到三个 MVC 用例：`query=""`/超长 -> 400；`projectId` 缺失 -> 400；`agentforge.graph.enabled=false`（或注入不可用 GraphStore）-> 503，并断言响应不含驱动/连接串信息。

### S-4 `chunk.id` 类型假设

- **Severity**：Low
- **File & Line**：`services/agent-service/src/agentforge_agent/retrieval.py:96`、`:146`、`:156–174`
- **Evidence**：

```python
96   task_targets=_task_targets([chunk for chunk in included if not chunk.id.startswith("graph:")]),
...
146  content = chunk.content.split("\n", 1)[0] if chunk.id.startswith("graph:") else chunk.content
...
165  identity = f"graph:{match.relation_id}:{evidence.source_id}"
172  result.append(StoredChunk(identity, project_id, source.source_type, source.source_id,
```

- **Description**：`_deduplicate_sources`（第 146 行）现在对**每次**检索的所有 chunk 调用 `chunk.id.startswith(...)`，`_task_targets` 过滤同样依赖 str。新增测试使用 `"text"`/`"a"` 等字符串 id，推定 `StoredChunk.id` 为 `str`；但该类型未在本 diff 内确认，若生产 chunk id 为 `UUID`，将导致所有 Chat 检索路径抛 `AttributeError`。
- **Suggested Fix**：改为显式类型安全判断，例如 `is_graph = str(chunk.id).startswith("graph:")`，或在 `StoredChunk` 定义处固定 id 类型并加断言，避免隐式类型耦合。

### S-5 `system-overview.md` 标题前缺空行

- **Severity**：Low
- **File & Line**：`docs/02-architecture/system-overview.md`（V3-03 段落后新增标题处）
- **Evidence**：

```diff
 V3-03（Implemented）在 Responder 上方增加确定性任务模型路由，... HTTP 与 Java 写入边界不变。
+## V3-07 GraphRAG 读取边界（Implemented）
+
+Agent Service 在既有 Hybrid RAG 来源回调后，...
```

- **Description**：Markdown 二级标题与上一段落之间缺少空行，部分渲染器（含 GitHub 的部分解析路径）可能将标题并入段落，影响文档可读性。
- **Suggested Fix**：在 `## V3-07 ...` 前插入一个空行。

### S-6 README 状态标注早于 Milestone Review

- **Severity**：Low
- **File & Line**：`README.md`（“当前状态与真实证据”段）、`docs/07-changes/2026-09-30-v3-07-graphrag-hybrid-retrieval.md`
- **Evidence**：

```diff
-- 🧭 V3-07：GraphRAG 检索/回答仍为规划状态。
+- ✅ V3-07：有证据约束的 GraphRAG 混合检索已实现；[设计、测试与限制](docs/03-features/graphrag.md)。
```
变更记录同时写明：`状态：Implemented（2026-10-02；Milestone Review 与 Node Close Gate 待收口）`。
- **Description**：本节点机器验证已完成、代码已实现，标 ✅ 在协议状态集合（Implemented / In Progress / Planned）内可接受；但里程碑 Review 尚未 PASS，README 未体现“审核待收口”，与节点协议“未完成 Milestone Review 前不宣称收口”的精神存在轻微措辞差。
- **Suggested Fix**：README 保持 ✅ 的情况下，补一句“Milestone Review 待收口”，或在 Review 通过后再改为最终措辞，保持与变更记录一致。

### S-7 Task 来源图证据的 Python 过滤缺少覆盖

- **Severity**：Low
- **File & Line**：`retrieval.py:163`、`services/agent-service/tests/test_graphrag_retrieval.py`
- **Evidence**：

```python
163  if source is None or source.version != evidence.source_version or evidence.excerpt not in source.content:
164      continue
```
新增测试的图证据均来自 `WIKI`，跨进程 smoke 亦只用 Wiki 来源。
- **Description**：V3-05 中 Task 关系 evidence 的 excerpt 取自 `description`，而 Java `RagSource.content` 是“由 Java 从 title/status/priority/... 组合”的文本。若组合结果未逐字保留 `description` 原文，`excerpt not in content` 会静默丢弃全部 Task 来源图证据，导致跨模块关系（如 `Task MODIFIES Service`）在回答中不可见；现有测试无法发现这一整类回归。
- **Suggested Fix**：确认 Java Task `RagSource.content` 原样嵌入 `description`；补一个 Python 用例：`sourceType=TASK`、`excerpt` 取 description 子串，断言图关系进入 Context 与 `sources`。

## 4. 无需修改（已确认符合预期）

| ID | 文件/位置 | 结论 |
| --- | --- | --- |
| N-1 | `SecurityConfiguration.java` + `GraphRetrievalController` | `permitAll` 仅用于放行到 `CoreInternalAuthentication.requireValid(token)`，与既有 `/internal/v1/rag/sources` 模式一致；Service 层 `requireUserExists` + `ProjectAccess.requireAccess` 二次强校验，未引入新的越权面 |
| N-2 | `GraphRetrievalService` 有界遍历 | 500/4/12/40/40/两跳硬上限齐全，`visitedNodes` 防重入，`matches` 按 relationId 去重，无无限扩散与 Context 爆炸路径 |
| N-3 | `core_client.py:fetch_graph` | 503/传输异常降级为 `[]`；401/403/404 与关联不匹配抛 `RagDependencyError` 失败关闭；与文档“越权不得当作空结果”一致 |
| N-4 | 跨库一致性 | Neo4j 投影与 PostgreSQL 无原子快照已在 `graphrag.md`、ADR-0035 明确声明，Python 以当次授权来源版本/原文再次过滤，属正确的 fail-closed 设计 |
| N-5 | 评测/文档真实性 | 变更记录明确“跨进程仅为契约 smoke，无完整 Chat/LLM 断言”“不宣称准确率提升”，未出现无证据指标或夸大表述 |
| N-6 | 测试中的 `test-only-core-token` | 为测试固定 token，与仓库既有测试一致，非真实凭据，无敏感信息泄露 |

## 5. 主开发（Codex）评估回填区

| ID | Codex 事实判断（真实/部分/不成立） | 是否需改 | 最小修复与补测范围 | 处理状态 |
| --- | --- | --- | --- | --- |
| S-1 | 不成立：`Neo4jGraphStore.driver()` 关闭时抛 `ServiceUnavailableException`，`transaction()` 转换 Neo4j 异常，`ApiExceptionHandler` 返回 503。 | 否 | 已核实既有异常链；额外 503 用例属后续覆盖。 | 已判断 |
| S-2 | 部分：`decision()` 对过期映射返回 UNMAPPED；仅二次读取间来源并发变化可能抛 404，保守隐藏关系。 | 否 | 与来源竞争时不输出旧证据的边界一致。 | 已判断 |
| S-3 | 真实覆盖缺口，但 Bean Validation 与既有 503 异常链明确；未发现契约错误。 | 否 | 后续回归可补输入/关闭分支，不是本次阻塞修复。 | 已记录 |
| S-4 | 不成立：`StoredChunk.id: str` 在 `rag_store.py` 中固定。 | 否 | 保留显式字符串契约。 | 已判断 |
| S-5 | 真实文档排版问题。 | 是 | 标题前补空行；仅文档格式复查。 | 已修复 |
| S-6 | Review 时进度措辞待收口；本次 Pi 已 PASS。 | 是 | 变更记录回填 PASS，README 的 Implemented 与真实状态一致。 | 已修复 |
| S-7 | Task 来源由 `RagSourceService.taskContent()` 原样拼接 description，excerpt 可逐字匹配；测试覆盖可加强。 | 否 | 非实际丢失缺陷，作为建议记录。 | 已判断 |

## 6. 节点完成度小结（Milestone）

- **Scope 完成度**：V3-07 声明的检索链（Query Understanding → Entity Candidate → Graph Traversal → Vector/BM25 → Merge/Rerank → Context → LLM）已在 Java 内部只读入口与 Python `RetrievalService` 中形成可运行闭环；返回 entity/relation/source/evidence/confidence 与 Wiki/Task 文档引用。
- **未实现的下一节点能力**：无 V3-08 Git 仓库上下文、无任意 Cypher、无 LLM 自动选模型、无 V3-09 Release Gate；未新增持久化表，与节点边界声明一致。
- **下一节点隐患**：GraphRAG 的召回依赖确定性名称/alias 匹配（短查询/弱匹配会漏召回），且图与业务库无原子快照；`S-1` 的 503 映射确认后，V3-09 集成阶段应把“图关闭仍可文本降级”纳入回归。
- **可关闭性**：无“必须修改”项；上述建议项不影响 Node Close Gate，建议在收口时一并处理 S-1、S-3（与图不可用回归相关）后提交。
