# Pi 代码审查报告：v3-06-entity-resolution / Attempt 2

- 日期：2026-09-29
- 审查阶段：v3-06-entity-resolution
- 审查对象：WORKTREE@2a0281d（基线：2a0281df0e2484740b9bbf9b96e4f48586df0e71）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-06 Entity Resolution — Milestone Review (Round 2/3)

- 日期：2026-09-29
- 审查阶段：v3-06-entity-resolution
- 审查对象：WORKTREE@2a0281d（基线：2a0281df0e2484740b9bbf9b96e4f48586df0e71）
- 审查模式：Milestone（只读；未运行命令，未修改任何文件或 Git 状态）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）

---

## 一、概述与总体结论

本轮 diff 相比 Attempt 1 已经包含 Review 修复后的完整实现：Java 侧候选发现、人工确认/撤销、规范实体读取与编辑（`GraphResolutionService`、`GraphResolutionDecisionService`、`GraphResolutionController`）、V13 四表持久化（canonical / member / event / canonical_event）、Python 内部建议端点（`entity_resolution.py` + `api.py` 新路由）、两侧契约测试与真实容器集成测试，以及相应文档/ADR/变更记录。

对 Attempt 1 结论的核查：

- **MR-01（必须修改，锚点来源版本不可刷新）已修复。** `confirm` 现在在人工确认事务内，当已存在 canonical 且存储的 `anchor_source_version` 低于当前来源版本时，执行 CAS `UPDATE ... WHERE anchor_source_version=? AND version=?` 刷新锚点版本并递增 canonical `version`、追加 `REFRESH_SOURCE` 审计；成员行保存确认时的锚点来源版本（`canonical_source_version`），锚点刷新后旧成员因版本不匹配仍返回 `UNMAPPED`。`refreshedAnchorRequiresEachMemberToBeReconfirmed` 覆盖了 409→200/UNMAPPED 的红绿路径。
- **MR-02（自映射/循环）已修复。** `confirm` 现在拒绝自映射（`entityId.equals(anchor.id())` → 400）、拒绝已是规范锚点的实体成为成员（`canonical(projectId,entityId).isPresent()` → 409）、拒绝已确认成员作为锚点（`member(projectId,anchor.id())` 为 CONFIRMED → 409）。`canonicalAnchorsCannotBecomeMembersOrFormCycles` 覆盖自映射与 A→B/B→A 双向。
- **MR-03（断言过弱）已修复。** `sourceChangeHidesExistingMemberAlias` 现轮询等待成员来源版本严格增大，再固定断言 `200 + UNMAPPED + version=1`。
- **MR-06（状态标签不一致）已修复。** README 为 🚧、Roadmap 为“阻塞项修复中”、`entity-resolution.md`/`docs/README.md` 为 In Progress、架构文档为“开发中”、变更记录为 In Progress，公开状态一致。
- **MR-04/05/07/08** 的处理与 Codex 回填一致：MR-04/05 为无当前缺陷复现的建议，未阻塞；MR-07/08 不构成可达缺陷。

在本轮全量 diff 中，**未发现具备明确证据、可复现的正确性/权限/并发/契约/数据一致性缺陷**，因此本轮结论为 PASS（保留若干非阻塞建议）。

## 二、详细发现清单

