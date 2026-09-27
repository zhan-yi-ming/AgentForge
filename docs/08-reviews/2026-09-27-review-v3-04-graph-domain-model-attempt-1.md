# Pi 代码审查报告：v3-04-graph-domain-model / Attempt 1

- 日期：2026-09-27
- 审查阶段：v3-04-graph-domain-model
- 审查对象：INDEX@46856cd（基线：46856cd97f03c4dc050a35445de17d2829acd7b1）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-04 Neo4j Graph Domain Model — Milestone Review 报告

## 一、概述与总体结论

- 审查阶段：`v3-04-graph-domain-model`（Milestone，第 1/3 轮）
- 审查范围：本次 diff 的 28 个文件（graph domain/application/infrastructure/api、配置、Compose、文档、测试）
- 结论：**需修复后交付（NEEDS_FIX）**
- 总体评价：节点 Scope 基本对齐路线图——只做实体/关系/evidence 的写入与查询、显式清理，未越界实现 V3-05 抽取、V3-06 消歧、V3-07 GraphRAG；PostgreSQL 事实所有权、Java 授权、来源版本与原文校验、失败隔离（图关闭/不可用 503）均有实现与测试证据；稳定 ID、CAS 语义、多来源 evidence、跨项目隔离、证据重验证等核心设计成立（见“无需修改”）。
- 但存在 1 项授权等级与文档/计划不一致的权限问题，以及 1 项明确未捕获异常路径和 1 项并发失败语义问题，按门禁规则进入“必须修改”。

---

## 二、详细发现清单（按严重度排序）

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| R1 | 高 | `services/core-api/src/main/java/com/agentforge/core/graph/application/GraphService.java` | 27-31、32-51、58-71 | 写入/清理端点与只读端点使用同一 `projects.requireAccess`，无法满足开发计划“写入仅 owner/admin、只读仅项目访问权”的两级要求；无普通成员写入负向测试 |
| R2 | 中 | `services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java` | 121-134 | `relation()` 对 `RETURN r` 直接 `.single()`，无 `hasNext()`；并发 `clear()` 与读事务交错时抛 `NoSuchRecordException`/`NoSuchElementException`，未被 `catch (Neo4jException | IllegalArgumentException)` 覆盖，返回 500 而非 404/空页 |
| R3 | 中 | `services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java` | 29-51 | `withMaxTransactionRetryTime(0)` + 所有 `Neo4jException` 一律转 503：并发争用（项目锁 MERGE、死锁/瞬态错误）会被表现成“图服务不可用”，与“失败隔离”语义混同，且无任何重试/可区分错误 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| R4 | 中低 | `services/core-api/src/main/java/com/agentforge/core/graph/domain/GraphModel.java` | 46-53 | `StrictVersion` 只校验 token 类型，未拒绝超出 `long` 范围的整数；文档声称“禁止截断为另一版本”，无对应测试 |
| R5 | 低 | `GraphService.java` / `Neo4jGraphStore.java` | 52-57、79-83、121-127 | `nextAfter` 取自未过滤扫描结果，隐藏实体时可能返回空页但游标非空；行为已文档化但无测试固定，客户端易误判为结束 |
| R6 | 低 | `GraphService.java` | 55-57、75-78、85-97 | 读路径 N+1：每个候选实体、每条关系的每个 evidence 都各自开启独立 Neo4j 事务并回查 PostgreSQL，`limit=100` 时放大为数百次跨库往返 |
| R7 | 低 | `GraphApiIntegrationTest.java` / `GraphDependencyIsolationTest.java` | 全文 | 关键负向测试缺口：非 owner 成员写入/清理、图关闭时写端点 503、请求类型与 `source.type` 不匹配、超出 `long` 范围的版本号 |

### 无需修改

