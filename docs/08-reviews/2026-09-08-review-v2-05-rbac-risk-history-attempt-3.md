# Pi 代码审查报告：v2-05-rbac-risk-history / Attempt 3

- 日期：2026-09-08
- 审查阶段：v2-05-rbac-risk-history
- 审查对象：INDEX@f8c0f10（基线：f8c0f10243cc8d02506228167ad9ef8bab0ae21a）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：v2-05-rbac-risk-history / Attempt 3（Round 3/3）

- 日期：2026-09-08
- 审查阶段：v2-05-rbac-risk-history（Milestone Review）
- 审查对象：INDEX@f8c0f10（基线：f8c0f10243cc8d02506228167ad9ef8bab0ae21a）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- Diff 截断：是（首/中/尾采样；完整文件清单与统计见启动器）

---

## 概述与总体结论

本轮为第 3/3 轮复审。Attempt 1 的 8 项发现与 Attempt 2 的 4 项发现在当前 diff 中均已按回填结论落实或有明确不采纳理由：竞态守卫（`requestedProjectId` + `activeProjectId.current` 三方比对，含 `catch`/`finally`）、`resetWorkspaceState` 共享重置（显式登出与 401 路径复用）、SSE `complete` 先交付再隔离持久化（`historyWriteFailureDoesNotSuppressTheCompletedStreamEvent` 佐证）、`UPDATE_WIKI.needApproval=true`、`AgentActionType` 穷尽 switch、codePoint 安全截断及边界测试、`operationForIntent` 移除、项目切换 effect 主动清 `busy`，均可在 diff 中直接核实。后端跨作用域隔离、IDOR 防护、流失败不持久化、HIGH 删除要求 ADMIN 等安全核心逻辑未被本轮修复破坏。

未发现新的“必须修改”项。剩余问题均为文档一致性/测试覆盖缺口类“建议修改”，不构成交付阻断。节点方向、边界与下一个节点（V2-06）的隐患未见越界：本节点未提前实现通用 Approval/Audit/Idempotency，仍沿用 Day 5 确认票据；未实现 V2-07 checkpoint。结论：**通过交付**。

---

## 详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| 1 | 建议修改 | Medium | `docs/03-features/web-workspace.md` | 文末“已知限制”段 | 仍写有“没有…聊天历史服务端持久化；对话历史仅存在于页面内存，切换项目或刷新页面会清空”，与 V2-05 已交付的持久化历史能力直接矛盾，误导读者 |
| 2 | 建议修改 | Low | `services/core-api/src/test/java/com/agentforge/core/conversation/api/ConversationHistoryApiTest.java` | 28-61 | 新增公共端点 `GET .../conversations/{conversationId}` 缺少后端 HTTP seam 契约测试（200 响应字段、跨作用域 404/403），仅覆盖 list 与匿名 401 |
| 3 | 建议修改 | Low | `docs/03-features/security-and-risk.md`、`docs/03-features/conversation-history.md` | 头部状态行 | 两个新功能文档状态仍为 `Proposed`，但已实现并通过真实 PostgreSQL/Core/Web 验证，进入节点审核，状态滞后 |
| 4 | 建议修改 | Low | `docs/05-development/testing-strategy.md` | “## Day 4 质量门槛”附近 | 新增 `## V2-05 质量门槛` 标题插入在 `## Day 4 质量门槛` 标题与 Day 4 正文之间，导致 Day 4 内容被错误归属到 V2-05 标题下 |

---

## 逐个 Issue 展开

### Issue 1 — `web-workspace.md` 已知限制与新能力矛盾（建议修改）

**Severity**: Medium
**File & Line**: `docs/03-features/web-workspace.md`（文末“## 已知限制”段）

**Evidence**:
```md
## 已知限制

当前没有 refresh token、多标签引导同步、复杂路由、分页、自动保存或聊天历史服务端持久化。
对话历史仅存在于当前项目的页面内存中；切换项目或刷新页面会清空。
```

**Description**:
V2-05 已通过 `agent_conversation` / `agent_message` 两表与 Core-owned 写入路径，提供了“同步成功或 SSE complete 后持久化、授权范围内列表与详情、Web 历史抽屉恢复 conversationId”的完整能力；`conversation-history.md`、`core-api.md`、变更记录均明确该能力。本段的“没有聊天历史服务端持久化”“仅存在于页面内存”与实现直接冲突，属于交付文档与实际能力不一致，会给读者（包括后续节点开发者与面试官）传递错误信息。前端 `chatHistory` 内存态在切项目/刷新时清空本身仍然为真，但已可由服务端历史恢复，应改写为准确表述。

