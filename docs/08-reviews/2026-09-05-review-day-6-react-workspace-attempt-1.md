# Pi 代码审查报告：day-6-react-workspace / Attempt 1

- 日期：2026-09-05
- 审查阶段：day-6-react-workspace
- 审查对象：WORKTREE@6168926（基线：61689269f28004d8a172c97a1d6cf40f2f89683b）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge Day 6 React Workspace 独立只读审查报告

- 审查阶段：day-6-react-workspace
- 审查轮次：1 / 3
- 审查目标：WORKTREE@6168926
- 审查方式：完全只读，仅依据提供的文件清单、Git Diff 采样与变更摘要
- 模型声明：DeepSeek V4-pro（未降级）

## 概述与总体结论

**结论：需修复后交付（NEEDS_FIX）**，无阻断性（Blocking）问题。

本次 Day 6 主要交付了 `apps/web`（React + TypeScript + Vite）首个可运行工作区与配套文档。整体实现质量良好：前端边界清晰（仅消费 Core API、sessionStorage 令牌、Markdown 不启用原始 HTML、无 `dangerouslySetInnerHTML`）、确认/拒绝交互与 Wiki 保存交互都有组件测试覆盖，文档先行与 Day 5 遗留问题回填也基本到位。

但审查发现 3 项中等级别（M）与 3 项低级别（L）问题，集中在：

1. 关键契约（Chat / confirm / reject）未在真实链路中被验证（Day 6 的“真实链路与视觉验证”仅覆盖 CRUD）。
2. `formatText` 复用正式对话 `conversationId` 并静默忽略响应中的 `pendingAction`。
3. 切换项目时未清空 `formatInput` / `formattedText`，与文档契约不一致，存在跨项目草稿混淆。
4. 测试全局 stub 未完整清理、Markdown 自定义渲染器透传非 DOM 属性、本地开发文档步骤编号错位。

以下按严重度从高到低列出，最多十项。

## 详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| AF-D6-01 | M | docs/07-changes/2026-09-05-day-6-react-workspace.md；apps/web/src/api.ts | — | Chat / confirm / reject 前端契约仅靠 mock 覆盖，真实链路验证只覆盖 CRUD，存在契约不一致的未验证风险 |
| AF-D6-02 | M | apps/web/src/App.tsx | L129–137 | `formatText` 复用正式对话 `conversationId`，且忽略响应 `pendingAction`，可能造成悬挂 PENDING 与对话上下文污染 |
| AF-D6-03 | M | apps/web/src/App.tsx | L62–69 | 切换项目时未清空 `formatInput` / `formattedText`，违反文档“切换项目清空项目相关草稿”，可能跨项目误用草稿 |
| AF-D6-04 | L | apps/web/tests/setup.ts；apps/web/tests/api.test.ts | setup.ts L5–9 | `vi.restoreAllMocks()` 不会恢复 `vi.stubGlobal` 的 `fetch`，存在全局 stub 残留风险 |
| AF-D6-05 | L | apps/web/src/MarkdownPreview.tsx | L10 | 自定义 `a` 组件未剥离 `node` 等非 DOM props，React 会产生 unknown prop 警告 |
| AF-D6-06 | L | docs/05-development/local-development.md | 7A 节 | 7A 节复用“打开第四个 PowerShell”，与第 7 节重复，应为第五个窗口 |

---

## 逐项 Issue 展开

### AF-D6-01 — Chat / confirm / reject 契约仅 mock 覆盖，真实链路未验证

- **Severity**：M
- **File & Line**：apps/web/src/api.ts（AgentChat / AgentAction 契约定义）；docs/07-changes/2026-09-05-day-6-react-workspace.md（真实链路与视觉验证）

- **Evidence**（前端契约字段）：
  ```ts
  export type AgentAction = {
    id: string; projectId: string; conversationId: string;
    actionType: "CREATE_TASK" | "UPDATE_TASK";
    status: "PENDING" | "EXECUTED" | "REJECTED";
    taskId?: string; expectedVersion?: number;
    title?: string; description?: string; taskStatus?: string; priority?: string;
    resultTask?: Task; createdAt: string; decidedAt?: string;
  };
  export type AgentChat = { conversationId: string; answer: string; requestId: string; sources: AgentSource[]; pendingAction?: AgentAction };
  ```
  文档“真实链路与视觉验证”仅记录：
  > 通过 `http://127.0.0.1:5173/api/v1` 完成注册、Bearer 鉴权、创建项目、创建 Markdown Wiki，并读取 Project/Wiki/Task 接口。

- **Description**：Day 6 的核心交互是 Chat、`pendingAction` 展示与 confirm/reject。但前端对这些字段（`conversationId`、`actionType`、`expectedVersion`、`taskStatus`、`priority`、`resultTask` 等）的契约只能由 Vitest 中的 mock `ApiClient` 验证，文档声称的真实链路联调只覆盖了 `auth/register`、`auth/login`、`projects`、`wiki-pages`、`tasks` 等 CRUD 端点，**没有覆盖 `/agent/chat`、`/agent/actions/{actionId}/confirm`、`/agent/actions/{actionId}/reject`**。若后端 Day 5 实际响应字段与前端推断不一致（例如字段名为 `expectedTaskVersion` 而非 `expectedVersion`，或 action 响应不含 `resultTask`），运行时才会暴露。

