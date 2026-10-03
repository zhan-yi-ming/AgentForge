# Pi 代码审查报告：r08-task-mode-capabilities / Attempt 1

- 日期：2026-10-03
- 审查阶段：r08-task-mode-capabilities
- 审查对象：INDEX@550656f（基线：550656f3b0b0f781622808960d4ad20e2961f1b4）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# R08 任务模式能力隔离 — Milestone Review 报告

## 一、概述与总体结论

- 审查阶段：`r08-task-mode-capabilities`
- 审查模式：Milestone（Round 1 / 3）
- 审查对象：`INDEX@550656f`（Commit 550656f）
- 总体结论：**通过（PASS）**。本次交付在 Python 上游能力门、Java 请求边界复核、异常 proposal 精确 Abort、文档与测试同步四个方向形成了完整闭环，未发现具备明确证据的真实 Bug、权限绕过、契约冲突、并发/幂等或数据一致性问题。
- 阻断性问题：无。
- 必须修改项：无。
- 结论依据：
  1. `graph.py` 新增 `TOOL_CAPABLE_TASK_TYPES = frozenset({"ANSWER", "PLAN"})`，采用**失败关闭（fail-closed）**白名单，未识别/受限 taskType 一律跳过 `tool_planner` 与 `with_tool`，不会建立 WAITING checkpoint，符合 ADR-0019 与 `model-routing.md` 契约。
  2. `AgentChatService` 在同步与流式两条路径均新增 `taskType.allowsToolProposal()` 复核：受限模式异常 proposal 精确 Abort 且不调用 `createPending`，与 `agent-service.md`「同步与流式一致」一致。
  3. 权限边界未变：能力门只做“是否允许进入 Tool 规划”的减法，不授予任何额外权限；真实写入仍由 Java Tool Metadata / Risk / Approval 决定。
  4. 文档（ADR-0019、model-routing、security-and-risk、agent-service）与新变更记录同步更新，无契约漂移。
  5. 正负向测试覆盖：Python 对 FORMAT/REWRITE/REVIEW × 同步/流式 做矩阵验证并断言 planner 零调用、无 checkpoint；Java 覆盖 FORMAT(sync)/REVIEW(stream) Abort 与 PLAN 保持既有流程；Web 保留 FORMAT taskType 与状态隔离回归。

---

## 二、详细发现清单（按严重程度排序）

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| SUG-01 | 建议（Low） | `apps/web/tests/app.test.tsx` | 1040–1075 | 移除了“格式化模式忽略意外 pendingAction”的回归断言，削弱纵深防御测试覆盖 |
| SUG-02 | 建议（Low） | `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java` | `finalizeResult` 约 158–169 | 新逻辑对 `taskType` 无 null 防护，与文档“null 默认为 ANSWER”契约存在潜在错配 |
| SUG-03 | 建议（Low） | `services/core-api/src/test/java/com/agentforge/core/agent/application/AgentChatServiceTest.java` | 新增测试段 | Java 侧矩阵仅覆盖 FORMAT(sync)/REVIEW(stream)，REWRITE 无 Java 侧覆盖；Python 侧已覆盖，风险低 |
| NOTE-01 | 无需修改 | `scripts/validation/v3-release-regression.ps1` | `-ExpectedTests 12 → 13` | 仅同步真实测试总数，需保留 Surefire 明细作为证据，未放宽 failures/errors/skips 断言 |
| NOTE-02 | 无需修改 | `services/agent-service/src/agentforge_agent/graph.py` | `plan()` 约 148–160 | 白名单失败关闭实现正确，上游跳过 planner 与 Java 复核形成双层保护 |

> 说明：不存在“必须修改”项，故未触发 NEEDS_FIX。

---

## 三、逐个 Issue 展开

### SUG-01 格式化模式对意外 `pendingAction` 的 Web 回归被删除

- Severity：Low（建议修改）
- File & Line：`apps/web/tests/app.test.tsx` 约 1040–1075
- Evidence（diff 片段）：

```diff
-  it("keeps formatting isolated from chat and ignores any proposed action", async () => {
-    const pending: AgentAction = { ... title: "Unexpected proposal" ... };
+  it("keeps formatting isolated from chat without an approval result", async () => {
     ...
-        conversationId: "format-conversation", answer: "# Formatted", requestId: "r5", sources: [], pendingAction: pending,
+        conversationId: "format-conversation", answer: "# Formatted", requestId: "r5", sources: [],
     ...
-    expect(screen.queryByText("Unexpected proposal")).not.toBeInTheDocument();
```

