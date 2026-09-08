# Pi 代码审查报告：v2-05-rbac-risk-history / Attempt 2

- 日期：2026-09-08
- 审查阶段：v2-05-rbac-risk-history
- 审查对象：INDEX@f8c0f10（基线：f8c0f10243cc8d02506228167ad9ef8bab0ae21a）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Codex 处理状态：RESOLVED（阻断项和采纳建议均已修复并验证，等待 Attempt 3 复审）
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# Pi 代码审查报告：v2-05-rbac-risk-history / Attempt 2（Round 2/3）

- 日期：2026-09-08
- 审查阶段：v2-05-rbac-risk-history（Milestone Review）
- 审查对象：INDEX@f8c0f10
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- Diff 截断：是（首/中/尾采样；完整文件清单与统计见启动器）

---

## 概述与总体结论

Attempt 1 的 8 项发现中，Issue #1（历史恢复项目切换竞态）、#2（Web 全量门禁）、#3（UPDATE_WIKI approval 契约）、#4（三元表达式静默降级）、#5（Unicode 截断）、#6（抽屉状态残留）、#8（`operationForIntent` 死代码）在本轮 diff 中均可见到正确对应的修复，且新增了竞态 DOM 用例、流式成功持久化断言与 Unicode 边界用例；后端跨作用域隔离、IDOR 防护、流失败不持久化等核心逻辑未被本轮修复破坏。

但本轮识别到一项新的必须修改问题：`logout()` 未清空本节点新增的 `conversationSummaries` / `historyOpen` / `conversationId` / `chatHistory`，且 `listProjects` 的 `setProjectId(current => current || ...)` 使旧 `projectId` 跨账号残留。同一标签页登出后以另一账号登录时，上一用户受项目作用域保护的历史摘要与聊天正文会先在 UI 中渲染，违反本节点“历史读取不能跨用户访问”的验收目标。另有持久化失败与主链路耦合、重复授权与 `busy` 残留三个非阻断建议项。

---

## 详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| 1 | 必须修改 | High | `apps/web/src/App.tsx` | 262-272（`logout`） | 登出不清空会话状态，`projectId` 残留，新账号登录后 UI 先渲染上一用户的历史摘要/正文，跨用户视图泄漏 |
| 2 | 建议修改 | Medium | `services/core-api/.../agent/application/AgentChatService.java` | 96-110（`stream`） | 在 `complete` 上先 `persist` 再 `sink.accept`，历史写入失败会吞掉已完整到达的回答，辅助功能影响主链路交付 |
| 3 | 建议修改 | Low | `services/core-api/.../agent/application/AgentActionService.java` | 55-66 | `projectAccess.requireAccess` 与 `riskEngine.authorize` 内再次 `requireAccess` 重复授权 |
| 4 | 建议修改 | Low | `apps/web/src/App.tsx` | 167-185（`openConversation`） | `finally { setBusy(false); }` 无项目守卫：请求挂起时切项目后 `busy` 长期为 true，且迟到 `finally` 可能清除新操作的 busy |

---

## 逐个 Issue 展开

### Issue 1 — 登出未清空会话状态，跨账号重新登录泄露上一用户历史（必须修改）

**Severity**: High
**File & Line**: `apps/web/src/App.tsx` : 262-272（`logout`）

**Evidence**:
```ts
  function logout() {
    sessionStorage.removeItem(TOKEN_KEY);
    setAuthenticated(false);
    setProjectsOpen(false);
    setTasksOpen(false);
    setActivePanel(null);
    setChatMode(false);
    chatModeRef.current = false;
    setChatExpanded(true);
    setUnreadChat(false);
    // 缺少:
    // setConversationSummaries([]);
    // setHistoryOpen(false);
    // setConversationId(undefined);
    // setChatHistory([]);
    // setPendingAction(undefined);
    // setProjectId("");
    // setProjects([]);
  }
```

配合项目加载 effect 中的保留逻辑：
```ts
      setProjects(items);
      setProjectId((current) => current || items[0]?.id || "");
```

**Description**:
本节点新增的 `conversationSummaries`、`historyOpen`、`conversationId` 与既有的 `chatHistory`、`pendingAction` 在 `logout()` 中均未清空，且 `projectId` 未被重置。`App` 组件在登出/登录间不卸载，React state 全程保留。登出后进入登录页时这些状态不渲染；但当另一账号在同一标签页登录成功后，`authenticated` 变为 `true`：
1. `historyOpen` 若仍为 `true`，历史抽屉立即渲染 `conversationSummaries`（上一用户的会话预览、消息数）；
2. `chatHistory` 若非空，主内容区渲染上一用户的问答正文；
3. `listProjects` 回调中 `setProjectId(current => current || items[0]?.id || "")` 因 `current` 仍是旧值而**不替换** `projectId`，故依赖 `projectId` 的清空 effect 不会重跑，状态得不到清理。

