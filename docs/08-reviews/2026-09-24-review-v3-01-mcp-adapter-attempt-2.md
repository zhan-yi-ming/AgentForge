# Pi 代码审查报告：v3-01-mcp-adapter / Attempt 2

- 日期：2026-09-24
- 审查阶段：v3-01-mcp-adapter
- 审查对象：INDEX@61626ed（基线：61626ed17cc7b7d507053fc319543dc043b6034c）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- 状态：RESOLVED
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# V3-01 MCP Adapter — Milestone 审查报告（第 2 / 3 轮）

- 日期：2026-09-24
- 审查阶段：v3-01-mcp-adapter
- 审查模式：Milestone（只读）
- 审查对象：INDEX@61626ed（基线 `61626ed17cc7b7d507053fc319543dc043b6034c`）
- 审查手段：仅依据本次 Git Diff、文件清单、显式上下文与上一轮报告；未运行任何命令，未改写 Codex 提供的测试结果。

## 一、概述与总体结论

- 本轮为修复验证轮，重点复核 Attempt 1 的 MCP-01～MCP-07，并识别修复引入的新问题。
- 架构判断成立：`McpServerConfiguration` 从 Spring Security `JwtAuthenticationToken` 提取 `AuthenticatedActor`；`McpToolService` 仅调用 `WikiPageService` / `TaskService` / `AgentActionService` / `ToolRiskEngine`，无 Repository/DB 直连；写 Tool 只落 `PENDING` Approval，人工 confirm 才走既有执行链。ADR-0029 与 V3-01 边界总体成立。
- 上一轮 2 项“必须修改”均已闭环：
  - **MCP-01**：新增服务端 `idempotencyKey`、`pg_advisory_xact_lock` 串行化、部分唯一索引 `agent_task_action_mcp_proposal_key_uk`、`matchesMcpProposal` 同意图复用/异意图冲突，并有并发、重放、终态重放测试。
  - **MCP-02**：`projectIsolationAndSchemaValidationReturnSafeToolErrors` 改用真实 `taskId` 注入未知字段，使 `additionalProperties:false` 断言可区分；并分别覆盖非法 UUID、超长描述、负版本、小数版本、缺参。
- 其余低级别项（MCP-03 空参数 NPE、MCP-04 小数截断、MCP-06 自动确认来源隔离、MCP-07 状态一致性）在 diff 中均见对应修复。
- 结论：**通过（PASS）**。未发现新的阻断性安全、权限、并发、数据一致性、契约或方向问题；存在 6 项“建议修改”，均为可读性/文档一致性/测试补强，不阻塞交付。下一轮（若有）应转向独立复现与修复回归确认。

> 说明：下文引用的测试结果（`McpHttpApiTest` 10/10、`mvnw clean verify` 142 tests、Web 72 tests 等）均来自 Codex 变更记录，仅作为审查证据引用，不代表 Pi 执行。

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 无。Attempt 1 的 MCP-01、MCP-02 已修复且证据可区分。 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| MCP2-01 | 低 | `docs/07-changes/2026-09-24-v3-01-mcp-adapter.md`、`docs/03-features/mcp-adapter.md`、`docs/04-api/core-api.md` | 变更记录 50 行 / 功能文档全文 | 变更记录称 `search_wiki` 语义已在功能文档澄清，但两份文档均未写明“标题/正文子串匹配、无分页/结果上限”，文档声明与真实不符，且与内部 `search_wiki` 存在同名不同义漂移 |
| MCP2-02 | 低 | `services/core-api/.../mcp/infrastructure/McpServerConfiguration.java`、`.../agent/application/AgentActionService.java` | Config 116–144 / AgentActionService `normalizeText` | `description` schema 为 `minLength:0`，但服务端对空串抛 `invalidProposal`，存在 schema 与校验不一致 |
| MCP2-03 | 低 | `services/core-api/src/test/java/com/agentforge/core/mcp/api/McpHttpApiTest.java` | `projectIsolationAndSchemaValidationReturnSafeToolErrors` | 跨项目负向隔离仅覆盖 `search_wiki`，未覆盖 `get_task` / `create_task` / `update_task` 的 MCP 入口越权 |
| MCP2-04 | 低 | `services/core-api/.../mcp/application/McpToolService.java` | `failure` / `safeMessage` | 对任意 `IllegalArgumentException` 原样返回 `getMessage()`，对下游 Service 抛出的异常缺少白名单，存在潜在信息泄漏面 |
| MCP2-05 | 低 | `services/core-api/.../mcp/application/McpToolService.java` | `uuid()` | `catch (IllegalArgumentException)` 会把 `requiredString` 的“缺参”消息改写成“must be a valid UUID”，降低错误可诊断性 |
| MCP2-06 | 低 | `docs/02-architecture/system-overview.md` | ASCII 组件图 | `External MCP Client (/mcp)` 被插入 Java→Python 的竖向连接线之间，视觉上暗示 MCP 客户端位于调用链上 |