- **Suggested Fix**：
  1. 由 Codex 补充一次真实 HTTP 联调：调用 `/api/v1/projects/{id}/agent/chat` 造出一个 `PENDING` 动作，并逐个断言前端 `AgentChat` / `AgentAction` 全字段与真实 JSON 完全一致。
  2. 将该联调证据回填到 `docs/07-changes/2026-09-05-day-6-react-workspace.md`，明确注明覆盖了 chat/confirm/reject 契约，而非仅 CRUD。
  3. 在证据中记录真实响应样例（脱敏后的 JSON），作为 Day 7 契约基线。

---

### AF-D6-02 — `formatText` 复用正式对话 conversationId，且丢弃响应 pendingAction

- **Severity**：M
- **File & Line**：apps/web/src/App.tsx L129–137（`formatText`）

- **Evidence**：
  ```tsx
  async function formatText() {
    if (!projectId || !formatInput.trim()) return;
    setBusy(true); setError("");
    try {
      const result = await api.chat(
        projectId,
        `请将以下内容整理为 Markdown，保留事实，不执行写入：\n\n${formatInput.trim()}`,
        conversationId,
      );
      setConversationId(result.conversationId);
      setFormattedText(result.answer);
    } catch (cause) { report(cause); } finally { setBusy(false); }
  }
  ```

- **Description**：错误 1：`formatText` 复用当前项目的聊天 `conversationId`，会把“文本整理”请求混入正式 Agent 对话上下文，影响后续 Chat 的连续上下文语义。错误 2：该调用只取 `result.answer`，完全不检查 `result.pendingAction`。虽然 prompt 要求“不执行写入”，但 Agent 侧可能因确定性 responder 或用户提供的原文（prompt 注入）而返回 `pendingAction`；此时前端既不会展示也不会确认/拒绝，动作会永久停在 `PENDING`，用户无感知。前端也未对 `formatInput` 做净化，prompt 注入风险被直接转嫁给后端契约，但前端应当至少处理 `pendingAction` 的可见性。

- **Suggested Fix**：
  1. `formatText` 请求不传 `conversationId`（保持独立一次性请求），避免污染正式对话上下文。
  2. 处理响应中的 `pendingAction`：若存在，应 `setPendingAction(result.pendingAction)` 并在界面上展示，或至少给出错误提示“整理请求未产生可确认动作”，不允许静默丢弃。
  3. 后续如需保留格式化历史，可将 format 会话与 Chat 会话分离（不同 conversationId 或不传）。

---

### AF-D6-03 — 切换项目未清空 `formatInput` / `formattedText`

- **Severity**：M
- **File & Line**：apps/web/src/App.tsx L62–69（项目切换 effect）

- **Evidence**：
  ```tsx
  useEffect(() => {
    if (!projectId) return;
    let active = true;
    setError("");
    setConversationId(undefined);
    setAnswer("");
    setSources([]);
    setPendingAction(undefined);
    Promise.all([api.listWikiPages(projectId), api.listTasks(projectId)])
      .then(([pages, loadedTasks]) => { /* ... */ selectWiki(pages[0]); })
      .catch(report);
    return () => { active = false; };
  }, [api, projectId, report, selectWiki]);
  ```
  文档契约 `docs/02-architecture/frontend-architecture.md`：
  > AI 整理的原始输入、返回 Markdown 和 Wiki 草稿是三个显式状态……切换项目时清空 Chat/pending action。
  `docs/03-features/web-workspace.md`：
  > 切换项目清空项目相关草稿、conversation 和 pending action。

- **Description**：实现清空了 `conversationId` / `answer` / `sources` / `pendingAction`，并通过 `selectWiki(pages[0])` 重置了 Wiki 草稿，但没有清空 `formatInput` 与 `formattedText`。用户从项目 A 切换到项目 B 后，A 的“AI 文本整理”原文与整理结果仍保留在面板中，点击“应用到 Wiki 草稿”会把 A 的内容覆盖到 B 的 Wiki 草稿。该跨项目混淆需要用户显式点击方可触发，且草稿未保存，因此定为中等级别而非高危，但与已写明的“清空项目相关草稿”契约不一致。

- **Suggested Fix**：
  ```tsx
  setPendingAction(undefined);
  setFormatInput("");
  setFormattedText("");
  ```
  在上述 `useEffect` 清理区补充两行，使三条项目相关草稿（Wiki 草稿、格式化原文、格式化结果）在切换项目时全部重置。

---

### AF-D6-04 — 测试全局 stub 未完整清理

- **Severity**：L
- **File & Line**：apps/web/tests/setup.ts L5–9；apps/web/tests/api.test.ts L4–23

