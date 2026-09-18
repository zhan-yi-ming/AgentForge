# Pi 代码审查报告：pre-v3-chat-routes-pi-v41 / Attempt 3

- 日期：2026-09-18
- 审查阶段：pre-v3-chat-routes-pi-v41
- 审查对象：INDEX@7778a78（基线：7778a78be7b509e6ddfbfcb9d149d0e8f34a09e3）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：pre-v3-chat-routes-pi-v41 / Attempt 3

- 日期：2026-09-18
- 审查阶段：pre-v3-chat-routes-pi-v41
- 审查模式：Milestone
- 审查轮次：3 / 3
- 审查对象：INDEX@7778a78（基线：7778a78be7b509e6ddfbfcb9d149d0e8f34a09e3）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 审查范围：本次暂存 diff（29 文件，+1150 / −100）、文件清单与显式上下文（roadmap、conversation-history、diff 内含架构文档、attempt-1/2 报告结论）

---

## 一、概述与总体结论

本轮交付由两条相对独立的线组成：

1. **P3-01 路由与会话隔离**：新增 `route.ts`（History API 小型路由层），把 Chat/Wiki/Task/Format 拆为 `lazy` 页面组件；`App.tsx` 保留认证、项目选择与 typed API 协调。针对 attempt-1 F1、attempt-2 F1/F2/F6 引入了 `activeConversationLoad`、`loadingConversationId`、`previousRoutePath` 与统一的失效/中止逻辑。
2. **Pi 审核模型切换为 `deepseek-flash`**：`run-review.ps1` 的 `ValidateSet`、目录预检正则、合同测试、`pi/AGENTS.md`、`stage-review-system.md`、`AGENTS.md`、`definition-of-done.md`、本地开发模型表与运维文档同步更新。

对 attempt-2 两项阻塞项的复核：

- **attempt-2 F1（A→B 切换被旧路由重载）**：`openConversation` 现在**先** `navigate(/chat/:目标)`，并让路由 effect 依赖 `[authenticated, projectId, route.page, route.conversationId]`（不再依赖会被清空的 `conversationId`）；effect 触发条件加入 `loadingConversationId.current !== route.conversationId`。按 A→B 跟踪，effect 重跑时 `loadingConversationId === route.conversationId`，不会用旧 ID 重新加载；新增用例 “replaces a loaded conversation with the newly selected history entry” 覆盖该路径。**已闭环。**
- **attempt-2 F2（离开聊天后迟到 metadata 回写路由）**：`openPanel`/`leaveChatMode` 现调用 `streamAbort.current?.abort()`；`onMetadata` 守卫扩展为 `streamAbort.current !== controller || controller.signal.aborted || parseRoute(window.location.pathname).page !== "chat"`。新增用例 “does not route back to chat when streaming metadata arrives after leaving” 覆盖。**已闭环。**
- **attempt-2 F6（无 ID `/chat` 在途详情未失效）**：effect 分支改为 `!route.conversationId && routeChanged && (loadingConversationId.current || conversationId)`，由 `loadingConversationId` 兜住 `conversationId` 被清空的情况；新增用例 “keeps an empty chat route when pending conversation detail arrives late” 覆盖。**已闭环。**

