# Pi 代码审查报告：v3-04-graph-domain-model / Attempt 2

- 日期：2026-09-28
- 审查阶段：v3-04-graph-domain-model
- 审查对象：INDEX@46856cd（基线：46856cd97f03c4dc050a35445de17d2829acd7b1）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-04 Neo4j Graph Domain Model — Milestone Review 报告（Attempt 2）

## 一、概述与总体结论

- 审查阶段：`v3-04-graph-domain-model`（Milestone，第 2/3 轮）
- 审查对象：INDEX@46856cd（基线 46856cd97f03c4dc050a35445de17d2829acd7b1）
- 审查模式：完全只读，未运行命令，未修改文件，未改变 Git 状态
- 结论：**通过（PASS）**，无阻塞性“必须修改”项；以下均为建议修改或无需修改
- 总体评价：
  - **节点 Scope 对齐**：只实现实体/关系/evidence 写入与查询、显式清理，未越界 V3-05 抽取、V3-06 消歧、V3-07 GraphRAG；`/wiki/graph` 未改动。
  - **上轮 R1（授权等级）已澄清并成立为不阻塞**：`ProjectService.requireAccess` 与 `getProject` 均调用私有 `requireOwnerOrAdmin`（`services/core-api/src/main/java/com/agentforge/core/project/application/ProjectService.java`），当前无普通成员模型；`v3-04-development-plan.md` 第 4 条已改为“只读入口要求现有项目访问权（ProjectAccess 当前同样为 owner/admin）”，与实现一致。
  - **上轮 R2（未捕获 `.single()`）已确认修复且被真实并发测试固定**：`relation()` 改为返回 `Optional`，`neighbors` 用 `flatMap(...stream())` 跳过并发消失的关系，`putRelation` 用 `orElseThrow(ResourceNotFoundException)`；新增 `concurrentCleanupNeverTurnsNeighborReadIntoServerError`（8 轮 × 3 读 + 1 并发清理，断言仅 200/404）。
  - **R3/R4/R5/R6 维持非阻塞或建议**：零重试 + 统一 503 属已声明有界失败策略；`StrictVersion` 对超 `long` 整数未纳入回归测试；空页+非空游标已文档化；读路径 N+1 为有界性能限制。
  - 权限强校验（Service 层 `requireAccess`/`getProject` + 来源归属/版本/原文比对）、CAS、稳定 ID、多来源 evidence、失败隔离均成立，测试证据充分。

---

## 二、详细发现清单（按严重度排序）

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | 低 | `docs/README.md` | 约 29 | 计划条目仍写“（Accepted，Start Gate 已确认，开发中）”，与 `v3-04-development-plan.md` 头部“Implemented”及路线图状态不一致 |
| S2 | 低 | `GraphService.java` | 96-100（`putRelation` 返回 `visible`） | 写路径返回 `visible(result, actor)`，极端并发（写入提交后、读取前来源被更新）时可能返回 `null`，HTTP 200 空 body，与契约声明“返回 relation”不符 |
| S3 | 低 | `Neo4jGraphStore.java` | 44-51（`transaction`/`Config`） | 锁争用/瞬态错误在 `withMaxTransactionRetryTime(0)` 下不重试，统一转 503，与真实依赖故障不可区分（已文档化，故非阻塞） |
| S4 | 低 | `GraphApiIntegrationTest.java` / `GraphDependencyIsolationTest.java` | 全文 | 关键分支未固定：`request.type` 与 `source.type` 不匹配分支、图关闭时**写**端点 503、超出 `long` 范围版本号、空页+非空 `nextAfter` |
| S5 | 低 | `GraphService.java` | 52-57、72-78、85-97 | 读路径 N+1 跨库往返：每个候选实体一次 Neo4j 事务 + 一次 PostgreSQL 查询；每 evidence 再查一次 |
| S6 | 低 | `infra/compose.prod.yaml` | neo4j 服务块（约 226-243） | 生产 compose 的 neo4j 未设置 `cap_drop: [ALL]` / `no-new-privileges`，与同文件 grafana 等服务的加固基线不一致 |

### 无需修改

