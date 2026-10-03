# Pi 代码审查报告：r02-action-checkpoint-compensation / Attempt 1

- 日期：2026-10-03
- 审查阶段：r02-action-checkpoint-compensation
- 审查对象：INDEX@2dab22e（基线：2dab22eaf4b14e2eca1d4ebd489492e703267e31）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# R02 Action Checkpoint Compensation — Milestone Review (Round 1/3)

## 概述与总体结论

- 审查对象：`INDEX@2dab22e`（r02-action-checkpoint-compensation），Milestone 模式，只读。
- 变更范围：Python `action_runtime` 新增 `ABORTED` 与补偿接口、`schemas`/planner 文本边界统一、Core `AgentChatService` create-or-abort + 确定性 conversationId、Java HTTP `abort` 契约、文档/ADR/变更记录、对应测试。
- 总体判断：设计方向正确且与 R02 目标一致——Java 仍为唯一写入权威，补偿以 request/workflow 精确界定轮次，公共 schema 未泄漏内部字段。**但流式入口的补偿失败分支缺少有效测试，且现有测试无法证明终止事件被正确发出；另存在若干契约/幂等与文档一致性问题。**

结论：**需修复后交付（NEEDS_FIX）**。阻断项为 1 条（流式补偿失败的核心分支测试不足，可能静默丢失终止事件），其余为建议项。

---

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- |
| R02-01 | Major | `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java` / `services/core-api/src/test/java/com/agentforge/core/agent/application/AgentChatServiceTest.java` | `finalizeEvent` ~155-165；测试 ~`streamAbortsTheExactWaitingRoundWhenPendingActionCannotBeCreated` ~210-247 | 流式补偿失败（abort 抛异常/返回非法）没有"终止事件"测试；现有测试断言 `events.getLast().pendingAction()==null`，即使 `complete` 未发出（只剩 metadata）也会通过，无法验证文档要求的 `error`/终态契约 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- |
| R02-02 | Minor | `docs/04-api/core-api.md` / `HttpAgentServiceClient.java` | core-api.md 新增段；client `abort` catch ~190-205 | 文档规定 Abort 失败返回 503，但实现把 Python 404/409 映射为 `ConflictException`（公共 409），契约与文档不一致 |
| R02-03 | Minor | `AgentChatService.java` / `ConversationHistoryService` | `effectiveConversationId` ~150-158；`persist` ~166-170 | 确定性 conversationId 使"相同 X-Request-Id 重试"落回同一会话，而 `appendCompletedExchange` 无请求级幂等，重试会重复写入展示历史 |
| R02-04 | Minor | `AgentChatServiceTest.java` / `test_action_runtime.py` | runtime `test_resumed_round_cannot_be_aborted` ~205-225 | 缺少"补偿 Abort 后以相同 X-Request-Id 重试"的测试；且 RESUMED 拒绝测试实际命中 request_id 校验而非 RESUMED 守卫 |
| R02-05 | Minor | `services/agent-service/src/agentforge_agent/natural_tool_planner.py` | ~55-70 | 直接构造 `ToolProposal` 绕过了新的安全构造器；一旦内联边界与 Pydantic schema 漂移，将抛出未捕获 `ValidationError`（500） |

### 无需修改

| ID | 严重级别 | 文件 | 说明 |
| --- | --- | --- | --- |
| R02-06 | Info | `action_runtime.py` / `api.py` | ABORTED 终态、replay、v1 拒绝 Abort、Namespace/request/workflow 精确匹配、内部 token 保护均正确；Python/Java proposal 文本边界已统一，无需改动 |

---

## Issue 展开

### R02-01（必须修改）流式补偿失败分支无有效测试，可能静默丢失终止事件

- Severity: Major
- File & Line:
  - `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java`：`finalizeEvent`、`createPendingOrAbort`
  - `services/core-api/src/test/java/com/agentforge/core/agent/application/AgentChatServiceTest.java`：`streamAbortsTheExactWaitingRoundWhenPendingActionCannotBeCreated`

