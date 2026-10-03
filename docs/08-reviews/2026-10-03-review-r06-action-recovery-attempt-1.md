# Pi 代码审查报告：r06-action-recovery / Attempt 1

- 日期：2026-10-03
- 审查阶段：r06-action-recovery
- 审查对象：INDEX@86bd002（基线：86bd002efb18e1802769eac6572d024e71b3c6b3）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 代码审查报告 · r06-action-recovery（Milestone / 第 1 轮）

## 一、概述与总体结论

- **审查范围**：本次 commit 新增「可恢复 Action」读取能力，覆盖 Core API（新增 `GET /agent/actions/recoverable`、`RecoverableAgentActionView/Response`、repository 查询）、Web（`listRecoverableActions`、App 启动/会话加载恢复、审批弹窗 APPROVED 态）、文档与测试。
- **总体结论：通过（可交付，附建议改进）**。
- 未发现具备明确证据的 **必须修改** 项：权限仍走 `ProjectAccess.requireAccess` + `requestedByUserId` 双重隔离；终态过滤由 `StatusIn(PENDING, APPROVED)` 保证；APPROVED 恢复复用原决定键符合 V2-07 幂等语义；`conversationId` 改为可选与 MCP Action 契约一致；相关 service/API/持久化/前端测试覆盖了本次新增的主要分支。
- 存在若干**稳健性与行为边界**层面值得在后续轮次处理的问题（均为建议，不阻塞）。

## 二、详细发现清单

| ID | 级别 | 文件 | 行号 | 核心问题 | 归类 |
|----|------|------|------|----------|------|
| S1 | Major | apps/web/src/App.tsx | 235–252（init）、约 262–270（openConversation） | 新恢复接口并入 `Promise.all`，其失败会阻断项目基础数据加载与历史会话打开 | 建议修改 |
| S2 | Major | apps/web/src/pages/ActionApprovalDialog.tsx / App.tsx | 17–18（autoEligible） | 恢复的 PENDING Chat CREATE_TASK 会**重新**进入 60s 自动确认窗口，可能在用户未显式操作时执行旧提案 | 建议修改 |
| S3 | Minor | apps/web/src/App.tsx | 244–248、openConversation 分支 | 项目级恢复只取 `[0]`，处理完不推进、切路由后不再拉取，其余可恢复项需刷新才出现 | 建议修改 |
| S4 | Minor | services/core-api/src/test/.../AgentActionServiceTest.java、PersistenceIntegrationTest.java | 新增用例 | 缺少跨用户隔离与终态（EXECUTED/REJECTED/FAILED）排除的自动化证明；返回 `decisionKey` 属能力型字段 | 建议修改 |
| S5 | Minor | docs/04-api/core-api.md vs apps/web/src/api.ts | 文档 `string \| null` vs TS `decisionKey?: string` | 空值序列化口径（省略 vs null）未固定，两处表述不一致（当前实现无害） | 无需修改 |

> 未列入表格的其它审阅点（无问题）：ADMIN 恢复列表仍按 `requestedByUserId` 过滤、`APPROVED` 隐藏 reject 且禁用自动确认、MCP 恢复保留手动确认、`Idempotency-Key` 正则与生成键一致，均属正确或已文档化的有意设计。

## 三、逐个 Issue 展开

### S1 — 恢复接口失败会拖垮核心数据加载（Major / 建议修改）
**File & Line**：`apps/web/src/App.tsx` 项目初始化 effect 与 `openConversation`。

**Evidence（diff）**：
```ts
Promise.all([api.listWikiPages(projectId), api.listTasks(projectId), api.listConversations(projectId),
  api.listRecoverableActions(projectId)])
  .then(([pages, loadedTasks, loadedConversations, recoverableActions]) => { ... })
  .catch(report);
```
```ts
const [detail, recoverableActions] = await Promise.all([
  api.getConversation(requestedProjectId, selectedConversationId),
  api.listRecoverableActions(requestedProjectId, selectedConversationId),
]);
```

