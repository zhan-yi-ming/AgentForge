# Pi 代码审查报告：pre-v3-approval-timeout / Attempt 1

- 日期：2026-09-20
- 审查阶段：pre-v3-approval-timeout
- 审查对象：INDEX@c08beba（基线：c08bebaf82b3943c1cda06d154bc781cca78e86c）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 代码审查报告 — P3-06 审批时限（pre-v3-approval-timeout）

## 一、概述与总体结论

- **审查对象**：Commit INDEX@c08beba，21 个改动文件（后端 Java 6 个、前端 TS/CSS 8 个、文档 5 个、迁移 1 个）。
- **审查阶段/模式**：Milestone Review，轮次 1/3。
- **任务目标**：悬浮审批表单 60 秒倒计时；仅 Java Tool Policy 明确为 LOW 且列入白名单的 `CREATE_TASK` 可在弹窗持续打开、倒计时结束后走独立 `auto-confirm` 入口自动确认；MEDIUM/HIGH 不得自动通过；服务端以持久化 `created_at + 60s` 为下限，行为幂等、可审计。
- **总体结论**：**通过（PASS）**。核心安全边界实现正确：风险与时限由 Java 服务端二次判定，前端计时器仅作 UI 提示；复核链路 `authorize → 风险/时限校验 → 行锁 → 幂等 → 审计 → resume → execute` 完整；审计通过新增 `AUTO_APPROVED` 类型与 V10 迁移区分来源。未发现具备明确证据的可运行性、正确性、安全、权限、并发、契约或方向性问题。
- **未发现阻断性问题。**

依据的判断要点：
1. 自动确认的风险判定使用 `riskEngine.authorize(operation,...)` 返回的 `ToolMetadata`，并额外要求 `operation == CREATE_TASK`、`riskLevel == LOW`、`needApproval`，属白名单而非黑名单，方向安全。
2. 时限使用服务端注入 `Clock`：`Instant.now(clock).isBefore(action.getCreatedAt().plusSeconds(60))` 抛 `ConflictException`，浏览器时间不可影响，符合 ADR-0028。
3. 自动与手动共用同一幂等键（`decisionKeys.current` 按 actionId 存储），并发/竞态请求在行锁 + `requireMatchingKey` 下至多执行一次。
4. 权限仍以 `projectAccess.requireAccess` + `findForDecision`（项目/actor 作用域）为前置，未绕过 V2 安全体系。
5. 未引入 V2/V3 后续节点组件（无 Langfuse、MCP、LiteLLM、GraphRAG 等），节点边界未被破坏。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 位置（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| S1 | 建议（Low） | apps/web/src/pages/ActionApprovalDialog.tsx | L19（`autoEligible`） | 前端硬编码 `CREATE_TASK` 复刻后端 Tool Policy，策略漂移时 UI 显示自动确认但请求被 403，用户卡在 0 秒 |
| S2 | 建议（Low） | apps/web/tests/approval-timeout.test.tsx | 全文件 | 未覆盖自动确认请求失败（403/409/网络）后的 UI 行为，`autoSent` 不重试逻辑无测试保护 |
| S3 | 建议（Low） | services/core-api/src/test/.../AgentActionApiTest.java | L70-90 | HTTP 层缺少 auto-confirm 未到期 409、非低风险 403、未认证 401 的负向测试（服务层已覆盖逻辑） |
| S4 | 建议（Trivial） | apps/web/src/pages/ActionApprovalDialog.tsx | L22-33（effect deps） | `onAutoDecision` 为内联箭头，父组件每次渲染都会重建 interval（churn） |
| N1 | 无需修改 | AgentActionService / AgentActionController | — | 权限、项目/actor 作用域复核保持完整 |
| N2 | 无需修改 | App.tsx `decideAction` | L565-590 | 手动/自动共用幂等键，竞态下至多一次执行 |
| N3 | 无需修改 | AgentAuditEventType / V10 迁移 | — | 审计区分 APPROVED/AUTO_APPROVED 且迁移约束正确 |

## 三、逐个 Issue 展开

### S1 — 前端硬编码自动确认白名单与后端策略重复（Severity: Low / 建议）

- **File & Line**：`apps/web/src/pages/ActionApprovalDialog.tsx` L19（`const autoEligible = action.actionType === "CREATE_TASK";`）

- **Evidence**：
```tsx
const autoEligible = action.actionType === "CREATE_TASK";
...
<p className="approval-countdown" role="timer" aria-live="off">{remaining} 秒{autoEligible ? "后自动确认" : "后仍需手动确认"}</p>
```
后端为唯一权威：`services/core-api/.../AgentActionService.java` 中
```java
if (operation != ToolOperation.CREATE_TASK || metadata == null
        || metadata.riskLevel() != RiskLevel.LOW || !metadata.needApproval()) {
    throw new ForbiddenException("This action requires manual confirmation.");
}
```

- **Description**：前端以 `actionType` 字符串自行推断“可自动确认”，与 Java Tool Policy 形成两处事实源。若未来策略调整（例如 `CREATE_TASK` 被提升为 MEDIUM），前端仍会展示 60 秒倒计时并发出 `auto-confirm`，服务端返回 403；由于 `autoSent.current` 置位后不再重试，用户会看到 0 秒倒计时 + 错误提示却无自动恢复。**安全性不受影响**（服务端拒绝，绝不会越权执行），但属可维护性与 UX 风险。

