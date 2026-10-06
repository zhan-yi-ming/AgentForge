# Pi 代码审查报告：main-review-r01-r18-cumulative / Attempt 1

- 日期：2026-10-06
- 审查阶段：main-review-r01-r18-cumulative
- 审查对象：INDEX@cb46f93（基线：ff1a0a52317c69dba3d51e61e12e56dbf46f2bca）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge R01–R18 累计变更（Milestone 只读审查）报告

## 一、概述与总体结论

- 审查范围：`ff1a0a52317c69dba3d51e61e12e56dbf46f2bca .. cb46f93` 累计差异（含 Action workflow 轮次身份、Abort 补偿、checkpoint owner/actor 分离、数据库角色分离、RAG 快照代际与有界检索、Repository 固定 commit、Web 异步作用域、会话 revision lease、ASR 限流与备份恢复）。
- 审查方式：仅依据提供的 Diff、文件清单、变更记录与文档，未运行命令、未改动任何文件或 Git 状态。
- 总体结论：本次累计修复方向正确，跨服务契约（`actionWorkflowId`、Abort、`knownSnapshotVersion/snapshotVersion/sourcesChanged`、`requestId` 幂等交换）、数据库角色边界与 Web 代际隔离在 Diff 层面自洽，测试覆盖显著增强。但发现 1 项具备明确证据的状态码契约回归，需修复后交付；另附若干不阻塞的建议项。
- 结论：**需修复后交付（NEEDS_FIX）**。阻塞项为直接 Task 更新接口在 flush 期乐观锁冲突下由 409 退化为 500。

## 二、详细发现清单

| ID | 分级 | 严重级别 | 文件 | 行号（近似） | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| R-01 | 必须修改 | Medium | `services/core-api/src/main/java/com/agentforge/core/task/application/TaskService.java` | `update()` L88–L98 | 移除 `OptimisticLockingFailureException → ConflictException` 映射后，直接 Task 更新 API 的 flush 期并发冲突从文档承诺的 409 退化为 500 |
| R-02 | 建议修改 | Low | `ConversationHistoryService.java` / `V15` / `RagSourcesRequest.java` | `appendCompletedExchange` L44–L50 | `requestId` 上游允许 128 字符，但历史持久化列与校验为 100，长请求头会在生成成功后中断聊天并已扣配额 |
| R-03 | 建议修改 | Low | `AgentChatService.java` + `docs/06-operations/production-single-host.md` | `createPendingOrAbort` L180–L230 | 文档声明支持“先升级 Core、旧 Agent 暂存”的窗口，但新 Core 对无 `actionWorkflowId` 的旧 Agent proposal 会强制 Abort，旧 Agent 无该端点导致 404→409，窗口内任务提案不可用 |
| R-04 | 建议修改 | Low | `services/agent-service/src/agentforge_agent/tool_planner.py` | `return None` 与 `def _parse_create_body` 之间 | 删除了两个空行，顶级函数间为 0 空行（E302），可能触发 lint/format 门禁 |
| R-05 | 建议修改 | Low | `apps/web/src/App.tsx` | `decideAction` L700 附近 | `listRecoverableActions` 迟到响应只校验 `decisionSequence`，未校验会话加载代际；决策进行中点击“新建会话”后仍可能重新弹出恢复弹层 |

## 三、逐项展开

### R-01（必须修改 / Medium）直接 Task 更新接口丢失 409 映射

- File & Line：`services/core-api/src/main/java/com/agentforge/core/task/application/TaskService.java`，`update()` 方法（Diff 中 `task.update(...)` 之后的 `tasks.save(task)` 段）。

- Evidence（Diff 变更前后）：

```java
// 变更前
try {
    var updated = TaskView.from(tasks.save(task));
    if (graphSync != null) graphSync.mark(projectId, SourceType.TASK, taskId);
    return updated;
}
catch (OptimisticLockingFailureException exception) {
    throw new ConflictException("The Task was changed by another request.");
}

// 变更后
var updated = TaskView.from(tasks.save(task));
if (graphSync != null) graphSync.mark(projectId, SourceType.TASK, taskId);
return updated;
```

新增单测明确断言异常外泄：

```java
@Test
void updateLetsAFlushOptimisticConflictEscapeForTransactionRollback() {
    ...
    when(tasks.save(task)).thenThrow(new OptimisticLockingFailureException("concurrent flush"));
    assertThatThrownBy(() -> service.update(...))
            .isInstanceOf(OptimisticLockingFailureException.class);
}
```