**Description**：可恢复 Action 是**辅助性**读取，但被放进了核心加载的 `Promise.all`。一旦新接口返回非 2xx/网络失败（如前后端滚动发布期前端先上线、后端旧版本返回 404，或该路径瞬时 5xx），`catch(report)` 会吞掉成功结果：Wiki/任务/会话列表全部不写入，`openConversation` 也会因整体 reject 而**不渲染任何聊天历史**。这是相对改动前的能力回退——单个非核心依赖失败即可让工作区与历史会话不可用。

**Suggested Fix**：将恢复读取做失败隔离，不参与核心读取的成败判定：
```ts
const [pages, loadedTasks, loadedConversations] = await Promise.all([
  api.listWikiPages(projectId), api.listTasks(projectId), api.listConversations(projectId),
]);
setWikiPages(pages); setTasks(loadedTasks); setConversationSummaries(loadedConversations);
api.listRecoverableActions(projectId)
  .then((recoverableActions) => { /* 现有恢复逻辑，需同时校验 active */ })
  .catch(() => { /* 恢复失败静默降级，不影响工作区加载 */ });
```
`openConversation` 同理：先 `getConversation` 渲染，再独立拉取恢复项。

### S2 — 恢复的 PENDING Chat 动作会重新武装自动确认（Major / 建议修改）
**File & Line**：`apps/web/src/pages/ActionApprovalDialog.tsx:17-18`。

**Evidence**：
```ts
const approved = action.status === "APPROVED";
const autoEligible = !approved && action.source !== "MCP" && action.actionType === "CREATE_TASK";
```

**Description**：本轮仅对 `APPROVED` 与 `MCP` 关闭自动确认。对于**恢复出来的 PENDING Chat CREATE_TASK**（`source === "CHAT"` 或未携带 source 的会话内新提案），弹窗挂载后重新计时 60 秒并调用 `onAutoDecision()`。由于服务端自动确认的前置条件是 Action 已存在 ≥60 秒（`automaticApprovalRejectsAnEarlyCreateWithoutWriting` 即此约束），一个可能来自数天前的旧提案在用户本次打开应用约 60 秒后即可自动写入，而用户并未对该恢复项做任何显式操作。这与功能文档「用户明确确认后 Java 才执行写入」的直觉存在张力（虽然自动确认是既有低风险策略）。

**Suggested Fix**：区分「新提案」与「恢复提案」，让恢复项一律需要显式确认（或至少不自动重新武装）：
```ts
type Props = { action: AgentAction; recovered?: boolean; ... };
const autoEligible = !recovered && !approved && action.source !== "MCP" && action.actionType === "CREATE_TASK";
```
若产品明确希望恢复项也可自动确认，建议在文档与测试中显式声明该语义，补充对应回归。

### S3 — 项目级恢复只展示首项且不随导航推进（Minor / 建议修改）
**File & Line**：`apps/web/src/App.tsx` 初始化 effect。

**Evidence**：
```ts
const recovered = recoverableActions[0];
setPendingAction(recovered ? { ...recovered.action, source: recovered.source } : undefined);
if (recovered?.decisionKey) decisionKeys.current.set(recovered.action.id, recovered.decisionKey);
```

**Description**：项目级恢复固定取最早一条；该 effect 仅在 `projectId` 变化时运行。用户处理完首条后 UI 不会拉取/推进到下一条；从 `/chat`（显示项目级项）导航到 `/chat/{id}` 后该入口被覆盖为 `undefined`，再回到 `/chat` 也不会重新拉取，需整页刷新才能看到。对「审批恢复」的完整性有一定影响。

**Suggested Fix**：把恢复项加载抽成可复用的 `refreshRecoverable(projectId, conversationId?)`，在决策成功、路由回到 `/chat` 无会话、项目切换时调用，并在前端维护一个可恢复队列，决策完成后自动推进到下一条。

### S4 — 隔离与终态排除缺少自动化证据（Minor / 建议修改）
**File & Line**：`AgentActionServiceTest`、`PersistenceIntegrationTest` 新增用例。

