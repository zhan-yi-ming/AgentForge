# Pi 代码审查报告：v3-04-graph-domain-model / Attempt 3

- 日期：2026-09-28
- 审查阶段：v3-04-graph-domain-model
- 审查对象：INDEX@46856cd（基线：46856cd97f03c4dc050a35445de17d2829acd7b1）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：v3-04-graph-domain-model / Attempt 3

- 日期：2026-09-28
- 审查阶段：v3-04-graph-domain-model
- 审查对象：INDEX@46856cd（基线：46856cd97f03c4dc050a35445de17d2829acd7b1）
- 审查模式：Milestone Review，第 3/3 轮
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，完全只读）
- Pi 进程超时上限：900 秒

---

## 一、概述与总体结论

- 审查范围：本次 diff 的 30 个文件（graph domain/application/infrastructure/api、配置、Compose、文档、测试）。
- 结论：**通过（PASS）**，无“必须修改”项；以下均为建议修改或无需修改。
- 总体评价：
  - **节点 Scope 对齐**：只实现实体/关系/evidence 的写入与查询、显式清理，未越界 V3-05 抽取、V3-06 消歧、V3-07 GraphRAG；`/wiki/graph` 未改动。
  - **Attempt 2 的 S1 已修复**：`docs/README.md` 计划条目已改为“Implemented；GraphRAG 仍 Planned”，与 `v3-04-development-plan.md`、`v2-v3-node-roadmap.md` 一致。
  - **Attempt 2 的 S2 已修复且被可控测试固定**：`GraphService.putRelation` 现在对写入后重验为 `null` 的情况抛 `ConflictException`（409），不再返回 200 空 body；新增 `sourceChangingAfterGraphCommitCannotReturnEmptySuccess` 通过 `GraphStore` 公共 seam 的 spy 在真实 Neo4j 提交后调用真实 `WikiPageService.update`，精确复现跨库来源竞争并断言 409。`graph-domain-model.md` 与 `core-api.md` 已同步该语义。
  - **权限/租户隔离成立**：`ProjectService.requireAccess` 与 `getProject` 均走 `requireOwnerOrAdmin`；图读/写/清理逐端点重新校验；来源归属、版本、原文逐字比对在 Service 层强校验；写路径还二次核对请求端点在库中真实存在与类型。
  - **并发/幂等成立**：项目锁 + 唯一约束 + CAS 版本语义；`relation()` 返回 `Optional`，`neighbors` 跳过并发消失关系；`concurrentCleanupNeverTurnsNeighborReadIntoServerError`（8 轮 × 3 读 + 并发清理，仅 200/404）与两个 6 线程并发用例通过。
  - **失败隔离成立**：Neo4j 默认关闭、无主机端口、独立密码边界；关闭/不可用统一 503，且不阻断 Chat/Wiki/Task 启动与健康。

---

## 二、详细发现清单（按严重度排序）

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | 低 | `Neo4jGraphStore.java` | 44-51（`driver()`/`transaction()`） | `withMaxTransactionRetryTime(0)` 下锁争用/瞬态错误零重试并统一转 503，与真实依赖故障不可区分（已文档化为有界失败策略，故非阻塞） |
| S2 | 低 | `GraphApiIntegrationTest.java` / `GraphDependencyIsolationTest.java` | 全文 | 关键分支仍缺回归：`request.type` 与 `source.type` 不匹配析取、图关闭时**写**端点（PUT/DELETE）503、超 `long` 范围版本号、空页 + 非空 `nextAfter` |
| S3 | 低 | `GraphService.java` | 52-57、72-78、85-100 | 读路径 N+1 跨库往返：每个候选实体一次 Neo4j 事务 + 一次 PostgreSQL 查询；每条关系两端点 + 每条 evidence 再各查一次 |
| S4 | 低 | `infra/compose.prod.yaml` | neo4j 服务块 | 生产 compose 的 neo4j 未设置 `cap_drop: [ALL]` / `no-new-privileges`，与同文件 grafana 等的加固基线不一致 |
| S5 | 低 | `.env.example` / `.env.production.example` / `application.yml` | 图密码配置 | `AGENTFORGE_GRAPH_ENABLED=true` 但密码为空时无启动期显式拒绝，仅运行期以 503 fail-closed；行为可接受但错误信息不可区分 |

### 无需修改