| ID | 严重级别 | 文件 | 行号（约） | 说明 |
| --- | --- | --- | --- | --- |
| R8 | 低 | 文档与配置 | — | ADR-0032 / graph-domain-model / core-api / local-stack 的状态标注（Implemented、GraphRAG Planned）、Neo4j 默认关闭、无主机端口、独立密码边界均与实现一致 |
| R9 | 低 | `.env.example` / `.env.production.example` | 末行 | 缺少结尾换行属风格问题，不影响功能 |
| R10 | 低 | `GraphService.java` / `GraphModel.java` | 48、66-67 | ID 拼接无碰撞：`type` 枚举名互不为前缀且含分隔冒号；`chunkIndex=null` 与整数不会与其他取值冲突；`Source`/`Evidence` 的 `Objects.equals` 语义正确 |

---

## 三、逐个 Issue 展开

### R1（必须修改，高）图写入/清理授权等级与计划、契约不一致

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphService.java:27-31, 32-51, 58-71`（`clear`、`put`、`putRelation`）；`GraphController.java:18-33`

**Evidence**
```java
// clear
projects.requireAccess(projectId, actor);
// entities / neighbors（只读）
projects.requireAccess(projectId, actor);
// putRelation（写）
projects.requireAccess(projectId, actor);
```
显式上下文中的 `docs/01-product/v3-04-development-plan.md` 明确要求：
> 受限图写入入口只允许项目 owner/admin 并重新验证来源；只读入口要求项目访问权。

只读端点与写入端点调用的是**同一个** `ProjectService.requireAccess`，单一检查在语义上不可能同时满足“只读=项目访问权”和“写入=owner/admin”两级要求；`docs/04-api/core-api.md` 又对所有 graph 端点宣称 owner/admin。现有测试 `anonymousAndOtherUserCannotReadWriteOrClearProjectGraph` 只覆盖“非成员 403”和“平台 ADMIN 可读”，没有任何用例区分普通成员与 owner/admin。

**Description**
若 `requireAccess` 是成员级访问检查（其名称、被只读端点复用、以及 `getProject`/`wiki.get`/`tasks.get` 也基于项目访问判断，均指向这一语义），则图写入、关系写入、整项目图清理对任意项目成员开放，弱于已确认的开发计划；`DELETE /graph?confirm=true` 属破坏性操作，风险最高。若 `requireAccess` 已是 owner/admin，则只读端点被过度收紧，与计划“只读入口要求项目访问权”冲突。两种情况下都存在实现与文档/计划不一致，且无测试固定预期。

**Suggested Fix**
1. 写入与清理改用 owner/admin 级校验（沿用项目现有方法名，如 `requireOwner`/`requireManage`），只读保持 `requireAccess`：
```java
public void clear(UUID projectId, AuthenticatedActor actor, boolean confirm) {
    projects.requireOwner(projectId, actor);   // 或既有 owner/admin 检查
    if (!confirm) throw invalid();
    store.clear(projectId);
}
public Entity put(...) { projects.requireOwner(projectId, actor); ... }
public Relation putRelation(...) { projects.requireOwner(projectId, actor); ... }
```
2. 若 `requireAccess` 本身即 owner/admin，则修正 `docs/01-product/v3-04-development-plan.md` 与 `docs/04-api/core-api.md` 的措辞，并说明只读端点的真实等级。
3. 补测试：构造“同项目普通成员”（非 owner、非 ADMIN）——
   - `PUT /entities`、`PUT /relations`、`DELETE ?confirm=true` → 403；
   - `GET /entities`、`GET /entities/{id}/neighbors` → 200。
   现有 fixture 只支持 owner 与无关用户，需要新增成员/角色构造路径。

---

### R2（必须修改，中）`relation()` 未检查查询结果存在性，异常路径产生 500

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java:128-134`（`relation`），由 `neighbors`（121-127）调用。