- Description：
  旧测试显式注入一个 FORMAT 场景下“意外”的 `pendingAction`，断言 Web 不把它渲染成审批条目。新测试移除了该夹具与断言，只保留 FORMAT `taskType`、不传 `conversationId` 与聊天状态隔离。后端现在确实保证 FORMAT/REWRITE/REVIEW 不返回 `pendingAction`，因此该场景在合规后端下不可达；但 Web 侧“即使收到也不当成正常结果”的这层纵深防御行为，在本次改动后**失去自动化覆盖**。若未来 Java Abort 分支回归、或 Python 门被绕过，Web 端是否安全降级将不再被检测到。
- Suggested Fix：
  在保留新的后端契约断言基础上，恢复一条聚焦的前端防御用例（可独立成测），确保 FORMAT 响应即便携带 `pendingAction` 也不会被消费/渲染：

```tsx
it("ignores an unexpected pendingAction in the formatting view", async () => {
  const pending: AgentAction = { id: "action-format", /* ... */ status: "PENDING", title: "Unexpected proposal" };
  const streamMock = vi.fn().mockResolvedValue({
    conversationId: "format-conversation", answer: "# Formatted", requestId: "r5", sources: [], pendingAction: pending,
  });
  const user = await login(api({ chatStream: streamMock }));
  await user.click(screen.getByRole("button", { name: "AI 文本整理" }));
  await user.type(await screen.findByLabelText("待整理原文"), "format me");
  await user.click(screen.getByRole("button", { name: "AI 整理并预览" }));
  expect(await screen.findByRole("heading", { name: "Formatted" })).toBeInTheDocument();
  expect(screen.queryByText("Unexpected proposal")).not.toBeInTheDocument();
});
```

---

### SUG-02 `finalizeResult` 对 `taskType` 缺少 null 防护

