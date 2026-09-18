# Pi 代码审查报告：pre-v3-chat-routes-pi-v41 / Attempt 2

- 日期：2026-09-18
- 审查阶段：pre-v3-chat-routes-pi-v41
- 审查对象：INDEX@7778a78（基线：7778a78be7b509e6ddfbfcb9d149d0e8f34a09e3）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# Pi 代码审查报告：pre-v3-chat-routes-pi-v41 / Attempt 2

- 日期：2026-09-18
- 审查阶段：pre-v3-chat-routes-pi-v41
- 审查模式：Milestone
- 审查轮次：2 / 3
- 审查对象：INDEX@7778a78（基线：7778a78）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 审查范围：本次暂存 diff（28 文件，+791 / −100）、文件清单与显式上下文（roadmap、conversation-history、diff 内含架构文档、attempt-1 报告）

---

## 一、概述与总体结论

本轮交付包含：

1. **P3-01 路由与会话隔离**：新增 `route.ts`（History API 小型路由层），将 Chat/Wiki/Task/Format 拆为 `lazy` 页面组件；`App.tsx` 保留认证、项目与 typed API 协调；新增路由回归测试并修复 attempt-1 的 F1/F3/F4。
2. **Pi 审核模型切换到 `deepseek-flash`**：`run-review.ps1` 的 `ValidateSet`、目录预检、合同测试、AGENTS/运维/变更文档与本地开发模型表同步。

attempt-1 结论为 NEEDS_FIX（F1 高：详情迟到回写路由）。本轮 diff 已加入 `activeConversationLoad` 失效、`routeChanged && route.page !== "chat"` 失效分支、`applyFormattedText → navigate("/wiki")`，并把 roadmap P3-01 与变更记录统一为 `In Progress`；F3/F4 已闭环。模型 ID 切换在 diff 内自洽。

**结论：需修复后交付。** 本轮发现在“页面拆分 + 路由驱动加载”这条主线上仍有两项具备明确代码证据的正确性/并发缺陷，其中 F1 直接破坏 P3-01 明确要求的“点击另一历史会话替换当前会话内容”，属于必须修改；另有 2 项建议修改不阻塞。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| F1 | 高 | `apps/web/src/App.tsx` | `openConversation` 清空会话 ≈L213；路由 effect 触发分支 ≈L262–L268 | 已加载会话时点击另一条历史会话，`setConversationId(undefined)` 使路由 effect 重跑并调用 `openConversation(当前路由 ID)`，覆盖并取消用户刚选择的新会话 |
| F2 | 中 | `apps/web/src/App.tsx` | `onMetadata` ≈L452–L457；`openPanel` ≈L352–L360；`leaveChatMode` ≈L372–L380；路由 effect | 离开聊天不中止在途 SSE，metadata 回调仍 `navigate` 回 `/chat/:id`，把用户从 Wiki/首页拉回聊天，违背本 diff 写入的架构契约 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| F3 | 中 | `scripts/agent-bridge/README.md` | 目录说明 `run-validation.ps1` 行 | 仍写 “以 V4-pro 启动”；且 diff 外调用 `run-review.ps1` 的桥接脚本是否仍传旧 `-Model` 无法从本 diff 确认，若传旧值会被 `ValidateSet` 直接拒绝 |
| F4 | 低 | `apps/web/tests/app.test.tsx` | 新增 3 个测试 | 直接写 `localStorage`/`sessionStorage` 且无显式 teardown，隔离依赖隐式全局清理，顺序敏感 |
| F5 | 低 | `apps/web/tests/app.test.tsx` | 新增用例 | 缺“从已加载会话切换到另一历史会话”回归（F1 场景）、metadata 驱动 URL 断言与“流中离开聊天”用例 |
| F6 | 低 | `apps/web/src/App.tsx` | 路由 effect `else if (!route.conversationId && routeChanged && conversationId)` | 详情加载在途时返回无 ID 的 `/chat`，因 `conversationId` 已被清空，失效分支不触发，迟到详情仍会重开旧会话 |

### 无需修改

| ID | 文件 | 说明 |
|----|------|------|
| F7 | `apps/web/src/markdown.ts` / `MarkdownPreview.tsx` | 抽取与 re-export 正确，未破坏既有导入方 |
| F8 | `route.ts` / `App.tsx` / 文档 | 路由解析、`decodeURIComponent` 回退、`popstate`、URL 不含 userId；模型 ID 切换在 diff 内自洽；roadmap 与变更记录状态已统一 |

---

## 三、逐个 Issue 展开

### F1（必须修改，高）切换历史会话时，路由 effect 会重新加载当前路由会话并吞掉用户选择

