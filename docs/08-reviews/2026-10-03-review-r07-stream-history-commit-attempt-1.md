# Pi 代码审查报告：r07-stream-history-commit / Attempt 1

- 日期：2026-10-03
- 审查阶段：r07-stream-history-commit
- 审查对象：INDEX@146dc58（基线：146dc580067772266ca9e4c605e510705cd90cd1）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# R07 流式完成与历史提交一致性 — Milestone 审查报告

## 一、概述与总体结论

- 审查目标：Commit INDEX@146dc58（`146dc580067772266ca9e4c605e510705cd90cd1`），r07-stream-history-commit，Milestone 模式，第 1 轮。
- 审查范围：本次 diff 全量（15 文件，+247/-43）。核心行为变更为两处：
  1. `AgentChatService.stream` 将展示历史提交从「`complete` 先发、失败仅 warning」改为「先提交历史、再发公共 `complete`，失败向上抛入流式 `error` 边界」；
  2. `ConversationHistoryService.appendCompletedExchange` 引入 `requestId` 幂等去重 + `V15` 迁移新增 `agent_message.request_id` 与部分唯一索引 `(conversation_id, request_id, role)`。
- 总体结论：**通过（PASS）**。变更与 R07 目标、ADR-0020、`docs/03-features/conversation-history.md` 及 `docs/04-api/agent-service.md` 契约一致；关键顺序修复有真实公共 seam 测试（含真实 PostgreSQL）覆盖；未发现可确认的必须修改项。存在若干建议修改项，未构成阻断。
- 是否阻断：无阻断性问题。
- 边界遵守：未引入 Neo4j/Langfuse/LiteLLM/MCP 等 V2/V3 组件；未修改权限、配额、审批边界；未在模型生成期间开启数据库事务。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 本次 diff 未发现具备明确证据的必须修改项 |

### 建议修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Medium | `AgentChatService.java` | `stream(...)` complete 分支 | 历史提交失败时，先前的 `finalizeEvent` 已独立提交 PENDING Action，客户端仅收到 `error`，形成「客户端以为失败、服务端存在孤儿待办」的语义差 |
| S2 | Low | `ConversationHistoryService.java` / `V15` | `appendCompletedExchange` 入参校验 | `requestId` 仅校验非空，未校验长度；列宽 `VARCHAR(100)` 与客户端可控 header 之间缺少显式边界保护 |
| S3 | Low | `ConversationHistoryService.java` | 幂等冲突分支 | 同 `requestId` 重试但模型重新生成不同答案时返回 409，依赖「重试答案确定」假设；需确认 Web 侧展示与重试策略 |
| S4 | Low | `*Test.java` | 测试覆盖 | 未覆盖 requestId 空白/超长、历史失败时 PENDING Action 状态、并发重试（虽由行锁保护） |

### 无需修改

| ID | 严重级别 | 文件 | 位置 | 说明 |
| --- | --- | --- | --- | --- |
| N1 | — | `V15__idempotent_conversation_exchange.sql` | 部分唯一索引 | 迁移对遗留 NULL `request_id` 兼容，索引选择 `(conversation_id, request_id, role)` 与实体 `length=100` 一致，`ddl-auto=validate` 不受影响 |
| N2 | — | `ConversationHistoryService.java` | 幂等校验顺序 | 幂等查询在 `requireAccess` 与 `belongsTo` 归属校验之后，未引入跨作用域信息泄露；会话行锁串行化同会话写入 |
| N3 | — | `AgentChatService.java` | complete 顺序 | `finalizeEvent`（Action 处理）→ `persist`（历史）→ `sink.accept(complete)` 顺序正确；sink 断线不回滚已提交历史，符合 R07 目标 |
| N4 | — | Docs | data-architecture / agent-chat / conversation-history / agent-service | 文档与实现一致（先提交历史再发 `complete`、幂等去重、`message_count` 不重复增长） |

---

## 三、逐个 Issue 展开