后端 `findByScope`/`findAllByScope` 的 RBAC 仍然有效（新用户点击详情会 404/403），但摘要视图已经被渲染，构成前端跨用户数据暴露。`report()` 的 401 会话失效路径同样只 `setAuthenticated(false)`，未清空上述状态，问题一致。这直接违反本节点验收标准“历史会话列表与详情只能返回当前授权作用域内的数据，不能跨项目或跨用户访问”。

**Suggested Fix**:
在 `logout()` 中完整重置项目与会话状态（并建议在 401 会话失效路径复用同一清理函数）：
```ts
function resetWorkspaceState() {
  setProjects([]);
  setProjectId("");
  setConversationSummaries([]);
  setHistoryOpen(false);
  setConversationId(undefined);
  setChatHistory([]);
  setExpandedChatIds(new Set());
  setPendingAction(undefined);
  setError("");
  setBusy(false);
  setChatMode(false);
  chatModeRef.current = false;
  setWikiPages([]);
  setTasks([]);
}

function logout() {
  sessionStorage.removeItem(TOKEN_KEY);
  setAuthenticated(false);
  resetWorkspaceState();
  // 其余 UI 状态复位
}
```
同步新增 DOM 用例：登录 A 查看历史 → 登出 → 登录 B → 断言既不展示 A 的会话摘要，也不展示 A 的聊天正文。

---

### Issue 2 — SSE `complete` 上先持久化再转发，历史写入失败会丢弃已完整到达的回答（建议修改）

**Severity**: Medium
**File & Line**: `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java` : 96-110（`stream`）

**Evidence**:
```java
AgentStreamEvent finalized = finalizeEvent(command, effectiveConversationId.get(), event);
if ("complete".equals(finalized.type())) {
    persist(command, new AgentChatResult(effectiveConversationId.get(), answer.toString(),
            command.requestId(), sources.get()));
}
sink.accept(finalized);
```

**Description**:
`persist` 发生在 `sink.accept(finalized)` 之前。若 `appendCompletedExchange` 抛异常（如 PostgreSQL 瞬时不可用、序列化失败），`complete` 事件不会到达 sink，浏览器端因未收到 `complete` 而按 503 “stream ended unexpectedly” 处理，**丢弃已经通过多个 `delta` 完整送达的回答**。按本节点设计，“历史表服务于展示与继续使用 conversationId”，是辅助能力；主链路（流式回答交付）不应因历史写入失败而失败。同步 `chat()` 路径同样耦合，但同步路径本就在一个 transaction/调用内完成，语义一致；SSE 路径的回答已经对用户可见，失败体验更明显。

**Suggested Fix**:
将 `sink.accept(finalized)` 前移，或对 `persist` 做隔离容错，保证 `complete` 永远送达：
```java
AgentStreamEvent finalized = finalizeEvent(...);
sink.accept(finalized);
if ("complete".equals(finalized.type())) {
    try {
        persist(...);
    } catch (RuntimeException exception) {
        log.warn("Failed to persist completed exchange: {}", exception.getMessage());
        // 历史丢失但不阻断已完成的聊天响应
    }
}
```
如担心吞掉真实 DB 故障，至少应在 `sink.accept` 之后再持久化，避免回答交付被辅助功能劫持。

---

### Issue 3 — Agent Action 路径重复执行 ProjectAccess（建议修改）

**Severity**: Low
**File & Line**: `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` : 55-66

**Evidence**:
```java
    projectAccess.requireAccess(projectId, actor);
    NormalizedProposal normalized;
    try {
        normalized = normalize(proposal);
    }
    catch (IllegalArgumentException exception) {
        return Optional.empty();
    }
    ToolOperation operation = switch (normalized.type()) { ... };
    riskEngine.authorize(operation, projectId, actor);  // 内部再次 projectAccess.requireAccess
```

**Description**:
`createPending` 先显式调用 `projectAccess.requireAccess`，随后 `riskEngine.authorize` 内部又调用一次。功能正确但产生重复的授权查询（owner 查询/DB 往返），且两条调用路径的语义容易让后续维护者误以为需要双重校验。TaskService/WikiPageService 已完全收敛为只经 `riskEngine.authorize`，Agent 路径保持一致更清晰。

**Suggested Fix**:
删除方法顶部的 `projectAccess.requireAccess(projectId, actor);`，统一由 `riskEngine.authorize` 执行 ProjectAccess（`authorize` 首行已含 `requireAccess`）。若担心 `normalize` 抛异常前就应授权，可将 `requireAccess` 保留但去掉 `authorize` 内的重复，二者择一即可。

---

### Issue 4 — `openConversation` 的 `finally` 无守卫，挂起请求导致 `busy` 残留（建议修改）

**Severity**: Low
**File & Line**: `apps/web/src/App.tsx` : 167-185（`openConversation`）

**Evidence**:
```ts
  async function openConversation(summary: ConversationSummary) {
    if (!projectId) return;
    const requestedProjectId = projectId;
    setBusy(true); setError("");
    try {
      const detail = await api.getConversation(requestedProjectId, summary.conversationId);
      if (activeProjectId.current !== requestedProjectId) return;
      // ...
    } catch (cause) {
      if (activeProjectId.current === requestedProjectId) report(cause);
    } finally { setBusy(false); }
  }
```