| ID | 分类 | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| MR2-01 | 建议修改 | 中 | `services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionService.java` | `suggest` 扫描循环（约 L28–60） | 每个候选重复 `graph.entity` + `decisions.decision`，最坏约 1500 次权限校验/DB 往返（Attempt 1 MR-05 未处理，仍建议） |
| MR2-02 | 建议修改 | 中 | `GraphResolutionDecisionService.java`、`docs/04-api/core-api.md` | `confirm` canonical 校验分支（约 L96–110） | 复用已有 canonical 要求请求的 `canonicalName`/`metadata` 与存储值完全相等，不同成员确认同一锚点必须逐字复制，无法顺带改名 |
| MR2-03 | 建议修改 | 低 | `GraphResolutionDecisionService.java` | `revert` 入口（约 L186–196） | 成员来源失效时 `graph.entity` 先抛 404，导致无法撤销，DB 永久保留 CONFIRMED 行 |
| MR2-04 | 建议修改 | 低 | `GraphResolutionDecisionService.java` | `updateCanonical`（约 L166–176） | 幂等短路（名称/metadata 相同即返回）在 `expectedVersion` 校验之前，陈旧 CAS 会返回 200 |
| MR2-05 | 建议修改 | 低 | `services/agent-service/src/agentforge_agent/entity_resolution.py` | `resolution_model`（约 L31–37） | `getattr(responder,"responders",{})` 后直接 `.get(...)`，若该属性非 dict 会在依赖解析期抛 500 |
| MR2-06 | 建议修改 | 低 | `services/agent-service/tests/test_entity_resolution.py` | `test_resolution_returns_only_bounded_candidate_ids_for_review` | 未显式覆盖 `resolution_model`，结果依赖 `llm_provider == disabled` 才稳定 |
| MR2-07 | 建议修改 | 低 | `GraphResolutionService.java` | `similarity`（约 L68–78） | 规范化后为空串时 `"".split(" ")` 返回 `[""]`，可能得到错误相似度 |
| MR2-08 | 无需修改 | — | `GraphResolutionDecisionService.java` | `decision`（约 L225） | 未显式判空 `entity.source()`，但可达路径保证 SERVICE/API/ISSUE 必有来源 |
| MR2-09 | 无需修改 | — | `entity_resolution.py` | `_normalize` | 与 Java `normalize` 规则不同，仅用于排序/提示，无契约风险 |
| MR2-10 | 无需修改 | — | 全 diff | 状态与证据 | MR-01/02/03/06 修复经红绿测试与文档一致性验证成立 |

## 三、逐个 Issue 展开

### MR2-01（建议修改，中）候选扫描仍为 N+1