**结论：通过，可交付。** 未发现具备明确代码证据的可运行性、正确性、安全、权限、并发、数据一致性或契约类阻塞问题。以下 5 项均为建议修改或文档一致性提示，不阻塞提交；另有 5 项无需修改（确认修复正确）。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| — | — | — | — | 本轮无可确认的阻塞性问题 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| S1 | 低 | `docs/02-architecture/frontend-architecture.md` | V1.2 视觉与演示信息架构段落 | 文档仍写“项目、任务与三个工作台入口在会话内只打开悬浮窗，不改变会话模式”，但聊天内 Wiki 入口（`openChatTool("wiki") → openPanel("wiki")`）现已 `navigate("/wiki")` 并退出会话模式，与同文新增的 P3-01 “独立路由页面”描述冲突 |
| S2 | 低 | `scripts/agent-bridge/README.md` | 目录文件说明 `run-validation.ps1` 行 | 仍描述“以 V4-pro 启动受限验证会话”，与新 `ValidateSet("deepseek/deepseek-flash")` 及本批更新的其他文档不一致（该入口已停用，属历史描述残留） |
| S3 | 低 | `apps/web/tests/app.test.tsx` | 新增 3 个测试 | 新用例直接写 `localStorage.onboardingComplete` / `sessionStorage.accessToken` 且仅在测试末尾清理，隔离依赖 diff 外的 `setup.ts`；失败或新增用例时易变为 flaky |
| S4 | 低 | `apps/web/tests/app.test.tsx` | 新增用例集合 | 缺“首条消息 `onMetadata` 后 URL 变为 `/chat/:conversationId`”的显式断言（P3-01 明确验收点之一） |
| S5 | 低 | `apps/web/src/App.tsx` | `openConversation` 成功分支（≈L230–L247） | 成功回写前只校验 `activeProjectId` 与 `activeConversationLoad`，未校验当前路径仍指向目标会话；在 popstate 与详情响应同一微任务窗口内到达的极窄竞态下，迟到响应理论上可覆盖用户的后退导航（无复现证据） |

### 无需修改

| ID | 文件 | 说明 |
|----|------|------|
| N1 | `apps/web/src/markdown.ts` / `MarkdownPreview.tsx` | 抽取 `normalizeMarkdownContent` 并保留 re-export，未破坏既有导入方 |
| N2 | `apps/web/src/route.ts` | 路径解析、`decodeURIComponent` 异常回退、`popstate` 监听与 `navigate(path, replace)` 语义正确；URL 不携带 userId/凭据 |
| N3 | `scripts/agent-bridge/run-review.ps1` 等 | 模型 ID 切换在 diff 内自洽，合同测试新增旧 `deepseek-v4-flash` 拒绝断言 |
| N4 | `apps/web/src/App.tsx` | attempt-1 F1 / attempt-2 F1/F2/F6 修复已落地并有对应失效逻辑与回归测试 |
| N5 | 授权边界 | 会话详情仍以 `projectId + conversationId` 经 Core API 读取，前端不承担授权判定 |

---

## 三、逐个 Issue 展开

### S1（建议修改，低）架构文档 V1.2 与新的 Wiki 路由行为不一致

**File & Line**：`docs/02-architecture/frontend-architecture.md`，V1.2 段落

**Evidence**

```text
- 工作区采用……只有发送消息后才进入独立会话布局……项目、任务与三个工作台入口在会话内只打开悬浮窗，不改变会话模式，左上角返回按钮才回到首页。
```

```ts
function openChatTool(panel: WorkspacePanel) {
  if (panel === "wiki") { openPanel("wiki"); return; }   // openPanel 内 setChatMode(false) + navigate("/wiki")
  ...
}
```

**Description**

同一次 diff 在该文档追加了 P3-01 段：“聊天与 Wiki 视图已分离为按需加载的页面组件……离开聊天或切换 project 时中止在途流”。但 V1.2 段落仍声明聊天内三个工作台入口“不改变会话模式”。实现上聊天内 Wiki 入口现已导航到 `/wiki` 并退出会话模式，导致同一文档内两条陈述冲突。该差异不影响数据正确性、安全或授权，仅为文档一致性问题，故列为建议。

**Suggested Fix**

更新 V1.2 表述，明确“Wiki 入口进入独立 `/wiki` 路由；Task/Format 仍在会话内以悬浮工具窗呈现”，或在新 P3-01 段显式说明 V1.2 中该句已由 P3-01 取代。

---

### S2（建议修改，低）桥接 README 仍残留 V4-pro 描述

**File & Line**：`scripts/agent-bridge/README.md` 目录文件说明

**Evidence**