| ID | 严重级别 | 文件 | 行号（约） | 说明 |
| --- | --- | --- | --- | --- |
| N1 | — | `docs/README.md` | 约 29 | Attempt 2 S1 已修复：统一为 Implemented；GraphRAG 仍 Planned |
| N2 | — | `GraphService.java` | 96-102 | Attempt 2 S2 已修复：写入后 `visible` 为 `null` 抛 409，不再 200 空 body；有可控并发/来源变更测试固定 |
| N3 | — | `Neo4jGraphStore.java` | 79-92、124-152 | 无残留未守卫 `.single()`：所有读取先 `hasNext()`/`list()`；`relation()` 返回 `Optional` |
| N4 | — | `GraphModel.java` | 30-53 | ID 稳定无碰撞；方向白名单 `allows` 与计划/契约一致；`StrictVersion` 拒绝小数/字符串，超范围由 Jackson 抛 `InputCoercionException`（Codex 已用 JShell 验证） |
| N5 | — | `GraphService.java` | 27-135 | `clear` 先鉴权后校验 confirm；来源版本/原文比对与失效来源隐藏逻辑正确；`ProjectLock` 保留、仅删派生节点 |
| N6 | — | 文档与配置 | — | ADR-0032、graph-domain-model、core-api、local-stack 的 Implemented / GraphRAG Planned、Neo4j 默认关闭、无主机端口、独立密码边界与实现一致 |

---

## 三、逐个 Issue 展开