- Severity：Low（建议修改）
- File & Line：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java`，`finalizeResult` 约 158–169
- Evidence（diff 片段）：

```java
private AgentChatResult finalizeResult(UUID projectId, AuthenticatedActor actor, String requestId,
        AgentTaskType taskType, AgentChatResult result) {
    if (result.toolProposal() == null) {
        return result;
    }
    if (!taskType.allowsToolProposal()) {   // taskType 为 null 时 NPE
        abortWaitingRound(projectId, actor, result.conversationId(), result.toolProposal(), requestId);
        return result.withoutToolProposal();
    }
    return createPendingOrAbort(...)
            .map(result::withPendingAction)
            .orElseGet(result::withoutToolProposal);
}
```

- Description：
  改动前该分支只做 `result.toolProposal() == null` 判断，不会解引用 `taskType`；新逻辑在“存在 proposal”时调用 `taskType.allowsToolProposal()`。若 6 参重载被传入 `null`（例如 DTO 显式 `"taskType": null` 且未在边界归一化），将抛出 `NullPointerException`，最终以 500/503 结束，而不是按文档承诺回落到 ANSWER。`model-routing.md` 与 `agent-service.md` 均声明“省略或 null 默认为 ANSWER”，因此服务层对 null 采取防御性归一更稳妥。
- 补充说明：若边界层（Controller/DTO）已保证非空，此问题不可达，属纯防御建议；但当前 diff 未包含该归一化证据，且服务方法为 `public`，建议在服务入口显式收敛契约。
- Suggested Fix：

```java
public AgentChatResult chat(UUID projectId, AuthenticatedActor actor, String message,
        UUID conversationId, String requestId, AgentTaskType taskType) {
    AgentTaskType effectiveTaskType = taskType == null ? AgentTaskType.ANSWER : taskType;
    ...
    AgentChatResult finalized = finalizeResult(projectId, actor, requestId, effectiveTaskType, result);
    ...
}
```

或最小改动：

```java
if (taskType == null || !taskType.allowsToolProposal()) {
    abortWaitingRound(...);
    return result.withoutToolProposal();
}
```

---

### SUG-03 Java 侧受限模式矩阵未覆盖 REWRITE

- Severity：Low（建议修改）
- File & Line：`services/core-api/src/test/java/com/agentforge/core/agent/application/AgentChatServiceTest.java` 新增测试段
- Evidence：

```java
@Test void formatChatAbortsAnUnexpectedProposalWithoutCreatingAnAction() { ... }   // FORMAT sync
@Test void reviewStreamAbortsAnUnexpectedProposalWithoutCreatingAnAction() { ... }  // REVIEW stream
@Test void planModeKeepsTheExistingPendingActionFlow() { ... }                       // PLAN 正向
```

- Description：
  Java 侧新增覆盖为 FORMAT(同步) + REVIEW(流式) + PLAN(正向)，未单独覆盖 REWRITE；同步与流式两个入口的 Abort 分支均已被至少一个受限模式触达，Python 侧已对 FORMAT/REWRITE/REVIEW × 同步/流式全矩阵验证 `plan_calls == 0`，因此整体风险低，属测试广度补充。
- Suggested Fix：
  将 Java 受限模式用例参数化，或新增 `rewriteChatAbortsAnUnexpectedProposalWithoutCreatingAnAction`，确保三种只读模式在 Java 层均有 Abort 断言，避免后续因枚举新增/调整出现盲区。

```java
@ParameterizedTest
@EnumSource(value = AgentTaskType.class, names = {"FORMAT", "REWRITE", "REVIEW"})
void restrictedModeAbortsUnexpectedProposal(AgentTaskType taskType) { ... }
```

---

### NOTE-01 验证脚本测试计数同步（无需修改）

- File & Line：`scripts/validation/v3-release-regression.ps1` `Assert-SurefireResult ... -ExpectedTests 12 → 13`
- 审查意见：改动仅同步实际测试总数，未放宽 `failures/errors/skips` 的严格等值断言。变更记录给出了三份 Surefire 明细 `9 + 3 + 1` 的核对结论，并说明首次失败为硬编码陈旧值，重跑为 `PASS_PARTIAL`。属可接受的验证基建同步；建议在提交信息中保留“目标类零失败/零错误/零跳过”的原始输出链接以便追溯（非阻塞）。

### NOTE-02 Python 失败关闭实现正确（无需修改）

- File & Line：`services/agent-service/src/agentforge_agent/graph.py` `plan()` 约 148–160
- 审查意见：白名单 `frozenset({"ANSWER","PLAN"})` 决定是否调用 planner，未命中即 `proposal = None` 传入 `ContextManager.with_tool`，从上游阻断 WAITING checkpoint。与 `ADR-0019`「能力门先于 Tool Metadata/Risk/Approval，不授予任何额外权限」一致；与 Java 的二级 Abort 形成纵深防御，方向正确。

---

## 四、主开发（Codex）评估回填区

| Issue ID | 是否认可 | 处理方式（修复/延后/驳回） | 修复提交/说明 |
| --- | --- | --- | --- |
| SUG-01 | 不认可为应修缺陷 | 驳回 | 旧测试把“后端创建了不可见 Action，而前端静默忽略”固化为成功行为，正是 R08 要消除的错误契约。格式化视图无权补偿或终止服务端 workflow；后端 Python/Java 负向测试才是可靠防线。恢复该断言会掩盖回归，不做修改。 |
| SUG-02 | 不认可为可达缺陷 | 驳回 | `AgentChatRequest` compact constructor 已把省略/null 正规化为 `ANSWER`，Controller 对 ANSWER 调用五参重载；流式 `AgentChatCommand` 亦在 compact constructor 正规化。六参服务方法没有生产 null 调用方，当前文档边界已满足，不扩大范围。 |
| SUG-03 | 认可为测试广度建议 | 延后 | Java 能力判断集中在 `AgentTaskType.allowsToolProposal()`，不按具体受限枚举分支；现有 FORMAT(sync) 与 REVIEW(stream) 已覆盖两条 Java 路径，Python 另覆盖三种只读模式 × 同步/流式全矩阵。无真实盲区或阻塞，不为同义重复断言扩大本批。 |
| NOTE-01 | 认可 | 已核验 | Surefire 三类报告为 9 + 3 + 1，全部零失败、零错误、零跳过；更新后真实跨进程门禁重跑 PASS。 |
| NOTE-02 | 认可 | 已实现 | Python 白名单能力门与 Java 二级 Abort 保持不变。 |

---

## 五、审查结论汇总

- 本次 R08 目标“受限任务模式不得规划 Tool、异常 proposal 必须被精确 Abort”在 Python 上游与 Java 边界均已落地，且为失败关闭语义，未破坏 ANSWER/PLAN 既有 Tool 流程。
- 未发现权限绕过、跨用户越权、契约冲突、并发/幂等或数据一致性问题；无“必须修改”项，判定 `REVIEW_RESULT: PASS`。
- 三条建议均为非阻塞：Web 纵深防御用例可恢复（SUG-01）、服务层 taskType 归一化（SUG-02）、Java 受限模式矩阵补 REWRITE（SUG-03）。下一轮建议以验证上述建议与回归确认为主。