- **Evidence**：
  ```ts
  // setup.ts
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.restoreAllMocks();
  });
  ```
  ```ts
  // api.test.ts 两个用例均使用 vi.stubGlobal("fetch", ...)
  vi.stubGlobal("fetch", fetchMock);
  ```

- **Description**：`vi.restoreAllMocks()` 只恢复 `vi.spyOn` 创建的 mock，**不会**恢复经 `vi.stubGlobal` 注入的全局 `fetch`。当前 api.test.ts 中两个用例连续执行时第二次 `stubGlobal` 会覆盖第一次，影响暂未显现；但一旦其他测试文件扩容并依赖真实 `fetch`，残留 stub 会造成非确定性污染。测试卫生应在 `afterEach` 中同时清理全局 stub。

- **Suggested Fix**：
  ```ts
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });
  ```

---

### AF-D6-05 — Markdown 自定义 `a` 组件未剥离非 DOM props

- **Severity**：L
- **File & Line**：apps/web/src/MarkdownPreview.tsx L10

- **Evidence**：
  ```tsx
  components={{ a: ({ children, ...props }) => <a {...props} target="_blank" rel="noreferrer noopener">{children}</a> }}
  ```

- **Description**：react-markdown 的自定义组件回调会额外传入 `node`、`urlTransform` 等非 DOM 属性；将 `...props` 直接展开到 `<a>` 会把 `node` 传递到真实 DOM，React 会在控制台输出 “React does not recognize the x prop on a DOM element” 警告。功能不受影响，但会污染开发/测试控制台，降低真实告警的信噪比。

- **Suggested Fix**：
  ```tsx
  components={{ a: ({ node: _node, ...props }) => <a {...props} target="_blank" rel="noreferrer noopener">{props.children}</a> }}
  ```
  或显式解构 `({node, ...props})`，仅透传合法的锚点属性。同时确认 `react-markdown` 版本默认 `urlTransform` 已拦截 `javascript:` 协议（v10 默认安全），保持现状即可。

---

### AF-D6-06 — 本地开发文档 PowerShell 窗口编号重复

- **Severity**：L
- **File & Line**：docs/05-development/local-development.md（“7A. 启动 React Web” 节）

- **Evidence**：
  > ## 7. 检查两个服务 —— 打开第四个 PowerShell：……
  > ## 7A. 启动 React Web —— 打开第四个 PowerShell：……

- **Description**：新增的 7A 节沿用了第 7 节的“第四个 PowerShell”，导致同一窗口编号出现两次，后续“注册并取得 JWT”等步骤的窗口编号逻辑被打乱，给读者造成困惑。属文档精度问题，不涉及功能。

- **Suggested Fix**：将 7A 节文字改为“打开第五个 PowerShell”（或重新理顺 7/7A/8 的窗口序号），保持每步的 PowerShell 编号唯一且连续。

---

## 主开发 (Codex) 评估回填区

| ID | Codex 评估 | 处置方式 | 验证证据 | 状态 |
| --- | --- | --- | --- | --- |
| AF-D6-01 | 采纳；核心动作契约需要真实证据 | 复用隔离跨进程 E2E 验证 chat/confirm/reject 及 JSON 字段 | E2E PASS：确认前 0、重复确认后 1、更新 version=1、拒绝后仍 1、跨用户 403 | 已解决 |
| AF-D6-02 | 采纳；违反会话隔离和可见确认边界 | formatText 不传 conversationId，若返回 pendingAction 则展示 | 新增失败测试后修复；最终 Vitest 10/10 | 已解决 |
| AF-D6-03 | 采纳；与项目切换契约不一致 | 切换项目清空 formatInput/formattedText | 新增失败测试后修复；最终 Vitest 10/10 | 已解决 |
| AF-D6-04 | 采纳 | setup.ts 增加 vi.unstubAllGlobals() | Vitest 10/10 | 已解决 |
| AF-D6-05 | 采纳 | MarkdownPreview 仅透传合法锚点属性 | TypeScript/Vite production build 成功 | 已解决 |
| AF-D6-06 | 采纳 | 服务检查为第四个窗口，React Web 为第五个窗口 | 文档复核 | 已解决 |

**说明**：本次审查不运行命令、不执行测试、不修改任何文件或 Git 状态。Codex 的测试记录仅作为证据材料引用，本报告未将其改写为 Pi 自行执行的结果。

## Codex 最终处置

- 处置：`ACCEPTED_AFTER_BATCH_FIXES`
- 阻断项：0；Pi 明确报告无 Blocking。
- 最终验证：Vitest 3 个文件 10 项全部通过；Vite production build 成功；Java clean verify 75 项、0 失败、0 错误、6 跳过；真实 Chat/confirm/reject 跨进程 E2E PASS。
- 复审决定：按用户要求，小问题集中修正且不反复审核；本批没有重大架构、安全或不可运行问题，因此不启动 Attempt 2。
