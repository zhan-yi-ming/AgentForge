# Pi 代码审查报告：v2-05-rbac-risk-history / Attempt 1

- 日期：2026-09-08
- 审查阶段：v2-05-rbac-risk-history
- 审查对象：INDEX@f8c0f10（基线：f8c0f10243cc8d02506228167ad9ef8bab0ae21a）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Codex 处理状态：RESOLVED（阻断项已修复并完成相称回归，等待 Attempt 2 复审）
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

## 概述与总体结论

本节点 V2-05 在方向与边界上基本符合路线图：Java 侧建立了集中式 `ToolPolicy/RiskEngine`，直接 Wiki/Task API 与 Agent Intent 两端均接入授权；持久化历史采用 Core-owned 方案并以 `project/user/conversation` 联合作用域读取；Web 提供历史抽屉入口并支持恢复 `conversationId`。后端 RBAC、IDOR 防护（`findByProjectIdAndId` / `findByScope`）、未知 Tool 丢弃与流失败不持久化的核心逻辑正确，V6 迁移约束完整。

但存在两项必须修复的问题：其一，Web 历史恢复存在项目切换竞态，旧项目的会话详情可在 `await` 后写入新项目视图，违反前端隔离契约；其二，Web 全量测试套件不绿（14/17 通过率），虽变更记录解释为 pre-existing UI 测试基线问题，但属于本节点交付质量门禁未闭合，并使其成为 V2-06/V2-09 的明确隐患。

此外存在若干文档/实现不一致、非白名单映射的潜在降级风险与测试覆盖缺口，均列为建议修改。核心 RBAC 与数据隔离逻辑经核实无需修改。最终结论：**需修复后交付**。

---

## 详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| 1 | 必须修改 | High | `apps/web/src/App.tsx` | 167-185 | `openConversation` 缺少 `activeProjectId` 竞态检查，跨项目会话内容泄漏到新项目视图 |
| 2 | 必须修改 | High | Web 测试套件 | — | 全量 `npm test -- --run` 14 passed / 17 failed，测试门禁不绿 |
| 3 | 建议修改 | Medium | `services/core-api/.../security/ToolRiskEngine.java` | 58 | `UPDATE_WIKI` 的 `needApproval=false` 与 `security-and-risk.md` 矩阵 "yes for Agent intent" 不一致 |
| 4 | 建议修改 | Medium | `services/core-api/.../agent/application/AgentActionService.java` | 73-78 | 三元表达式将所有非 `CREATE_TASK` 类型静默映射为 `UPDATE_TASK`，缺乏显式白名单 |
| 5 | 建议修改 | Low | `services/core-api/.../conversation/domain/AgentConversation.java` | 43-47 | `preview` 240 字符截断非 codePoint 安全，边界可能切碎代理对导致 UTF-8 无效序列 / DB 异常 |
| 6 | 建议修改 | Low | `apps/web/src/App.tsx` | 271-285 | `leaveChatMode` 与 `openPanel` 未关闭 `historyOpen`，历史抽屉状态残留 |
| 7 | 建议修改 | Low | 测试文件 | — | 缺少 API 层跨用户拒绝、流式 complete 成功持久化、Web `getConversation` 测试 |
| 8 | 建议修改 | Low | `services/core-api/.../security/ToolRiskEngine.java` | 30-41 | `operationForIntent` 未接入任何生产调用路径，属死代码 |

---

## 逐个 Issue 展开

### Issue 1 — Web 历史恢复的项目切换竞态（必须修改）

**Severity**: High
**File & Line**: `apps/web/src/App.tsx` : 167-185（`openConversation` 函数）

**Evidence**:
```ts
async function openConversation(summary: ConversationSummary) {
    if (!projectId) return;
    setBusy(true); setError("");
    try {
      const detail = await api.getConversation(projectId, summary.conversationId);
      // ↓ 缺少 activeProjectId.current !== projectId 检查
      const items: ChatHistoryItem[] = [];
      for (let index = 0; index < detail.messages.length; index += 2) {
        ...
        if (question?.role === "USER" && answer?.role === "ASSISTANT") {
          items.push({ id: `persisted-${index}`, ... });
        }
      }
      setConversationId(detail.conversationId);
      setChatHistory(items);
      setExpandedChatIds(new Set(items.map((item) => item.id)));
      setPendingAction(undefined);
      setHistoryOpen(false);
      setChatMode(true);
      chatModeRef.current = true;
    } catch (cause) { report(cause); } finally { setBusy(false); }
}
```

