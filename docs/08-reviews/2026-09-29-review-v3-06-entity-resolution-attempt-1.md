# Pi 代码审查报告：v3-06-entity-resolution / Attempt 1

- 日期：2026-09-29
- 审查阶段：v3-06-entity-resolution
- 审查对象：WORKTREE@2a0281d（基线：2a0281df0e2484740b9bbf9b96e4f48586df0e71）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- 处理状态：RESOLVED（阻塞项修复后 Attempt 2 PASS）
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-06 Entity Resolution — Milestone Review (Round 1/3)

## 一、概述与总体结论

本次变更实现了 V3-06 的核心闭环：Java 侧候选发现（`GraphResolutionService`）、人工确认 / 撤销 / 规范实体管理（`GraphResolutionDecisionService`、`GraphResolutionController`）、V13 持久化（canonical / member / event）、Python 内部建议端点（`entity_resolution.py` + `api.py` 新路由）以及两侧契约与集成测试。总体方向与 Roadmap 中 V3-06 Scope 一致：只有人工确认才落库、原始 Neo4j 图不被物理合并、覆盖层可撤销、LLM 建议不构成授权。权限边界（`graph.entity` → `projects.requireAccess`）与项目隔离、CAS、来源版本重验均有实现和集成测试证据。

但发现 1 个具有明确证据的数据生命周期缺陷：规范实体的 `anchor_source_version` 是只写字段，锚点来源版本一旦变化，规范实体将永久不可读、不可改、无法重建，导致该锚点实体此后无法再被人工消歧。该问题落在本节点明确要求的“来源更新/删除后的生命周期”范围内，需要修复后才能关闭节点。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| MR-01 | 必须修改（高） | `services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionDecisionService.java` | `confirm` 的 canonical 校验分支（约 L72–86） | `anchor_source_version` 写入后无任何刷新路径，锚点来源版本变化后规范实体永久 404/409 |
| MR-02 | 建议修改（中） | `GraphResolutionDecisionService.java` | `confirm` 入口（约 L46–56） | 未校验自映射（`entityId == canonicalEntityId`）与循环合并（A→B、B→A） |
| MR-03 | 建议修改（低） | `services/core-api/src/test/java/com/agentforge/core/graph/GraphResolutionIntegrationTest.java` | `sourceChangeHidesExistingMemberAlias` | 断言接受 200 或 404，弱化了来源变更后失效路径的回归保护 |
| MR-04 | 建议修改（中） | `GraphResolutionIntegrationTest.java`、`services/agent-service/tests/test_entity_resolution.py` | 多个测试 | 关键否定/边界分支无覆盖：aliases>10、未知 metadata key、confidence 越界、canonical 跨项目 GET/PUT、revert 不存在成员、>20 候选截断 |
| MR-05 | 建议修改（低） | `GraphResolutionService.java` | `suggest` 扫描循环（约 L28–50） | 最多 500 个实体，每个候选重复 `graph.entity` + `decisions.decision`（各含 `requireAccess` 与 DB 查询），N+1 明显 |
| MR-06 | 建议修改（低） | `README.md`、`docs/03-features/entity-resolution.md`、`docs/README.md`、`docs/02-architecture/*` | 状态标注 | 同一提交内 V3-06 状态标签不一致（Implemented vs In Progress/开发中） |
| MR-07 | 无需修改 | `services/agent-service/src/agentforge_agent/entity_resolution.py` | `_normalize` | 与 Java `normalize` 规则不同，仅影响提示排序，无契约风险 |
| MR-08 | 无需修改 | `GraphResolutionDecisionService.java` | `decision`（约 L200） | 未显式判空 `entity.source()`，但可达路径上可消歧实体 source 必非空 |

## 三、逐个 Issue 展开

### MR-01（必须修改，高）规范实体锚点来源版本不可刷新，来源更新后永久失效

- File & Line：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphResolutionDecisionService.java`，`confirm` 中 `INSERT ... ON CONFLICT DO NOTHING` 与其后的 canonical 校验分支（新文件约 L72–86）；`canonicalView`（约 L118–127）。
- Evidence：

```java
jdbc.update("""
    INSERT INTO graph_canonical_entity
        (project_id,id,entity_type,canonical_name,metadata,anchor_source_type,anchor_source_id,anchor_source_version)
    VALUES (?,?,?,?,?::jsonb,?,?,?) ON CONFLICT DO NOTHING
    """,projectId,anchor.id(),anchor.type().name(),name,metadataJson,
    anchor.source().type().name(),anchor.source().id(),anchor.source().version());