**Description**:
Attempt 1 修复正确拦截了迟到详情写入，但 `finally { setBusy(false); }` 无守卫。`openConversation` 在 `await` 前已 `setBusy(true)`；若用户此时切换项目，项目切换 effect 的清空列表不含 `setBusy(false)`。若该请求长时间挂起（网络黑洞），`busy` 会长期为 `true`，禁用保存/发送/确认等按钮；且挂起请求最终结束后，其 `finally` 会在新视图里无条件 `setBusy(false)`，可能清除另一新近操作刚设置的 `busy`。

**Suggested Fix**:
与 `catch` 一致增加守卫，或在项目切换 effect 中复位 `busy`：
```ts
    } finally {
      if (activeProjectId.current === requestedProjectId) setBusy(false);
    }
```
同时在项目切换 effect 的清空列表中补 `setBusy(false)`，避免任何在途 `await` 之外的 busy 悬挂。

---

## 无需修改（核实项）

1. **Attempt 1 修复逐项核实通过**：Issue #1 竞态守卫（`requestedProjectId` + `activeProjectId.current` 比对，含 `catch` 分支守卫）、#3 `UPDATE_WIKI` 改为 `needApproval=true`、#4 穷尽 `switch`、#5 codePoint 安全截断及边界测试、#6 `openPanel/openChatTool/leaveChatMode` 均关闭 `historyOpen`、#8 删除 `operationForIntent` 并改为 `AgentActionType` 驱动，均已按回填说明落实。
2. **后端跨作用域隔离**：`findByScope` / `findAllByScope` 联合 projectId+userId 查询，跨 Project/User/Thread 不返回正文；`appendCompletedExchange` 对已存在但 `belongsTo` 不符的会话抛 `ForbiddenException`。
3. **RBAC 策略穷尽性**：`ToolRiskEngine.POLICIES` 覆盖全部 8 个 `ToolOperation`，`Map.copyOf` 保证不可变；HIGH（DELETE）要求 ADMIN 且先经 `requireAccess`。
4. **乐观锁与 IDOR**：`TaskService`/`WikiPageService` 的 update/delete 仍校验版本并联合 projectId 查资源；新增 `riskEngine.authorize` 不削弱既有校验。
5. **流失败不持久化**：`failedStreamDoesNotPersistPartialAnswer` 与 `chatPersistsOnlyTheCompletedServerResult` 覆盖同步成功与半截失败分支；`stream` 仅在 `complete` 时累积持久化。

---

## 主开发 (Codex) 评估回填区

请 Codex 针对上述发现按行回填处理结论与说明，严禁空行省略或“已修复”式占位；若经分析认定为误报，需给出反驳证据。

| Issue ID | Codex 处理 | 修复/反驳说明 | 验证证据（测试/构建命令与结果） |
| --- | --- | --- | --- |
| 1 | 采纳并修复 | 提取 `resetWorkspaceState`，统一清空项目、Wiki、Task、历史摘要/详情、聊天、待确认动作和面板状态；显式登出与 API 401 失效路径均复用该重置，消除跨账号残留。 | 新增“用户 A 查看历史后登出、用户 B 登录”DOM 用例；修复前可见 A 的秘密正文，修复后通过。Web 全量 `3 files / 34 tests passed`。 |
| 2 | 采纳并修复 | SSE 完成事件先交付 sink，再尝试持久化；历史写入的 `RuntimeException` 被隔离并仅记录 requestId 与异常类型，已完成回答不再被辅助存储故障吞掉。 | 新增持久化失败仍收到 `complete` 的单元测试；修复前红灯、修复后通过。Core `clean verify`：95 tests，0 failures/errors。 |
| 3 | 不采纳 | 两次检查处于不同信任边界：首次检查保证在解析/规范化不可信 proposal 前完成项目授权，避免未授权的非法 proposal 通过错误时序泄露行为差异；随后 `riskEngine.authorize` 将已识别 Tool 操作绑定到集中策略。保留该 defense-in-depth 顺序，不以一次查询优化削弱 fail-closed seam。 | `AgentActionServiceTest` 与 Core `clean verify` 通过；策略测试覆盖所有 ToolOperation 及角色/审批语义。 |
| 4 | 采纳并修复 | 项目切换 effect 主动清除 `busy`；`openConversation.finally` 仅在仍处于原项目时清除 `busy`，防止旧请求覆盖新项目操作状态。 | 跨项目迟到详情 DOM 用例与 Web 全量 `3 files / 34 tests passed`。 |

**下一轮重点**：验证 Issue #1 登出清理与新增 DOM 场景是否覆盖 401 会话失效同路径；确认 Issue #2 调整后 SSE `complete` 送达与时序不被破坏；回归 Core `clean verify`、Web 全量与 Java→Python 契约门禁。
