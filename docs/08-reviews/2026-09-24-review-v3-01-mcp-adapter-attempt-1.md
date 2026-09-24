# Pi 代码审查报告：v3-01-mcp-adapter / Attempt 1

- 日期：2026-09-24
- 审查阶段：v3-01-mcp-adapter
- 审查对象：INDEX@61626ed（基线：61626ed17cc7b7d507053fc319543dc043b6034c）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- 状态：RESOLVED（Attempt 2 PASS）
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-01 MCP Adapter — Milestone 审查报告（第 1 / 3 轮）

## 一、概述与总体结论

- 审查对象：Commit INDEX@61626ed，范围 `services/core-api`（MCP Adapter、Approval source 迁移）、`apps/web/nginx.conf`、`infra/nginx/production.conf.template`、README 与 7 份文档。
- 审查模式：完全只读，仅依据本次 diff、文件清单与显式上下文；未运行任何命令，未改写 Codex 提供的测试结果。
- 架构判断：`McpServerConfiguration` 通过 Spring Security 的 `JwtAuthenticationToken` 提取 `AuthenticatedActor`，`McpToolService` 只调用 `WikiPageService` / `TaskService` / `AgentActionService`，未出现 MCP 直连 Repository/DB；写 Tool 只落 `PENDING` Approval，confirm 复用既有 Core API 与幂等键。ADR-0029 与 V3-01 边界总体成立。
- 结论：**需修复后交付（NEEDS_FIX）**。无阻断性安全问题或数据破坏路径，但存在 2 项必须修改：MCP 写 Tool 的幂等/重试语义缺口，以及“JSON Schema 拒绝非法输入”这一验收声明缺少可区分的验证。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| MCP-01 | 中 | `services/core-api/.../mcp/application/McpToolService.java`、`.../agent/application/AgentActionService.java` | 93–148 / 127–165 | MCP 写 Tool 无请求级幂等键，超时重试会重复创建 PENDING Approval，可经两次 confirm 造成重复 Task 写入；无重试测试 |
| MCP-02 | 中 | `services/core-api/.../mcp/api/McpHttpApiTest.java`、`docs/07-changes/2026-09-24-v3-01-mcp-adapter.md` | 245–268 | 唯一“schema 校验”测试用随机 taskId，无法区分“schema 拒绝”与 ResourceNotFound；宣称的未知字段/非法 UUID/超长文本/负版本拒绝无直接断言 |
| MCP-03 | 低 | `.../mcp/application/McpToolService.java` | 226–244、255–295 | `request.arguments()` 为 null 时触发 NPE，被归为“意外错误”打 ERROR 日志并返回通用消息，客户端输入错误被当作服务端故障 |
| MCP-04 | 低 | `.../mcp/application/McpToolService.java` | 200–206 | `requiredLong` 使用 `Number.longValue()`，对 `1.9` 之类小数静默截断（server 端无再校验） |
| MCP-05 | 低 | `.../mcp/application/McpToolService.java` | 53–69 | `search_wiki` 以“全量 `wikiPages.list()` + 内存 substring 过滤”实现，规模与既有 `search_wiki` 检索语义可能漂移 |
| MCP-06 | 低 | `docs/03-features/mcp-adapter.md`、P3-06 相关路径（未在 diff 中） | — | P3-06 低风险自动确认路径与 `source=MCP` Action 的隔离未在本次改动中显式约束或验证 |
| MCP-07 | 低 | `docs/07-changes/2026-09-24-v3-01-mcp-adapter.md` vs `README.md` / `v2-v3-node-roadmap.md` | — | 同一节点状态“Review Pending”与“Implemented”并存 |

## 三、逐个 Issue 展开