- **Suggested Fix**：保持 Java 为唯一事实源；前端可仅在注释/常量集中声明，并在请求失败时给出可操作提示，例如：

```tsx
// 说明：autoEligible 仅为 UI 提示，真正的风险判定由 Java Tool Policy 决定。
const autoEligible = action.actionType === "CREATE_TASK";
```
并在 `App.tsx` 的自动路径错误分支中显式提示“自动确认未被服务端接受，请手动确认”：
```tsx
catch (cause) {
  if (automatic) setError("自动确认未被服务端接受，请手动确认。");
  report(cause);
}
```

### S2 — 缺少自动确认失败路径的前端测试（Severity: Low / 建议）

- **File & Line**：`apps/web/tests/approval-timeout.test.tsx`、`apps/web/tests/app.test.tsx`

- **Evidence**：本 diff 新增断言仅覆盖成功自动确认（`app.test.tsx` "routes an elapsed low-risk dialog through Java auto-confirm"）与倒计时/中风险/卸载（`approval-timeout.test.tsx`）。

- **Description**：核心分支（到期触发、中风险不触发、卸载取消）已有测试，但 `autoSent` 防重入在“请求被拒后不重试”的行为没有测试保护。该行为是 `docs/07-changes/2026-09-20-p3-06-approval-timeout.md` 中“失败不降级为手动确认”的显式约定。

- **Suggested Fix**：补充用例，令 `autoConfirmAction` reject（模拟 403/409），断言：
```tsx
await waitFor(() => expect(autoConfirmAction).toHaveBeenCalledTimes(1));
await act(() => vi.advanceTimersByTime(30_000));
expect(autoConfirmAction).toHaveBeenCalledTimes(1); // 不重试
expect(screen.getByRole("alert")).toBeInTheDocument();
```

### S3 — HTTP 层负向测试缺口（Severity: Low / 建议）

- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentActionApiTest.java` L70-90

- **Evidence**：新增测试 `timedAutoConfirmationHasADedicatedAuthenticatedRoute` 仅验证 200 路由与参数透传。

- **Description**：变更记录验证计划写明“未到时拒绝、低风险到期一次执行、中高风险自动拒绝、跨用户/项目”，服务层 `AgentActionServiceTest` 已覆盖到期、未到期、非低风险与幂等；但 HTTP 状态码映射（未到期→409、非低风险→403、未认证→401）未有 API 级测试。逻辑已由服务层保证，故列为建议而非阻塞。

- **Suggested Fix**：新增 `mockMvc` 用例，mock `workflowService.confirmAutomatically` 抛 `ConflictException`/`ForbiddenException`（或使用真实服务），断言 `status().isConflict()` / `isForbidden()`，并补一条无 JWT 的 401 用例。

### S4 — interval 因内联回调反复重建（Severity: Trivial / 建议）

- **File & Line**：`apps/web/src/pages/ActionApprovalDialog.tsx` L22-33；`apps/web/src/App.tsx` `onAutoDecision={() => void decideAction("confirm", true)}`

- **Evidence**：
```tsx
useEffect(() => {
  const tick = () => { ... };
  const timer = window.setInterval(tick, 250);
  return () => window.clearInterval(timer);
}, [autoEligible, busy, onAutoDecision]);
```
`onAutoDecision` 每次父渲染都是新引用，触发 effect 清理与重建。

- **Description**：`deadline` 为 ref，重建 interval 不会重置倒计时（无功能缺陷），仅造成每 250ms 级不必要的 interval churn 与潜在调度抖动。

- **Suggested Fix**：用 ref 固定回调并缩小依赖：
```tsx
const autoDecisionRef = useRef(onAutoDecision);
useEffect(() => { autoDecisionRef.current = onAutoDecision; }, [onAutoDecision]);
useEffect(() => {
  const tick = () => { /* ... */ autoDecisionRef.current(); };
  const timer = window.setInterval(tick, 250);
  return () => window.clearInterval(timer);
}, [autoEligible, busy]);
```

## 四、无需修改确认

- **N1 权限与作用域**：`approveInternal` 先 `projectAccess.requireAccess`，再 `findForDecision(projectId, actionId, actor)`，最后 `riskEngine.authorize`。自动路径同样受限，无跨项目/跨用户越权证据。
- **N2 幂等与并发**：`findByProjectIdAndIdForUpdate` 行锁 + `requireMatchingKey`；自动与手动经 `decisionKeys.current` 共用同一 key，重放返回既有终态，符合 V2-06 语义。
- **N3 审计与迁移**：新增 `AUTO_APPROVED` 枚举与 `V10__add_auto_approval_audit_type.sql` 更新 CHECK 约束；审计在审批事务内写入，未记录敏感信息。
- **N4 前后端职责边界**：倒计时为 UI，服务端以 `created_at + 60s` 为下限并以注入 `Clock` 判定；`auto-confirm` 为独立入口，Python 仅在 Java 决定后 resume，符合 ADR-0028 与 P3-06 目标。

## 五、Codex 评估回填区

| Issue ID | 是否采纳 | 处理方式 | 修复提交/说明 |
|----------|----------|----------|----------------|
| S1 | 待填 | | |
| S2 | 待填 | | |
| S3 | 待填 | | |
| S4 | 待填 | | |

---

**结论**：本次交付在 P3-06 的目标、边界与安全语义上成立，未发现必须修改项，**REVIEW_RESULT: PASS**。上述建议项不阻塞交付，可由 Codex 在文档先行后按需处理。