### S1（建议修改，Medium）历史提交失败后的 PENDING Action 孤儿

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentChatService.java`，`stream(...)` 内 `complete` 分支。

**Evidence（diff 片段）**：

```java
AgentStreamEvent finalized = finalizeEvent(command, effectiveConversationId.get(), event);
if ("complete".equals(finalized.type())) {
    persist(command, new AgentChatResult(effectiveConversationId.get(), answer.toString(),
            command.requestId(), sources.get()));
}
sink.accept(finalized);
```

`finalizeEvent` 内部对 complete 调用 `createPendingOrAbort(...)`，而 `persist` 调用独立的 `ConversationHistoryService.appendCompletedExchange`（`@Transactional`）。二者为不同事务，`stream` 本身非事务方法。

**Description**：
改动前：Action 提交 → `complete` 发送成功 → 历史失败仅 warning，客户端已知存在 pendingAction，只是历史缺失。
改动后：Action 提交 → 历史提交抛异常 → 客户端只收到通用 `error`，不再收到 `complete`，但数据库中 PENDING Action 已提交且会进入 `listRecoverable`、并按 P3-02 阻塞会话删除。
即：出现「客户端认为整轮失败、服务端存在待决 Action」的用户可见不一致。若客户端不携原 requestId 重试，该 Action 成为长期孤儿。该问题在旧实现中亦存在（且更糟），故列为建议而非阻断；但因恰好由本次「错误提前」变更放大，建议纳入 R07 或紧随的收尾。

**Suggested Fix（方向）**：
- 首选：将「Action 持久化 + 历史持久化」置于同一事务边界（例如引入一个协调方法在单事务内先历史后 Action，或将二者合并到同一 `@Transactional` 应用服务），使失败时 Action 一并回滚。
- 次选（若不改事务模型）：历史失败时对刚创建的 pending Action 执行补偿（终止/标记失败），或在文档中显式声明该失败窗口与「携原 requestId 重试即自愈」的可恢复语义，并在 `docs/03-features/conversation-history.md` 的「已知限制」补记。
- 至少补充一条测试：历史写入失败时断言 Action 的最终状态（回滚或显式可恢复），避免该分支无证据。

---

### S2（建议修改，Low）requestId 长度边界

**File & Line**：`ConversationHistoryService.java` `appendCompletedExchange`；`services/core-api/src/main/resources/db/migration/V15__idempotent_conversation_exchange.sql`。

**Evidence**：

```java
if (requestId == null || requestId.isBlank()) {
    throw new IllegalArgumentException("requestId is required for completed conversation history.");
}
...
ALTER TABLE agent_message ADD COLUMN request_id VARCHAR(100);
```

**Description**：
`requestId` 现被持久化到 `VARCHAR(100)`。除空白校验外未见长度约束。若 `X-Request-Id` 由外部客户端提供且入口未截断/拒绝超长值，超长 requestId 会在流末端（模型已生成、配额已消费）触发数据库层长度错误，最终表现为通用 `error` 且本可成功的回答无法入库。本次 diff 未包含过滤器代码，故无法确认入口是否已做限制；若 `agent_task_action` 等既有表已以相同列宽处理且入口已有约束，则此风险已被覆盖，可忽略本条。

**Suggested Fix（方向）**：
- 在 `RequestIdFilter` 或 `ConversationHistoryService` 入口显式约束长度（如 `requestId.length() > 100` 时拒绝或安全截断），并补一条边界测试。
- 或明确复用requestId 既有统一校验工具，保证所有落库点一致。

---

### S3（建议修改，Low）同 requestId 重试答案不同 → 409 的客户端语义

**File & Line**：`ConversationHistoryService.java` `matchesExchange` / 冲突分支。

**Evidence**：

```java
if (!existing.isEmpty()) {
    if (matchesExchange(existing, question, answer, sourcesJson)) {
        return;
    }
    throw new ConflictException("The requestId is already bound to another completed exchange.");
}
```

**Description**：
该设计明确拒绝「同 key 不同内容」以避免静默分叉，方向正确。但流式链路中模型输出非确定：若首次历史已提交而 `complete` 因断线未达客户端，客户端携同一 requestId 重试时，Python 可能生成不同答案，此时返回 409，用户既看不到新答案、也已存在旧历史。方案本身已由 `docs/07-changes/...` 说明为有意选择，故不阻断；建议确认 Web 端在此错误下的展示与「重新以新 requestId 发送」的降级路径，避免用户陷入无法获取结果的循环。

**Suggested Fix（方向）**：
- 明确 Web 端 409 场景的提示与重发策略（换新 requestId 重发），并在 `conversation-history.md` 的「接口/已知限制」中说明该语义。
- 如需更平滑，可考虑在服务端把「同 requestId 不同答案」按首次结果返回（当前实现二选一，需评估契约后决定）。

---

### S4（建议修改，Low）测试覆盖缺口

**File & Line**：`ConversationHistoryServiceTest.java`、`AgentChatServiceTest.java`、`PersistenceIntegrationTest.java`。

**Description**：
现有测试已较好覆盖主分支（幂等去重、冲突、`complete` 先于历史、sink 断线后已提交、真实 PostgreSQL 去重）。仍缺：
- `requestId` 为空白/超长时的行为；
- 历史提交失败后 PENDING Action 的最终状态（见 S1）；
- 同 requestId 并发重试（虽由 `findByIdForUpdate` 行锁串行化，仍建议一条并发/锁行为测试或明确注释说明依赖）。

**Suggested Fix（方向）**：按上列各补 1 条最相关测试，优先 S1 相关的 Action 状态断言。

---

## 四、主开发 (Codex) 评估回填区

| 发现 ID | 是否认同 | 处理决定（修复/记录/拒绝） | 关联提交或说明 | 回填日期 |
| --- | --- | --- | --- | --- |
| S1 | 是 | 记录 | R06 已让当前用户恢复 PENDING/APPROVED；本项不把 DB Action/历史与 Python Abort 冒险并入长事务，且历史失败不再发虚假 complete。 | 2026-10-03 |
| S2 | 是 | 修复 | service 补 1–100 字符边界；红灯后完整 `ConversationHistoryServiceTest` 10/10 通过。 | 2026-10-03 |
| S3 | 是 | 保持设计 | 同 key 不同交换继续 fail-closed；Web 常规请求使用新 requestId，已提交结果可从历史恢复。 | 2026-10-03 |
| S4 | 部分 | 部分修复/记录 | 补长度测试；主路径已有 PostgreSQL 与唯一索引证据，并发会话专项留给报告 R18。 | 2026-10-03 |

---

## 五、审查结论

本次 R07 变更准确修复了「公共 SSE `complete` 早于历史提交、历史失败仍报成功、`complete` sink 断线跳过落库」三个根因，并以数据库部分唯一索引 + 会话行锁 + 应用层内容比对实现幂等，权限与作用域校验顺序正确，文档与实现一致。未发现必须修改项，`REVIEW_RESULT: PASS`；建议 Codex 对 S1（Action 孤儿）、S2（requestId 长度边界）给出结论或记录，S3/S4 视排期处理。