对比同文件 `sendMessage` 中多处使用了 `if (activeProjectId.current !== requestedProjectId) return;` 守卫：

```ts
const result = await api.chatStream(projectId, question, conversationId, {
  onMetadata: (metadata) => {
    if (activeProjectId.current !== requestedProjectId) return;
    ...
```

**分析**:
`openConversation` 是异步函数。用户点击"历史"抽屉中的某条记录后，`await api.getConversation(...)` 挂起期间，若用户切换到另一项目，`useEffect` 会执行 `activeProjectId.current = 新 projectId`，清空 `chatHistory` / `conversationId` 并按新项目重载。但旧 `openConversation` 的 `await` 返回后**没有检查当前项目是否仍是发起请求的项目**，直接将旧项目的 `conversationId` 与消息正文写入 `setChatHistory(items)`、`setChatMode(true)`，造成旧项目对话正文显示在新项目视图下。这与 `frontend-architecture.md` 明确约定"切换项目必须取消在途请求、清空旧详情并按新项目重新加载列表"直接冲突，属于前端跨项目数据隔离失败（后端数据隔离仍有效，但用户界面层泄漏）。

**Suggested Fix**:
在 `await` 之后、写入任何状态之前增加竞态守卫：

```ts
async function openConversation(summary: ConversationSummary) {
    if (!projectId) return;
    setBusy(true); setError("");
    try {
      const detail = await api.getConversation(projectId, summary.conversationId);
      if (activeProjectId.current !== projectId) return; // 项目已切换，丢弃迟到详情
      const items: ChatHistoryItem[] = [];
      // ... 原逻辑不变
    } catch (cause) { report(cause); } finally { setBusy(false); }
}
```

注意 `projectId` 是闭包捕获的旧值，`activeProjectId.current` 在项目切换 effect 中已同步为新值，因此比较可正确拦截。

---

### Issue 2 — Web 全量测试门禁不绿（必须修改）

**Severity**: High
**File & Line**: Web 测试套件（`apps/web/tests/app.test.tsx` 等）

**Evidence**（来自 `docs/07-changes/2026-09-08-v2-05-rbac-risk-history.md` 验证结果）:
> Web 全量 `npm test -- --run` 为 14 passed / 17 failed；失败集中在本地 main 已合并的新样式与旧 UI 用例选择器不一致（例如用例查找"新手引导"和默认展开的整理面板，当前 UI 为"产品说明"和按需展开）。V2-05 新增历史用例独立通过；本节点不扩展为无关的 UI 测试基线重写。

**分析**:
17 项 Web 测试失败意味着交付分支的测试套件整体不绿。虽然变更记录将其归因于 `main` 基准中已合并的视觉/交互刷新（"产品说明"替代"新手引导"、整理面板从默认展开改为按需展开）导致旧选择器过时，且本节点新增的历史用例独立通过，但作为当前被审查节点，交付物包含了这份不绿的测试基线。这直接成为 V2-06（后续节点回归依赖）与 V2-09（Regression Release Gate 要求整体通过）的明确隐患——测试基线的持续腐烂会使后续节点无法用测试证明回归安全。测试策略文档新增的 V2-05 质量门槛也要求 Web DOM 覆盖项目切换、选择恢复等行为，而当前套件无法作为可靠门禁。

**Suggested Fix**:
在修复轮以最小改动更新过时测试选择器，使 `npm test -- --run` 回归全绿（不重写功能、不扩展无关范围）：将"新手引导"查询改为"产品说明"、将默认展开的整理面板断言改为按需展开交互，以及其余 17 项中的选择器同步。若暂时无法全绿，至少应在变更记录中明确承认该债务并写入后续回归计划（V2-06 或 V2-09 的验收前置条件）。

---

### Issue 3 — `UPDATE_WIKI` 的 `needApproval` 与文档矩阵不一致（建议修改）

**Severity**: Medium
**File & Line**: `services/core-api/src/main/java/com/agentforge/core/security/ToolRiskEngine.java` : 58

