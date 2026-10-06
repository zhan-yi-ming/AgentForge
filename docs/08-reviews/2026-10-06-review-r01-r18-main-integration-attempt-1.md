# Pi 代码审查报告：r01-r18-main-integration / Attempt 1

- 日期：2026-10-06
- 审查阶段：r01-r18-main-integration
- 审查对象：INDEX@54a2810（基线：ff1a0a52317c69dba3d51e61e12e56dbf46f2bca）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立只读代码审查报告

- 审查阶段：r01-r18-main-integration（Milestone）
- 审查轮次：1 / 3
- 审查对象：Commit INDEX@54a2810（ff1a0a5..54a2810 候选，134 files，+5385/-475）
- 审查方式：完全只读。未运行任何命令、未修改任何文件或 Git 状态、未执行测试。
- 证据来源：本次提供的 Git Diff、文件清单、`docs/07-changes/2026-10-06-r01-r18-*.md`、`docs/02-architecture/**`、`docs/03-features/**`、`docs/04-api/**`。Codex 记录的回归/门禁结果只作为审查上下文引用，本报告不将其改写为本审查员执行的结果。

---

## 一、概述与总体结论

**总体结论：通过（PASS）。**

本次候选把 R01–R18 已审计的修复合入 main，不新增产品能力；改动集中在四条主线：

1. **Action workflow 轮次身份与补偿**（ADR-0037/0038/0039）：Python checkpoint 引入 `workflow_id` 与 `ABORTED` 终态；Java 新增 `action_workflow_id` 唯一约束、`/agent/actions/recoverable`、`/internal/v1/agent/abort`、事务外 flush 乐观锁补偿、checkpoint owner 与决策 actor 分离。
2. **数据库身份分离**（ADR-0040）：`agentforge_core` / `agentforge_agent` 角色、Flyway 管理员连接、V16–V18 迁移与 `database-roles` 引导服务。
3. **RAG 快照代际与有界检索**（ADR-0010 修订）：`rag_source_generation` / `rag_project_snapshot`、Core 代际握手、PostgreSQL 内向量+词法候选与 GIN 索引、Graph evidence 在相同代际内匹配。
4. **Web 异步作用域与可恢复审批**：workspace generation + 操作序号隔离迟到响应；恢复的 `PENDING`/`APPROVED` Action 列表与"仅用原决定键继续执行"。
5. **会话并发修订**（ADR-0042）：Python `claim_exchange` / `release_exchange` / revision，以及历史 exchange 的 `requestId` 幂等。

经逐域核对服务端授权（`projectAccess.requireAccess`）、写路径幂等、乐观锁、fail-closed 语义、公共/内部契约字段与异常分支测试覆盖，**未发现具备明确证据的可运行性、正确性、安全、权限、并发、数据一致性、契约或方向问题**。下述 5 项均为非阻塞建议，不触发 NEEDS_FIX。

**未运行项（阻断说明）**：本次审查为纯静态只读，未执行任何用例；Release Gate/角色边界/备份恢复/ASR 限流结论完全依赖 Codex 记录，本报告不背书其执行结果本身，仅指出代码与该记录不矛盾。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 无。未发现具备明确证据的必须修改项。 |

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| S-01 | Medium | services/core-api/src/main/java/com/agentforge/core/conversation/application/ConversationHistoryService.java | 约 48–72 | `requestId` 重试复用只在重新生成的回答与已提交内容逐字一致时成立；模型非确定性时同 requestId 重试返回 409，与文档"同 requestId 重试直接复用已提交事实"的表述存在落差 |
| S-02 | Low | services/core-api/src/main/java/com/agentforge/core/conversation/application/ConversationHistoryService.java | 约 52–62 | 同一 requestId 对"新会话首次落库"的并发重试可能在 `agent_conversation` 主键或 `agent_message` 唯一索引上抛出 500，而不是幂等重放 |
| S-03 | Low | services/agent-service/src/agentforge_agent/tool_planner.py | 约 60–62 | 本次 diff 删除了 `plan_tool` 与 `_parse_create_body` 之间的两个空行，形成 PEP8 E302 风格回归（Milestone 审计已记录为 Low） |
| S-04 | Low | services/core-api/src/test/java/com/agentforge/core/agent/api/AgentActionApiTest.java | 新增用例段 | 新增公共端点 `GET /agent/actions/recoverable` 只有正向用例与越权数据隔离（集成级），缺少 MVC seam 的未认证 401 负向断言 |
| S-05 | Low | apps/web/src/pages/ActionApprovalDialog.tsx | 26–31、57–60 | 恢复的 `PENDING` Action 仍渲染倒计时并停在"0 秒后仍需手动确认"，属可读性瑕疵（不影响安全性） |