var canonical=canonical(projectId,anchor.id()).orElseThrow();
if(canonical.type()!=anchor.type() || !canonical.name().equals(name)
    || !canonical.metadata().equals(metadata)
    || canonical.sourceType()!=anchor.source().type()
    || !canonical.sourceId().equals(anchor.source().id())
    || canonical.sourceVersion()!=anchor.source().version())
    throw new ConflictException("The canonical entity changed.");
```

`graph_canonical_entity` 的唯一 UPDATE 位于 `updateCanonical`，只写 `canonical_name` / `metadata` / `version`，不写 `anchor_source_version`（V13 中该列也没有任何触发器或后续迁移刷新）。

- Description：V3-05 的图来源同步在 Wiki/Task 更新后会重新投影，`source.version` 递增，但实体稳定 ID 不变。此时：
  1. `canonicalView` 比较 `stored.sourceVersion()!=anchor.source().version()` → 抛 `ResourceNotFoundException`（404）；
  2. `confirm` 的 `INSERT ... ON CONFLICT DO NOTHING` 因主键 `(project_id, id=anchorId)` 已存在而不生效，随后校验 `canonical.sourceVersion() != anchor.source().version()` → 抛 `ConflictException`（409）；
  3. 而 `updateCanonical` 依赖 `canonicalView`，必然先 404，因此没有任何路径可以刷新 `anchor_source_version`。

  结果是：某个 Wiki/Task 被编辑一次后，该锚点对应的规范实体永久不可读、不可改、不可重建（因为规范 ID 即锚点实体 ID，无法为同一锚点再插一行）。成员映射被 `decision()` 正确隐藏为 UNMAPPED，但用户对该真实实体此后再也无法重新确认消歧。这与本节点“来源更新/删除后的生命周期 + 可撤销覆盖层”的目标直接冲突，且是本节点显式验收项（“来源更新后失效”）。
- Suggested Fix：在 `confirm` 命中已存在 canonical 时，允许在同一人工确认事务内按当前来源版本刷新锚点版本（并递增 `version`、追加审计），即把 `ON CONFLICT DO NOTHING` 改为 CAS 语义的可刷新写入；或增加显式的 canonical 重验/失效清理路径。示意：

```java
// 位置：confirm 中 INSERT 之后、name/metadata 比较之前
var existing = canonical(projectId, anchor.id());
if (existing.isPresent()) {
    var c = existing.get();
    boolean sameIdentity = c.type()==anchor.type()
        && c.sourceType()==anchor.source().type()
        && c.sourceId().equals(anchor.source().id());
    if (!sameIdentity) throw new ConflictException("The canonical entity changed.");
    if (c.sourceVersion()!=anchor.source().version()) {
        int refreshed = jdbc.update("""
            UPDATE graph_canonical_entity
               SET anchor_source_version=?, version=version+1, updated_at=now()
             WHERE project_id=? AND id=? AND anchor_source_version=? AND version=?
            """, anchor.source().version(), projectId, anchor.id(), c.sourceVersion(), c.version());
        if (refreshed!=1) throw new ConflictException("The canonical entity changed.");
    }
}
// 之后按刷新后的 canonical 再比较 name/metadata（名称/元数据仍由首次确认决定，不变更）
```

同时补充集成测试：确认成功 → `wiki.update(...)` → 再次 `PUT /decisions/{entityId}` 与 `GET /canonicals/{id}` 能基于新来源版本成功（而非永久 404/409）。

### MR-02（建议修改，中）缺少自映射与循环合并校验

- File & Line：`GraphResolutionDecisionService.confirm`，成员/锚点读取后（约 L46–56）。
- Evidence：`graph.entity(projectId,entityId,actor)` 与 `graph.entity(projectId,request.canonicalEntityId(),actor)` 之间没有任何 `entityId.equals(canonicalEntityId)` 判断；也没有判断 `anchor` 是否本身已是某个规范化实体成员（`graph_resolution_member` 中 status=CONFIRMED 的行）。
- Description：可以构造 `entityId == canonicalEntityId` 的自映射，也可构造 A→B、B→A 的循环：`decision(A)` 返回 canonical B，`decision(B)` 返回 canonical A（两者都是合法“当前有效同类型实体”）。当前无消费者，不会抛异常，但会写入不可达/自洽性受损的规范映射，V3-07 消费时需额外处理。
- Suggested Fix：在 `confirm` 增加 `if(entityId.equals(request.canonicalEntityId())) throw invalid();`；并检查 anchor 是否为已确认成员（`member(projectId, anchor.id())` 为 CONFIRMED），若是则拒绝或递归解析到最终规范化 ID，避免链式/循环。

### MR-03（建议修改，低）来源变更隐藏测试断言过弱

- File & Line：`services/core-api/src/test/java/com/agentforge/core/graph/GraphResolutionIntegrationTest.java`，`sourceChangeHidesExistingMemberAlias`。
- Evidence：

```java
var response=mvc.perform(get(f.path()+"/resolution/decisions/"+member).with(f.token())).andReturn();
assertThat(response.getResponse().getStatus()).isIn(200,404);
if(response.getResponse().getStatus()==200)
    assertThat(json.readTree(...).path("status").asText()).isEqualTo("UNMAPPED");