**File & Line**：`apps/web/src/App.tsx`，`openConversation`（新增 ≈L205–L247）与路由 effect（新增 ≈L255–L272）

**Evidence**

```ts
async function openConversation(selectedConversationId: string) {
  if (!projectId) return;
  const requestedProjectId = projectId;
  const load = ++activeConversationLoad.current;
  loadingConversationId.current = selectedConversationId;
  streamAbort.current?.abort();
  setBusy(true); setError("");
  setChatHistory([]);
  setConversationId(undefined);   // ← 清空当前会话
  setPendingAction(undefined);
  ...
}
```

```ts
if (route.page === "chat") {
  setChatMode(true);
  chatModeRef.current = true;
  if (route.conversationId && route.conversationId !== conversationId &&
    loadingConversationId.current !== route.conversationId) {
    void openConversation(route.conversationId);   // ← 仍以路由中的旧 ID 再加载
  } else if (!route.conversationId && routeChanged && conversationId) {
    ...
  }
}
...
// eslint-disable-next-line react-hooks/exhaustive-deps
}, [authenticated, projectId, route.page, route.conversationId, conversationId]);
```

**Description**

可复现路径（全部由本次 diff 代码推导）：

1. 用户在 `/chat/conversation-A` 查看会话 A（`conversationId === "A"`，`route.conversationId === "A"`）。
2. 打开历史抽屉，点击会话 B → `openConversation("B")`：`load = n+1`、`loadingConversationId = "B"`、`setConversationId(undefined)`；此时 URL 仍为 `/chat/conversation-A`（成功后才 `navigate`）。
3. `conversationId` 由 `"A"` 变为 `undefined`，触发依赖其中的路由 effect 重跑；`routeChanged` 为 false，但分支条件 `route.conversationId("A") !== conversationId(undefined)` 且 `loadingConversationId("B") !== route.conversationId("A")` 成立。
4. effect 再次调用 `openConversation("A")`：`load = n+1`，作废 B 的请求；B 返回时被 `activeConversationLoad.current !== load` 丢弃。
5. A 的详情返回并通过守卫 → `setConversationId("A")`、`navigate("/chat/conversation-A")`，UI 回到 A。

结果：用户点击历史会话 B 后界面仍/重新显示 A，即 `docs/03-features/conversation-history.md` 明确承诺的“点击另一历史会话替换当前会话内容并取消旧流”失效。这不是风格问题，而是路由驱动加载与状态清空之间的竞态，属于本阶段核心功能正确性缺陷。

**Suggested Fix**

不要把“当前已加载会话”用会在请求开始时被清空的状态来判定。用 ref 记录“已加载/请求中的会话 ID”，并让 effect 只依赖路由：

```ts
// 成功加载后：loadedConversationId.current = detail.conversationId;
// 请求开始时：loadingConversationId.current = selectedConversationId;（openConversation 内保留）
if (route.conversationId &&
    route.conversationId !== loadedConversationId.current &&
    route.conversationId !== loadingConversationId.current) {
  void openConversation(route.conversationId);
}
```

或把 `conversationId` 从 effect 依赖中移除，改为与 `loadedConversationId.current` 比较。并补充回归测试：先加载 A（URL `/chat/A`），再点击历史 B，断言最终显示 B、URL 为 `/chat/B`，且 A 的迟到响应不覆盖。

---

### F2（必须修改，中）离开聊天不中止在途 SSE，metadata 回调把路由拉回聊天

**File & Line**：`apps/web/src/App.tsx`，`sendChat` 的 `onMetadata`（≈L452–L457）、`openPanel`（≈L352–L360）、`leaveChatMode`（≈L372–L380）与路由 effect

**Evidence**

```ts
onMetadata: (metadata) => {
  if (activeProjectId.current !== requestedProjectId || streamAbort.current !== controller) return;
  setConversationId(metadata.conversationId);
  navigate(`/chat/${encodeURIComponent(metadata.conversationId)}`, true);
  ...
},
```

```ts
function openPanel(panel: WorkspacePanel) {
  activeConversationLoad.current += 1;
  loadingConversationId.current = undefined;
  setActivePanel(panel);
  setChatMode(false);
  navigate(panel === "wiki" ? "/wiki" : "/");
  ...
}
```

```ts
function leaveChatMode() {
  activeConversationLoad.current += 1;
  loadingConversationId.current = undefined;
  chatModeRef.current = false;
  setChatMode(false);
  navigate("/");
  ...
}
```

同 diff 写入的契约（`docs/02-architecture/frontend-architecture.md` P3-01 段）：