**Evidence**:
```java
policies.put(ToolOperation.UPDATE_WIKI, new ToolMetadata(UserRole.USER, RiskLevel.MEDIUM, false));
```

`docs/03-features/security-and-risk.md` 策略矩阵明确：
| `update_task`, Wiki/Task 普通修改 | MEDIUM | USER | yes for Agent intent |

**分析**:
文档将 "Wiki/Task 普通修改" 的 `need_approval` 描述为 "yes for Agent intent"，但实现中 `UPDATE_WIKI` 的 `needApproval=false`，而 `UPDATE_TASK` 为 `true`。当前因 Python 不产生 Wiki 修改 proposal，`UPDATE_WIKI` 的 `needApproval` 字段不参与任何执行路径，无实际安全影响，但这是新写入的文档与实现之间的契约性不一致，后续 MCP/V3 复用该策略时可能产生误判。应统一为文档语义（或反向修正文档并说明原因）。

**Suggested Fix**:
```java
policies.put(ToolOperation.UPDATE_WIKI, new ToolMetadata(UserRole.USER, RiskLevel.MEDIUM, true));
```
若维持 `false`，则同步修订 `security-and-risk.md` 矩阵，明确声明 Wiki 修改当前无 Agent intent 路径、该字段为预留。

---

### Issue 4 — `createPending` 三元表达式缺少显式白名单映射（建议修改）

**Severity**: Medium
**File & Line**: `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` : 73-78

**Evidence**:
```java
riskEngine.authorize(
        normalized.type() == AgentActionType.CREATE_TASK
                ? ToolOperation.CREATE_TASK
                : ToolOperation.UPDATE_TASK,
        projectId,
        actor);
```

**分析**:
这里采用"非 `CREATE_TASK` 即 `UPDATE_TASK`"的三元表达式，而非显式 switch / 白名单映射。当前 `AgentActionType` 枚举仅含 `CREATE_TASK` 与 `UPDATE_TASK`（Web 类型亦为 `"CREATE_TASK" | "UPDATE_TASK"`），因此功能正确。但该写法使未来新增 `DELETE_TASK` 等 AgentActionType 时会被静默降级为 `UPDATE_TASK`（MEDIUM + needApproval），违背"未知 Tool Intent 被丢弃"的安全原则，且与 `ToolRiskEngine.operationForIntent` 的名称驱动机制不一致。作为集中式安全策略入口，应使用穷尽性映射避免静默降级。

**Suggested Fix**:
```java
ToolOperation operation = switch (normalized.type()) {
    case CREATE_TASK -> ToolOperation.CREATE_TASK;
    case UPDATE_TASK -> ToolOperation.UPDATE_TASK;
    default -> throw new IllegalArgumentException("Unsupported tool intent.");
};
riskEngine.authorize(operation, projectId, actor);
```

---

### Issue 5 — `preview` 截断非 codePoint 安全（建议修改）

**Severity**: Low
**File & Line**: `services/core-api/src/main/java/com/agentforge/core/conversation/domain/AgentConversation.java` : 43-47

**Evidence**:
```java
public static AgentConversation start(UUID id, UUID projectId, UUID userId, String question, Instant now) {
    String normalized = question.strip();
    return new AgentConversation(id, projectId, userId,
            normalized.substring(0, Math.min(normalized.length(), 240)), now);
}
```

**分析**:
`String.substring` 按 UTF-16 code unit 截断。当用户消息恰好在前 240 个 code unit 处出现 emoji（代理对）时，`substring` 会从代理对中间切开，产生孤立的高代理项；在写入 PostgreSQL 的 UTF-8 `VARCHAR(240)` 列时可能触发 invalid byte sequence 异常，导致 `appendCompletedExchange` 抛出 `DataIntegrityViolationException`，进而使整个 exchange 持久化失败。触发条件窄（240 边界 + 多字节字符），但属于真实的边界条件 bug。

**Suggested Fix**:
使用 code point 安全截断：
```java
static String truncateToCodePoints(String value, int maxLength) {
    int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), maxLength));
    return value.substring(0, end);
}
// preview = truncateToCodePoints(normalized, 240);
```
同时可给 `preview` 增加 DB 或应用层长度校验作为兜底。

---