```

- Description：断言允许 200 或 404 两种结果，且仅在 200 时校验 UNMAPPED；无法区分“来源更新后成员被隐藏”和“来源同步尚未完成/其他 404 原因”。若实现某天在来源已变更时仍返回 CONFIRMED，只要状态码是 404 该测试也会通过。
- Suggested Fix：用轮询等待来源同步完成后，固定断言 200 + `status == "UNMAPPED"`（或明确断言 404 的具体错误语义），并断言 `version` 保持不变。

### MR-04（建议修改，中）关键否定/边界分支缺少测试

- File & Line：`GraphResolutionIntegrationTest.java`、`services/agent-service/tests/test_entity_resolution.py`。
- Description：当前测试覆盖了主路径、来源过期、CAS、跨项目 anchor、撤销、false split，但以下实现中的显式校验分支无自动化覆盖：`aliases.size()>10`、重复 alias（大小写不敏感）、未知 metadata key、`confidence` 越界 / 非有限值、`revert` 对不存在成员返回 404、`GET/PUT /canonicals` 跨项目 403/404、候选 >20 的 `truncated` 截断。这些分支一旦回归不会被发现。
- Suggested Fix：按服务端校验逐条补充参数化/边界 MockMvc 测试；Python 侧补 `confidence` 非数值/越界/NaN 与候选为空的 abstain 用例。

### MR-05（建议修改，低）候选扫描存在明显 N+1

- File & Line：`GraphResolutionService.suggest` 扫描循环。
- Evidence：`store.entities(projectId, after, 100)` 最多 5 页共 500 个实体，每个实体都调用 `graph.entity(...)`（内部 `requireAccess` + 读取）与 `decisions.decision(...)`（再次 `graph.entity` + 一次 JDBC 查询）。
- Description：最坏情况产生约 1500 次应用层权限校验与数据库往返；候选上限 500 是为安全的合理约束，但实现开销偏大，在规模较大项目上会拖慢建议接口。
- Suggested Fix：在循环外做一次权限校验，批量/单次读取本项目同类型有效实体及成员的规范化状态（一次查询带 `entity_type` 与状态过滤），减少重复 `requireAccess` 与逐实体查询。

### MR-06（建议修改，低）V3-06 状态标签在同一提交内不一致

- File & Line：`README.md`（“✅ V3-06 … 已实现”）、`docs/03-features/entity-resolution.md`（“状态：Implemented”）、`docs/README.md`（“（In Progress）”）、`docs/02-architecture/backend-architecture.md`（“（开发中）”）、`docs/02-architecture/data-architecture.md`（“（开发中）”）、`docs/07-changes/2026-09-29-v3-06-entity-resolution.md`（“状态：In Progress”）。
- Description：同一提交内公开状态从 In Progress 到 ✅ Implemented 并存；Roadmap 的 Current Node 也标注 “Milestone Review 待完成”。按协议第 9 节，公开状态需真实一致，README 的 ✅ 通常应在 Milestone Review 通过后才置位。
- Suggested Fix：在 Review 通过前统一为 In Progress/🚧，或在审查通过后同批把变更记录与架构文档一并更新为 Implemented，避免同一提交内自相矛盾。

### MR-07（无需修改）Python 与 Java 规范化规则差异

- File & Line：`services/agent-service/src/agentforge_agent/entity_resolution.py` 的 `_normalize`（`\w+`，含下划线）与 `GraphResolutionService.normalize`（`\p{L}\p{N}`）。
- Description：两侧规则不完全一致，但二者都只用于候选排序与提示，Java 不信任返回分数，且确认必须人工。无契约或正确性影响。

### MR-08（无需修改）`decision()` 未显式判空 `entity.source()`

- File & Line：`GraphResolutionDecisionService.decision`（约 L200）。
- Description：`member.sourceType()!=entity.source().type()` 未先判空，而 `confirm` 中明确检查了 `member.source()==null` / `anchor.source()==null`。但可达路径上进入 `decision` 的实体类型均为 SERVICE/API/ISSUE，`GraphService.put` 对非 PROJECT 类型强制要求 source，且存在 member 行则必然经过 `confirm` 的 source 非空校验，因此当前不构成缺陷。可在后续重构中统一加防御性判空。

## 四、主开发（Codex）评估回填区

| ID | 是否成立 | 原因判断 | 最小修复 | 补测范围 | 备注 |
| --- | --- | --- | --- | --- | --- |
| MR-01 |   |   |   |   |   |
| MR-02 |   |   |   |   |   |
| MR-03 |   |   |   |   |   |
| MR-04 |   |   |   |   |   |
| MR-05 |   |   |   |   |   |
| MR-06 |   |   |   |   |   |
| MR-07 |   |   |   |   |   |
| MR-08 |   |   |   |   |   |

## 五、审查结论

- 结论：需修复后交付（NEEDS_FIX）。
- 阻塞项：MR-01（规范实体锚点来源版本不可刷新，来源更新后永久失效）。该项与本节点“来源更新/删除生命周期 + 可撤销覆盖层”的验收目标直接冲突，需先修复并补测。
- 非阻塞项：MR-02～MR-06 为建议，可由 Codex 判断后择机处理；MR-07、MR-08 无需修改。
- 已确认的正面证据：只有人工确认落库、原始图与 evidence 不重接线、跨项目 anchor 返回 404、无权重项目 403、来源版本 CAS 409、重复确认幂等、撤销后 `version` 递增且不能以旧 `expectedVersion=0` 重建、Python 候选白名单与模型故障 abstain、Java 只接受候选集合内 ID。

## 六、Codex 事实核对（Attempt 1 后）

| Finding | 判断 | 处理与证据 |
| --- | --- | --- |
| MR-01 | 成立，阻塞；锚点来源更新后无刷新路径。 | 在确认事务中 CAS 刷新锚点来源版本并记录 `REFRESH_SOURCE`；成员保存确认时锚点版本，未复核的旧成员仍隐藏。`refreshedAnchorRequiresEachMemberToBeReconfirmed` 红 409 → 绿 200/UNMAPPED。 |
| MR-02 | 成立，数据一致性风险。 | 禁止自映射、已映射成员作锚点、已有规范锚点变成员；`canonicalAnchorsCannotBecomeMembersOrFormCycles` 红 200 → 绿 400/409。 |
| MR-03 | 成立，测试信号过宽。 | 等待新来源图版本后固定断言 200 + UNMAPPED + 原成员版本，针对性测试通过。 |
| MR-04 | 建议，未提供现存错误信号。 | 已有边界校验与当前范围相称的 Graph/Python 测试；不为未证实缺陷扩展参数化测试。 |
| MR-05 | 建议，未提供负载指标或超时复现。 | 保留 500 扫描/20 候选上限并在功能文档记录限制；本节点不改批量存储接口。 |
| MR-06 | 成立，审核中的状态标签不一致。 | 审核修复期间统一为 In Progress；通过后同批统一为 Implemented。 |
| MR-07 | 不构成缺陷。 | 两端规范化均只用于建议排序，人工确认与 Java 校验决定写入。 |
| MR-08 | 不构成可达缺陷。 | Service/API/Issue 图实体必须带来源；GraphService 的来源校验先于 decision()。 |