| ID | 严重级别 | 文件 | 行号（约） | 说明 |
| --- | --- | --- | --- | --- |
| N1 | — | `ProjectService.java` / `GraphService.java` | — | R1 不成立：`requireAccess`=owner/admin，`core-api.md` 与计划已同步；图写/读/清理逐端点均重新校验 |
| N2 | — | `Neo4jGraphStore.java` | 121-134、86-92、142-152 | R2 已修复：所有查询先 `hasNext()`/`list()`，`relation()` 返回 `Optional`，无残留 `.single()` 未守卫 |
| N3 | — | `GraphModel.java` | 30-40、46-53 | ID 无碰撞、方向白名单 `allows` 与计划一致；`StrictVersion` 拒绝小数/字符串（有测试），超范围由 Jackson 抛 `InputCoercionException`（Codex 已用 JShell 验证） |
| N4 | — | `GraphService.java` | 27-31、101-135 | `clear` 先鉴权后校验 confirm；来源版本/原文比对与失效来源隐藏逻辑正确；`ProjectLock` 保留、仅删派生节点 |
| N5 | — | 文档与配置 | — | ADR-0032、graph-domain-model、core-api、local-stack 的 Implemented / GraphRAG Planned、Neo4j 默认关闭、无主机端口、独立密码边界与实现一致 |

---

## 三、逐个 Issue 展开

### S1（建议修改，低）计划状态标注不一致

**File & Line**
`docs/README.md` 约 29 行；对照 `docs/01-product/v3-04-development-plan.md:3`、`docs/01-product/v2-v3-node-roadmap.md` V3-04 状态。

**Evidence**
```text
docs/README.md:
- `v3-04-development-plan.md`：V3-04 图领域模型开发计划（Accepted，Start Gate 已确认，开发中）。

docs/01-product/v3-04-development-plan.md:
-- 状态：Planned；Start Gate 待用户确认
+- 状态：Implemented；本机验收已完成，Milestone Review 与 Close Gate 见变更记录
```

**Description**
同一节点在目录索引中仍标注“开发中”，在计划文档中被标注为 Implemented，路线图亦为 Implemented。属于文档一致性缺陷，不影响运行时行为。

**Suggested Fix**
统一为 Implemented（或在本轮 Close Gate 后统一收口）；若计划文档定义为“Implementing / 已完成待关闸”，则路线图与 README 同步同一措辞。

---

### S2（建议修改，低）写路径 `putRelation` 返回 `visible()`，极端并发下可能 200 空 body

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphService.java:96-100`（`putRelation` 末尾 `return visible(result, actor);`），`GraphService.java:85-97`（`visible`）。

**Evidence**
```java
var result = store.putRelation(new Relation(...), input.expectedVersion());
return visible(result, actor);   // visible 可能返回 null
...
private Relation visible(Relation relation, AuthenticatedActor actor) {
    try {
        requireEntity(relation.projectId(), relation.fromId(), actor);
        requireEntity(relation.projectId(), relation.toId(), actor);
    } catch (ResourceNotFoundException expired) { return null; }
    var evidence = relation.evidence().stream().filter(e -> {
        try {
            var document = source(relation.projectId(), actor, e.source());
            ...
        } catch (ResourceNotFoundException | ConflictException expired) { return false; }
    }).toList();
    return evidence.isEmpty() ? null : new Relation(...);
}
```

**Description**
`visible` 为只读展示过滤（这是 `neighbors`/`entities` 的正确语义），但被复用于写端点返回值。若写入事务提交后、`visible` 重校验前来源被并发更新/删除，`visible` 会返回 `null`；Spring 对 `@RestController` 返回 `null` 的处理为 HTTP 200 且空体，客户端无法按文档解析出 Relation。触发窗口窄，但属契约可观察偏差。

**Suggested Fix**
写路径不要把展示过滤结果当作写入回执：直接返回 `store.putRelation(...)` 的结果（写入时来源/端点已校验），或 `visible` 为 `null` 时改抛 `ConflictException`（409，来源已失效）：
```java
var result = store.putRelation(...);
var shown = visible(result, actor);
if (shown == null) throw new ConflictException("The graph source is stale.");
return shown;
```

---

### S3（建议修改，低）锁争用统一转 503 且零重试

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java:44-51`、`GraphService.java` 项目锁 `MERGE (p:GraphProjectLock ...)`。