**Evidence**
```java
private Relation relation(TransactionContext tx,UUID id,UUID projectId) {
    var p=Map.<String,Object>of("id",id.toString(),"project",projectId.toString());
    var n=tx.run("MATCH (r:GraphRelation {id:$id,projectId:$project}) RETURN r",p).single().get("r").asNode();
    ...
}
```
同一文件在 `entity()`（88-91）与 `put()`（68-69）都先 `hasNext()`，唯独 `relation()` 直接 `.single()`。
`neighbors` 是只读事务，先扫描 `r.id`（123 行），再逐条 `relation(tx, ...)`（124 行）。Neo4j 默认隔离为 read-committed，长读事务期间其它事务的提交可见；若此时并发执行 `DELETE /graph?confirm=true`（`clear` 会 `DETACH DELETE` 这些关系），第二次查询会返回 0 行，`Result.single()` 抛出 `NoSuchRecordException`（`NoSuchElementException` 子类）。`transaction()` 的 `catch (Neo4jException | IllegalArgumentException)` 不覆盖它，异常直接冒泡为 HTTP 500。

**Description**
这是可复现触发条件的未捕获异常路径：并发清理 + 邻接查询。契约上应表现为稳定的 404 或空结果（文档明确“未知或失效节点 404”“clear 后邻居 404”），而不是 500。当前测试均为串行，无法发现。

**Suggested Fix**
```java
private Relation relation(TransactionContext tx, UUID id, UUID projectId) {
    var p = Map.<String,Object>of("id", id.toString(), "project", projectId.toString());
    var result = tx.run("MATCH (r:GraphRelation {id:$id,projectId:$project}) RETURN r", p);
    if (!result.hasNext()) {
        throw new ResourceNotFoundException("Graph relation not found.");
    }
    var n = result.single().get("r").asNode();
    ...
}
```
并在 `neighbors` 中把该异常收敛为“跳过该条关系/返回空页”，或让 `relation()` 返回 `Optional` 由调用方决定 404 语义；补一个并发 `clear` + `neighbors` 的回归用例（可用固定顺序的双线程 + latch 复现）。

---

### R3（必须修改，中）并发争用被统一下沉为 503，且不重试

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/infrastructure/Neo4jGraphStore.java:29-51`（`driver()`、`transaction()`）

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
项目锁依赖 `MERGE (p:GraphProjectLock {id:$project})`，而该标签恰有唯一约束 `graph_project_lock_id_v1`（34-40 行）。同一项目首次并发写入或高争用时，Neo4j 可能抛出瞬态异常/约束校验失败（`TransientException`、`ConstraintValidationFailed`、锁升级死锁），在 `maxTransactionRetryTime=0` 下不重试，随后被统一转成 503 “Graph service is unavailable.”。

**Description**
当前并发用例（6 线程实体/证据重试）通过，说明常见路径可串行化，但这不能证明争用下不会出现瞬态失败。结果是：一次可重试的并发争用被报告为“图服务不可用”，与真实故障（图关闭、连接失败）不可区分，既可能误导排障，也可能让调用方在可恢复场景下放弃写入。文档 `graph-domain-model.md` 对证据语义有清晰定义，但对争用失败无任何承诺。

**Suggested Fix**
1. 给出小的有界重试（例如 `withMaxTransactionRetryTime(2, TimeUnit.SECONDS)`），保留总时限，避免无限重试；`MERGE` 语义本身幂等，重试不会产生重复实体/证据。
2. 区分错误语义：`Neo4jException` 中真正的瞬态/锁争用可重试后仍失败则 503（保留 fail-closed），而由参数/语句导致的 `ClientException` 应视为服务端缺陷单独记录，不要与“服务不可用”混淆。
3. 补一条并发争用测试（例如 N 线程同时首次写同一项目不同实体），断言不出现 503 且无重复节点。

---

### R4（建议修改，中低）`StrictVersion` 未拒绝超出 `long` 范围的整数

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/graph/domain/GraphModel.java:46-53`

