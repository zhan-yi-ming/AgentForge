# Pi 代码审查报告：main-review-r17 / Attempt 2

- 日期：2026-10-06
- 审查阶段：main-review-r17
- 审查对象：INDEX@2a6379d（基线：2a6379d9959982cad67f91c3a554b5dab6e1e3b7）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒
- 处置状态：RESOLVED（PASS，无阻断项）

---

REVIEW_RESULT: PASS

# R17 Web 异步响应作用域隔离 · Diff Review 报告（Attempt 2）

## 一、概述与总体结论

- 审查阶段：main-review-r17 / Diff / 第 2 轮
- 审查范围：`apps/web/src/App.tsx`、`apps/web/tests/app.test.tsx`、`docs/03-features/web-workspace.md`、`docs/07-changes/2026-10-04-r17-web-async-scope.md`、`docs/07-changes/README.md`、`docs/08-reviews/2026-10-06-review-main-review-r17-attempt-1.md`
- 总体结论：**通过（PASS，可交付）**
- 核心判断：
  1. Attempt 1 的 R17-01 已修复：新 project generation 初始化时显式中止并释放旧 `streamAbort`/`formatAbort`，并复位 `streaming`，切项目后发送按钮不再永久禁用。
  2. R17-02 已修复：同一位置复位 `deleteBusy`，切换项目后历史删除按钮恢复可用。
  3. R17-03 已补测试：新增“切换项目中止 Chat 流后控件恢复”“删除进行中切换项目后删除按钮恢复”两例，先红灯后绿灯，证据与文档一致。
  4. workspace generation + token + projectId 的作用域机制在 A→B→A（同 projectId）、注销重登录、旧 Action 收口新 pending 等路径上自洽，未发现新引入的必须修改问题。
- 未发现“必须修改”项；仅有 2 项非阻塞“建议修改”，不触发 NEEDS_FIX。

---

## 二、详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|------|----------|------|-----------|----------|
| R17-04 | 建议修改 | Low | apps/web/src/App.tsx | ≈L187-L200、≈L251-L258 | 项目加载与会话恢复路径调用 `showFirstRecoverable` 时未传 `replacingActionId`，理论上可在同一 generation 内用较早的恢复结果覆盖较新的 pending action |
| R17-05 | 建议修改 | Low | apps/web/tests/app.test.tsx | 新增用例组 | 缺少“旧 Chat 流被中止后仍迟到成功返回”的断言（现有用例覆盖的是 abort/拒绝路径与格式化 A→B→A，未断言 sendChat 成功结果不回写） |

---

## 三、Issue 逐个展开

### R17-04（建议修改 / Low）恢复查询未参与 pending action 所有权校验

- Severity：Low（并发窗口很窄，无阻塞性证据）
- File & Line：`apps/web/src/App.tsx` `showFirstRecoverable`（≈L187-L200）、projectId effect 的恢复查询（≈L251-L258）

**Evidence**

```tsx
const showFirstRecoverable = useCallback((recoverableActions: RecoverableAgentAction[], replacingActionId?: string) => {
  const current = pendingActionRef.current;
  if (replacingActionId && current && current.id !== replacingActionId) return;
  const recovered = recoverableActions[0];
  commitPendingAction(recovered ? { ...recovered.action, source: recovered.source, recovered: true } : undefined);
  if (recovered?.decisionKey) decisionKeys.current.set(recovered.action.id, recovered.decisionKey);
}, [commitPendingAction]);
```

```tsx
// projectId effect
api.listRecoverableActions(projectId).then((recoverableActions) => {
  const latestRoute = parseRoute(window.location.pathname);
  if (active && isWorkspaceScopeCurrent(scope) && (latestRoute.page !== "chat" || !latestRoute.conversationId)) {
    showFirstRecoverable(recoverableActions);   // ← 未传 replacingActionId
  }
}).catch(...);
```

**Description**

- `replacingActionId` 机制目前只被 `decideAction` 的成功/失败收口路径使用，用于防止“旧 Action 的收口结果清除更新的 pending action”。
- 项目加载时的恢复查询仍无此守卫：若该 GET 在用户于同一 generation 内先取得一个更新的 pending action 之后才返回，`showFirstRecoverable` 会无条件用较早的恢复列表覆盖 `pendingActionRef`。
- 可达性很低：`openConversation` 在恢复查询完成前 `busy` 保持 true，会禁用发送，故会话恢复路径基本不可达；项目加载路径 `busy` 为 false，但需要“用户发 Chat 且 LLM 先于一次简单 GET 返回 pending action”，实际概率极小。
- 因此列为建议，不阻塞交付。但这与本次文档声明的“旧结果不能覆盖较新 pending action”契约存在一致性缺口，建议顺手收口。

**Suggested Fix**

在加载路径也复用所有权守卫，例如仅在当前没有更新 pending action 时才写入：

```tsx
const recovered = recoverableActions[0];
// 若期间已产生更新的 pending action，则放弃本次恢复结果
if (pendingActionRef.current && recovered && pendingActionRef.current.id !== recovered.action.id) return;
```

或在 effect 内记录发起恢复查询时的既有 pendingAction id，并作为 `replacingActionId` 传入 `showFirstRecoverable`。

---

### R17-05（建议修改 / Low）缺少“旧 Chat 流迟到成功返回”的回归断言

- Severity：Low（测试覆盖缺口，非产品缺陷）
- File & Line：`apps/web/tests/app.test.tsx` 新增作用域用例组（≈L1115-L1340）

**Evidence**

- 新增“restores chat controls when switching projects aborts an active stream”用例中，`chatStream` 在 abort 时直接 reject：