### 无需修改（已核实，仅记录判断依据）

| ID | 事项 | 结论依据 |
| --- | --- | --- |
| N-01 | `TaskService.update` 放开 flush 期 `OptimisticLockingFailureException`，公共 Task API 的 409 依赖全局异常映射 | 该映射（`ApiExceptionHandler`）与基线测试 `ResourceApiTest` 均不在本次 diff 中，属既有行为；`AgentActionWorkflowService` 已捕获该异常并在事务外补偿，`TaskServiceTest#updateLetsAFlushOptimisticConflictEscapeForTransactionRollback` 与 `PersistenceIntegrationTest#flushOptimisticConflictPersistsFailedAfterTheExecutionTransactionRollsBack` 覆盖新语义 |
| N-02 | V14 唯一约束与 v1/v2 兼容 | `action_workflow_version IN (1,2)`、v2 强制 `action_workflow_id`，与 `AgentTaskAction` 构造器（CHAT=2、MCP=null）及 `data-architecture.md` 一致 |
| N-03 | V16 角色/权限与 default privileges | 初始化与 Flyway 仍由管理员执行；Core 仅 DML 且 `public` CREATE 被回收；Agent 仅 `rag_chunk` DML + `agent_checkpoint` 属主；与 `database-role-boundary.ps1` 断言一致 |
| N-04 | V17 代际单调性 | 触发器随 Wiki/Task 提交推进、只读不推进；`RagSourceService.snapshot` 在同一 REPEATABLE READ 事务内取代际与来源；`RagStore` 拒绝旧快照回滚且代际不匹配时搜索失败关闭 |
| N-05 | 补偿顺序 | Python 先 `claim_exchange` 再 `interrupt`，`finally release_exchange`；`test_conversation_claim_conflict_happens_before_action_checkpoint` 证明冲突时不会创建 WAITING |
| N-06 | Web 代际隔离 | `workspaceGeneration` + `wikiSaveSequence`/`decisionSequence`/`activeConversationLoad` + token 快照，A→B→A 迟到结果被丢弃；`app.test.tsx` 覆盖保存、格式化、决策、流式中断、删除并发 |
| N-07 | 公共契约未泄漏内部字段 | `AgentActionApiTest` 断言 `$.requestedByUserId` 不存在；`/recoverable` 仅返回 `action/source/decisionKey`；`actionWorkflowId` 不进入公共 pendingAction |

---

## 三、逐个 Issue 展开

### S-01 requestId 重试只有在回答逐字一致时才幂等