**Suggested Fix**:
改为例如：
```md
当前没有 refresh token、多标签引导同步、复杂路由、分页、自动保存或历史会话删除/搜索。
当前页面内存中的对话视图在切换项目或刷新时清空，但已完成问答已持久化到 Core API/PostgreSQL，
可在左侧“历史”抽屉中按当前项目/用户作用域恢复并继续使用原 conversationId。
```

---

### Issue 2 — 详情端点缺后端 HTTP seam 契约测试（建议修改）

**Severity**: Low
**File & Line**: `services/core-api/src/test/java/com/agentforge/core/conversation/api/ConversationHistoryApiTest.java` : 28-61

**Evidence**:
当前测试仅包含：
```java
authenticatedUserListsProjectConversationSummaries()  // GET .../conversations 200 + preview
anonymousHistoryRequestIsRejected()                    // GET .../conversations 未认证 401
```
而 `ConversationHistoryController` 同时暴露 `GET /api/v1/projects/{projectId}/agent/conversations/{conversationId}`，该端点无任何 `@WebMvcTest` 覆盖。

**Description**:
Service 层已有跨作用域负向用例（`detailLookupDoesNotRevealConversationOutsideTheExactScope`）与持久化集成测试，前端 `ApiClient.getConversation` 也有 URL/Bearer 契约测试，核心隔离逻辑有保障。但新增的详情公共端点缺少 HTTP 层契约测试：200 时 `messages` 数组、`role/content/sources/createdAt` 字段形状、`sequence` 排序透传，以及跨 Project/User/Thread 时 404（不泄露他人正文）未被断言。该端点属安全敏感读取端点，且本轮为最后一轮，建议在后续节点（V2-06/V2-09）补上最小契约测试。此缺口不构成本节点交付阻断，归为建议。

**Suggested Fix**:
在 `ConversationHistoryApiTest` 增补：
```java
@Test
void authenticatedUserLoadsConversationDetailInsideBoundScope() throws Exception {
    UUID projectId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    when(service.get(eq(projectId), eq(conversationId), any()))
        .thenReturn(new ConversationDetailView(conversationId, "Q", 2,
            Instant.EPOCH, Instant.EPOCH, List.of(
                new ConversationMessageView(AgentMessageRole.USER, "Q", List.of(), Instant.EPOCH),
                new ConversationMessageView(AgentMessageRole.ASSISTANT, "A", List.of(), Instant.EPOCH))));
    mockMvc.perform(get("/api/v1/projects/{projectId}/agent/conversations/{conversationId}",
            projectId, conversationId).with(jwt()...))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[?(@.role=='USER')].content").value("Q"));
}
```
并在 service mock 返回空/抛 `ResourceNotFoundException` 时断言 404。

---

### Issue 3 — 新功能文档状态仍为 Proposed（建议修改）

**Severity**: Low
**File & Line**: `docs/03-features/security-and-risk.md` : 3；`docs/03-features/conversation-history.md` : 3

**Evidence**:
```md
# Security and Risk
- 状态：Proposed
# 持久化历史会话
- 状态：Proposed
```

**Description**:
两个文档所描述的能力已完成实现并通过 95 项 Core 测试、真实 PostgreSQL/Flyway/JPA 集成、81 项 Agent 测试、Web 34 项测试与真实 uvicorn 契约，且 `docs/07-changes/2026-09-08-v2-05-rbac-risk-history.md` 状态为 `In Review`。功能文档自身停留 `Proposed` 与“已实现并进入节点审核”事实不一致，属文档口径滞后，非功能缺陷。

**Suggested Fix**:
将状态改为与 `docs/07-changes` 一致的 `Implemented`（或本轮审核通过后统一改为 `Implemented`），并保持与 `docs/03-features/README.md` 中“已完成实现并进入节点审核”的表述一致。

---

### Issue 4 — `testing-strategy.md` 标题错位导致 Day 4 内容归属到 V2-05（建议修改）

**Severity**: Low
**File & Line**: `docs/05-development/testing-strategy.md`（“## Day 4 质量门槛 / ## V2-05 质量门槛”附近）

**Evidence**:
```md
## Day 4 质量门槛

## V2-05 质量门槛
- Risk Engine 公共 seam ...
- ...
## 质量门槛
```

**Description**:
V2-05 块被插入在 `## Day 4 质量门槛` 标题之后、Day 4 正文之前，使后续 Day 4 的 pytest/pgvector/clean verify/e2e 门禁内容在结构上被归入 `## V2-05 质量门槛` 标题之下，文档层次错乱。不影响代码正确性，但会误导读者判断各门槛的适用节点。