- Description：
  1. `TaskService.update` 同时服务两条路径：Agent Approval 执行（`AgentActionService.executeApproved → executeUpdate`）与公开 `PUT /api/v1/projects/{projectId}/tasks/{taskId}`。
  2. Agent 路径确实需要原始 `OptimisticLockingFailureException` 才能让执行事务完整回滚、再由 `AgentActionWorkflowService.confirm` 捕获并调用 `failApprovedAfterOptimisticConflict` 收敛 FAILED，因此不能在 `TaskService.update` 内改回 `ConflictException`（否则事务被标记 rollback-only 或 FAILED 半提交）。
  3. 但公开 API 路径丢失了转换：`requireVersion` 只能拦“读取时已可见”的版本不一致（返回 409）；两个并发 PUT 读同一 version，后提交方在 `save` flush 时抛 `ObjectOptimisticLockingFailureException`，该异常现在直接冒泡到 MVC 层。
  4. 仓库现状是显式转换而非全局处理：`TaskService.delete` 仍保留 `catch (OptimisticLockingFailureException) → ConflictException`，`TaskService.update` 与 `WikiPageService` 等此前也都显式转换，说明不存在覆盖该异常的全局 `@ExceptionHandler`。因此默认错误处理会把并发更新变为 500。
  5. 这与 `docs/04-api/core-api.md`“更新和删除要求客户端提交当前 version。版本不一致返回 409，禁止 last-write-wins 静默覆盖”的承诺不一致，属明确的 HTTP 契约回归；且当前测试只覆盖服务层“异常外泄”，未覆盖直接 API 的状态码，形成测试盲区。

- Suggested Fix（保持 Agent 路径语义不变，在 HTTP 边界补回 409）：

```java
// shared/web 全局异常处理（或 TaskController 内）：
@ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException exception) {
    ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.CONFLICT);
    detail.setTitle("Conflict");
    detail.setDetail("The resource was changed by another request.");
    return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
}
```

并补充一条直接 API 并发更新断言 409 的测试；`AgentActionWorkflowService` 的 `catch (OptimisticLockingFailureException)` 补偿逻辑保持不变。

### R-02（建议修改 / Low）`requestId` 100 与 128 边界不一致

- File & Line：`ConversationHistoryService.appendCompletedExchange`（`if (requestId == null || requestId.isBlank() || requestId.length() > 100)`）、`V15__idempotent_conversation_exchange.sql`（`ADD COLUMN request_id VARCHAR(100)`）、`RagSourcesRequest.requestId`（`@Size(max = 128)`）。

- Evidence：

```java
if (requestId == null || requestId.isBlank() || requestId.length() > 100) {
    throw new IllegalArgumentException(
            "requestId is required and must not exceed 100 characters for completed conversation history.");
}
```

```sql
ALTER TABLE agent_message ADD COLUMN request_id VARCHAR(100);
```

```java
boolean actorAdmin,
@PositiveOrZero Long knownSnapshotVersion,
@NotBlank @Size(max = 128) String requestId
```

- Description：平台对 `X-Request-Id` 的上游约束为 128（Python 模型与 Java 内部请求均为 128），但历史持久化列与新增校验为 100。若客户端传入 101–128 字符的 `X-Request-Id`，聊天会先消耗日配额并完成模型生成，随后在 `persist` 阶段抛 `IllegalArgumentException`（同步入口报错、流式入口不再发送 `complete`）。Web 自身使用服务端 UUID（36 字符）不受影响，但这属内部契约不一致，且失败点在计费之后。

- Suggested Fix：统一边界，任选其一并补测试：
  - 将列与新迁移扩到 `VARCHAR(128)` 并同步校验常量；
  - 或在入口（`RequestIdFilter` / `AgentChatController`）将 requestId 截断/校验为 ≤100，使持久化约束不再可能被触发。

### R-03（建议修改 / Low）Core 先行升级窗口内任务提案不可用

- File & Line：`AgentChatService.createPendingOrAbort` / `abortWaitingRound`；`docs/06-operations/production-single-host.md`（“先升级 Core、确认内部来源接口已返回当前契约，再升级 Agent，不支持‘新 Agent + 旧 Core’的混合版本”）。

- Evidence：

```java
if (pending.isEmpty()) {
    abortWaitingRound(projectId, actor, conversationId, proposal, requestId);
}
```

```java
UUID actionWorkflowId = proposal.actionWorkflowId();
if (actionWorkflowId == null) {
    return Optional.empty();   // 旧 Agent 永远不返回 actionWorkflowId
}
```

```java
catch (RestClientResponseException exception) {
    if (exception.getStatusCode().value() == 404
            || exception.getStatusCode().value() == 409) {
        throw new ConflictException("Agent workflow cannot be aborted.");
    }
    ...
}
```