Evidence（测试断言）：

```java
AgentChatCommand command = service.prepareStream(
        projectId, actor, "create", conversationId, "request-stream-abort");
List<AgentStreamEvent> events = new ArrayList<>();
service.stream(command, events::add);

assertThat(events.getLast().pendingAction()).isNull();
verify(client).abort(projectId, userId, false, conversationId, workflowId, "request-stream-abort");
```

Evidence（新增调用点）：

```java
private AgentStreamEvent finalizeEvent(...) {
    ...
    if (event.toolProposal() != null) {
        pendingAction = createPendingOrAbort(
                command.projectId(), command.actor(), conversationId,
                event.toolProposal(), command.requestId())
                .orElse(null);
    }
    return AgentStreamEvent.completed(pendingAction, event.sources());
}
```

Description：
`finalizeEvent` 现在会在 `createPendingOrAbort` 内调用 `agentServiceClient.abort(...)`，而 abort 可能抛出 `ConflictException`/`ServiceUnavailableException`。同步入口已有专门测试 `chatDoesNotHideAnAbortFailureAsAnOrdinaryAnswer`，流式入口却没有任何对应测试。更关键的是，现有流式测试的断言无法区分两种结果：

- 若 `complete` 正常发出且 `pendingAction=null` → 断言通过；
- 若补偿失败被上游 catch 吞掉、只发出了 `metadata` → `metadata.pendingAction()` 同样为 `null`，断言依然通过。

因此该测试**不能证明**流式入口在补偿失败时按 `docs/04-api/agent-service.md` 的约定（"流式入口在已开始响应后输出 error"，且"补偿失败不得静默伪装成普通回答"）终止。这是一个核心分支（本节点两个入口之一）的覆盖缺口，且存在静默丢失终态的合同风险，需在合入前确认并补测。

Suggested Fix：

1. 在流式路径显式把补偿失败转换为终止事件（若尚未如此），并新增失败用例：

```java
@Test
void streamSurfacesAbortFailureInsteadOfSilentlyDroppingComplete() {
    ...
    when(actions.createPending(projectId, actor, conversationId, proposal, "request-stream-abort-fail"))
            .thenReturn(Optional.empty());
    when(client.abort(projectId, userId, false, conversationId, workflowId, "request-stream-abort-fail"))
            .thenThrow(new ServiceUnavailableException("abort unavailable"));

    AgentChatCommand command = service.prepareStream(
            projectId, actor, "create", conversationId, "request-stream-abort-fail");
    List<AgentStreamEvent> events = new ArrayList<>();
    service.stream(command, events::add);

    // 必须是终止事件，不能被静默吞掉
    assertThat(events).extracting(AgentStreamEvent::type).contains("error");
    assertThat(events.getLast().type()).isEqualTo("error");
}
```

2. 同时把成功用例的断言从 `pendingAction()` 改为显式校验终态类型，避免"只剩 metadata 也通过"：

```java
assertThat(events).extracting(AgentStreamEvent::type).containsExactly("metadata", "complete");
```

3. 若上游既有 catch 会 `sink.accept(error)`，请在报告中补充说明并由 Codex 保留该行为；否则必须在此节点补齐。

---

### R02-02（建议修改）Abort 失败状态码与 API 文档不一致

- Severity: Minor
- File & Line: `docs/04-api/core-api.md` 新增段；`HttpAgentServiceClient.java` `abort` catch 块

Evidence（文档）：

```text
Core ... Abort 自身失败返回 503，不得把孤立 checkpoint 隐藏为普通回答。
```

Evidence（实现）：

```java
catch (RestClientResponseException exception) {
    if (exception.getStatusCode().value() == 404
            || exception.getStatusCode().value() == 409) {
        throw new ConflictException("Agent workflow cannot be aborted.");
    }
    throw new ServiceUnavailableException("Agent Service abort is unavailable.", exception);
}
```

