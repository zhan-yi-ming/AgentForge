# Pi 代码审查报告：pre-v3-chat-routes-pi-v41 / Attempt 1

- 日期：2026-09-18
- 审查阶段：pre-v3-chat-routes-pi-v41
- 审查对象：INDEX@7778a78（基线：7778a78be7b509e6ddfbfcb9d149d0e8f34a09e3）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# Pi 代码审查报告：pre-v3-chat-routes-pi-v41 / Attempt 1

- 日期：2026-09-18
- 审查阶段：pre-v3-chat-routes-pi-v41
- 审查模式：Milestone
- 审查对象：INDEX@7778a78（基线：7778a78）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 审查范围：本次暂存 diff（27 文件，+491 / −98）及显式上下文（roadmap、conversation-history、diff 内含的架构文档）

---

## 一、概述与总体结论

本次交付包含两条相对独立的线：

1. **P3-01 前端路由与会话隔离**：新增 `route.ts` 小型 History API 路由层，将 Chat/Wiki/Task/Format 拆分为 lazy 页面组件，`App.tsx` 保留共享状态与 API 协调；新增两个断言路由行为的测试，并把既有异步页面测试改为 `findByLabelText`。
2. **Pi 审核模型从 `deepseek/deepseek-v4-flash` 切换到官方 API ID `deepseek-flash`**：同步更新启动器校验、合同测试、AGENTS/运维/变更文档与本地开发模型表。

整体方向与 `docs/01-product/v2-v3-node-roadmap.md` 的 P3-01 定义、`docs/02-architecture/decisions/ADR-0024` 与 `docs/03-features/*` 的声明的页面拆分/按需加载/路径会话作用域一致；模型 ID 的切换在 diff 内自洽，合同测试对旧 ID（`deepseek-v4-pro`、`deepseek-v4-flash`）增加了拒绝断言。

**结论：需修复后交付。** 存在 1 项有明确代码证据的导航/并发正确性问题，违反本阶段同一 diff 写入的架构契约（“离开聊天或切换 project 时中止在途流，收到旧响应不得更新当前路由”），触发 NEEDS_FIX。其余为文档一致性与测试隔离类建议，不阻塞。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| F1 | 高（必须修改） | `apps/web/src/App.tsx` | `openConversation` L205–L245、路由 effect L250–L272 | 离开聊天/切到 Wiki/浏览器后退时，在途的会话详情加载未被失效；旧响应仍会 `setChatMode(true)` 并 `navigate("/chat/:id")`，覆盖用户当前路由 |
| F2 | 中（建议修改） | `scripts/agent-bridge/README.md` | L6 等 | 仍描述 `run-review.ps1` 为 “Pi V4-pro”，且 `run-validation.ps1` 描述为 V4-pro；与新 `ValidateSet("deepseek/deepseek-flash")` 及仓库其他文档不一致 |
| F3 | 中（建议修改） | `docs/01-product/v2-v3-node-roadmap.md` / `docs/07-changes/2026-09-18-pre-v3-chat-experience-plan.md` | roadmap P3-01 条目 | roadmap 标注 P3-01 为 `Implemented`，变更记录仍为 `In Progress`，且审核/提交尚未完成，状态语义冲突 |
| F4 | 低（建议修改） | `apps/web/src/App.tsx` | `applyFormattedText` L358–L366 | 只 `setActivePanel("wiki")`/`setPreviewOpen(true)` 而不 `navigate("/wiki")`，URL 与页面状态可在非聊天模式分叉，违背 ADR-0024“新页面必须有独立路由”的模型 |
| F5 | 低（建议修改） | `apps/web/tests/app.test.tsx` | 新增两个测试 | 新测试设置 `localStorage.onboardingComplete` / `sessionStorage.accessToken` 但无显式 teardown；隔离依赖隐式全局清理，顺序敏感风险 |
| F6 | 低（建议修改） | `apps/web/tests/app.test.tsx` | 新增测试 | 缺少对 P3-01 明确验收“首条消息完成 metadata 后 URL 变为 `/chat/:conversationId`”的断言，也缺少“加载中离开聊天”的路由回归测试 |
| F7 | 低（无需修改） | `apps/web/src/App.tsx` | `sendChat` catch L472–L476 | catch 被 `streamAbort.current === controller` 收窄后，理论上并发发送会残留空回答占位；当前发送在 `streaming` 期间禁用，UI 不可达 |

---

## 三、逐个 Issue 展开