> 离开聊天或切换 project 时中止在途流，收到旧响应不得更新当前路由。

**Description**

`openPanel`/`leaveChatMode` 只作废详情加载（`activeConversationLoad`），既不 `streamAbort.current?.abort()`，也不改变 `streamAbort.current` 的身份；因此 `onMetadata` 的守卫 `streamAbort.current === controller` 仍成立。可达路径：

1. 用户在首页发送消息 → `enterChatMode()` 进入聊天，`sendChat` 发起 SSE。
2. metadata 返回前，用户点击聊天导航栏的 Wiki（`openChatTool("wiki") → openPanel("wiki")`）离开聊天到 `/wiki`。
3. metadata 到达 → 守卫通过 → `navigate("/chat/:id", true)`；路由 effect 随后 `setChatMode(true)`，用户被强行拉回聊天页面。

即“离开聊天中止在途流”在实现中并未发生，且迟到响应更新了当前路由，与同批文档契约冲突（并发/契约冲突，需修复）。窗口较窄（用户须在 metadata 到达前离开），但路径完全由本 diff 代码构成。

**Suggested Fix**

在离开聊天的所有出口（`openPanel`、`leaveChatMode`、路由 effect 的 `routeChanged && route.page !== "chat"` 分支）统一 `streamAbort.current?.abort()`，并对回调增加“仍在聊天路由”的判定，例如：

```ts
onMetadata: (metadata) => {
  if (activeProjectId.current !== requestedProjectId || streamAbort.current !== controller) return;
  if (parseRoute(window.location.pathname).page !== "chat") return;   // 用户已离开聊天
  ...
}
```

注意：`abort()` 后不要直接把 `streamAbort.current` 置空，否则 `sendChat` 的 `catch` 守卫 `streamAbort.current === controller` 失败，会残留空的 typing 占位项（参见无需修改项的同源风险）。

---

### F3（建议修改，中）桥接 README 与可能的旧模型调用残留

**File & Line**：`scripts/agent-bridge/README.md` 目录说明中 `run-validation.ps1` 一行

**Evidence**

```text
- `run-validation.ps1`：以 V4-pro 启动受限验证会话；允许 read/grep/find/ls ...
```

**Description**

`run-review.ps1` 的 `Model` 现在只接受 `deepseek/deepseek-flash`，合同测试也断言旧 `deepseek-v4-pro`、`deepseek-v4-flash` 均被拒绝；但本 README 仍把 `run-validation.ps1` 描述为 V4-pro，与同批更新的 `docs/06-operations/pi-review-connection.md`、`AGENTS.md`、`docs/08-reviews/README.md` 不一致。此外 `review-loop.ps1`、`run-validation.ps1`、`Test-ReviewBridge.ps1` 不在本 diff 中，若它们内部仍以 `-Model deepseek/deepseek-v4-pro` 调用 `run-review.ps1`，会因 `ValidateSet` 直接参数绑定失败。自动推进虽已停用，但这些是“当前生效”的桥接文档与回归入口。

**Suggested Fix**

把仍写 V4-pro 的描述改为 `deepseek/deepseek-flash`；确认所有调用 `run-review.ps1` 的脚本传入新 ID 或省略 `Model`，并对 `Test-ReviewBridge.ps1` 的默认路径做一次最小验证。若确认这些入口已停用并保留历史说明，请在变更记录中显式写明豁免依据。

---

### F4（建议修改，低）新测试对 storage 的写入缺少显式 teardown

**File & Line**：`apps/web/tests/app.test.tsx` 新增 3 个测试

**Evidence**

```ts
localStorage.setItem("agentforge.onboardingComplete", "true");
...
sessionStorage.setItem("agentforge.accessToken", "token");
...
sessionStorage.removeItem("agentforge.accessToken"); // 仅在测试末尾，失败时不执行
```

**Description**

`beforeEach` 只重置 `window.history`，新测试对 `localStorage`/`sessionStorage` 的写入依赖隐式全局清理（否则后续需要引导弹窗的既有用例会顺序敏感）。本轮 42 passed 说明当前环境有清理，但该依赖未在测试文件内声明，失败重跑或新增用例时容易变成 flaky。

**Suggested Fix**

在 `beforeEach`/`afterEach` 显式 `localStorage.clear(); sessionStorage.clear();`，让隔离不依赖外部 setup。

---

### F5（建议修改，低）缺少切换会话、metadata 路由与流中离开的回归覆盖

**File & Line**：`apps/web/tests/app.test.tsx`

**Description**