**Evidence**
```java
if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
    return (Long) context.handleUnexpectedToken(Long.class, parser);
}
return parser.getLongValue();
```
`docs/04-api/core-api.md` 明确声称：“图 source.version 与 expectedVersion 必须为 JSON 整数，**禁止截断为另一版本**。”小数与数字字符串已被测试覆盖（`fractionalOrStringVersionsCannotBeCoercedToAnotherVersion`），但超范围整数（如 `9223372036854775808`）没有校验也没有测试；其行为取决于 Jackson 版本对 `getLongValue()` 的溢出处理。

**Description**
若实现层静默截断，则与公开契约直接冲突（即使具体可利用性有限，CAS 语义不允许任何“版本被改写成另一值”的可能）；若 Jackson 抛 `InputCoercionException`，也应有测试锁定该行为，避免未来升级 Jackson 时契约漂移。

**Suggested Fix**
```java
if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
    return (Long) context.handleUnexpectedToken(Long.class, parser);
}
try {
    return parser.getBigIntegerValue().longValueExact(); // 超范围 -> ArithmeticException
} catch (ArithmeticException e) {
    return (Long) context.handleUnexpectedToken(Long.class, parser);
}
```
测试中增加 `new java.math.BigInteger("9223372036854775808")` 与 `"-9223372036854775809"` 两个用例，断言 400。

---

### R5（建议修改，低）隐藏实体导致“空页但游标非空”，行为已文档化但无测试

**File & Line**
`Neo4jGraphStore.java:79-83`（`nextAfter` 基于未过滤行）、`GraphService.java:52-57, 85-97`（过滤后可能为空）

**Evidence**
```java
return new Page<>(rows, rows.size()==limit ? rows.getLast().id().toString() : null);
...
return new Page<>(page.items().stream().map(e -> current(e, actor)).filter(Objects::nonNull).toList(), page.nextAfter());
```
`graph-domain-model.md` 已说明“分页游标按扫描候选前进，可能返回空页，必须按 nextAfter 继续”，属于有意设计；但 `expiredAndDeletedEvidenceIsNeverReturnedAsUsableProvenance` 等测试只断言 `items` 为空，没有断言“空页 + 非空 nextAfter”的组合，也没有覆盖 `entities` 分页遇到隐藏实体时的游标行为。

**Suggested Description / Fix**
这是契约与实现一致但缺少测试固定的点：客户端若把空 `items` 视为结束，会静默漏读。建议补一条测试（如存在失效来源实体且 limit 恰好命中该页），断言返回空页且 `nextAfter` 非空、可继续翻页；或在公开文档中追加客户端必须依据 `nextAfter` 判断结束的显式提示。

---

### R6（建议修改，低）读路径 N+1 跨库往返

**File & Line**
`GraphService.java:52-57, 72-78, 85-97`

**Evidence**
```java
return new Page<>(page.items().stream().map(e -> current(e, actor)).filter(...).toList(), page.nextAfter());
...
var evidence = relation.evidence().stream().filter(e -> { var document = source(...); ... }).toList();
```
`current()` 每个实体一次 Neo4j 事务 + 一次 PostgreSQL 查询；`visible()` 对每条关系先做两次 `requireEntity`（各自独立 Neo4j 事务），再对每个 evidence 做一次 PostgreSQL 查询。`limit` 上限 100，最坏情况放大到数百次事务/查询，且全部在单请求内串行。

**Suggested Fix**
在 application 层批量预取（按 `sourceId` 去重一次性 `wiki.get`/`tasks.get`，按 project 一次性批量取实体），或让 `GraphStore` 提供批量 `entities(projectId, ids)` 与批量关系读取；至少对 evidence 校验按 `(sourceType, sourceId, version)` 去重，避免同一来源重复查询。

---

### R7（建议修改，低）关键负向测试缺口

**File & Line**
`GraphApiIntegrationTest.java` / `GraphDependencyIsolationTest.java`（全文）