```text
- `run-validation.ps1`：以 V4-pro 启动受限验证会话；允许 read/grep/find/ls ...
```

**Description**

`run-review.ps1` 的 `Model` 现仅接受 `deepseek/deepseek-flash`，合同测试也断言旧 `deepseek-v4-pro`、`deepseek-v4-flash` 被拒；但 README 仍把 `run-validation.ps1` 描述为 V4-pro。该入口已停用（目录顶部已声明所有 monitor/loop/validation 返回 `DISABLED`），因此不构成运行阻塞，属历史描述残留，与 `docs/06-operations/pi-review-connection.md` 等已更新文档不一致。

**Suggested Fix**

将该行改为“已停用；历史版本使用 V4-pro”，或直接标注为停用入口并移除模型名，避免与新审核入口混淆。若确认保留历史描述，请在变更记录中显式写明豁免依据（attempt-2 F3 已给出同样建议）。

---

### S3（建议修改，低）新测试对 storage 的写入缺少文件内显式 teardown

**File & Line**：`apps/web/tests/app.test.tsx` 新增用例

**Evidence**

```ts
beforeEach(() => window.history.replaceState({}, "", "/"));
it("opens a selected conversation at its own route ...", async () => {
  localStorage.setItem("agentforge.onboardingComplete", "true");
  ...
  sessionStorage.setItem("agentforge.accessToken", "token");
  ...
  sessionStorage.removeItem("agentforge.accessToken"); // 失败时不执行
});
```

**Description**

本轮新增用例对 `localStorage` / `sessionStorage` 的写入依赖 diff 外的 `apps/web/tests/setup.ts` 全局清理（Codex 在 attempt-2 回填中已说明）。该隔离在测试文件内不可见，失败重跑或后续新增依赖“首次显示引导”的用例时容易产生顺序敏感。当前 45/45 通过说明环境有清理，故不阻塞。

**Suggested Fix**

在 `beforeEach`/`afterEach` 显式 `localStorage.clear(); sessionStorage.clear();`，让隔离不依赖外部 setup 文件。

---

### S4（建议修改，低）缺少 metadata 驱动 URL 的显式断言

**File & Line**：`apps/web/tests/app.test.tsx`

**Description**

P3-01 验收包含“新会话在首条消息完成 metadata 后取得新的 conversationId”。现有新增用例覆盖了历史选择进入 `/chat/:id`、直接访问 `/chat/:id`、新建会话回 `/chat`、加载中离开、流中离开，但未直接断言 `onMetadata` 回调后 `window.location.pathname` 变为 `/chat/<id>`。该行为由 `navigate(..., true)` 实现且逻辑清晰，风险低，属测试完整性建议。

**Suggested Fix**

补充一例：从首页发送首条消息，触发 `onMetadata({ conversationId: "new-id", ... })`，断言 `window.location.pathname === "/chat/new-id"`，并断言后续消息使用该 ID。

---

### S5（建议修改，低）详情成功回写未校验当前路径（极窄竞态）

**File & Line**：`apps/web/src/App.tsx` `openConversation` 成功分支

**Evidence**

```ts
const detail = await api.getConversation(requestedProjectId, selectedConversationId);
if (activeProjectId.current !== requestedProjectId || activeConversationLoad.current !== load) return;
...
setConversationId(detail.conversationId);
setChatMode(true);
chatModeRef.current = true;
navigate(`/chat/${encodeURIComponent(detail.conversationId)}`);
```

**Description**

`openConversation` 开头的失效由路由 effect（`routeChanged && route.page !== "chat"` / 无 ID `/chat` 分支）驱动，依赖 React 在 popstate 后提交并运行 effect 来递增 `activeConversationLoad`。若 `getConversation` 的续体与 popstate 后的 effect 调度落在同一微任务窗口内，理论上存在“用户已后退、迟到响应仍 `navigate` 回该会话”的窄窗口。attempt-1 曾建议过 `window.location.pathname !== /chat/<id>` 这一路径级守卫，本轮未采用；现有回归测试在响应落定前 `await` 了 effect，故未覆盖该窗口。因缺少可复现证据，按准则不进入“必须修改”，列为建议。