### Issue 6 — `leaveChatMode` / `openPanel` 未关闭 `historyOpen`（建议修改）

**Severity**: Low
**File & Line**: `apps/web/src/App.tsx` : 271-285

**Evidence**:
```ts
function leaveChatMode() {
    chatModeRef.current = false;
    setChatMode(false);
    setActivePanel(null);
    setChatExpanded(true);
    setProjectsOpen(false);
    setTasksOpen(false);
    // 缺少 setHistoryOpen(false)
}

function openPanel(panel: WorkspacePanel) {
    setActivePanel(panel);
    setChatMode(false);
    setChatExpanded(false);
    setProjectsOpen(false);
    setTasksOpen(false);
    // 缺少 setHistoryOpen(false)
}
```

对比同一文件中 `openChatTool` 与 rail 按钮均已显式 `setHistoryOpen(false)`。

**分析**:
历史抽屉的渲染条件是独立的 `{historyOpen && <aside className="floating-drawer history-drawer">...}`。若用户在会话模式（`chatMode`）下打开历史抽屉后点击左上角"返回首页"，`leaveChatMode` 不关闭 `historyOpen`，抽屉继续显示，形成状态残留。虽然不影响数据安全，但与项目切换 / 工作台切换的状态清理约定不一致。

**Suggested Fix**:
在 `leaveChatMode` 与 `openPanel` 中补上 `setHistoryOpen(false);`，与 `openChatTool` 保持一致。

---

### Issue 7 — 测试覆盖缺口（建议修改）

**Severity**: Low
**File & Line**: `ConversationHistoryApiTest.java` / `AgentChatServiceTest.java` / `apps/web/tests/api.test.ts`

**Evidence**:
- `ConversationHistoryApiTest` 仅有 list happy path 与 anonymous 401，未覆盖详情 200/404、跨 User/Thread 拒绝的 HTTP seam。
- `AgentChatServiceTest` 覆盖了 `failedStreamDoesNotPersistPartialAnswer`（异常分支），但默认的 `streamAuthorizesAndConsumesQuotaBeforeForwardingOrderedEvents` 使用四参构造器（`conversationHistory=null`），**未验证流式 `complete` 成功时确实调用了 `appendCompletedExchange`**。
- `apps/web/tests/api.test.ts` 仅新增 `listConversations`，未覆盖 `getConversation` 路径与错误透传。

**分析**:
后端 Service 层已测跨 scope 拒绝（`existingConversationCannotBeReboundToAnotherProjectOrUser`、`detailLookupDoesNotRevealConversationOutsideTheExactScope`），核心数据隔离逻辑有保障。但测试策略要求"通过公共 HTTP seam 验证跨 Project/User/Thread 负向隔离"，当前 API 层未覆盖该 seam；流式成功持久化是新增核心分支，缺少正向断言。建议补齐上述缺口，尤其覆盖 Issue #1 的"选择历史后切换项目"竞态 DOM 场景。

**Suggested Fix**:
- `ConversationHistoryApiTest` 增加：详情 200、详情跨用户 404/403、list 不返回他人会话。
- `AgentChatServiceTest` 增加带 `ConversationHistoryService` 的流式成功测试，verify `appendCompletedExchange` 被调用且参数正确。
- `apps/web/tests/api.test.ts` 增加 `getConversation` 构造 URL 与 Bearer header 断言。
- `apps/web/tests/app.test.tsx` 增加历史恢复后切换项目的竞态取消测试（数据不得跨项目显示）。

---

### Issue 8 — `operationForIntent` 为未接入生产路径的死代码（建议修改）

**Severity**: Low
**File & Line**: `services/core-api/src/main/java/com/agentforge/core/security/ToolRiskEngine.java` : 30-41

**Evidence**:
```java
public Optional<ToolOperation> operationForIntent(String toolName) {
    if (toolName == null) {
        return Optional.empty();
    }
    try {
        return Optional.of(ToolOperation.valueOf(toolName.trim().toUpperCase(Locale.ROOT)));
    }
    catch (IllegalArgumentException exception) {
        return Optional.empty();
    }
}
```