- File & Line：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionService.java`，`suggest` 循环（约 L28–60）。
- Evidence：

```java
while(scanned<500) {
    var page=store.entities(projectId,after,100);
    for(var raw:page.items()) {
        scanned++;
        if(raw.id().equals(entityId) || raw.type()!=source.type()) continue;
        Entity current;
        try { current=graph.entity(projectId,raw.id(),actor); }      // requireAccess + store 读
        catch (ResourceNotFoundException expired) { continue; }
        ...
        var decision=decisions.decision(projectId,current.id(),actor); // 再次 graph.entity + JDBC
```

- Description：`graph.entity` 每次调用 `projects.requireAccess` 并读取图；`decisions.decision` 又解析成员/规范行。最坏 500 实体时约 1000–1500 次权限校验与数据库往返。与 Attempt 1 的判断一致：这是可安全兜底的上限，但实现开销偏大，规模较大项目会拖慢建议接口。
- Suggested Fix：循环外做一次权限校验，改为一次查询批量读取本项目同类型实体的来源与成员规范化状态（带 `entity_type`/`status` 过滤），仅在需要时逐实体回读；或在项目层面缓存 `requireAccess` 结果。

### MR2-02（建议修改，中）复用 canonical 时要求名称/metadata 完全相等

- File & Line：`GraphResolutionDecisionService.confirm`，canonical 校验分支（约 L96–110）。
- Evidence：

```java
var canonical=canonical(projectId,anchor.id()).orElseThrow();
if(canonical.type()!=anchor.type() || !canonical.name().equals(name)
    || !canonical.metadata().equals(metadata)
    || canonical.sourceType()!=anchor.source().type()
    || !canonical.sourceId().equals(anchor.source().id()))
    throw new ConflictException("The canonical entity changed.");
```

- Description：行为本身是刻意设计（“已有规范实体不得因另一个成员请求悄悄改名/改 metadata”），并在 ADR/功能文档中有说明，不构成缺陷。但它带来两个使用面上的副作用：(1) 第二个成员确认同一锚点必须逐字重复首次的名称与全部 metadata，否则 409；(2) 锚点来源刷新后成员重新确认时，仍需复用旧名称/metadata，无法在同一次确认中顺带更新 canonical 名。当前仅能通过 `PUT /canonicals` 改名，且该路径在锚点来源未刷新前会 404。这属于易用性/契约明确度问题，不阻塞交付。
- Suggested Fix：在 API 文档中显式说明“确认同一锚点必须提供与首次一致的 `canonicalName`/`metadata`，否则 409”；或放宽为“仅 `canonicalEntityId` 绑定，名称/metadata 仅首次生效、后续忽略”，减少调用方歧义。

### MR2-03（建议修改，低）来源失效成员无法被撤销

- File & Line：`GraphResolutionDecisionService.revert`（约 L186–196）。
- Evidence：

```java
var entity=graph.entity(projectId,entityId,actor);   // 来源失效时抛 ResourceNotFoundException
if(!resolvable(entity.type()) || expectedVersion==null || expectedVersion<0) throw invalid();
var stored=member(projectId,entityId).orElseThrow(...);
```

- Description：`graph.entity` 在成员来源已失效（版本过期/来源删除）时先抛 404，因此无法把仍为 `CONFIRMED` 的成员行置为 `REVERTED`。该行在读取时会被 `decision()` 正确隐藏为 UNMAPPED，所以不产生对外可见的错误；但数据库永久残留 CONFIRMED 行与 `canonical_id` 外键，缺少清理路径。文档已把“来源失效”列为 404 语义，因此这是明确记录的限制而非实现错误。
- Suggested Fix：如需可清理，可在 `revert` 中允许仅基于 `graph_resolution_member` 历史行执行撤销（不依赖当前来源有效），并在文档/测试中固定该语义；否则在功能文档“限制”一节补充说明。

### MR2-04（建议修改，低）`updateCanonical` 幂等短路先于 CAS 校验

- File & Line：`GraphResolutionDecisionService.updateCanonical`（约 L166–176）。
- Evidence：

```java
if(current.canonicalName().equals(name) && current.metadata().equals(metadata))
    return current;
if(current.version()!=request.expectedVersion())
    throw new ConflictException("The canonical version is stale.");
```

- Description：当名称/metadata 与当前一致时，即使 `expectedVersion` 陈旧或与当前版本不符也会返回 200。这与“同 payload 重试幂等”一致，但调用方无法用该接口探测 CAS。属于契约细节。
- Suggested Fix：在文档中明确该幂等语义，或把 `expectedVersion` 校验移到幂等判断之前（会牺牲纯重试的幂等性，需权衡）。

### MR2-05（建议修改，低）`resolution_model` 对 `responders` 的隐式假设

- File & Line：`services/agent-service/src/agentforge_agent/entity_resolution.py`，`resolution_model`（约 L31–37）。
- Evidence：

```python
responder = get_responder()
review = getattr(responder, "responders", {}).get("REVIEW", responder)
return getattr(review, "model", None)
```

- Description：若 `responder.responders` 存在但不是 `dict`（例如列表），`.get` 会抛 `AttributeError`，该异常发生在 FastAPI 依赖解析阶段，会变成 500，而非文档期望的“模型不可用降级为人工审阅”。当前无法从 diff 确认该属性类型，因此仅作低风险建议。
- Suggested Fix：显式判断类型并回退：

```python
responders = getattr(responder, "responders", None)
review = responders.get("REVIEW", responder) if isinstance(responders, dict) else responder
return getattr(review, "model", None)
```

### MR2-06（建议修改，低）建议端点测试隐式依赖 `llm_provider=disabled`

- File & Line：`services/agent-service/tests/test_entity_resolution.py`，`test_resolution_returns_only_bounded_candidate_ids_for_review`。
- Evidence：该用例未覆盖 `resolution_model`，直接断言 `recommendedCandidateId == candidates[0]`；而 `suggest_resolution` 在 `model is not None` 且模型输出异常时会 fallback 为 abstain（`recommendedCandidateId=None`）。
- Description：默认环境 `llm_provider=disabled` 时稳定通过；一旦运行环境启用 provider（或注入真实 responder），模型失败/超界会翻转为 abstain，使该用例偶发失败。属于测试环境耦合，非实现缺陷。
- Suggested Fix：在该用例内显式 `app.dependency_overrides[resolution_model] = lambda: None`，与其它用例一致地固定依赖。

### MR2-07（建议修改，低）`similarity` 空串边界

- File & Line：`GraphResolutionService.similarity`（约 L68–78）。
- Evidence：

```java
if(left.equals(right)) return 1;
var a=new java.util.HashSet<>(List.of(left.split(" ")));
```

- Description：`"".split(" ")` 返回 `[""]` 而非空数组，全部由标点组成的名称规范化后为空串时可能得到 1.0 的异常相似度。由于实体 `displayName` 有 1..200 约束、规范化后极少为空，当前不构成可复现缺陷。
- Suggested Fix：在计算前对空串短路返回 0。

### MR2-08（无需修改）`decision()` 未显式判空 `entity.source()`

- File & Line：`GraphResolutionDecisionService.decision`（约 L225）。
- Description：`member.sourceType()!=entity.source().type()` 未先判空，但 `GraphService.put` 对非 PROJECT 实体强制要求 source，且 `decision` 仅对 SERVICE/API/ISSUE 可达，因此当前不构成可达缺陷。可在后续统一加防御性判空。

### MR2-09（无需修改）Python 与 Java 规范化规则差异

- File & Line：`entity_resolution.py` 的 `_normalize`（`\w+`，含下划线）与 Java `normalize`（`\p{L}\p{N}`）。
- Description：两侧仅用于候选排序与提示，Java 不信任返回分数，人工确认决定写入，无契约或正确性影响。

### MR2-10（无需修改）修复有效性与文档一致性

- Description：MR-01 的 CAS 刷新与成员锚点版本绑定、MR-02 的自映射/循环约束、MR-03 的强断言、MR-06 的状态统一均在 diff 与测试中可见。V13 的 `ck_graph_resolution_canonical_status`、`ck_graph_canonical_event_action`、`ck_graph_resolution_event_action` 与代码写入值一致；`canonicalView`/`decision` 在锚点或成员来源失效时一致降级为 404/UNMAPPED。

## 四、主开发（Codex）评估回填区

| ID | 是否成立 | 原因判断 | 最小修复 | 补测范围 | 备注 |
| --- | --- | --- | --- | --- | --- |
| MR2-01 |   |   |   |   |   |
| MR2-02 |   |   |   |   |   |
| MR2-03 |   |   |   |   |   |
| MR2-04 |   |   |   |   |   |
| MR2-05 |   |   |   |   |   |
| MR2-06 |   |   |   |   |   |
| MR2-07 |   |   |   |   |   |
| MR2-08 |   |   |   |   |   |
| MR2-09 |   |   |   |   |   |
| MR2-10 |   |   |   |   |   |

## 五、审查结论

- 结论：**通过（PASS）**，无“必须修改”项。
- 阻塞项：无。Attempt 1 的阻塞项 MR-01 已修复并由红绿测试覆盖；MR-02、MR-03、MR-06 的修复与文档一致性在 diff 中可见。
- 非阻塞项：MR2-01～MR2-07 为建议，可由 Codex 判断后择机处理；MR2-08～MR2-10 无需修改。
- 已确认的正面证据：仅人工确认落库；锚点来源更新需人工确认触发 CAS 刷新且旧成员保持隐藏；自映射/循环/链式被拒；原始 Neo4j 节点与 evidence 不重接线；跨项目 anchor 404、无权重项目 403；来源版本 CAS 409、重复确认幂等、撤销后版本递增且旧 `expectedVersion=0` 不能重建；Python 候选白名单、越界/非有限 confidence、模型故障 abstain；Java 只接受候选集合内推荐 ID；公开状态标签一致。
- 边界确认：本次变更未引入 V3-07 GraphRAG 消费、未改 `/wiki/graph`、未触碰 V1 业务主流程；V13 为独立迁移，规范映射为可撤销覆盖层。
- 说明：本轮仅依据给定的 Git Diff、文件清单与历史报告完成只读审查，未运行测试；Codex 提供的机器证据被视为其执行结果，未改写为 Pi 自测。

## Codex 对 Attempt 2 建议的判断

- MR2-01：候选 N+1 与 500 节点上限属可量化性能限制；当前无超时复现，保留后续性能优化，不扩展本 Node 存储接口。
- MR2-02：行为成立且为有意约束；Core API 文档明确已有规范实体名称/metadata 必须一致，改名走单独 PUT。
- MR2-03：行为成立且当前读取安全；功能文档补充失效成员无法通过现有撤销入口清理、历史行保留用于审计的限制。
- MR2-04：幂等与 CAS 的优先顺序属有意契约；Core API 文档补充无变化时即使旧 expectedVersion 也返回当前值。
- MR2-05：现有 `RoutedResponder.responders` 是 dict，单模型 responder 不暴露该属性；无可达错误，维持现实现。
- MR2-06：测试默认配置禁用 LLM，模型边界已有显式 override 测试；当前全套 Python gate 通过，无需修改测试。
- MR2-07：全标点名称会得到不精确建议分数，但所有建议固定人工审阅，Java 不自动合并；作为排序限制记录，不改本节点执行边界。
- MR2-08～MR2-10：无需修改，理由与 Pi 报告一致。