- **Severity**：Medium（非阻塞）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/conversation/application/ConversationHistoryService.java:48-72`
- **Evidence**

```java
String sourcesJson = writeSources(sources);
List<AgentMessage> existing = messages.findAllByConversationIdAndRequestId(conversationId, requestId);
if (!existing.isEmpty()) {
    if (matchesExchange(existing, question, answer, sourcesJson)) {
        return;
    }
    throw new ConflictException("The requestId is already bound to another completed exchange.");
}
```

- **Description**：新会话的 conversationId 现在由 `AgentChatService.effectiveConversationId()` 按 `project:user:requestId` 确定性派生，因此携带同一 `X-Request-Id` 的重试会落回同一 Python Namespace。Python 侧不会复用已提交回答：重试会基于已推进 revision 的历史重新生成答案。若模型非确定性（真实 provider），重试产生的 `answer` 与首次落库内容不同，`matchesExchange` 失败并抛出 409。也就是说，`data-architecture.md` 中"同 requestId 重试直接复用已提交事实，不增加 message_count"的语义只在确定性 responder（`LLM_PROVIDER=disabled`）或答案恰好相同时成立。该行为是失败关闭，不产生重复或脏数据，故不阻塞；但会让"响应丢失→原样重试"的客户端拿到 409 而不是原结果。Web 端不发送 `X-Request-Id`（`api.ts` 未设置该 header），实际暴露面有限。
- **Suggested Fix**（二选一，均不扩大范围）
  1. 文档对齐：在 `conversation-history.md`/`core-api.md` 明确"requestId 幂等仅在重新生成的完成交换与已提交内容一致时复用；否则返回 409，属于 requestId 复用冲突"。
  2. 语义对齐：当 USER 正文一致、仅 ASSISTANT 正文不同时，视为同一 requestId 的重放，保留已提交交换并直接返回（不覆盖摘要/正文），使幂等承诺与实现一致；`ConflictException` 仅保留给 USER 正文不同的真冲突。若采用方案 2，需同步 `requestIdCannotBeReusedForADifferentCompletedExchange` 用例的判定条件。

### S-02 新会话首次落库的并发重试可能是 500 而非幂等重放

- **Severity**：Low
- **File & Line**：`ConversationHistoryService.java:52-62`
- **Evidence**

```java
AgentConversation conversation = conversations.findByIdForUpdate(conversationId).orElse(null);
if (conversation == null) {
    conversation = conversations.save(
            AgentConversation.start(conversationId, projectId, actor.userId(), question, now));
}
```

- **Description**：`findByIdForUpdate` 只对已存在行加悲观锁；对"会话尚不存在"的并发两个同 requestId 请求，两者都可能读到 null 并各自 `save`，后提交方在 `agent_conversation` 主键（以及随后的 `agent_message` 唯一索引 `(conversation_id, request_id, role)`）上失败，表现为 500 而非幂等重放。Python 侧 claim 会串行化大部分并发，因此触发窗口很窄（需要同一 requestId 被并发发送且两侧都越过 Python 提交阶段），但该路径正是新引入的幂等机制所要覆盖的场景。
- **Suggested Fix**：在 `appendCompletedExchange` 的持久化边界捕获 `DataIntegrityViolationException`，重新读取该 `(conversationId, requestId)` 的既有交换并按 `matchesExchange` 返回（复用 S-01 的判定），或在 `AgentConversationRepository` 增加"INSERT ... ON CONFLICT DO NOTHING 后重查"的适配方法。相关并发用例可放在 `ConversationHistoryServiceTest` 的公共 seam 上。

### S-03 `tool_planner.py` 顶层函数空行被删除

- **Severity**：Low（风格）
- **File & Line**：`services/agent-service/src/agentforge_agent/tool_planner.py:60-62`
- **Evidence**（diff 上下文）

```diff
         return tool_proposal_or_none(
             action_type="CREATE_TASK",
             title=title,
             status=fields.pop("status", "TODO"),
             priority=fields.pop("priority", "MEDIUM"),
             **fields,
         )
     return None
-
-
 def _parse_create_body(body: str) -> dict[str, object]:
```

- **Description**：`plan_tool` 与 `_parse_create_body` 之间不再保留两个空行，违反 PEP8 E302。功能无影响，但属于本 diff 引入的可读性回归（R01–R18 累计审计已把同类问题记为 Low）。
- **Suggested Fix**：恢复两个空行；若项目已配置 ruff/flake8，将其纳入 lint 门禁即可，不需要额外实现。

### S-04 新公共端点缺少未认证 401 的 MVC 层断言

- **Severity**：Low（测试覆盖）
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentActionApiTest.java`（新增用例段）
- **Evidence**：新增 `recoverableActionsAreReadForTheAuthenticatedUserAndIncludeApprovedRetryKey` 只覆盖带 JWT 的正向读取；越权隔离由 `PersistenceIntegrationTest#recoverableActionsIncludeChatAndMcpButConversationScopeReturnsOnlyItsChatAction` 在服务层覆盖。
- **Description**：该端点在 `/api/v1/**` 下由 `SecurityConfiguration` 默认保护，未被绕过；但公共端点的"未认证 401、无项目权限 403"负向契约没有在 MVC seam 固化，未来调整 request matcher 时不会立刻被测试捕获。
- **Suggested Fix**：在 `AgentActionApiTest` 追加一个不带 `jwt()` 的 `GET /recoverable` 用例断言 401；如需 403，可再补一个 `projectAccess.requireAccess` 抛 `ForbiddenException` 的 stub 用例。

### S-05 恢复的 PENDING Action 倒计时停在 0 秒

- **Severity**：Low（可读性）
- **File & Line**：`apps/web/src/pages/ActionApprovalDialog.tsx:26-31, 57-60`
- **Evidence**

```tsx
const approved = action.status === "APPROVED";
const autoEligible = !action.recovered && !approved && action.source !== "MCP" && action.actionType === "CREATE_TASK";
...
{!approved && <p className="approval-countdown" role="timer" aria-live="off">{remaining} 秒{autoEligible ? "后自动确认" : "后仍需手动确认"}</p>}
```