**Suggested Fix**

在成功回写前追加路径守卫，作为对计数器失效的第二道保险：

```ts
if (window.location.pathname !== `/chat/${encodeURIComponent(selectedConversationId)}`) return;
```

并为该窗口补一条先红后绿测试（可能在 `popstate` 与 `resolveDetail` 间可控地插入调度顺序）。

---

## 四、无需修改 / 已确认正确

- **attempt-2 F1 已闭环**：`openConversation` 先 `navigate` 目标路径，路由 effect 依赖移除 `conversationId`，并用 `loadingConversationId.current !== route.conversationId` 阻止旧 ID 重载；A→B 用例覆盖。
- **attempt-2 F2 已闭环**：`openPanel`/`leaveChatMode` 中止在途流；`onMetadata` 同时校验 `AbortSignal` 与当前路由 page，迟到 metadata 不再改写路由。
- **attempt-2 F6 已闭环**：无 ID `/chat` 分支用 `loadingConversationId.current` 兜底失效，`conversationId` 被清空时仍能作废在途详情。
- **attempt-1 F3/F4 已闭环**：roadmap 与变更记录统一为 `In Progress`；`applyFormattedText` 补 `navigate("/wiki")`。
- **模型 ID 切换自洽**：`run-review.ps1` 的 `ValidateSet`、`--list-models deepseek-flash` 预检正则、报告标题、`pi/AGENTS.md`、`stage-review-system.md`、`docs/06-operations`、`docs/08-reviews/README.md`、`AGENTS.md`、`definition-of-done.md`、`local-development.md` 均已同步；`pi-flash-review-contract.ps1` 新增旧 `deepseek-v4-flash` 拒绝断言。
- **`markdown.ts` 抽取**：`MarkdownPreview.tsx` re-export 保留，`App.tsx` 无 `MarkdownPreview` / `streamingPreviewText` 悬空引用，`FormatView` 承接流式预览文案。
- **页面拆分**：`default export + lazy + Suspense`，fallback 使用 `role="status"`；`ChatHistoryItem` 改为 `import type` 从页面模块引入，编译期擦除不会造成首屏强依赖。
- **`route.ts`**：解析 `/chat`、`/chat/:id`、`/wiki` 与未知路径回退正确，`decodeURIComponent` 异常回退 home，`popstate` 监听与 `navigate(path, replace)` 语义正确；URL 不含 userId/凭据。
- **授权边界未变化**：会话详情仍以 `projectId + conversationId` 经 Core API 读取，前端不承担授权判定；错误响应不回退为其他会话内容。

---

## 五、主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 技术研判与依据 | 修复提交 / 验证 |
|---------|----------|----------------|------------------|
| S1 | 是 | V1.2 描述已明确 Wiki 在 P3-01 后使用独立路由。 | 架构文档已更新 |
| S2 | 是 | README 已将 V4-pro 验证入口明确标为停用历史脚本。 | 桥接说明已更新 |
| S3 | 否 | `apps/web/tests/setup.ts` 在 `afterEach` 已显式清理两种 storage；测试文件直接使用该既有隔离设置。 | Web 46/46 通过 |
| S4 | 是 | 新增首条流 metadata 后 URL 为 `/chat/:conversationId` 的 DOM 验收断言。 | Web 46/46 通过 |
| S5 | 否 | 该微任务竞态无复现证据；现有 `activeConversationLoad` 与路由离开失效守卫覆盖可测路径。 | 路由回归与 Web 46/46 通过 |

> 说明：本轮不存在“必须修改”项，`REVIEW_RESULT: PASS`。S1–S5 可在提交前顺手处置或按上述依据豁免；如采纳 S5，请在提交记录中保留先红后绿证据，并确保不引入新回归。