- Description：文档声明支持“先升级 Core、旧 Agent 暂存”的分阶段窗口（仅禁止“新 Agent + 旧 Core”）。但在该窗口内，旧 Agent 返回的 `toolProposal` 不含 `actionWorkflowId`，新 Core 的 `createPending` 直接返回 `empty`，随即调用 `/internal/v1/agent/abort`；旧 Agent 无该端点返回 404，被映射为 `ConflictException`，导致该窗口内所有任务提案请求（含普通 Chat 里出现写意图的消息）以失败结束。建议要么在文档中把“Core 先行”限定为不产生任务提案的迁移步骤，要么让新 Core 对“proposal 无 workflowId”退化为不调用 Abort（仅记录告警），使窗口内普通问答与旧行为兼容。

- Suggested Fix（示例）：

```java
private void abortWaitingRound(...) {
    if (proposal.actionWorkflowId() == null) {
        // 旧 Agent 契约：无 workflow 身份可补偿，保持既有普通回答语义
        return;
    }
    ...
}
```

并补充“新 Core + 旧 Agent”契约测试。

### R-04（建议修改 / Low）`tool_planner.py` 顶级函数间空行被删除

- File & Line：`services/agent-service/src/agentforge_agent/tool_planner.py`，`plan_tool` 末尾 `return None` 与 `_parse_create_body` 定义之间。

- Evidence：

```python
    return None
def _parse_create_body(body: str) -> dict[str, object]:
```

- Description：Diff 删除了两个空行，导致两个顶级函数之间为 0 空行（PEP8 E302 期望 2 空行）。不改变运行行为，但若 Agent Service 的 lint/format 门禁启用（ruff/flake8）会直接失败，属本次无意引入的格式回归。

- Suggested Fix：恢复两个空行。

### R-05（建议修改 / Low）恢复查询迟到响应可能覆盖“新建会话”状态

- File & Line：`apps/web/src/App.tsx`，`decideAction` 成功/失败分支中的 `await api.listRecoverableActions(...)` 与 `showFirstRecoverable(...)`。

- Evidence：

```ts
const isCurrentDecision = () =>
  decisionSequence.current === decisionRequest && isWorkspaceScopeCurrent(scope);
...
const recoverableActions = await api.listRecoverableActions(scope.projectId, selectedConversationId);
if (isCurrentDecision()) showFirstRecoverable(recoverableActions, actionId);
```

```ts
const showFirstRecoverable = useCallback((recoverableActions, replacingActionId?) => {
  const current = pendingActionRef.current;
  if (replacingActionId && current && current.id !== replacingActionId) return;
  const recovered = recoverableActions[0];
  commitPendingAction(recovered ? { ...recovered.action, source: recovered.source, recovered: true } : undefined);
  ...
}, [commitPendingAction]);
```

- Description：`newChat` / `openConversation` 通过递增 `activeConversationLoad` 清空 pending action，但 `isCurrentDecision` 只比较 `decisionSequence` 与 workspace scope，`showFirstRecoverable` 的守卫也只在“已存在另一个 pending action”时提前返回。因此在决策请求往返期间点击历史抽屉“新建会话”，`listRecoverableActions` 的迟到响应会把恢复弹层重新写回空白新会话。属低概率 UI 一致性问题，不影响后端事实。

- Suggested Fix：在 `decideAction` 捕获 `activeConversationLoad.current` 作为操作身份，恢复查询返回后同时校验其未变化；或让 `showFirstRecoverable` 接受并校验会话加载代际。

## 四、无需修改（已确认无问题的交叉边界）