### F1（必须修改，高）离开聊天时在途会话详情加载未失效，旧响应回写路由

**File & Line**：`apps/web/src/App.tsx`，`openConversation`（新增约 L205–L245）与路由 effect（新增约 L250–L272）

**Evidence**

```ts
// openConversation 成功分支：只校验 project 与 load 计数
const detail = await api.getConversation(requestedProjectId, selectedConversationId);
if (activeProjectId.current !== requestedProjectId || activeConversationLoad.current !== load) return;
...
setChatMode(true);
chatModeRef.current = true;
navigate(`/chat/${encodeURIComponent(detail.conversationId)}`);
```

```ts
// 路由 effect：只有 chat 且“已有 conversationId”时才失效在途加载
} else if (route.page === "wiki") {
  setChatMode(false);
  chatModeRef.current = false;
  setActivePanel("wiki");
} else {
  setChatMode(false);
  chatModeRef.current = false;
}
```

`newChat()` 会执行 `activeConversationLoad.current += 1`，因此“新建会话”能正确作废在途请求；但**离开聊天（home / wiki / 浏览器后退）分支都不增加 `activeConversationLoad`，也不清空/标记 `loadingConversationId`**。由于 `openConversation` 起始处已执行 `setConversationId(undefined)`，路由 effect 中的后退清理分支 `!route.conversationId && routeChanged && conversationId` 也会因 `conversationId` 已为 falsy 而不触发。

**Description**

可复现路径（均在本次 diff 代码内可推导）：

1. 用户在首页/聊天页打开“历史”抽屉，点击某会话 → `openConversation` 发起详情请求，`loadingConversationId = id`，`conversationId = undefined`。
2. 请求未返回期间，用户点击工作台 “Wiki 工作台”（`openPanel("wiki")` → `navigate("/wiki")`）或浏览器后退到 `/chat`、或返回首页。
3. 路由 effect 进入 wiki/home 分支，仅设置 `chatMode=false`，不失效在途加载。
4. 详情请求返回，成功守卫 `activeProjectId.current === requestedProjectId && activeConversationLoad.current === load` 仍成立 → 执行 `setChatMode(true)` + `navigate("/chat/:id")`。

结果：用户已被带离的聊天视图被强行重新打开，URL 被改回 `/chat/:id`。这违反本阶段同一 diff 写入的契约：

- `docs/02-architecture/frontend-architecture.md`：“离开聊天或切换 project 时中止在途流，收到旧响应不得更新当前路由。”
- `docs/03-features/web-workspace.md`：“路由切换取消旧页面在途请求……未授权或不存在时显示错误状态而不复用其他会话内容。”

这不是风格问题，而是并发/导航状态一致性缺陷：当前代码只中止了 SSE 流（`streamAbort`），没有对详情 fetch 应用同一失效语义。

**Suggested Fix**

在 `openConversation` 应用响应前增加“当前路径仍是目标会话”的守卫，或在离开聊天的所有分支统一失效在途加载。推荐两者之一：

```ts
const detail = await api.getConversation(requestedProjectId, selectedConversationId);
if (activeProjectId.current !== requestedProjectId || activeConversationLoad.current !== load) return;
// 新增：用户已离开该会话路由则丢弃迟到响应
if (window.location.pathname !== `/chat/${encodeURIComponent(selectedConversationId)}`) return;
```

或在路由 effect 的 wiki/home 分支与 `leaveChatMode` 中执行 `activeConversationLoad.current += 1; loadingConversationId.current = undefined;`，与 `newChat` 保持一致。并补充回归测试：加载未决时导航到 `/wiki`（或触发 `popstate`），断言迟到响应不会改变 `window.location.pathname`、也不会重新出现该会话正文。

---

### F2（建议修改，中）桥接脚本/README 仍残留 V4-pro 描述，与收窄后的 ValidateSet 不一致

**File & Line**：`scripts/agent-bridge/README.md` L6 起；关联 `scripts/agent-bridge/run-review.ps1` 的 `[ValidateSet("deepseek/deepseek-flash")]`

**Evidence**

```text
- `run-review.ps1`：执行单次、带超时的 Pi V4-pro 只读审查，输出独立报告。
...
- `run-validation.ps1`：以 V4-pro 启动受限验证会话……
```

**Description**

