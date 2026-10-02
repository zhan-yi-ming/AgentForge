# Pi 代码审查报告：v3-full-impact-audit / Attempt 1

- 日期：2026-10-02
- 审查阶段：v3-full-impact-audit
- 审查对象：INDEX@7dd6868（基线：61626ed17cc7b7d507053fc319543dc043b6034c）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3 全量跨功能影响审计 — Milestone Review（第 1/3 轮）

## 一、概述与总体结论

- 审查阶段：`v3-full-impact-audit`（Milestone / 累计 V3 `61626ed..7dd6868`）
- 审查范围：本次输入提供的累计 diff（117 个文件，8,371 insertions / 87 deletions；审计记录说明真实累计为 146 文件，差异来自历史 `docs/07-changes`、`docs/08-reviews` 被临时 index 还原的输入裁剪）、变更清单、路线图与审计记录。
- 总体结论：**通过（PASS）**，无阻断性问题，无需 NEEDS_FIX。发现的问题均为非阻塞的健壮性、文档一致性与有界性建议。
- 关于固定准则中“严禁建议引入 V2/V3 组件（Neo4j/GraphRAG/Langfuse/LiteLLM/MCP）”的说明：本规则针对“不得**新增**未授权能力”。本轮是用户显式授权的 V3 累计审计，路线图 `docs/01-product/v2-v3-node-roadmap.md` 已逐节点确认 Scope、验收与 Review 状态，因此本报告不把已交付的 V3 能力本身判定为越界；同时未建议任何超出已实现 Scope 的新能力、新迁移或新接口。
- 重点结论摘要：
  - MCP 写路径未绕过 Java：`McpToolService` 只创建 `source=MCP` 的 PENDING Approval，执行仍需既有 confirm/reject，且显式禁止 `auto-confirm`（`forbidden` 分支 + `AgentActionSource != CHAT` 校验 + `V11` 的库级 CHECK）。
  - 权限/项目隔离在 application 层再次强校验：`GraphService`、`GraphResolutionDecisionService`、`GraphRetrievalService` 均在读取/写入前调用项目访问校验，跨项目来源、端点与来源版本在读取/写入时重验。
  - 幂等与并发：MCP 提案使用 `pg_advisory_xact_lock` + 部分唯一索引双保险；Neo4j 图写入使用项目锁 + 稳定 ID + CAS 语义。
  - 契约：`taskType` 为可选向后兼容字段，未知值 400；`StrictVersion` 拒绝小数/字符串版本；GraphRAG 仅把 503/传输故障降级为空图，401/403/404 与响应关联不匹配**不**降级。
  - 测试证据与审计记录一致：Java `clean verify` 201/0/0、Python 199 passed、Web 73 tests + production build、4/4 阶段 runner PASS；未发现被吞掉的失败。

---

## 二、详细发现清单

### 必须修改（Must Fix）

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 本轮未发现具备明确证据的可运行性、正确性、安全、权限、并发、幂等、数据一致性、契约或方向性缺陷。 |

### 建议修改（Should Fix）

| ID | 严重级别 | 文件 | 行号/位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| S-1 | 中 | `services/core-api/src/main/java/com/agentforge/core/graph/application/GraphSyncProcessor.java` | `processOne()` 首个 `jdbc.query(...)` 与 `catch` 内 `jdbc.update(...)` | 轮询查询与失败回写不在任何 try/catch 内，DB 暂不可用或测试上下文关闭时会从 `@Scheduled` 抛未捕获异常 |
| S-2 | 低 | `docs/02-architecture/decisions/README.md` | ADR 索引段 | 新增 ADR 只登记了 0029、0032、0033、0034；0030、0031、0035、0036 未入索引，且链接格式不统一 |
| S-3 | 低 | `docs/03-features/graphrag.md`、`docs/02-architecture/data-architecture.md` | 文件头“状态”字段 | 功能文档状态与路线图/README 声明不一致（GraphRAG “待 Milestone Review 收口”；V3-08 “Accepted，实施中”） |
| S-4 | 低 | `services/core-api/src/main/java/com/agentforge/core/mcp/application/McpToolService.java` | `searchWiki(...)` | MCP 读 Tool 全量加载项目 Wiki 并在内存过滤，无结果数上限；对外部 MCP 客户端是无界响应 |
| S-5 | 低 | `services/core-api/src/main/java/com/agentforge/core/graph/application/GraphRetrievalService.java` | `retrieve(...)` 根候选扫描 | 每个候选各起一次 Neo4j 会话 + 一次 ProjectAccess 查询，上限 500 次，属 N+1 放大（有界但延迟可观） |