**Evidence**
```java
Config.builder().withConnectionTimeout(2,TimeUnit.SECONDS)
    .withConnectionAcquisitionTimeout(3,TimeUnit.SECONDS)
    .withMaxTransactionRetryTime(0,TimeUnit.SECONDS).build()
...
} catch (Neo4jException | IllegalArgumentException e) {
    throw new ServiceUnavailableException("Graph service is unavailable.");
}
```

**Description**
`MERGE` 项目锁在唯一约束下高并发首次写入时可能产生瞬态异常；零重试后被统一映射为“图服务不可用”，与真实依赖故障不可区分。已有 6 线程实体/证据并发用例通过，未复现死锁，且文档已声明“锁争用/timeout 也 fail-closed 返回通用 503，调用者按稳定 ID 幂等重试”，故按既定策略视为非阻塞。

**Suggested Fix**
（可选）给出小的有界重试并保留总时限（`withMaxTransactionRetryTime(2, SECONDS)`，`MERGE` 幂等不会产生重复），或在文档中补充客户端对 503 的退避重试指引。不作为关闭门禁条件。

---

### S4（建议修改，低）关键分支回归缺口

**File & Line**
`services/core-api/src/test/java/com/agentforge/core/graph/GraphApiIntegrationTest.java`（`sourceCannotCrossProjectAndBusinessIdentityMustMatchSource`、`fractionalOrStringVersionsCannotBeCoercedToAnotherVersion`、`expiredAndDeletedEvidenceIsNeverReturnedAsUsableProvenance`）；`GraphDependencyIsolationTest.java`。

**Evidence / Description**
- `!request.type().name().equals(source.type().name())` 这一析取分支没有独立用例（现有用例只覆盖同一条件中的 `externalId` 不匹配分支）。
- 图关闭/不可用仅覆盖 `GET /entities` 的 503 与 store 级 `disabledAdapterFailsClosedWithoutConnecting`，未断言写端点（PUT/DELETE）503；虽共用同一 `driver()` seam。
- 超 `long` 范围版本（如 `9223372036854775808`）无测试固定；`StrictVersion` 对小数/字符串已覆盖。
- 文档明确“空页 + 必须按 nextAfter 继续”，但无用例断言 `items` 为空而 `nextAfter` 非空、且可继续翻页。

**Suggested Fix**
在 `GraphApiIntegrationTest` 增加：`request.type=WIKI` + `source.type=TASK`（合法 task）→ 400；`PUT /relations`、`DELETE ?confirm=true` 在图 URI 不可达时 → 503；`expectedVersion`/`source.version` 为 `9223372036854775808` → 400；失效来源实体且 `limit` 命中该页 → `items` 空且 `nextAfter` 非空。

---

### S5（建议修改，低）读路径 N+1 跨库往返

**File & Line**
`GraphService.java:52-57`（`entities` 逐实体 `current`）、`72-78`、`85-97`（`neighbors`/`visible` 逐关系两端点 + 逐 evidence）。

**Evidence**
```java
return new Page<>(page.items().stream().map(e -> current(e, actor)).filter(Objects::nonNull).toList(), page.nextAfter());
...
for (var e : relation.evidence()) {
    var document = source(relation.projectId(), actor, e.source());  // 每条证据一次 PostgreSQL 查询
    ...
}
```

**Description**
`current()` 每个候选实体一次 Neo4j 读事务 + 一次 PostgreSQL 查询；`visible()` 每条关系两次 `requireEntity`（各自 Neo4j 事务）+ 每条 evidence 一次 PostgreSQL 查询。`limit` 上限 100，最坏为数百次串行跨库往返。属有界性能限制，非正确性缺陷。

**Suggested Fix**
（后续优化）在 application 层按 `(sourceType, sourceId, version)` 去重预取，或为 `GraphStore` 增加批量实体读取；当前不阻塞交付。

---

### S6（建议修改，低）生产 compose 的 neo4j 加固缺项

**File & Line**
`infra/compose.prod.yaml` neo4j 服务块（约 226-243）。