`run-review.ps1` 的 `Model` 现在只接受 `deepseek/deepseek-flash`，合同测试也断言 `deepseek-v4-pro` 与 `deepseek-v4-flash` 均被拒绝。但 `scripts/agent-bridge/README.md` 目录说明仍把 `run-review.ps1`、`run-validation.ps1` 描述为 V4-pro，与 `docs/06-operations/pi-review-connection.md`、`AGENTS.md`、`docs/08-reviews/README.md` 已更新的描述矛盾。此外，若 `review-loop.ps1` / `run-validation.ps1` / `Test-ReviewBridge.ps1` 内部仍以 `-Model deepseek/deepseek-v4-pro` 调用 `run-review.ps1`，会因 `ValidateSet` 直接抛参数绑定错误；这些文件不在本次 diff 中，无法确认是否同步。相关自动推进虽已停用，但作为“当前生效”的桥接说明与回归入口，存在误导和潜在回归。

**Suggested Fix**

更新 README 中仍写 V4-pro 的条目为 V4.1 Flash；确认所有调用 `run-review.ps1` 的脚本传入 `deepseek/deepseek-flash`（或省略 `Model` 使用默认值），并对 `Test-ReviewBridge.ps1` 的默认路径做一次最小验证。

---

### F3（建议修改，中）P3-01 状态在 roadmap 与变更记录之间不一致

**File & Line**：`docs/01-product/v2-v3-node-roadmap.md`（P3-01 条目 “Implemented”）；`docs/07-changes/2026-09-18-pre-v3-chat-experience-plan.md`（状态 “In Progress”）

**Description**

roadmap 将 P3-01 标为 `Implemented`，而变更记录仍为 `In Progress`，且本轮 Milestone Review 未通过、提交与远端核验尚未发生。按 `docs/07-changes/README.md` 的状态定义，`Implemented` 表示“实现和验证已完成，AI 已创建或即将创建阶段提交并推送”。在审核未通过时提前标记，会让后续节点/负责人误判完成度；评审记录自身的 “Pi 预检失败待用户处理” 也说明门禁尚未闭环。

**Suggested Fix**

在 F1 修复、Pi 复审通过并完成提交/远端核验前，将 roadmap P3-01 与变更记录统一为 `In Progress`；提交后再改为 `Implemented`。路线文字可保留完整阶段编号，仅调整状态语义。

---

### F4（建议修改，低）`applyFormattedText` 切换 Wiki 面板但不更新路由

**File & Line**：`apps/web/src/App.tsx` `applyFormattedText`（约 L358–L366）

**Evidence**

```ts
setWikiFeedback("已应用到 Wiki 草稿，请确认后保存");
setActivePanel("wiki");
setPreviewOpen(true);
wikiPanel.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
```

**Description**

`openPanel("wiki")` 会 `navigate("/wiki")`，但 `applyFormattedText` 只改 `activePanel`。在非聊天模式于首页/其他路径应用整理结果时，URL 仍停留在原路径（如 `/`），与 ADR-0024 “新增页面必须声明新路径及独立页面组件”的模型出现分叉：URL 无法表达当前页面，刷新后回到首页。属于一致性问题，不影响数据正确性，故列为建议。

**Suggested Fix**

在 `applyFormattedText` 中改用/补充 `navigate("/wiki")`（或调用 `openPanel("wiki")` 后再追加 preview/scroll 行为），保证 URL 与页面状态同步。

---

### F5（建议修改，低）新测试对 storage 的写入缺少显式 teardown

**File & Line**：`apps/web/tests/app.test.tsx` 新增两个测试

**Evidence**

```ts
localStorage.setItem("agentforge.onboardingComplete", "true");
...
sessionStorage.setItem("agentforge.accessToken", "token");
...
sessionStorage.removeItem("agentforge.accessToken"); // 仅在测试末尾，失败时不执行
```

**Description**

两个新测试分别写入 `localStorage` 与 `sessionStorage`，且 `beforeEach` 只重置 `window.history`。若测试环境不存在全局 storage 清理，后续依赖“首次显示引导”的既有测试会顺序敏感（例如 `findByRole("dialog")`）。当前验证证据为 41 passed，说明很可能已有隐式清理，但该依赖未在测试文件中显式声明，失败重跑或新增用例时容易演变为 flaky。

**Suggested Fix**

在 `beforeEach`/`afterEach` 显式 `localStorage.clear(); sessionStorage.clear();`（或使用 `afterEach` 恢复），让隔离不依赖外部 setup。

---

### F6（建议修改，低）缺少 metadata 驱动 URL 与“加载中离开聊天”的回归覆盖

**File & Line**：`apps/web/tests/app.test.tsx`