| 范围 | 结论 |
| --- | --- |
| DB 角色分离（V16–V18、`provision-roles.sh`、Compose） | `POSTGRES_USER` 仅初始化/Flyway，Core 用 `agentforge_core`，Agent 仅 `rag_chunk`/`rag_project_snapshot`/`agent_checkpoint`；`REVOKE CREATE ON SCHEMA public FROM PUBLIC`、密码字符集校验与 `NOBYPASSRLS` 失败关闭，且有 `database-role-boundary.ps1` 与 `test_database_role_boundary.py` 佐证 |
| Action 轮次身份（V14 + 唯一索引 + advisory lock） | `action_workflow_id` 唯一约束、`source_scope_check` 覆盖 v1/v2/MCP 三种组合、`lockChatWorkflow` 串行化同轮重复创建，测试覆盖并发重放只产生一个 Approval |
| Abort 补偿与 owner/actor 分离 | `abort` 精确匹配 namespace/request/workflow，仅 WAITING 可 ABORTED 且可重放；Resume 使用持久化 `requestedByUserId` 作为 checkpoint owner、当前 actor 记审计，缺失 owner 失败关闭 |
| 会话 revision/lease | `claim_exchange` 先于 checkpoint 创建，`release` 仅释放自身 lease，已认领会话不被 LRU 淘汰、容量耗尽失败关闭，均有单测覆盖 |
| RAG 快照代际与有界候选 | `rag_source_generation` 触发器单调推进、`rag_project_snapshot` 拒绝旧快照回滚、搜索代际不等 fail closed 为空、向量/词法候选受 `candidate_k` 限制，图证据按代际有界匹配 |
| Repository 固定 commit | `rev-parse HEAD` 解析为完整 commit 后 tree/blob/log/UUID 全部由该 commit 派生，HEAD 中途移动不影响本批来源，测试双向覆盖 |
| Web 异步作用域 | login/project/operation generation 覆盖 Chat、Wiki 保存、Action 决策、格式化与历史删除，A→B→A 与重登录迟到响应均被丢弃 |
| Voice 与限流 | 48,000 字节触发、≤64,000 偶数字节分块、1,500ms 预览轮询，Nginx 独立 `asr_per_ip` 限流区并有专用合同测试 |
| 迁移与兼容 | V14–V18 均为追加式迁移，未改写历史版本；`application.yml` Flyway 管理凭据与运行 datasource 分离，`ddl-auto=validate` 保持 |

## 五、主开发（Codex）评估回填区

| Finding | 结论（接受/拒绝/部分接受） | 处理方式与提交 | 验证证据（命令/退出码/测试数） | 备注 |
| --- | --- | --- | --- | --- |
| R-01 | 拒绝 | 不修改。Pi 忽略了累计 diff 之外、在基线前已经存在的全局 ApiExceptionHandler；该 advice 明确把 OptimisticLockingFailureException 映射为 409 resource-conflict，因此 TaskService.update 让原异常越过事务边界正是既定设计，不会退化为 500 | git show 基线版 ApiExceptionHandler 已存在同一 handler；基线前 ResourceApiTest.wikiUpdateMapsDatabaseOptimisticLockToConflict 通过公共 MVC seam 验证同一全局映射。当前 clean verify：242 tests、0 failures/errors、12 conditional skips | Pi 的“仓库不存在全局 handler”事实前提错误；无需新测试或复审 |
| R-02 | 拒绝 | 不修改。公共 X-Request-Id 由 RequestIdFilter 的安全正则限制为 1–100 字符，101–128 字符不会进入 Chat 持久化，而是替换为 UUID；RagSourcesRequest 的 128 是独立内部 body 字段，不是公共请求头契约 | 直接代码核对 RequestIdFilter.SAFE_REQUEST_ID、ConversationHistoryService 与 RagSourcesRequest；完整跨进程与 Java 回归通过 | 可统一内部字段上限作为未来清理，但不存在 Pi 描述的计费后失败路径 |
| R-03 | 接受为 Low 建议 | 不修改实现。生产流程要求 Core↔Agent 同版本发布；手工 Core 先行窗口若产生旧 Agent 写提案确有失败可能，运维应在窗口内避免业务写意图或一次性切换两服务 | 当前完整 Compose 同版本 smoke、Tool HITL 与 13/13 Java↔Python 契约通过 | 非当前严重问题；若未来承诺无损滚动升级，应单独设计跨版本矩阵 |
| R-04 | 接受为 Low 建议 | 不修改。return None 与下一顶级函数之间缺少空行，运行语义不变；当前仓库没有把 E302 lint 纳入本批门禁 | Agent Service 233 passed；全量 runner 退出 0 | 纯格式建议，不触发复审 |
| R-05 | 接受为 Low 建议 | 不修改。Action 决策后的恢复查询未绑定 activeConversationLoad，在用户决策中途主动新建会话的窄窗口可能重新显示恢复弹层，但 workspace/project/login scope 与后端事实均未被污染 | Web 6 files、86 passed，production build 通过；后端 Action 状态机与恢复列表回归通过 | 低概率 UI 一致性建议；不属于安全、数据一致性或交付阻塞项 |

Codex 最终判断：Attempt 1 未发现经复现确认的严重问题。唯一阻塞项基于错误事实前提，四个其余 finding 均为 Pi 明确标注的 Low 建议；按项目治理不修改生产实现、不运行 Attempt 2。

> 说明：本报告仅依据所提供的累计 Diff、文件清单、变更记录与显式上下文形成；未执行任何命令、未修改代码或 Git 状态。R-01 的修复不得通过还原 `TaskService.update` 的 catch 实现，以免破坏 Agent Approval 的 flush 冲突补偿语义。