**Evidence / Description**
- 无“同项目普通成员（非 owner）”对 `PUT /entities`、`PUT /relations`、`DELETE ?confirm=true` 的 403 用例，也没有成员 `GET` 的 200 用例（与 R1 直接相关）。
- 图关闭（`agentforge.graph.enabled=false`）只在 `GraphDependencyIsolationTest.disabledAdapterFailsClosedWithoutConnecting` 做了 store 级断言，缺少 HTTP 层写端点（PUT/DELETE）的 503 断言；现有 503 用例只覆盖 `GET /entities`。
- 未测 `request.type=WIKI` 而 `source.type=TASK`（或反向）的 400，只测了 `externalId` 与 source id 不匹配。
- 未测超出 `long` 范围的 `expectedVersion`/`source.version`（与 R4 相关）。

**Suggested Fix**
在 `GraphApiIntegrationTest` 增加上述四类用例；成员授权用例需先构造“项目成员”fixture（当前 fixture 只有 owner 与无关用户），否则 R1 的修复无法被证明。

---

## 四、主开发 (Codex) 评估回填区

| Issue ID | 是否成立 | 事实判断 | 处理方式 | 修复文件 / 补测 | 备注 |
| --- | --- | --- | --- | --- | --- |
| R1 | 不成立 | ProjectService.requireAccess/getProject 均调用 requireOwnerOrAdmin；无普通成员模型 | 计划澄清只读权限同为 owner/admin | ProjectService 必要上下文加入 Attempt 2 | 不新增权限 |
| R2 | 成立 | 真实 HTTP 并发复现 NoSuchRecordException；javap 确认继承 NoSuchElementException | relation Optional / 邻接跳过并发消失关系 | 新增并发 clear/neighbors 测试实际 red 后 green | 最终 clean verify 待复验 |
| R3 | 非阻塞 | 已有 6 并发实体/证据验证；无死锁缺陷复现，事务重试为 0 是有界失败策略 | 文档明确锁争用/timeout 503，调用者可稳定 ID 重试 | 不扩大自动重试 | 实现与声明一致 |
| R4 | 不成立 | 当前测试 classpath 的 JsonFactory/getLongValue 对 9223372036854775808 实际抛 InputCoercionException | 无截断，不新增替代解析实现 | JShell 当前依赖 probe 退出 0 | 测试建议保留 |
| R5 | 建议 | 候选游标有意设计且已声明空页按 nextAfter 继续 | 保留契约 | 无代码调整 | 非阻塞 |
| R6 | 建议 | 存在有界重复查询；entity current 无 Neo4j 二次查询，relation endpoint 有两次 | 保留性能限制，不扩新批量接口 | 无性能承诺 | 非阻塞 |
| R7 | 建议 | 当前无普通成员；已有外部 actor 拒绝、类型/来源/版本测试，关闭 adapter 公共 seam 已覆盖 | 不伪造不存在成员模型 | 保留后续测试建议 | 无真实缺陷证据 |
| R8-R10 | 无需修改 | 范围、ID 与配置一致 | 无调整 | — | — |

---

## 五、审查边界说明

- 本轮为 Milestone Review：已结合显式提供的节点定义、Roadmap、开发计划、变更记录与测试证据检查方向、边界与下一节点隐患（未发现 V3-05/06/07 越界；GraphRAG、Entity Resolution、抽取流水线均保持 Planned）。
- 本轮未运行任何命令、未修改任何文件、未改变 Git 状态；所有结论仅基于给定 diff、文件清单与文档证据。
- 未把 `docs/07-changes/2026-09-27-v3-04-graph-domain-model.md` 中的机器记录改写为本次执行结果；其记录仅作为审查证据使用。
- 上一轮报告为“无”，本轮一次性列出全部可确认问题（7 项，其中必须修改 3 项）。

Codex 处理状态：RESOLVED（2026-09-28；真实问题已修复，最终 Attempt 3 PASS；原 REVIEW_RESULT 保留供追溯）。