### 无需修改（No Change）

| ID | 结论 |
| --- | --- |
| N-1 | MCP 写路径未绕过 RBAC/Risk/Approval/幂等/审计，`V11` CHECK 约束在库层兜底，无需修改 |
| N-2 | 图与来源版本重验、跨项目拒绝、CAS/409 语义、图读重验 evidence 原文，设计完整，无需修改 |
| N-3 | `taskType` 可选字段与未知值 400、`StrictVersion` 拒绝非整数版本，契约一致，无需修改 |
| N-4 | GraphRAG 仅在依赖故障降级、权限错误 fail-closed；Repository HEAD 只读、工作树/secret 过滤与 revision 级 sourceId，安全边界成立，无需修改 |
| N-5 | 核心分支与异常分支的自动化覆盖充分（MCP 越权/schema/并发幂等、图 CAS/清理竞态/两跳上限、resolution CAS/撤销、gateway fallback 与凭据隔离） |

---

## 三、逐个 Issue 展开

### S-1（中）GraphSyncProcessor 轮询与失败回写缺少异常兜底

- **Severity**：中（健壮性 / 日志噪声，非数据一致性问题，不阻塞）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphSyncProcessor.java`，`processOne()` 内首条 `jdbc.query` 与 `catch (RuntimeException failure)` 中的 `jdbc.update`
- **Evidence（当前实现）**：
```java
@Transactional
public boolean processOne() {
    if (!enabled) return false;
    var rows = jdbc.query("""
        SELECT project_id, source_type, source_id FROM graph_source_sync
        WHERE next_attempt_at<=now()
        ORDER BY next_attempt_at, updated_at
        LIMIT 1 FOR UPDATE SKIP LOCKED
        """, (rs, i) -> new Pending(...));   // ← 位于 try 之外
    if (rows.isEmpty()) return false;
    var row = rows.getFirst();
    try {
        ...
    } catch (RuntimeException failure) {
        log.warn(...);
        jdbc.update("""
            UPDATE graph_source_sync
            SET attempts=attempts+1, next_attempt_at=now()+interval '30 seconds'
            WHERE project_id=? AND source_type=? AND source_id=?
            """, ...);                        // ← 自身也可能抛
    }
    return true;
}
```
- **Description**：审计记录中“Java 全量套件在 Graph Testcontainers 上下文关闭后，后台 `GraphSyncScheduler` 记录一次访问已释放 PostgreSQL 的 connection-refused 堆栈”与该实现完全吻合：轮询查询未纳入错误处理，专用调度线程在依赖不可用/关闭竞态时会把异常抛给 `TaskUtils` 并打印完整堆栈。功能上不会造成业务事实、图投影或待办状态丢失——`@Scheduled(fixedDelay)` 下一轮会重新执行，且 `drain()` 循环会随即终止——因此不构成生产故障或吞错结论，但与 ADR-0033“持久待办 + 失败保留重试”的失败隔离表述存在落差：基础设施级失败路径缺少显式兜底与可观测降级信息。
- **Suggested Fix**（保持“文档先行 + TDD”，仅收敛异常边界）：
```java
@Transactional
public boolean processOne() {
    if (!enabled) return false;
    final Pending row;
    try {
        var rows = jdbc.query("""
            SELECT project_id, source_type, source_id FROM graph_source_sync
            WHERE next_attempt_at<=now()
            ORDER BY next_attempt_at, updated_at
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, (rs, i) -> new Pending(...));
        if (rows.isEmpty()) return false;
        row = rows.getFirst();
    } catch (DataAccessException unavailable) {
        log.warn("Graph source sync poll deferred; failure={}",
            unavailable.getClass().getSimpleName());
        return false;   // 事务内不遗留失败语句，交由下一轮重试
    }
    try {
        ...
    } catch (RuntimeException failure) {
        log.warn(...);
        // attempts 回写失败不应再次向外抛出；如需强保证可独立短事务处理
    }
    return true;
}
```
建议同时补一条“数据库不可用时 `processOne` 不抛异常、待办不被删除”的单元/集成红灯测试，作为该修复的证据。

### S-2（低）ADR 索引未随新增 ADR 完整更新

- **Severity**：低（文档一致性）
- **File & Line**：`docs/02-architecture/decisions/README.md` 索引段
- **Evidence**：本次新增 `ADR-0030-litellm-model-gateway.md`、`ADR-0031-deterministic-task-model-routing.md`、`ADR-0035-evidence-bounded-graphrag.md`、`ADR-0036-project-scoped-repository-context.md`，但索引仅新增：
```
+ `ADR-0029-mcp-adapter-through-java-policy.md`：...
+ - ADR-0032-neo4j-derived-graph.md：...
+ - [ADR-0033-...](ADR-0033-...)：...
+ - [ADR-0034-...](ADR-0034-...)：...
```
- **Description**：其余 4 个 ADR 已在 `system-overview.md`、`data-architecture.md`、`backend-architecture.md`、功能文档中被交叉引用，但 ADR 中心索引缺失，且新增条目链接/反引号风格不统一，削弱“文档先行 + 可追溯”的公开证据链。不影响运行与契约。
- **Suggested Fix**：在索引中按编号补齐 0030/0031/0035/0036 条目，统一为 `` `ADR-00XX-*.md`：一句话决策 `` 形式，并保留指向文件的相对链接。

### S-3（低）功能/架构文档状态与路线图声明不一致

- **Severity**：低（文档真实性/一致性）
- **File & Line**：
  - `docs/03-features/graphrag.md` 头部：`- 状态：Implemented（V3-07；待 Milestone Review 收口）`
  - `docs/02-architecture/data-architecture.md` V3-08 段：`## V3-08 Repository Context 数据路径（Accepted，实施中）`
- **Description**：路线图与 `README.md` 已声明 V3-07/V3-08 “机器验证与 Milestone Review PASS / Implemented”，两处文档状态滞后，容易被面试官或审计者解读为能力未收口。属文档不一致，不涉及实现。
- **Suggested Fix**：将 `graphrag.md` 状态改为 `Implemented（V3-07；Milestone Review PASS）`；将 `data-architecture.md` 的 V3-08 段标题改为 `（Implemented）`，并保持与路线图、README 表述一致。

### S-4（低）MCP `search_wiki` 结果无上限

- **Severity**：低（资源消耗 / 对外有界性）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/mcp/application/McpToolService.java`，`searchWiki(...)`
- **Evidence**：
```java
List<Map<String, Object>> matches = wikiPages.list(projectId, actor).stream()
        .filter(page -> page.title().toLowerCase(Locale.ROOT).contains(query)
                || page.content().toLowerCase(Locale.ROOT).contains(query))
        .map(this::wikiSummary)
        .toList();
return success(Map.of("items", matches), "Found " + matches.size() + " Wiki page(s).");
```
- **Description**：功能文档已把“不分页或限制结果数”记录为已知限制，且权限仍由 `WikiPageService.list` 的 ToolRiskEngine 保证，因此不构成越权或契约破坏。但 `/mcp` 面向任意已认证客户端，单个项目 Wiki 规模增长后响应体与序列化成本无界，属可放大的读放大面。
- **Suggested Fix**：在应用层加固定上限（例如 100 条，返回 `truncated` 标记），并同步更新 `docs/03-features/mcp-adapter.md` 的已知限制；如暂不改，明确在文档中记为运维容量约束即可。

### S-5（低）GraphRAG 根候选扫描为 N+1 访问

- **Severity**：低（有界但延迟）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/graph/application/GraphRetrievalService.java`，`retrieve(...)` 的根扫描循环
- **Evidence**：
```java
while (scanned < MAX_SCANNED) {
    var page = store.entities(projectId, after, Math.min(100, MAX_SCANNED - scanned));
    scanned += page.items().size();
    for (var candidate : page.items()) {
        Entity entity;
        try { entity = graph.entity(projectId, candidate.id(), actor); }  // 每候选一次 Neo4j + 一次 ProjectAccess
        catch (ResourceNotFoundException expired) { continue; }
        ...
    }
    ...
}
```
- **Description**：`MAX_SCANNED = 500` 保证了硬上限，且读取前重新授权、失效来源被跳过（安全性正确）；但每个候选都要新开一次 Neo4j 读事务并重做项目访问校验，冷启动或大盘项目下延迟会明显放大 Chat 的图检索阶段。
- **Suggested Fix**：可选优化——改为一次 `store.entities` 分页 + 在应用层用一次项目访问校验结果复用，或让 `GraphStore` 提供批量 `entities(projectId, ids)`；本节点不阻塞，可作为已知限制记录。

---

## 四、主开发（Codex）评估回填区

| 发现 ID | Codex 结论（接受/部分接受/拒绝） | 修复提交 / 依据 | 复验方式与证据 | 备注 |
| --- | --- | --- | --- | --- |
| S-1 | 拒绝本次修改 | Pi 已判定为非阻断健壮性建议；本机仅在 Testcontainers 上下文关闭竞态中复现日志噪声，201 个 Java 测试与随后 13 个隔离契约均通过，没有生产路径失败、待办丢失或吞错证据。按项目规则不为非严重建议改业务异常语义。 | 本次完整 runner 4/4 PASS；Java 201/0/0/11，专项 13/0/0/0 | 保留为测试生命周期/日志噪声观察项；未来若生产观测出现同类错误，另立故障记录诊断。 |
| S-2 | 接受 | 补齐 ADR-0030/0031/0035/0036，并统一 0029–0036 的相对链接格式。 | Markdown 相对链接检查与 `git diff --check` | 纯文档真实性修正，不触发 Pi 复审。 |
| S-3 | 接受 | GraphRAG 改为 Milestone Review PASS；V3-08 数据路径改为 Implemented。 | 文档状态搜索、相对链接检查与 `git diff --check` | 与路线图、README 和已通过门禁一致。 |
| S-4 | 接受为已知限制，不修改实现 | 功能文档已记录 MCP 当前不分页/无通用结果上限；权限边界与数据隔离正确，本次不扩接口契约。 | MCP HTTP tests + Tool/HITL smoke | 若真实容量数据证明风险，再以独立 Node/变更设计分页或截断字段。 |
| S-5 | 接受为已知限制，不修改实现 | `MAX_SCANNED=500`、根/邻居/关系/两跳均有硬上限；当前无延迟失败证据，本次不增加 GraphStore 批量 API。 | Graph 集成测试、GraphRAG 契约与完整 runner | 作为有界性能优化候选，不阻断 V3。 |
| N-1 ~ N-5 | 无需改动 | Pi 结论与 Codex 的权限、幂等、来源版本、契约和覆盖映射一致。 | 本次完整 runner 与人工热点核对 | 作为本轮已核对结论归档。 |

---

## 五、审查边界声明

- 本报告严格只读：未运行命令、未编辑文件、未改变 Git 状态；所有结论仅基于本次输入提供的 diff、文件清单、审计记录与路线图。
- 审计记录中的测试数字（Java 201/0/0/11 skips、Python 199 passed、Web 73 tests、runner 4/4 PASS、13 项跨进程契约 0 skip）作为 **Codex 提供的外部证据**引用，未改写为 Pi 自执行结果。
- 未将历史节点 PASS、旧构建产物或本机未复现的旧报告当作本轮工作树证据；审计记录中 `docs/07-changes` 与 `docs/08-reviews` 被临时 index 还原的做法已解释来源，未发现因此产生的虚假新增或断链。