**分析**:
该方法是"以服务端 Tool 名称查询固定策略"的入口，但 `AgentActionService.createPending` 实际使用 `AgentActionType` 三元表达式直接映射，从未调用 `operationForIntent`。方法仅在 `ToolRiskEngineTest.resolvesIntentByServerToolNameAndRejectsUnknownNames` 中被动断言，生产路径未使用。这既造成设计意图（名称驱动白名单）与实现（类型驱动三元映射）的割裂，也留有死代码。应明确其用途或移除，避免后续维护者误以为该入口已生效。

**Suggested Fix**:
将 `createPending` 改为基于服务端 tool 名称调用 `operationForIntent`（配合 Issue #4 的显式映射），或将 `operationForIntent` 标记为 V2-06 / V3 MCP 预留并在方法注释中说明，或直接删除并同步调整测试。

---

## 无需修改（核实项）

经核实，以下实现符合设计意图与安全要求，无需修改：

1. **后端跨 scope 隔离**：`ConversationHistoryService.get` 使用 `findByScope(conversationId, projectId, actor.userId())` 联合查询，跨 Project/User/Thread 不返回正文；`appendCompletedExchange` 对已存在 conversation 校验 `belongsTo` 后抛 `ForbiddenException`，无法通过伪造 `conversationId` 注入他人历史。
2. **直接 API 的 RBAC/IDOR**：`TaskService` / `WikiPageService` 以 `riskEngine.authorize` 统一经过 `ProjectAccess`，并在 `find(projectId, resourceId)` 中联合项目 ID 查询，删除要求 ADMIN（HIGH）且校验乐观锁版本。直接 API 不伪造 Agent approval 的行为符合文档。
3. **流失败不持久化半截 assistant**：`AgentChatService.stream` 仅在收到 `complete` 且 `finalizeEvent` 未抛异常时才持久化；`error` 事件或下游异常不触发 `persist`，`failedStreamDoesNotPersistPartialAnswer` 已覆盖。

---

## 主开发 (Codex) 评估回填区

请 Codex 针对上述发现按行回填处理结论与说明，严禁空行省略或"已修复"式占位；若经分析认定为误报，需给出反驳证据。

| Issue ID | Codex 处理 | 修复/反驳说明 | 验证证据（测试/构建命令与结果） |
| --- | --- | --- | --- |
| 1 | 采纳 | 捕获请求 projectId；详情返回后与 activeProjectId 比对，迟到响应和错误不写入新项目视图。 | 新增 DOM 竞态用例先红后绿；Web 全量 33/33。 |
| 2 | 采纳 | 按当前 UI 更新过时选择器、面板打开步骤与异步资源加载断言。 | `npm test -- --run --reporter=dot`：3 files / 33 tests passed；`npm run build` 退出 0。 |
| 3 | 采纳 | `UPDATE_WIKI.needApproval` 改为 true，与 MEDIUM Agent intent 文档一致；直接 API 仍不伪造 approval。 | `ToolRiskEngineTest` 先红后绿；Core clean verify 通过。 |
| 4 | 采纳 | 改为对 `AgentActionType` 的穷尽 switch，未来枚举扩展必须显式选择策略。 | Java 21 编译与 Core clean verify 通过。 |
| 5 | 采纳 | preview 按 Unicode code point 安全截断 240 字符，不产生孤立代理项。 | `AgentConversationTest` 以 239 ASCII + emoji 边界先红后绿。 |
| 6 | 采纳 | `openPanel`、`openChatTool`、`leaveChatMode` 均关闭历史抽屉。 | Web 全量 33/33。 |
| 7 | 部分采纳 | 新增跨项目迟到详情 DOM、Web `getConversation` URL/Bearer、流式 complete 持久化与 Unicode 覆盖。Service 层已有跨 scope 负向用例，Controller 不重复 mock Service 内部授权。 | Web 33/33；Core 94 tests，0 failures/errors，7 conditional skips。 |
| 8 | 采纳 | 删除无生产调用的 `operationForIntent`；proposal 由 `AgentActionType.valueOf` 白名单拒绝未知值，再经穷尽 switch 映射。 | `AgentActionServiceTest` 与 Core clean verify 通过。 |

**下一轮重点**：验证 Issue #1 竞态修复是否引入新问题、Issue #2 测试基线是否回归全绿，以及 Issue #3/#4 的策略映射统一是否保持 RBAC 穷尽性。