### 无需修改（已确认成立）

| 项 | 依据 |
| --- | --- |
| 提案幂等键去重 + 并发串行化 | `lockMcpProposal` 使用 `pg_advisory_xact_lock(hashtextextended(...))`；`V11` 建部分唯一索引；`createPendingMcp` 先锁后查再插；测试覆盖并发、重放、异意图冲突 |
| 写 Tool 只创建 PENDING、不执行业务写入 | `AgentTaskAction.pendingMcp` + `AgentActionService.createPendingMcp` 仅落 Action/Audit；测试断言 Task 列表为空 |
| `source` 可信来源与 `conversation/workflow` 约束 | 实体构造函数与 `V11` 双 CHECK 一致；CHAT 回填后置 NOT NULL，向后兼容 |
| MCP Action 禁止低风险超时自动确认 | `approveInternal` 对 `automatic && source != CHAT` 抛 403；测试验证 Action 保持 PENDING 且 Task 不变 |
| 小数/负版本拒绝 | `requiredLong` 改为 `new BigDecimal(number.toString()).longValueExact()`，测试覆盖 `0.5` 与 `-1` |
| 空参数不再 NPE | `arguments(request)` 对 null 归一为 `Map.of()`；缺参测试返回可修正 Tool 错误 |
| JWT/actor 不可被 Tool 参数覆盖 | context 提取仅取 `JwtAuthenticationToken`；`actorUserId` 注入被 schema 拒绝（真实 taskId 对照组可区分） |
| 错误不泄漏凭据/堆栈/资源正文 | `safeMessage` 对 Forbidden/NotFound/Conflict/Unknown 统一脱敏；测试断言不含 `Private Wiki/Private body` |
| Nginx 入口 | Web 与生产 TLS Gateway 均增加精确 `/mcp` 反代，关闭缓冲并复用既有 API 限流 |
| 文档状态一致性 | README、路线图、功能索引、变更记录统一为 “Review Fixing / Milestone Review” |

## 三、逐个 Issue 展开（建议修改）

### MCP2-01（低，建议修改）变更记录声称的 `search_wiki` 文档澄清在交付文档中不可见

**File & Line**：`docs/07-changes/2026-09-24-v3-01-mcp-adapter.md`（Codex 评估回填 MCP-05 行），`docs/03-features/mcp-adapter.md`，`docs/04-api/core-api.md`。

**Evidence**

变更记录回填：

```text
MCP-05 | 部分认可 | 首版明确为当前项目授权 Wiki 标题/正文子串匹配；分页与语义检索超出 V3-01 Scope，规模限制记为后续优化。 | 功能文档已澄清
```

但本次新增/修改的功能与 API 文档只写到：

```markdown
- `search_wiki`：输入 `projectId`、`query`，返回已授权 Wiki 页面摘要列表。
```