- **Description**：`autoEligible` 已正确排除 recovered/approved/MCP，不会误触发自动确认（安全语义正确，测试 `does not automatically reapprove a recovered pending Chat create` 覆盖）。但恢复的 `PENDING` 动作仍渲染倒计时并递减到 `0 秒后仍需手动确认`，用户可能误以为已超时失效。
- **Suggested Fix**：对 `action.recovered === true` 的 PENDING 动作隐藏倒计时或改为静态文案（如"请人工确认，不自动执行"）；同时保留现有 `autoEligible` 判定不变。

---

## 四、主开发（Codex）评估回填区

请按"文档先行后修改"流程逐条填写；对本报告判定为"无需修改"的 N 项也请确认是否保留记录。

| Finding ID | 级别 | Codex 评估（接受 / 拒绝 / 部分接受） | 证据或复现步骤 | 处置（修改文件 / 更新文档 / 仅记录） | 验证方式与结果 |
| --- | --- | --- | --- | --- | --- |
| S-01 | Medium | 接受为非阻塞建议 | 代码在同 requestId 对应的完整 exchange 不一致时 fail-closed 返回 409；不会重复落库或覆盖既有事实，Web 当前也不发送该 header | 仅记录；后续幂等语义专题可选择文档收窄或返回已提交结果 | 现有会话 revision/幂等测试与本轮全量回归通过；无严重缺陷复现 |
| S-02 | Low | 接受为理论边缘建议 | 新会话并发首次 insert 的唯一键竞争可能失败，但 Python claim 已收窄窗口，且失败不产生脏写 | 仅记录；未来可补真实 PostgreSQL 并发重放用例 | 本轮未复现 500；无数据破坏或权限绕过证据 |
| S-03 | Low | 接受 | 仅 PEP8 顶层空行风格，不影响解释器、类型或行为 | 仅记录，不为纯风格触发复审 | Python 233 passed |
| S-04 | Low | 接受 | 端点仍受 `/api/v1/**` 默认安全链保护；缺少的是专属 MVC 负向回归，不是可见授权绕过 | 仅记录，后续测试维护可补 | Core clean verify 242 tests；Pi 未发现 matcher 绕过 |
| S-05 | Low | 接受 | recovered action 已明确禁止自动确认；问题仅为 0 秒静态文案体验 | 仅记录，后续 UI polish 可调整 | Web 86 tests 与 build 通过；安全语义测试已覆盖 |
| N-01 | — | 确认 | 全局异常映射属基线，补偿路径已有测试 | 无修改 | 全量回归通过 |
| N-02 | — | 确认 | V14 约束与构造器/架构文档一致 | 无修改 | Flyway V1–V18 与契约测试通过 |
| N-03 | — | 确认 | 角色、default privileges 与专项脚本一致 | 无修改 | database-role-boundary PASS |
| N-04 | — | 确认 | 代际触发、快照和 fail-closed 搜索一致 | 无修改 | RAG/数据库/跨服务门禁通过 |
| N-05 | — | 确认 | claim 先于 interrupt 且 finally release | 无修改 | Agent 全量与 Tool/HITL 通过 |
| N-06 | — | 确认 | generation/sequence/token 快照共同丢弃迟到结果 | 无修改 | Web 86 tests 通过 |
| N-07 | — | 确认 | 公共 DTO 未暴露内部 owner/workflow 字段 | 无修改 | Core API 与跨进程契约通过 |

---

## 五、审查边界声明

1. 本报告仅依据提供给我的 diff、文件清单与显式上下文；未读取未在 diff 中出现的文件（如 `ApiExceptionHandler`、`AgentChatController`、`services/deploy/common.sh`、`AgentActionResponse`），相关判断已明确标注其依赖前提。
2. 未运行任何命令或测试，未触发 VALIDATION；Codex 的回归数字（Java 242/0 fail/12 skip、Python 233 passed、Web 86、13/13 契约、Gitleaks 无命中）仅作为上下文引用。
3. 结论：建议修改 5 项均不构成当前同版本发布的安全、权限、并发、数据一致性或契约阻塞；`REVIEW_RESULT: PASS`。下一轮（如有）应以验证上述建议的处置与识别修复引入的新问题为主，不重复列举本轮已记录项。