**Evidence**
```yaml
  neo4j:
    image: neo4j:5.26-community
    profiles: ["graph"]
    ...
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/7687'"]
```

**Description**
同文件其他服务（如 grafana）使用 `cap_drop: [ALL]` 与 `security_opt: [no-new-privileges:true]`，neo4j 未设置。neo4j 无主机端口发布（正确），但生产加固基线不一致。healthcheck 仅探测 Bolt 端口，属文档已说明的“真正鉴权与 schema 由首次 Java 请求验证”，可接受。

**Suggested Fix**
为 neo4j 增加 `cap_drop: [ALL]` 与 `security_opt: - no-new-privileges:true`；如需更严格，可加 `read_only` 之外的资源限制。作为部署加固建议，不阻塞。

---

## 四、主开发 (Codex) 评估回填区

| Issue ID | 是否成立 | 事实判断 | 处理方式 | 修复文件 / 补测 | 备注 |
| --- | --- | --- | --- | --- | --- |
| S1 | 成立 | docs/README 索引仍写开发中 | 已改 Implemented | 仅文档 | 已验证链接 |
| S2 | 成立且具真实契约冲突 | 提交后来源更新导致 200 空 body | 改返回 409 | 真实 HTTP/Neo4j seam red→green，最终 clean verify | Pi Attempt 3 |
| S3 | 非阻塞 | 有界零重试 503 已明确声明 | 保留 | 既有并发测试通过 | — |
| S4 | 建议 | 缺口不影响已覆盖主契约 | 记录 | 无修改 | — |
| S5 | 建议 | 有界 N+1 限制 | 记录 | 无批量接口扩展 | — |
| S6 | 建议 | Neo4j 仅可选内部网络且尚未生产部署，容器内置权限未宣称加固等级 | 留部署硬化评估 | 无修改 | — |
| N1 |  |  |  |  | R1 已澄清 |
| N2 |  |  |  |  | R2 已修复并测试 |
| N3 |  |  |  |  | R4 维持建议 |
| N4 |  |  |  |  | clear/来源校验 |
| N5 |  |  |  |  | 文档与配置一致 |

---

## 五、上一轮 Finding 复核

| 上轮 ID | 本轮结论 | 复核依据 |
| --- | --- | --- |
| R1（授权等级） | **不成立 / 已澄清** | `ProjectService.requireAccess`、`getProject` 均走 `requireOwnerOrAdmin`；开发计划第 4 条与 `core-api.md` 已统一为 owner/admin |
| R2（`.single()` 未捕获 → 500） | **已修复** | `relation()` 返回 `Optional`；`neighbors` 跳过并发消失关系；新增真实 HTTP 并发清理测试 |
| R3（零重试统一 503） | **非阻塞，保留** | 无复现死锁；文档声明锁争用/超时 fail-closed 503 + 稳定 ID 幂等重试 |
| R4（超 long 版本） | **建议保留** | 小数/字符串已测；超范围行为未纳入回归测试 |
| R5（空页 + 非空游标） | **建议保留** | 契约已声明，测试未固定 |
| R6（读路径 N+1） | **建议保留** | 有界性能限制 |
| R7（负向测试缺口） | **部分收口，余项见 S4** | 已补并发清理；类型不匹配分支/写端点 503/超范围版本仍缺 |

---

## 六、审查边界说明

- 本轮为 Milestone Review：结合显式提供的节点定义、Roadmap、开发计划、变更记录与测试证据核对方向、边界与下一节点隐患；未发现 V3-05 抽取、V3-06 消歧、V3-07 GraphRAG 越界。
- 全程未运行命令、未修改文件、未改变 Git 状态；所有结论仅基于本次 diff、文件清单与显式上下文。
- `docs/07-changes/2026-09-27-v3-04-graph-domain-model.md` 的机器记录仅作为审查证据，未改写为本次执行结果。
- 本轮一次性列出全部可确认问题（6 项建议，0 项必须修改）；无阻塞项，故 `REVIEW_RESULT: PASS`。

Codex 处理状态：RESOLVED（2026-09-28；真实问题已修复，最终 Attempt 3 PASS；原 REVIEW_RESULT 保留供追溯）。