**Suggested Fix**:
将 `## V2-05 质量门槛` 块移动到 Day 4 正文完整结束后，或调整标题顺序使 Day 4 的标题与其正文相邻（保证 Day 4 标题不再为空、内容不串到 V2-05 门下）。

---

## 无需修改（核实项）

经逐项比对当前 diff 与 Attempt 1/2 回填，以下修复与安全属性均已确认，无需修改：

1. **Attempt 1 Issue #1（历史恢复竞态）已修复**：`openConversation` 捕获 `requestedProjectId`，在 `try` 成功写入前、`catch` 与 `finally` 三处均以 `activeProjectId.current === requestedProjectId` 守卫，迟到详情与迟到错误/`busy` 复位不会写入新项目视图。
2. **Attempt 2 Issue #1（跨账号登出残留）已修复**：新增 `resetWorkspaceState` 统一清空项目、Wiki/Task、历史摘要/详情、聊天、pendingAction 与面板状态；`logout()` 复用并额外清空 `email/password/error`；`report()` 的 401 路径复用同一重置。DOM 用例 `clears conversation state before another user logs in` 红绿验证在 diff 中可见。
3. **Attempt 2 Issue #2（SSE 持久化耦合）已修复**：`stream` 中 `sink.accept(finalized)` 先于持久化执行，且 `historyWriteFailureDoesNotSuppressTheCompletedStreamEvent` 证明持久化异常被隔离、`complete` 事件照常交付（`events` 精确为 `metadata/delta/complete`）。
4. **Attempt 2 Issue #4（busy 残留）已修复**：项目切换 effect 增加 `setBusy(false)`；`openConversation.finally` 带项目守卫。
5. **Attempt 1 Issue #3/#4/#5/#6/#8 均核实通过**：`UPDATE_WIKI.needApproval=true`（`ToolRiskEngine.java`）；`AgentActionService` 对 `AgentActionType` 穷尽 switch；`AgentConversation.start` 用 `offsetByCodePoints` 按 code point 安全截断且 `AgentConversationTest` 以 239 字符 + emoji 边界红绿；`openPanel/openChatTool/leaveChatMode` 均关闭 `historyOpen`；`operationForIntent` 已删除。
6. **后端跨作用域隔离与 IDOR**：`ConversationHistoryService` 以 `findByScope(id, projectId, userId)` / `findAllByScope` 联合查询；`TaskService`/`WikiPageService` 的 update/delete 仍联合 `projectId` 查资源并校验乐观锁版本；`ToolRiskEngine.authorize` 统一 `ProjectAccess.requireAccess` + ADMIN 角色门槛（HIGH 要求 ADMIN）。
7. **Attempt 2 Issue #3（Agent 路径重复 requireAccess）不采纳理由成立**：normalize 前先授权属 fail-closed 防御性边界，保留合理，不重复追责。
8. **边界合规**：未提前实现 V2-06 通用 Approval/Audit/Idempotency 与 V2-07 checkpoint/interrupt/resume；Agent 仍需人工确认票据，V1 边界未引入 V2/V3 外部组件。

---

## 主开发 (Codex) 评估回填区

请 Codex 针对上述发现按行回填处理结论与说明，严禁空行省略或“已修复”式占位；若经分析认定为误报，需给出反驳证据。

| Issue ID | Codex 处理 | 修复/反驳说明 | 验证证据（测试/构建命令与结果） |
| --- | --- | --- | --- |
| 1 | 采纳并修正文档 | 将已知限制改为“页面视图会清空、已完成问答可由 Core/PostgreSQL 历史恢复”，并明确尚无删除/搜索。 | 文档与 `conversation-history.md`、Core API 及已通过 Web 历史恢复测试一致。 |
| 2 | 本节点不追加 | Controller 的 list/匿名认证 seam、Service 精确作用域详情负向用例、Web `getConversation` URL/Bearer 契约及真实持久化集成已经共同覆盖当前风险；Pi 已判定为非阻断覆盖增强，避免在 PASS 后扩大节点实现范围。 | Core `clean verify` 95 tests；Web 34 tests；Pi Attempt 3 PASS。 |
| 3 | 采纳并修正文档 | 两份功能文档状态改为 `Implemented`，与节点完成态一致。 | Roadmap、功能索引与变更记录同步为完成。 |
| 4 | 采纳并修正文档 | 将 V2-05 门槛整体移动到 Day 4 正文结束后，避免标题归属错乱；同时准确表述 HTTP 与应用服务的组合覆盖。 | Markdown 结构人工核对，`git diff --cached --check` 纳入最终门禁。 |