### MCP-01（中，必须修改）MCP 写 Tool 缺幂等键，重试可重复创建 Approval

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/mcp/application/McpToolService.java` 约 93–148；`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` 约 127–165。

**Evidence**

```java
public McpSchema.CallToolResult createTask(McpTransportContext context, McpSchema.CallToolRequest request) {
    ...
    ToolProposal proposal = new ToolProposal("CREATE_TASK", null, null,
            requiredString(request.arguments(), "title"), ...);
    AgentActionView action = actions.createPendingMcp(projectId, actor, proposal, requestId(context))
            .orElseThrow(() -> new IllegalArgumentException("Invalid create_task arguments."));
```

```java
public Optional<AgentActionView> createPendingMcp(UUID projectId, AuthenticatedActor actor,
        ToolProposal proposal, String requestId) {
    ...
    AgentTaskAction action = AgentTaskAction.pendingMcp(...);   // 每次调用 new UUID
    AgentTaskAction saved = actions.save(action);
    auditEvents.save(AgentAuditEvent.record(saved, actor.userId(), REQUESTED, ...));
```

会话级 Chat 有 LangGraph thread/checkpoint，MCP 侧没有任何前序键：同一 `create_task` 被重试（网络超时、客户端重放）会生成两条独立 `PENDING` Action，各自 confirm 可带不同 `Idempotency-Key`，从而创建两个 Task。V3-01 验收明确要求“Schema、Validation、错误契约、Retry/Idempotency 一致”，而本次新增的唯一写路径没有幂等维度，且测试未覆盖重放。

**Description**：confirm 幂等（同 key 终态复用）已被测试覆盖，但“意图创建”幂等缺失。人工确认只保证单条 Action 不重复执行，不阻止同一意图产生多条可执行 Action；文档也未声明“MCP 提案非幂等、需人工自行去重”。

**Suggested Fix**：为 `create_task` / `update_task` 增加可选 `idempotencyKey` 参数并在服务端按 `(requested_by_user_id, project_id, source=MCP, idempotency_key)` 去重（命中已存在 Action 时直接返回该 Approval 视图），或在 `mcp-adapter.md` / ADR-0029 明确声明提案级非幂等并加测试证明重放不会静默双写。示例：

```java
String key = optionalString(request.arguments(), "idempotencyKey");
AgentActionView action = actions.createPendingMcpIdempotent(projectId, actor, proposal, key, requestId(context))
        .orElseThrow(() -> new IllegalArgumentException("Invalid create_task arguments."));
```

### MCP-02（中，必须修改）“Schema 拒绝非法输入”的验收声明不可被现有测试证明

**File & Line**：`services/core-api/src/test/java/com/agentforge/core/mcp/api/McpHttpApiTest.java` 约 245–268；`docs/07-changes/2026-09-24-v3-01-mcp-adapter.md` “TDD”段落。

**Evidence**

```java
JsonNode injected = callTool(ownerAuthentication.token().value(), "get_task", Map.of(
        "projectId", project.id().toString(),
        "taskId", UUID.randomUUID().toString(),
        "actorUserId", outsiderAuthentication.user().id().toString()));
assertThat(injected.at("/result/isError").asBoolean()).isTrue();
```

```java
"create_task", ... objectSchema(...), // 含 "additionalProperties", false
```

该断言无论“SDK 依据 `additionalProperties=false` 拒绝未知字段”还是“handler 运行后 `TaskService.get` 抛 `ResourceNotFoundException`”都会为 `true`，因此不能证明 schema 层生效。变更记录却声称“以 JSON Schema 拒绝未知字段、非法 UUID、超长文本和负版本”，`McpServerConfiguration` 只声明了 schema，未在应用层做二次校验。若 SDK 未内置输入校验，未知字段会被静默忽略，说法与实现不一致；Milestone 审核要求文档与真实实现一致。

**Suggested Fix**：补充可区分的用例（每条只制造一种错误并断言消息/`isError`）：(a) 仅多传未知字段且其余参数合法；(b) `projectId` 为非 UUID 字符串；(c) `description` 长度 10001；(d) `expectedTaskVersion = -1`。若发现 SDK 不校验，则在 `McpToolService` 显式校验并在变更记录中修正措辞。

### MCP-03（低，建议修改）`arguments()` 为 null 时 NPE 被当作内部错误

**File & Line**：`McpToolService.java` 约 226–295。

**Evidence**

```java
private String requiredString(Map<String, Object> arguments, String name) {
    Object value = arguments.get(name);   // arguments 可能为 null
    ...
}
private McpSchema.CallToolResult failure(McpTransportContext context, RuntimeException exception) {
    if (!(exception instanceof IllegalArgumentException || exception instanceof ForbiddenException
            || exception instanceof ResourceNotFoundException || exception instanceof ConflictException)) {
        LOGGER.error("MCP tool call failed; requestId={}, errorType={}", requestId(context),
                exception.getClass().getSimpleName());
    }
    return error(safeMessage(exception));
}
```

当客户端只发 `{"name":"create_task"}` 不带 `arguments` 时得到 NPE，既被记为 ERROR 日志（可被低成本刷量），又返回通用消息，丢失“缺少参数”的可修正语义。

**Suggested Fix**：在 `uuid` / `requiredString` / `requiredLong` / `optionalString` 入口统一 `Map<String,Object> args = arguments == null ? Map.of() : arguments;`，或在四个 public 方法开头 `Objects.requireNonNullElse(request.arguments(), Map.of())`。

### MCP-04（低，建议修改）`requiredLong` 静默截断小数

**File & Line**：`McpToolService.java` 约 200–206。

**Evidence**

```java
if (!(value instanceof Number number) || number.longValue() < 0) {
    throw new IllegalArgumentException(name + " must be a non-negative integer.");
}
return number.longValue();
```

schema 声明 `"type":"integer"`，但一旦 SDK 未校验，`expectedTaskVersion = 1.9` 会被截断为 1 并进入乐观锁比较，与“非法版本应被拒绝”不一致。

**Suggested Fix**：限定整型，例如 `value instanceof Integer || value instanceof Long`，否则报错；或在服务端补一层 `BigDecimal` 精度检查。

### MCP-05（低，建议修改）`search_wiki` 全量加载 + 内存过滤

**File & Line**：`McpToolService.java` 约 53–69。

**Evidence**

```java
List<Map<String, Object>> matches = wikiPages.list(projectId, actor).stream()
        .filter(page -> page.title().toLowerCase(Locale.ROOT).contains(query)
                || page.content().toLowerCase(Locale.ROOT).contains(query))
```

每次调用把项目全部 Wiki（含正文）载入内存再 `contains`；项目规模增大后结果集与耗时不收敛，且与 Python/AI 侧 `search_wiki`（检索语义）同名不同义，存在 Schema/语义漂移风险。

**Suggested Fix**：若 `WikiPageService` 提供分页/标题检索能力，优先复用并加 `LIMIT`；或在 `mcp-adapter.md` 明确 MCP `search_wiki` 定义为“授权页面子串匹配”的受限语义。

### MCP-06（低，建议修改）P3-06 自动确认路径与 `source=MCP` 的隔离未验证

**File & Line**：`docs/03-features/mcp-adapter.md`（权限与安全段）；P3-06 自动确认实现（不在本次 diff 中）。

**Evidence**：本次改动新增 `source=CHAT|MCP`，但 diff 中未见自动确认/超时处理对 `source` 或“无 conversation”Action 的显式过滤；`create_task` 为 LOW risk，正是 P3-06 允许自动处理的等级。

**Suggested Fix**：在 Milestone 证据中补充验证：低风险自动确认入口只能处理确实由 UI 会话发起并持有上下文的 Action，MCP 来源 Action 必须经显式人工 confirm；若自动确认按 risk 选择 PENDING Action，则需加 `source` 条件。

### MCP-07（低，建议修改）节点状态在文档间不一致

**File & Line**：`docs/07-changes/2026-09-24-v3-01-mcp-adapter.md`（`- 状态：Review Pending`）vs `README.md`（`✅ V3-01 ... 已实现`）与 `docs/01-product/v2-v3-node-roadmap.md`（`Implemented`）。

**Description**：Milestone 审核尚未通过即宣称 Implemented，与变更记录自身状态矛盾。

**Suggested Fix**：Review 期间统一为“Implemented（Review Pending）”，PASS 后回填审核结论再定稿。

### 无需修改（已确认成立）

| 项 | 依据 |
| --- | --- |
| `source` 可信来源、CHAT 必填 conversation、MCP 置空 workflow | `AgentTaskAction` 构造函数与 `V11` 双 check 约束一致，回填 CHAT 后置 NOT NULL，向后兼容 |
| 读/写均经 Java Application Service 与 ProjectAccess，无 EAV/直连 DB | `McpToolService` 只注入 `WikiPageService`/`TaskService`/`AgentActionService`/`ToolRiskEngine` |
| MCP 上下文来自 JWT，参数不能覆盖 actor/role/risk | `McpServerConfiguration.contextExtractor` 仅取 `JwtAuthenticationToken` |
| 错误不泄漏凭据/堆栈/资源正文 | `safeMessage` 对 Forbidden/NotFound/Conflict/Unknown 统一脱敏；测试断言不含 `Private Wiki/Private body` |
| 写 Tool 不执行 Task、confirm 幂等 | `createTaskStaysPendingUntilHttpConfirmationAndExecutesOnlyOnce` 覆盖 `PENDING → EXECUTED` 与同 key 复用 |
| Nginx 精确 `/mcp` 代理、关闭缓冲、生产限流 | `apps/web/nginx.conf`、`infra/nginx/production.conf.template` |

## 四、主开发（Codex）评估回填区

| Issue ID | 是否认可 | 处理方式 | 关联提交 / 说明 |
| --- | --- | --- | --- |
| MCP-01 | 认可 | 写 Tool 强制提案幂等键；按 project/user/key 使用 PostgreSQL 事务锁及唯一索引，复用同意图 Approval，不同意图冲突。真实数据库串行、并发和终态重放测试通过。 | V3-01 同节点修复 |
| MCP-02 | 认可 | 改用真实 Task 检查未知字段，并分别验证非法 UUID、过长描述、负版本、小数版本和缺参；无 Action 落库。 | V3-01 同节点修复 |
| MCP-03 | 认可 | handler 空参数映射为空 Map；SDK 缺参返回可修正 Tool 错误。 | V3-01 同节点修复 |
| MCP-04 | 认可 | BigDecimal.longValueExact() 拒绝小数与溢出，协议测试覆盖小数版本。 | V3-01 同节点修复 |
| MCP-05 | 部分认可 | 首版明确为当前项目授权 Wiki 标题/正文子串匹配；分页与语义检索超出 V3-01 Scope，规模限制记为后续优化。 | 功能文档已澄清 |
| MCP-06 | 认可 | Java 自动确认入口按 source=CHAT 限制，MCP 即使超过 60 秒仍返回 403；集成测试验证 Action 保持 PENDING 且 Task 不变。 | V3-01 同节点修复 |
| MCP-07 | 认可 | README、路线图、功能索引与变更记录统一为 Review Fixing，复审通过后再统一标为 Implemented。 | 文档已修正 |

> 回填与修复完成后，请在下一轮提供：新增/修改的测试清单、MCP 重试/幂等语义的决定（修复或显式声明），以及 schema 拒绝行为的直接验证证据。下一轮审查将以验证修复与识别修复引入的新问题为主。