以及功能文档“读 Tool 在认证和 ProjectAccess 后返回 DTO”。两处均未出现“子串匹配”“无分页/结果上限”的限定。

**Description**：实现为 `wikiPages.list(...)` 全量加载 + 内存 `contains`，与产品内 Python 侧 `search_wiki`（向量+BM25 检索）同名不同义。MCP 对外是标准协议，外部 Agent 会按工具名推断语义；若文档不显式限定，属于 V3-01 Pi Review 明确点名的 “Schema/Semantic Drift” 面。当前不存在运行时错误，故不阻塞，但变更记录的“已澄清”表述与交付物不一致。

**Suggested Fix**：在 `docs/03-features/mcp-adapter.md` 的“范围与非目标/接口”与 `docs/04-api/core-api.md` 的 `search_wiki` 条目标注：“首版为当前项目已授权 Wiki 标题/正文子串匹配，不保证排序、分页或结果上限，与内部检索语义不同，后续节点再对齐”；同时修正变更记录措辞，避免声明未落地的文档。

### MCP2-02（低，建议修改）`description` schema 与服务端校验不一致

**File & Line**：`McpServerConfiguration.java` 约 116–144（`boundedStringProperty("Task description", 0, 10000)`）；`AgentActionService.java` `normalizeText`。

**Evidence**

```java
private Map<String, Object> boundedStringProperty(String description, int minimumLength, int maximumLength) {
    return Map.of("type", "string", "minLength", minimumLength, "maxLength", maximumLength, "description", description);
}
// create_task / update_task 中 description 以 minLength = 0 声明
```

```java
private String normalizeText(String value, int maximumLength) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
        throw invalidProposal();
    }
    return trimmed;
}
```

**Description**：schema 明确允许 `description: ""`，但服务端对空串（含纯空白）一律判为非法提案，返回 `isError=true`。这是声明契约与实际校验的偏差；虽无安全影响，但会让严格按 schema 生成参数的客户端产生难以解释的失败。

**Suggested Fix**：二选一——将 description 的 schema 改为 `minLength:1`（与 title 一致，缺省时省略字段），或在 `normalizeText` 对 description 允许空串并归一为 `null`。建议前者，避免“空描述”在实体中产生无意义空字段。

### MCP2-03（低，建议修改）MCP 入口跨项目越权测试仅覆盖读工具 `search_wiki`

**File & Line**：`McpHttpApiTest.projectIsolationAndSchemaValidationReturnSafeToolErrors`。

**Evidence**：该用例对 `outsider` 仅调用 `search_wiki` 断言 `Forbidden`；`get_task` 仅用于 actor 注入的 schema 断言；`create_task` / `update_task` 未做跨项目调用。

**Description**：写路径在 `createPendingMcp` 内已通过 `projectAccess.requireAccess(projectId, actor)` 与 `taskService.get(projectId, taskId, actor)` 双重保护，读路径经由既有 Service，实际越权风险已被底层覆盖。但 V3-01 验收把“授权隔离”列为 MCP 层证据，当前缺少 MCP 入口级负向用例，回归保护不完整。

**Suggested Fix**：新增一条测试：`outsider` 分别对 owner 的 `get_task`、`update_task`（携带 owner 的 taskId 与版本）、`create_task` 发起调用，断言 `isError=true`、`structuredContent` 缺失、错误文本不含资源正文，且 `agent_task_action` 无新增行。

### MCP2-04（低，建议修改）`IllegalArgumentException` 消息原样透出存在潜在泄漏面

**File & Line**：`McpToolService.failure` / `safeMessage`。

**Evidence**

```java
private String safeMessage(RuntimeException exception) {
    if (exception instanceof IllegalArgumentException) {
        return exception.getMessage();
    }
    ...
}
```

