# Pi 代码审查报告：r02-action-checkpoint-compensation / Attempt 2

- 日期：2026-10-03
- 审查阶段：r02-action-checkpoint-compensation
- 审查对象：INDEX@2dab22e（基线：2dab22eaf4b14e2eca1d4ebd489492e703267e31）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# R02 Action Checkpoint Compensation — Milestone Review (Round 2/3)

## 概述与总体结论

- 审查对象：`INDEX@2dab22e`（r02-action-checkpoint-compensation），Milestone 模式，只读。
- 审查范围：本次 diff 全部 22 个文件、上一轮报告的 6 项发现及其修复、以及修复可能引入的新问题。
- 上一轮结论：`NEEDS_FIX`，唯一阻断项 `R02-01`（流式补偿失败分支无有效测试且无法证明终止事件）。修复后：
  - `R02-01`：成功用例断言改为 `containsExactly("metadata","complete")`，新增 service 级失败传播测试 `streamPropagatesAbortFailureWithoutEmittingComplete`，新增 Controller 级 `chatStreamEmitsErrorWhenApplicationStreamFailsAfterMetadata`（验证 `metadata → error`、无 `complete`）。**阻断项已解除**。
  - `R02-02`：`core-api.md` 已对齐为“404/409 → 公共 409；网络/5xx/空响应/身份不匹配 → 503”，与 `HttpAgentServiceClient.abort`、`agent-service.md`、ADR-0038 一致。
  - `R02-04`：RESUMED 用例改用恢复后的 `resume-request`，真正命中状态守卫；新增同 request 在 Abort 后重建 WAITING 的测试。
  - `R02-05`：两个 planner 统一走 `tool_proposal_or_none`，捕获 `ValidationError` 降级为无 proposal。
  - `R02-03`：Codex 以 ADR-0038 非目标为由驳回，结论合理（非阻断）。
- 本次未发现具有明确证据的真实 Bug、安全/权限绕过、契约冲突或数据一致性问题。

结论：**通过（PASS）**。仅有若干建议级改进与测试补强，不阻塞交付。

---

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- |
| R02-07 | Minor | `AgentChatService.java` `createPendingOrAbort` | ~160-185 | 并发相同 request 重试时，若 `createPending` 在唯一约束冲突路径抛异常，补偿 Abort 可能终结另一个已成功持久化 Approval 的 checkpoint（需确认 `createPending` 对重复创建的处理） |
| R02-03 | Minor | `AgentChatService.java` `effectiveConversationId` / `persist` | ~143-170 | 确定性 conversationId 使同 `X-Request-Id` 重试落回同一会话，`appendCompletedExchange` 无请求级幂等→重复写入展示历史（已记录为非目标） |
| R02-08 | Minor | `HttpAgentServiceClient.abort` | ~175-205 | 缺少 abort 客户端 404/409→公共 409、5xx→503 的状态映射单测 |
| R02-09 | Minor | `action_runtime.py` `resume` / `abort` | ~130-205 | 缺少“ABORTED 后 resume 失败关闭”的自动化测试 |

### 无需修改

| ID | 严重级别 | 文件 | 说明 |
| --- | --- | --- | --- |
| R02-10 | Info | `tool_planner.py` | `_parse_create_body` 前由 diff 移除两个空行，仅风格问题；Python 语法无影响，不阻塞 |
| R02-06 | Info | `action_runtime.py` / `api.py` | ABORTED 终态、abort replay、v1 拒绝 Abort、Namespace/request/workflow 精确匹配、内部 token 保护、`_require_supported` 复用均正确；Python/Java proposal 文本边界（trim + 1–200 / 1–2000）已统一 |

---

## Issue 展开

### R02-07（建议修改）补偿 Abort 与并发幂等创建的竞态

- Severity: Minor（需确认，未阻断）
- File & Line: `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java` `createPendingOrAbort` ~160-185
- Evidence：

```java
catch (RuntimeException failure) {
    try {
        abortWaitingRound(projectId, actor, conversationId, proposal, requestId);
    }
    catch (RuntimeException compensationFailure) {
        compensationFailure.addSuppressed(failure);
        throw compensationFailure;
    }
    throw failure;
}
if (pending.isEmpty()) {
    abortWaitingRound(projectId, actor, conversationId, proposal, requestId);
}
```

- Description：
  相同 `X-Request-Id` 并发重试时，两个线程都会命中同一 LangGraph WAITING 轮次与同一 `actionWorkflowId`。若两个线程都先查询“无既有 Approval”再插入，后提交者可能因 workflow ID 唯一约束抛异常，从而进入 `catch` 并调用 Abort；而先提交者已持久化 PENDING Approval，此时 Python 侧被终结为 ABORTED，导致“存在 Approval 但无可用 checkpoint”，后续 confirm 的 resume 将失败关闭。该风险取决于 `AgentActionService.createPending` 是否在 ID 已存在时返回既有结果（幂等）而非抛异常；diff 未包含其实现，故不作为阻断项。