P3-01 验收包含“点击另一历史会话替换当前会话内容并取消旧流”“首条消息完成 metadata 后取得新 conversationId”。现有新增测试只覆盖：从空白 `/chat` 选择历史 → `/chat/:id`、直接访问 `/chat/:id`、新建会话回 `/chat`、加载中导航到 `/wiki`。缺三类关键用例：已加载 A 后点击 B（正是 F1）、发送首条消息后断言 URL 变为 `/chat/<id>`、流进行中离开聊天（F2）。这属于关键测试范围不充分的建议项。

**Suggested Fix**

按上述场景补测试，尤其第一条应能先红后绿验证 F1 修复。

---

### F6（建议修改，低）返回无 ID 的 `/chat` 时在途详情未失效

**File & Line**：`apps/web/src/App.tsx` 路由 effect 的 `else if (!route.conversationId && routeChanged && conversationId)`

**Description**

`openConversation` 在请求开始时已 `setConversationId(undefined)`，因此当用户在详情加载未决时从 `/chat/A` 后退到 `/chat`（或任何无 ID 的 chat 路由），分支中的 `conversationId` 为假值，不触发失效；迟到详情仍会 `navigate("/chat/:id")` 并重开旧会话。这是 attempt-1 F1 修复未覆盖的窄分支，与 F1 共享同一根因（用会被清空的状态判定在途加载）。

**Suggested Fix**

在 effect 中改为“只要 `loadingConversationId.current` 存在且 `route.conversationId !== loadingConversationId.current`，就作废在途详情”，可同时覆盖 `/wiki`、`/`、`/chat` 与 `/chat/other`。

---

## 四、无需修改 / 已确认正确

- **attempt-1 F1 主场景已修复**：导航到 `/wiki` 时 `routeChanged && route.page !== "chat"` 会作废 `activeConversationLoad`，新增测试 “does not reopen a conversation after leaving its route” 先红后绿，覆盖了详情迟到不改路由。
- **attempt-1 F3/F4 已闭环**：roadmap 与变更记录统一为 `In Progress`；`applyFormattedText` 补 `navigate("/wiki")`。
- **模型 ID 切换在 diff 内自洽**：`run-review.ps1` 的 `ValidateSet`、`--list-models deepseek-flash` 预检正则、报告标题、`pi/AGENTS.md`、`stage-review-system.md`、`docs/06-operations`、`docs/08-reviews/README.md`、`AGENTS.md`、`definition-of-done.md`、`local-development.md` 均已同步；合同测试新增对旧 `deepseek-v4-flash` 的拒绝断言。
- **`markdown.ts` 抽取**保留 `MarkdownPreview.tsx` re-export；`App.tsx` 无悬空引用；页面组件使用 default export + `lazy`，`Suspense` fallback 用 `role="status"`。
- **`route.ts`** 对 `/chat`、`/chat/:id`、`/wiki` 解析、`decodeURIComponent` 异常回退、`popstate` 监听与 `navigate(path, replace)` 语义正确；URL 不携带 userId/凭据。
- **授权边界**未变化：会话详情仍以 `projectId + conversationId` 经 Core API 读取，前端不承担授权判定。

---

## 五、主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 技术研判与依据 | 修复提交 / 验证 |
|---------|----------|----------------|------------------|
| F1 | 是 | 选择历史时立即更新目标路由，路由 effect 不再因清空 `conversationId` 重跑旧 ID。 | A→B 回归先红后绿，Web 45/45 通过 |
| F2 | 是 | 离开聊天中止流；metadata 同时检查 AbortSignal 与当前路由。 | 迟到 metadata 回归先红后绿，Web 45/45 通过 |
| F3 | 否 | `run-validation.ps1` 与 `review-loop.ps1` 已停用；README 保留对停用验证脚本原有模型的历史描述。当前生效 `run-review.ps1` 说明已更新。 | 脚本调用关系核对 |
| F4 | 否 | `apps/web/tests/setup.ts` 已在 `afterEach` 显式清理两种 storage，无顺序依赖。 | setup 文件核对，Web 45/45 通过 |
| F5 | 部分 | 补 A→B、空 `/chat` 与流迟到回归；metadata URL 单独断言可留后续聊天阶段。 | 3 项聚焦测试先红后绿 |
| F6 | 是 | 无 ID `/chat` 失效条件覆盖在途详情 ID，且保持新消息从首页进入 `/chat` 的正常流程。 | 空 `/chat` 回归与 Web 45/45 通过 |

> 说明：F1、F2 为本轮进入“必须修改”的阻塞项。建议在修复后进行一次只读复审，验证修复效果并识别修复引入的新问题；F3–F6 可在同一轮处置或给出明确豁免依据。