Description：
Python 对"状态冲突/不存在"返回 409/404（`docs/04-api/agent-service.md` 的新 Abort 段也是这么定义的），而 Core 客户端把 404/409 收敛为 `ConflictException` → 公共 409，与 `core-api.md` 明确的"Abort 自身失败返回 503"冲突。变更记录的描述是"映射为冲突或依赖不可用"，三份材料（agent-service.md、core-api.md、变更记录）对 Abort 失败的公共状态码未对齐。

Suggested Fix：二者取一并保持三处一致。若认为"已 RESUMED/轮次不匹配"应显式暴露为冲突，则修订 `core-api.md`：

```text
Abort 因目标轮次不存在或已 RESUMED 而冲突时返回 409；其它网络/5xx 失败返回 503。
```

若坚持"补偿失败统一视为依赖不可用"，则把客户端 404/409 也映射为 `ServiceUnavailableException`。

---

### R02-03（建议修改）确定性 conversationId 使重试重复写入展示历史

- Severity: Minor
- File & Line: `AgentChatService.java` `effectiveConversationId` / `persist`

Evidence：

```java
private UUID effectiveConversationId(UUID projectId, UUID userId, UUID conversationId, String requestId) {
    if (conversationId != null) return conversationId;
    String seed = "agentforge-chat-v1:" + projectId + ":" + userId + ":" + requestId;
    return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
}
...
private void persist(AgentChatCommand command, AgentChatResult result) {
    conversationHistory.appendCompletedExchange(
            command.projectId(), command.actor(), result.conversationId(),
            command.message(), result.answer(), result.sources());
}
```

Description：
`appendCompletedExchange` 的入参（见 `AgentChatServiceTest`）为 `(projectId, actor, conversationId, message, answer, sources)`，**不含 requestId**，因此没有请求级幂等。此前 `conversationId==null` 时 Python 每次生成不同会话，响应丢失后的重试会落到不同会话；现在同一 `X-Request-Id` 重试落到同一会话，`chat()` 会再次 `persist`，导致同一会话内重复写入 user/assistant 交换。该变化直接由本节点的"稳定派生 conversationId"引入，且未被文档或测试覆盖。

Suggested Fix（二选一）：
- 为展示历史引入请求级幂等键，例如 `appendCompletedExchange(..., requestId)`，在同一 conversation 内以 `requestId` 去重；
- 或在派生前确认该 conversation 是否已存在本轮 completed exchange，命中则跳过 append。

---

### R02-04（建议修改）缺少"补偿 Abort 后同请求重试"测试；RESUMED 测试未覆盖目标守卫

- Severity: Minor
- File & Line: `action_runtime.py` `abort`；`test_action_runtime.py`

Evidence：

```python
def test_resumed_round_cannot_be_aborted():
    runtime.interrupt(scope, proposal(), "request-start")
    runtime.resume(scope, workflow_id=..., decision="APPROVE", idempotency_key="resume-key",
                   request_id="resume-request")
    with pytest.raises(ActionWorkflowConflict):
        runtime.abort(scope, workflow_id=waiting.workflow_id, request_id="request-start")
```

Description：
`resume` 会把 state 的 `request_id` 覆盖为 resume 请求的 ID（`"resume-request"`）。上述调用传入的是原始 `"request-start"`，因此在 `abort` 中**先命中 `request_id` 不匹配**，并未真正走到"状态为 RESUMED、拒绝 Abort"的守卫。该断言虽仍成立，但没有验证 RESUMED 守卫本身。

更重要的是，本节点的核心可重试承诺是"相同 `X-Request-Id` 的响应丢失重试复用同一轮"。现有测试只覆盖"Abort 后以**不同** requestId 建立新轮次"（`test_aborted_waiting_round_is_replayable_and_allows_a_new_round`），**没有覆盖"补偿 Abort 后以相同 requestId 重试"**。该场景依赖 `interrupt` 对 `ABORTED` 且 `request_id` 相同状态的处理，行为未经证明，属于本节点关键路径的测试盲区。