- Suggested Fix：
  1. 确认 `createPending` 对同一 workflow ID 的重复创建是幂等返回既有 Approval，而非依赖唯一约束抛异常；若是后者，请在冲突时重查并返回既有结果，不触发 Abort。
  2. 在 `AgentChatServiceTest` 增加并发/重复创建用例，断言重复创建不会调用 `abort`。

---

### R02-03（建议修改）确定性 conversationId 使重试重复写入展示历史

- Severity: Minor（上一轮已提出，ADR-0038 明确为非目标）
- File & Line: `AgentChatService.java` `effectiveConversationId` / `persist` ~143-170
- Evidence：

```java
String seed = "agentforge-chat-v1:" + projectId + ":" + userId + ":" + requestId;
return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
...
conversationHistory.appendCompletedExchange(
        command.projectId(), command.actor(), result.conversationId(),
        command.message(), result.answer(), result.sources());
```

- Description：
  `appendCompletedExchange` 不含 `requestId`，无请求级幂等。稳定派生后同 `X-Request-Id` 重试会再次落回同一会话并重复追加 user/assistant 交换。该行为在既有“客户端显式传 conversationId”的重试路径中本就存在，ADR-0038 亦显式声明“普通回答历史的 exactly-once 不属于本 ADR”，因此不阻塞；仅记录残余展示层重复风险。
- Suggested Fix（后续节点）：为展示历史引入请求级幂等键（如以 `requestId` 在会话内去重）或在 append 前检查本轮是否已存在。

---

### R02-08（建议修改）缺少 Java abort 客户端状态映射测试

- Severity: Minor
- File & Line: `services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/HttpAgentServiceClient.java` `abort` ~175-205
- Evidence：

```java
catch (RestClientResponseException exception) {
    if (exception.getStatusCode().value() == 404
            || exception.getStatusCode().value() == 409) {
        throw new ConflictException("Agent workflow cannot be aborted.");
    }
    throw new ServiceUnavailableException("Agent Service abort is unavailable.", exception);
}
```

- Description：
  新增的 abort 客户端分支（404/409→公共 409；其它→503）没有对应单测，契约测试仅覆盖成功路径。文档已在 `core-api.md` 明确该映射，应有测试固化，防止后续漂移。
- Suggested Fix：参照既有 resume/chat 的映射测试，新增 404/409/500 用例，断言 `ConflictException` / `ServiceUnavailableException`。

---

### R02-09（建议修改）缺少 ABORTED 后 resume 失败关闭测试

- Severity: Minor
- File & Line: `services/agent-service/src/agentforge_agent/action_runtime.py` `resume` / `abort` ~130-205
- Evidence：

```python
if snapshot.values.get("status") == "ABORTED":
    return _view(snapshot.values, namespace.thread_id)
if not snapshot.next or snapshot.values.get("status") != "WAITING":
    raise ActionWorkflowConflict("action workflow is not waiting")
```

- Description：
  实现正确地在 ABORTED 状态下拒绝 resume（落入 `not WAITING` 冲突），但 `test_action_runtime.py` 未覆盖该组合。补齐可防止“ABORTED 轮次被 resume 消费”。
- Suggested Fix：新增 `test_aborted_round_cannot_be_resumed`，断言 `runtime.resume(...)` 抛 `ActionWorkflowConflict`。

---

## 主开发（Codex）评估回填区

| 发现 ID | 是否同意 | 处理方式（修复/文档/驳回） | 证据或提交 | 备注 |
| --- | --- | --- | --- | --- |
| R02-07 | 已确认无缺陷 | 不修改 | `AgentTaskActionRepositoryAdapter.lockChatWorkflow` 与 `PersistenceIntegrationTest.concurrentReplayOfOneChatWorkflowCreatesOnePendingApproval` | PostgreSQL advisory transaction lock 先串行化同 workflow，再查询并返回既有 Approval；并发实测单 Action/单 REQUESTED audit |
| R02-03 | 不纳入本次 | 记录剩余风险 | ADR-0038 / R02 变更记录 | 普通展示历史 exactly-once 是明确非目标 |
| R02-08 | 建议合理但非阻断 | 不修改 | 真实 HTTP 成功契约与既有异常映射模式 | 当前无可复现映射缺陷，不因 PASS 后纯测试建议扩大范围 |
| R02-09 | 建议合理但非阻断 | 不修改 | runtime 状态守卫与现有 ABORTED/RESUMED 测试 | 实现已由 `not WAITING` 失败关闭；可在后续测试整理中补强 |
| R02-10 | 同意 | 不修改 | — | 纯风格，无行为影响 |

---

## 备注（未计入发现）

- ADR-0038、`docs/03-features/agent-runtime.md`、`docs/04-api/agent-service.md`、`docs/04-api/core-api.md` 对 ABORTED、Abort 契约与失败状态码的描述与实现一致；ADR README 索引已补登。
- 上一轮 “Java 收到 proposal 之前 Python 已建立 WAITING” 的窗口仍只能依赖相同 `X-Request-Id` 重试，ADR 已显式承认为已知限制，不计入发现。
- `ToolProposal` 的 `strip_whitespace + min_length` 约束与 Java `normalizeText` 边界一致，`tool_proposal_or_none` 把未来约束漂移安全降级为无 proposal，符合防御性要求。