### S1（建议修改，低）锁争用统一转 503 且零重试

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java:44-51`，项目锁 `MERGE (p:GraphProjectLock {id:$project})`。

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
唯一约束下的项目锁在高并发首次写入/锁升级时可能产生瞬态异常；零重试后被统一映射为“图服务不可用”，与真实依赖故障不可区分。已有 6 线程实体/证据并发用例通过，未复现死锁，且 `graph-domain-model.md` 已声明“锁争用/timeout 也 fail-closed 返回通用 503，调用者按稳定 ID 幂等重试”，故按既定策略视为非阻塞。

**Suggested Fix**
（可选）给出小的有界重试并保留总时限（`withMaxTransactionRetryTime(2, SECONDS)`；`MERGE` 幂等不会产生重复节点），或在文档补充客户端对 503 的退避重试指引。不作为关闭门禁条件。

---

### S2（建议修改，低）关键分支回归缺口

**File & Line**
`services/core-api/src/test/java/com/agentforge/core/graph/GraphApiIntegrationTest.java`、`GraphDependencyIsolationTest.java`。

**Evidence / Description**
- `if (!request.type().name().equals(source.type().name()) || !external.equals(source.id().toString()))` 中“类型不匹配”析取分支无独立用例（现有用例只覆盖 `externalId` 不匹配分支）。
- 图关闭/不可用只在 `GraphDependencyIsolationTest.unavailableGraphReturnsGeneric503...` 覆盖 `GET /entities` 的 503，未断言写端点（`PUT /relations`、`DELETE ?confirm=true`）503；虽共用同一 `driver()` seam。
- 超 `long` 范围版本（如 `9223372036854775808`）无测试固定；`StrictVersion` 对小数/字符串已覆盖，但超范围行为未纳入回归。
- 文档明确“空页 + 必须按 nextAfter 继续”，但无用例断言 `items` 为空而 `nextAfter` 非空、且可继续翻页。

**Suggested Fix**
在 `GraphApiIntegrationTest` 增加：`request.type=WIKI` + `source.type=TASK`（合法 task）→ 400；`PUT /relations`、`DELETE ?confirm=true` 在图 URI 不可达时 → 503；`expectedVersion`/`source.version` 为 `9223372036854775808` → 400；失效来源实体且 `limit` 命中该页 → `items` 空且 `nextAfter` 非空。

---

### S3（建议修改，低）读路径 N+1 跨库往返

**File & Line**
`GraphService.java:52-57`（`entities` 逐实体 `current`）、`72-78`、`85-100`（`neighbors`/`visible` 逐关系两端点 + 逐 evidence）。

**Evidence**
```java
return new Page<>(page.items().stream().map(e -> current(e, actor)).filter(Objects::nonNull).toList(), page.nextAfter());
...
for (var e : relation.evidence()) {
    var document = source(relation.projectId(), actor, e.source()); // 每条证据一次 PostgreSQL 查询
    ...
}
```

**Description**
`current()` 每个候选实体一次 Neo4j 读事务 + 一次 PostgreSQL 查询；`visible()` 每条关系两次 `requireEntity`（各自 Neo4j 事务）+ 每条 evidence 一次 PostgreSQL 查询。`limit` 上限 100，最坏为数百次串行跨库往返。属有界性能限制，非正确性缺陷。

**Suggested Fix**
（后续优化）在 application 层按 `(sourceType, sourceId, version)` 去重预取，或为 `GraphStore` 增加批量实体读取；当前不阻塞交付。

---

### S4（建议修改，低）生产 compose 的 neo4j 加固缺项

**File & Line**
`infra/compose.prod.yaml` neo4j 服务块。

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
同文件 grafana 等服务使用 `cap_drop: [ALL]` 与 `security_opt: [no-new-privileges:true]`，neo4j 未设置。neo4j 无主机端口发布（正确），但生产加固基线不一致。healthcheck 仅探测 Bolt 端口，属文档已说明的“真正鉴权与 schema 由首次 Java 请求验证”。

**Suggested Fix**
为 neo4j 增加 `cap_drop: [ALL]` 与 `security_opt: - no-new-privileges:true`；作为部署加固建议，不阻塞。

---

### S5（建议修改，低）图启用但密码为空时无启动期显式拒绝

**File & Line**
`infra/compose.yaml` / `infra/compose.prod.yaml`（`NEO4J_AUTH: neo4j/${AGENTFORGE_GRAPH_PASSWORD:-}`）、`services/core-api/src/main/resources/application.yml`（`agentforge.graph.password: ${AGENTFORGE_GRAPH_PASSWORD:}`）。

**Evidence**
```yaml
NEO4J_AUTH: neo4j/${AGENTFORGE_GRAPH_PASSWORD:-}
```
```java
@Value("${agentforge.graph.password:}") String password
```

**Description**
`AGENTFORGE_GRAPH_ENABLED=true` 且密码为空时，实现不会在启动期显式拒绝，而是运行期连接/鉴权失败并由 `transaction()` 统一转 503。语义 fail-closed 正确，但错误信息与真实故障不可区分，且 Neo4j 空密码容器的初始化行为依赖镜像。属配置健壮性建议。

**Suggested Fix**
在 `Neo4jGraphStore.driver()` 首次初始化时，若 `enabled && password.isBlank()` 直接抛带明确信息的配置异常（启动/首次请求即失败），或在 `local-stack.md` 补充“图启用必须提供非空独立强密码”。

---

## 四、主开发 (Codex) 评估回填区

| Issue ID | 是否成立 | 事实判断 | 处理方式 | 修复文件 / 补测 | 备注 |
| --- | --- | --- | --- | --- | --- |
| S1 | 非阻塞 | 有界零重试 503 已明确声明 | 保留 | 既有并发测试通过 | — |
| S2 | 建议 | 缺口不影响已覆盖主契约 | 记录 | 无修改 | 与 Attempt 1/2 一致 |
| S3 | 建议 | 有界 N+1 限制 | 记录 | 无批量接口扩展 | — |
| S4 | 建议 | 可选部署未宣称加固等级 | 留部署硬化评估 | 无修改 | — |
| S5 | 建议 | 空密码 fail-closed 已成立 | 记录或补文档 | 可选 | — |
| N1 | 已修复 | docs/README 索引统一 Implemented | 已验证 | 仅文档 | Attempt 2 S1 |
| N2 | 已修复 | 写入后重验为 null → 409 | 已实现 + 可控测试 | GraphService + 新测试 | Attempt 2 S2 |
| N3 | 无需修改 | 无未守卫 `.single()` | — | — | R2 维持 |
| N4 | 无需修改 | ID/方向/StrictVersion 正确 | — | — | — |
| N5/N6 | 无需修改 | clear/来源校验、文档配置一致 | — | — | — |

---

## 五、上一轮 Finding 复核

| 上轮 ID | 本轮结论 | 复核依据 |
| --- | --- | --- |
| S1（docs/README 状态不一致） | **已修复** | `docs/README.md` 改为“Implemented；GraphRAG 仍 Planned” |
| S2（`putRelation` 返回 `visible` 可能 200 空 body） | **已修复** | `putRelation` 现为 `visible` 为 `null` 时抛 `ConflictException`（409）；`sourceChangingAfterGraphCommitCannotReturnEmptySuccess` 以真实 Neo4j + 真实 `WikiPageService.update` 复现并断言 409 |
| S3（零重试统一 503） | **非阻塞，保留** | 无复现死锁；文档声明锁争用/超时 fail-closed 503 + 稳定 ID 幂等重试 |
| S4（回归缺口） | **建议保留** | 类型不匹配析取、写端点 503、超范围版本、空页+非空游标仍缺 |
| S5（读路径 N+1） | **建议保留** | 有界性能限制 |
| S6（prod compose neo4j 加固） | **建议保留** | 见本轮 S4 |

---

## 六、审查边界说明

- 本轮为 Milestone Review：已结合显式提供的节点定义、Roadmap、开发计划、变更记录与测试证据核对方向、边界与下一节点隐患；未发现 V3-05 抽取、V3-06 消歧、V3-07 GraphRAG 越界，`/wiki/graph` 未改动。
- 全程未运行命令、未修改文件、未改变 Git 状态；所有结论仅基于本次 diff、文件清单与显式上下文。
- `docs/07-changes/2026-09-27-v3-04-graph-domain-model.md` 的机器记录仅作审查证据，未改写为本次执行结果。
- 本轮一次性列出全部可确认问题（5 项建议，0 项必须修改）；无阻塞项，故 `REVIEW_RESULT: PASS`。

Codex 处理状态：RESOLVED（当前机器验证、逐项裁定与最终门禁已完成；无阻塞项）。