**Description**：`McpToolService` 自产消息是安全的，但 `wikiPages.list` / `tasks.get` / `AgentActionService` 等下游若抛出携带内部标识、SQL 片段或路径的 `IllegalArgumentException`，会经此原样返回给外部 MCP 客户端。当前可达路径未发现具体泄漏，故不阻塞，但违反“错误不得包含内部细节”的防御式约定。

**Suggested Fix**：对 `IllegalArgumentException` 采用白名单/来源判断（例如由 `McpToolService` 统一包装为固定的参数错误消息），或对下游异常统一降级为 `"The tool arguments are invalid."`，仅保留本类自产校验消息。

### MCP2-05（低，建议修改）`uuid()` 吞并缺参错误导致消息失真

**File & Line**：`McpToolService.uuid`。

**Evidence**

```java
private UUID uuid(Map<String, Object> arguments, String name) {
    try {
        return UUID.fromString(requiredString(arguments, name));
    }
    catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(name + " must be a valid UUID.");
    }
}
```

**Description**：`requiredString` 在参数缺失/非字符串时抛出的“缺失参数”消息会被改写为“必须是合法 UUID”，使客户端无法区分“没传”和“传错格式”。缺参场景已由 `arguments()` 归一化覆盖，不会 NPE，仅影响错误可诊断性。

**Suggested Fix**：

```java
private UUID uuid(Map<String, Object> arguments, String name) {
    String raw = requiredString(arguments, name);
    try {
        return UUID.fromString(raw);
    }
    catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(name + " must be a valid UUID.");
    }
}
```

### MCP2-06（低，建议修改）系统架构 ASCII 图连接关系易误读

**File & Line**：`docs/02-architecture/system-overview.md` 组件图。

**Evidence**

```text
Java Core API (services/core-api) ------> PostgreSQL + pgvector
  ^
  | Streamable HTTP + Bearer JWT
External MCP Client (/mcp)
  | 业务对象、权限、审批、确定性写入
  |
  | internal REST / JSON + NDJSON stream
  v
Python Agent Service (services/agent-service)
```

**Description**：原图中 Java→Python 的竖向连接线在插入 `External MCP Client` 后，视觉上变成 Java→MCP Client→Python，与真实边界（MCP 客户端只调用 Java，Java 再调用 Python）不符。属文档可读性问题。

**Suggested Fix**：把 MCP 支路画成旁挂，例如单独用 `External MCP Client -->|Streamable HTTP + Bearer JWT| Java Core API`，并将 Java→Python 的竖线恢复为不经过 MCP 节点。

## 四、主开发（Codex）评估回填区

| Issue ID | 是否认可 | 处理方式 | 关联提交 / 说明 |
| --- | --- | --- | --- |
| MCP2-01 | 认可 | 功能/API 文档明确子串匹配、无分页和与内部检索的语义差异。 | V3-01 同节点文档修复 |
| MCP2-02 | 认可 | description Schema 改为 minLength:1；先红灯后绿灯，clean verify 通过。 | V3-01 同节点修复 |
| MCP2-03 | 认可，非阻断 | 现有 ProjectAccess 和 MCP 搜索负向测试覆盖权限主路径；其余 Tool 入口负向用例留待安全回归扩展。 | 后续按需补强 |
| MCP2-04 | 认可潜在风险，非阻断 | 当前可达路径未发现敏感信息泄漏；保留后续错误契约加固，不在无复现路径下扩展异常体系。 | 后续按需补强 |
| MCP2-05 | 认可，非阻断 | 错误仍以 isError 返回，影响仅为缺参文字精度；本节点不扩展。 | 后续按需改进 |
| MCP2-06 | 认可 | MCP 客户端改为独立旁路，不再位于 Java 与 Python 之间。 | V3-01 同节点文档修复 |

> 本轮为 PASS。若继续下一轮，请提供：MCP2-01～MCP2-06 的实际处理说明，以及 `McpHttpApiTest` 与 `mvnw clean verify` 的最新原始输出；下一轮将以验证上述建议是否引入回归为主。