```tsx
return new Promise<AgentChat>((_resolve, reject) => {
  signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
});
```

- 新增“ignores an older formatting stream after returning to the same project”覆盖了格式化流的“迟到成功不回写”，但 Chat 流仅覆盖了 abort/异常路径。
- `sendChat` 中结果写入依赖的守卫是：

```tsx
const isCurrentChat = () => streamAbort.current === controller &&
  !controller.signal.aborted && isWorkspaceScopeCurrent(scope);
...
if (!isCurrentChat()) return;
setConversationId(result.conversationId);
setChatHistory(...); commitPendingAction(result.pendingAction);
```

**Description**

切换项目时 effect 会把 `streamAbort.current` 置为 `undefined`，因此即便底层请求忽略 abort 而成功 resolve，`isCurrentChat()` 也会返回 false，迟到结果不会写入 `conversationId`/`chatHistory`/`pendingAction`。逻辑上正确，但没有自动化断言锁定这一分支；现有对 Chat 的用例只验证了 abort→复位控件，未验证“迟到成功”。属于较低风险的测试补强建议。

**Suggested Fix**

增加一例：`chatStream` 返回一个不响应 abort、可手动 resolve 的挂起 Promise；发送后切换到 Second Project，再 `resolve` 旧请求，断言新项目 `chatHistory` 不含旧回答、`pendingAction` 不被写入、发送按钮保持可用。

---

## 四、无需修改（确认项）

| ID | 项目 | 结论 |
|----|------|------|
| N-01 | R17-01 修复：新 generation 初始化中止并释放旧 controller（`streamAbort.current = undefined` / `formatAbort.current = undefined`）并 `setStreaming(false)` | 有效；旧 `finally` 的 `streamAbort.current === controller` 因已置空而跳过，不再造成“生成中…”永久禁用；新增用例覆盖 |
| N-02 | R17-02 修复：同位置 `setDeleteBusy(false)` | 有效；切换项目后新历史删除按钮恢复可用，新增用例覆盖 |
| N-03 | `workspaceGeneration` + token + projectId 作用域与 `advanceWorkspaceScope` 同步推进 | 正确；`chooseProject` 点击即推进代际，effect 中 `activeProjectId.current !== projectId` 才补推进，无重复递增、无死循环 |
| N-04 | A→B→A（相同 projectId）场景 | 每次项目选择推进 generation，旧代际迟到结果全部丢弃；`saveWiki`/`decideAction` 另有 operation sequence 与 controller identity，测试覆盖到位 |
| N-05 | 注销/重登录与 401 路径 | `resetWorkspaceState` → `advanceWorkspaceScope("")`，token 也纳入 scope 比较，旧用户迟到结果无法写入；测试覆盖 |
| N-06 | 旧 Action 收口自身：`stillOwnsPendingAction` + `showFirstRecoverable(replacingActionId)` | 正确；旧决策完成后不会清除新项目的 pending action，测试有效 |
| N-07 | 幂等 key 语义 | 请求已发出不重放，迟到结果仅丢弃前端写入，`decisionKeys` 仅在终态清理，符合“后端已成功不自动重发”契约 |
| N-08 | `saveWiki` 的 `wikiSaveSequence` + scope 双守卫 | 项目切换或重复保存时旧响应不写回当前视图，`finally` 不误复位 busy |
| N-09 | `formatText` 的 controller identity + scope 守卫 | 同项目新格式化请求启动后，旧 controller 被 abort 且已非当前 controller，旧 delta/完成不写入 |
| N-10 | 文档一致性 | `docs/03-features/web-workspace.md` 的测试边界与 `docs/07-changes/2026-10-04-r17-web-async-scope.md` 的验证回填与 diff 实现一致，无夸大或未实现声明 |

---

## 五、主开发 (Codex) 评估回填区

| Issue ID | 是否认可 | 处理方式（文档先行/修复） | 目标提交/文档 | 备注 |
|----------|----------|---------------------------|---------------|------|
| R17-01 | 是（已修复） | —— | 本次 diff | 已复现并修复，新增 Chat 控件恢复用例 |
| R17-02 | 是（已修复） | —— | 本次 diff | 已复现并修复，新增删除控件恢复用例 |
| R17-03 | 是（已修复） | —— | apps/web/tests/app.test.tsx | 两例先红灯后绿灯 |
| R17-04 | 认可为低风险建议，本批不改 | 项目恢复 GET 已受 session/project/generation 与 route 保护；其同 generation 极窄竞态无复现证据，不为 PASS 后的建议扩大状态机 | 后续若出现真实信号再独立记录 | 本批已覆盖用户要求的旧审批完成不清除新 pending |
| R17-05 | 认可为测试补强建议，本批不改 | 当前 Chat 结果同时受 controller identity、abort signal 与 workspace scope 阻断，且已有取消控件恢复测试；迟到成功专项断言不属于阻断缺口 | 后续测试维护候选 | 纯建议不触发复审 |

---

## 六、结论

- 本次 diff 无“必须修改”问题，REVIEW_RESULT 为 **PASS**。
- Attempt 1 的 R17-01 / R17-02 / R17-03 已全部收口：既有红灯证据、最小修复与新增回归用例，作用域设计在主要污染路径上自洽，未引入新的正确性、并发或契约回归。
- R17-04、R17-05 为低严重度建议，可与后续工作一并处理，不阻塞本次交付。
- 明确未建议引入任何 V2/V3 组件（Neo4j/GraphRAG、Langfuse、LiteLLM、MCP），审查范围限于本次前端异步作用域目标。