**Description**：新增用例覆盖了「PENDING 无键 / APPROVED 返键」「conversation 过滤只含 Chat」与排序，但：
1. 没有 **B 用户无法读到 A 用户可恢复 Action（含 `decisionKey`）** 的断言。当前隔离完全依赖 Spring Data 派生方法名 `...AndRequestedByUserId...` 与 service 传 `actor.userId()`，机制简单但属安全敏感路径。
2. 没有 **EXECUTED / REJECTED / FAILED 不返回** 的断言（`statuses = List.of(PENDING, APPROVED)` 仅在适配器内，单元测试 mock 了 repository，未验证该常量）。

**Suggested Fix**：在 `PersistenceIntegrationTest` 增加第二用户查询返回空、`claim` 终态 Action 后不出现在恢复列表的断言；或在 `AgentActionRepositoryAdapter` 上补一个切片测试。

### S5 — decisionKey 空值契约表述不一致（Minor / 无需修改）
**File & Line**：`docs/04-api/core-api.md`（`decisionKey: string | null`）vs `apps/web/src/api.ts`（`decisionKey?: string`）。

**Description**：文档写 `string | null`，前端类型写可选（缺失）。是否返回 `null` 取决于 Jackson 的 `NON_NULL` 策略；两种情况下前端 `if (recovered?.decisionKey)` 都能正确处理，故当前无功能影响。建议后续统一文档与类型口径。

**Suggested Fix**：统一为 `decisionKey?: string | null` 或在 API 文档明确「null 时字段省略/为 null」。

## 四、主开发 (Codex) 评估回填区

| Issue ID | 采纳结论（采纳/不采纳/部分采纳） | 理由 | 处理方式 | 验证证据 |
|----------|-------------------------------|------|----------|----------|
| S1 | 采纳 | 新恢复 GET 失败拖垮核心工作区/历史是可复现的既有能力回退。 | 项目基础读取先落 UI，再独立读取恢复队列；历史详情先渲染，再隔离恢复请求失败并报告。 | App 定向组首次 68/69 暴露测试 mock 顺序问题；最终完整 `app.test.tsx` 56/56 通过，新增 “keeps history usable when approval recovery is unavailable”。 |
| S2 | 采纳 | 旧 PENDING Chat CREATE 在重新打开后重新计时可能造成无新决定的延迟写入，偏离恢复入口的人工处理语义。 | 给客户端恢复 Action 标记 `recovered`，恢复的 Chat/MCP Action 均不重新启动 auto-confirm；实时 Chat 提案保持既有策略。 | `does not automatically reapprove a recovered pending Chat create` 与既有 `approval-timeout.test.tsx` 同组验证；修正夹具后 App 55/55 通过。 |
| S3 | 采纳 | 只展示首项但不推进会使同项目剩余审批仍需刷新，未完整满足恢复目标。 | 决策完成、新建会话及从 conversation 返回空 Chat 路由后重新查询，并推进队列首项。 | 新增 `advances to the next recoverable action after a decision`；最终 App 完整测试 56/56。一次过宽的空 Chat effect 导致 11 个路由回归，收敛为仅在新建/返回会话时刷新后全部恢复通过。 |
| S4 | 采纳 | `decisionKey` 属能力型字段，安全路径值得真实数据库证据。 | PostgreSQL 集成测试新增另一个 ADMIN actor 在同项目查询为空，并在 Chat=REJECTED、MCP=EXECUTED 后断言恢复列表为空。 | 定向 `PersistenceIntegrationTest#recoverableActionsIncludeChatAndMcpButConversationScopeReturnsOnlyItsChatAction` 1/1 通过。 |
| S5 | 采纳 | 统一类型能避免把省略字段误解为固定 JSON null。 | TS 改为 `decisionKey?: string | null`；API 文档已说明为空或省略。 | `npm run build` 成功。 |

## 五、审查边界说明

- 本轮为只读审查，未执行命令、未修改任何文件与 Git 状态。
- 报告中的测试/构建结论仅来自交付方在变更文档中的记录（`mvnw ... test`、`clean verify`、`npm test`、`npm run build`），非审查者复现结果。
- 未发现需要升级为 `NEEDS_FIX` 的阻塞项；建议在下一轮针对 S1/S2 的返工同步验证修复未引入新问题。