**Description**

P3-01 验收明确要求“新会话在首条消息完成 metadata 后取得新的 conversationId”。新增测试覆盖了历史选择进入 `/chat/:id`、直接访问 `/chat/:id`、新建会话回到 `/chat`，但未断言 `onMetadata` 回调后 URL 变为 `/chat/:conversationId`，也没有覆盖 F1 所述的“加载未决时离开聊天”。前者行为由 `navigate(..., true)` 实现，风险较低；后者正是本轮发现的问题。

**Suggested Fix**

补充两类用例：发送首条消息后断言 `window.location.pathname` 变为 `/chat/<id>`；在 `getConversation` 返回未决时触发导航离开，断言迟到响应不改路由、不重开聊天。

---

### F7（无需修改，低）`sendChat` catch 收窄后的理论占位残留

**File & Line**：`apps/web/src/App.tsx` `sendChat`（约 L472–L476）

**Evidence**

```ts
} catch (cause) {
  if (streamAbort.current === controller) {
    setChatHistory((current) => current.filter((item) => item.id !== historyId));
    if (!isAbortError(cause)) report(cause);
  }
}
```

**Description**

若并发存在两次发送，先发起的流在 `streamAbort.current` 被后一次覆盖后，其 catch 不再移除自己的空回答占位，可能留下无正文的 “typing dots” 项。但发送按钮在 `streaming` 期间禁用，且当前 UI 未暴露并发发送入口，故当前不可达，仅作为后续聊天 UI 阶段（P3-03/P3-02）的提示，不阻塞本次交付。

**Suggested Fix**

无需现在修改。若 P3-02/P3-03 允许停止或并发操作，应改为按 `historyId` 归属判断，而非依赖单一 `streamAbort` ref，并补充对应测试。

---

## 四、无需修改 / 已确认正确

- 模型 ID 切换在 diff 内自洽：`run-review.ps1` 的 `ValidateSet`、目录预检正则、报告标题、`pi/AGENTS.md`、`stage-review-system.md`、`docs/06-operations`、`docs/08-reviews/README.md`、`AGENTS.md`、`definition-of-done.md`、`local-development.md` 均已同步；`pi-flash-review-contract.ps1` 新增对旧 `deepseek-v4-flash` 的拒绝断言。
- `markdown.ts` 抽取并保留 `MarkdownPreview.tsx` 的 re-export，未破坏既有导入方。
- 页面拆分使用 default export + `lazy`，`Suspense` fallback 使用 `role="status"`；`App.tsx` 未再残留 `MarkdownPreview`/`streamingPreviewText` 的悬空引用。
- `route.ts` 对 `/chat`、`/chat/:id`、`/wiki` 的解析、`decodeURIComponent` 异常回退、`popstate` 监听与 `navigate(path, replace)` 语义正确；URL 不携带 userId/凭据，符合“前端不提交 userId 或权限 Metadata”。
- 会话详情仍以 `projectId + conversationId` 经 Core API 读取，前端不承担授权判定，符合“Core API 是授权权威”。

---

## 五、主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 技术研判与依据 | 修复提交/验证 |
|---------|----------|----------------|----------------|
| F1 | 是 | 离开聊天时失效在途详情；新增路由离开回归，先红后绿。 | Web 全量 42/42 通过；待复审 |
| F2 | 部分 | 当前生效的 `run-review.ps1` 说明已更新；停用的 validation/loop 保留历史模型说明。 | Pi 合同测试通过 |
| F3 | 是 | P3-01 尚未收口，路线状态改为 In Progress。 | 文档差异核对 |
| F4 | 是 | 格式化内容应用到 Wiki 草稿时同步导航 `/wiki`。 | Web 全量测试和构建通过 |
| F5 | 否 | 现有测试按需清理存储，且全量测试通过；不增加与本次缺陷无关的清理改造。 | Web 全量 42/42 通过 |
| F6 | 部分 | 补齐 F1 路由回归；metadata 路由行为由现有交互实现，留在后续聊天阶段补充验收。 | F1 回归先红后绿 |
| F7 | 否 | 当前 UI 不允许并发发送，留给 P3-02 的流生命周期检查。 | 现有发送测试通过 |

> 说明：F1 为本轮唯一进入“必须修改”的阻塞项，请在修复并完成相称测试后进行一次只读复审（验证修复与识别修复引入的新问题）；F2–F6 可在同一轮修复中一并处置或给出明确豁免依据。