Suggested Fix：

```python
def test_resumed_round_cannot_be_aborted():
    ...
    with pytest.raises(ActionWorkflowConflict):
        runtime.abort(scope, workflow_id=waiting.workflow_id, request_id="resume-request")

def test_same_request_retry_after_abort_reestablishes_or_conflicts_by_design():
    runtime = ActionWorkflowRuntime(InMemorySaver())
    scope = namespace()
    waiting = runtime.interrupt(scope, proposal(), "same-request")
    runtime.abort(scope, workflow_id=waiting.workflow_id, request_id="same-request")
    next_waiting = runtime.interrupt(scope, proposal(), "same-request")
    # 明确并固化预期语义（重建或冲突），并据此更新文档
    assert next_waiting.status == "WAITING"
```

---

### R02-05（建议修改）自然语言 planner 直接构造 `ToolProposal`，绕过安全构造器

- Severity: Minor
- File & Line: `natural_tool_planner.py`（构造 `ToolProposal(...)` 的两处）

Evidence：

```python
return ToolProposal(
    action_type="CREATE_TASK", title=title.strip(),
    description=description.strip() if description is not None else None,
    status=status or "TODO", priority=priority or "MEDIUM",
)
```

对比 `tool_planner.py` 已改为安全路径：

```python
def _proposal(**fields: object) -> ToolProposal | None:
    try:
        return ToolProposal(**fields)
    except ValidationError:
        return None
```

Description：
`parse_tool_intent` 依赖内联边界检查（`1 <= len(...strip()) <= N`）与 Pydantic schema（`StringConstraints(min_length=1, max_length=N)`）保持一致。当前二者恰好一致，但两处独立维护，一旦 schema 调整而内联检查遗漏，`ValidationError` 会从 `/internal/v1/chat` 逸出为 500，而不是安全地"无 proposal"。这属于易演化为真实缺陷的脆弱点。

Suggested Fix：让 `natural_tool_planner` 复用 `_proposal`（或提取到共享模块），统一在构造点捕获 `ValidationError` 返回 `None`。

---

## 主开发（Codex）评估回填区

| 发现 ID | 是否同意 | 处理方式（修复/文档/驳回） | 证据或提交 | 备注 |
| --- | --- | --- | --- | --- |
| R02-01 | 同意核心问题 | 修复测试 | `AgentChatServiceTest` 强化成功事件序列并验证失败传播；`AgentChatApiTest` 验证 `metadata -> error` 且无 `complete` | error 继续由既有 Controller SSE 边界产生 |
| R02-02 | 同意 | 文档 | `docs/04-api/core-api.md` | 对齐 404/409→公共 409，网络/5xx/无效响应→503 |
| R02-03 | 不同意纳入本次 | 驳回 | ADR-0038 与 R02 变更记录的非目标 | 既有 conversation 重试也有相同行为；普通历史 exactly-once 非本变更目标，避免扩散数据库模型 |
| R02-04 | 同意 | 修复测试 | `test_action_runtime.py` | 同 request 在 Abort 后新建 WAITING；RESUMED 用恢复后的 request ID 命中状态守卫 |
| R02-05 | 同意 | 修复 | `schemas.py` 与两个 planner | 共用捕获 `ValidationError` 的安全构造器 |
| R02-06 | 同意 | 无需修改 | — | 保持现有实现 |

---

## 备注（未计入发现）

- ADR-0038 与 `docs/03-features/agent-runtime.md` 的状态机、失败语义描述与实现基本一致；`README.md` ADR 索引已补登。
- "Java 在收到 proposal 之前（HTTP 超时/解码失败）Python 已建立 WAITING"的窗口仍只能依赖相同 `X-Request-Id` 重试恢复，ADR 已显式承认，属已知限制，不计入必须修改。
