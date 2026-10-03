# Pi 代码审查报告：r05-flush-conflict-transaction / Attempt 1

- 日期：2026-10-03
- 审查阶段：r05-flush-conflict-transaction
- 审查对象：INDEX@3776ecb（基线：3776ecb1f3a84b541a827106e29f7055e0dec720）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

## 一、概述与总体结论

- **审查阶段**：r05-flush-conflict-transaction（Milestone，Round 1/3）
- **审查基线**：`3776ecb1f3a84b541a827106e29f7055e0dec720`（INDEX@3776ecb），12 个文件，+271/-15
- **审查方式**：完全只读；仅依据本次 Git Diff、文件清单与 `docs/07-changes/2026-10-03-r05-flush-conflict-transaction.md` 内的验证记录，未执行命令、未修改任何文件或 Git 状态。

**总体结论：通过（PASS）。**

本次变更针对 R05 已确认的“真实乐观锁冲突在共享执行事务内被包装为 `ConflictException`，导致事务已 rollback-only 再提交时抛出 `UnexpectedRollbackException`、无法落 `FAILED`”问题，采用了清晰且收敛的修法：

1. `TaskService.update` 不再在已损坏的事务内吞掉 flush 期 `OptimisticLockingFailureException`，让异常跨越 `executeApproved` 事务边界，触发业务执行事务完整回滚（`Task / Graph sync / EXECUTED` 一起回滚）。
2. 非事务的 `AgentActionWorkflowService.confirm` 在事务退出后仅对 `UPDATE_TASK` 捕获该异常，调用独立短事务 `AgentActionService.failApprovedAfterOptimisticConflict` 收敛终态。
3. 补偿事务重新执行 project/actor/Permission 校验、幂等键校验并加行锁，仅当仍为 `APPROVED + UPDATE_TASK` 才写 `FAILED` 与审计；并发已写 `EXECUTED/FAILED` 时返回既有事实，不覆盖。

关键正确性点均成立：异常类型精准（仅 `OptimisticLockingFailureException`，不含 `PessimisticLockingFailureException` / 网络 / 数据库资源类异常），未知基础设施异常不会走终态补偿；补偿方法 `public` 且经 Spring 代理调用，事务边界成立；幂等键与归属校验覆盖了重放分支。未发现具备明确证据的必须修改项。以下 3 项为建议修改，不阻塞交付。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| S1 | 建议（中） | services/core-api/src/main/java/com/agentforge/core/task/application/TaskService.java | update 方法 ~95–106 | 移除本地包装后，普通 Task HTTP 更新的 flush 乐观锁冲突改为直接抛出 `OptimisticLockingFailureException`，409 映射完全依赖未在 diff 中出现的全局异常处理器 |
| S2 | 建议（中低） | services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java | `failApprovedAfterOptimisticConflict` ~313–343 | 补偿方法使用默认 `REQUIRED` 传播，其“独立短事务”语义依赖调用点无外层事务，未来若被事务包裹会复现 `UnexpectedRollbackException` |
| S3 | 建议（低） | services/core-api/src/test/java/com/agentforge/core/agent/application/AgentActionServiceTest.java / PersistenceIntegrationTest.java | 新增用例 | 补偿方法的 `EXECUTED`/`FAILED` 重放分支、非 `UPDATE_TASK` rethrow 分支无单测；集成用例的 graph source sync 计数断言区分度偏弱 |
| N1 | 无需修改 | AgentActionService / AgentActionWorkflowService / TaskService | — | 异常类型选择、幂等键校验、授权与行锁顺序、成功路径原子性均正确，符合文档与 R05 目标 |

## 三、逐个 Issue 展开

### S1（建议 / 中）TaskService.update 的 flush 冲突 HTTP 状态码映射未被本次 diff 证据覆盖

- **Severity**：建议（中）——若全局 handler 缺失则为契约回归，但本次 diff 无法证实。
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/task/application/TaskService.java`（`update`，约 95–106 行）
- **Evidence**：

```java
// 变更前
try {
    var updated = TaskView.from(tasks.save(task));
    if (graphSync != null) graphSync.mark(projectId, SourceType.TASK, taskId);
    return updated;
} catch (OptimisticLockingFailureException exception) {
    throw new ConflictException("The Task was changed by another request.");
}

// 变更后
var updated = TaskView.from(tasks.save(task));
if (graphSync != null) graphSync.mark(projectId, SourceType.TASK, taskId);
return updated;
```

- **Description**：变更方向本身正确（在已 rollback-only 的事务内包装会掩盖真实事务状态）。但普通 Task HTTP 更新路径此前通过 `ConflictException` 保证 409；现在改为原始 `OptimisticLockingFailureException` 向上抛。`docs/04-api/core-api.md` 与变更记录均声明“全局 HTTP 异常处理器仍将其映射为 409”，但该处理器不在本次 12 个改动文件中，diff 内也无对应回归测试证明该映射存在。`TaskServiceTest.updateLetsAFlushOptimisticConflictEscapeForTransactionRollback` 只验证异常逃逸，未验证 Web 层 409。
- **Suggested Fix**：确认 `@RestControllerAdvice` 中存在 `OptimisticLockingFailureException`（或其父类 `ConcurrencyFailureException`/`ObjectOptimisticLockingFailureException`）→ 409 的映射；若不存在，新增该映射，并在 `ResourceApiTest` 中补一条“普通 Task 更新遇到 flush 乐观锁冲突返回 409”的用例，避免文档声明与真实 HTTP 行为脱节。

### S2（建议 / 中低）补偿短事务的事务传播语义依赖调用点上下文

- **Severity**：建议（中低）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java`（`failApprovedAfterOptimisticConflict`，约 313–343 行）
- **Evidence**：

```java
@Transactional
public AgentActionView failApprovedAfterOptimisticConflict(
        UUID projectId, UUID actionId, AuthenticatedActor actor,
        String idempotencyKey, String requestId) {
    ...
    action.markFailed(Instant.now(clock));
    AgentActionView failed = AgentActionView.from(actions.save(action), null);
    auditEvents.save(AgentAuditEvent.record(...));
    return failed;
}
```

- **Description**：当前调用链 `AgentActionWorkflowService.confirm`（非事务）→ `executeApproved`（事务已回滚退出）→ 补偿方法，因此默认 `REQUIRED` 确实会新开事务，当前行为正确。但“独立短事务”这一关键不变量完全依赖调用点上不存在外层事务：一旦未来有人在 Controller 或编排层给 `confirm` 包上事务，`executeApproved` 的异常会把外层事务标记 rollback-only，补偿方法 `REQUIRED` 会加入该已损坏事务，提交时再次触发 `UnexpectedRollbackException` —— 正是本节点要消除的缺陷。
- **Suggested Fix**：将补偿方法显式声明为独立事务边界，使“不回滚外层、始终新事务”成为代码自证，而非调用约定，例如 `@Transactional(propagation = Propagation.REQUIRES_NEW)`（与变更记录非目标中“不给通用业务方法加 REQUIRES_NEW”不冲突，因为此方法是专用收敛入口）。

### S3（建议 / 低）测试覆盖与断言区分度

- **Severity**：建议（低）
- **File & Line**：`AgentActionServiceTest.optimisticConflictCompensationPersistsFailedInItsOwnTransactionBoundary`；`AgentActionWorkflowServiceTest.flushOptimisticConflictIsPersistedAfterTheExecutionTransactionRollsBack`；`PersistenceIntegrationTest.flushOptimisticConflictPersistsFailedAfterTheExecutionTransactionRollsBack`
- **Evidence**：

```java
// 仅覆盖 APPROVED -> FAILED 主路径
AgentActionView failed = service.failApprovedAfterOptimisticConflict(
        projectId, action.getId(), actor, "flush-key", "flush-request");
assertThat(failed.status()).isEqualTo(AgentActionStatus.FAILED);
```

```java
assertThat(jdbcTemplate.queryForObject(
        "select count(*) from graph_source_sync where project_id = ? and source_type = 'TASK' and source_id = ?",
        Integer.class, project.id(), task.id())).isEqualTo(1);
```

- **Description**：补偿方法包含 4 条控制流（`EXECUTED` 重放、`FAILED` 重放、非 `APPROVED/UPDATE_TASK` 冲突、正常落 `FAILED`），当前仅覆盖最后一条；`AgentActionWorkflowService` 中“非 `UPDATE_TASK` 时重新抛出”也无对应用例。集成用例断言 `graph_source_sync` 计数为 1，但任务创建阶段可能已写入同一行，无法强证明“失败事务未产生额外事实”（若该表为 upsert 语义，计数 1 会自然成立）。这些不影响本节点判定，但削弱了对回归的长期保护。
- **Suggested Fix**：补 3 条单测（`EXECUTED` 重放返回既有结果且不新增审计、`FAILED` 重放、`CREATE_TASK` 的 OLE 必须原样抛出且不调用补偿）；集成断言改为对“该 action 相关的额外 sync/审计事实”做差量或次数校验，而非固定总数。

### N1（无需修改）已确认正确的关键点

- **Severity**：无
- **File**：`AgentActionWorkflowService.java` / `AgentActionService.java` / `TaskService.java`
- **说明**：
  - 捕获类型精确为 `OptimisticLockingFailureException`（`org.springframework.dao`），不含 `PessimisticLockingFailureException`、连接/资源类异常，符合“未知基础设施异常不得转 FAILED、保留 APPROVED”的文档语义。
  - `failApprovedAfterOptimisticConflict` 在写入前依次执行 `projectAccess.requireAccess` → `findForDecision`（含 owner/ADMIN 校验与行锁）→ `riskEngine.authorize` → `requireMatchingKey`，跨用户越权与不同 key 覆盖终态均被阻断。
  - 成功路径 `Task / Graph sync / EXECUTED` 仍在同一事务原子提交，未引入 `REQUIRES_NEW` 或拆事务。
  - 文档四处（`agent-runtime.md`、`approval-idempotency-and-audit.md`、`tool-calling-and-confirmation.md`、`core-api.md`）与代码行为一致，未引入 V2/V3 越界组件（无 Neo4j/GraphRAG、Langfuse、LiteLLM、MCP 扩展）。
  - `TaskService.delete` 仍保留本地 `catch (OptimisticLockingFailureException)` 包装，属本次范围外的既有实现，未在本次修改，不纳入判定。

## 四、主开发（Codex）评估回填区

| 发现 ID | 是否同意 | 处置（修复 / 反驳 / 转后续节点） | 证据 / 说明 |
|---------|----------|----------------------------------|-------------|
| S1 | 同意核实，不需修改 | `ApiExceptionHandler.handleOptimisticLockingFailure` 已把 `OptimisticLockingFailureException` 通用映射为 409；`ResourceApiTest.wikiUpdateMapsDatabaseOptimisticLockToConflict` 固定该 handler 契约，本轮该类 9/9 通过。 | 公共 HTTP 契约未回归。 |
| S2 | 不作为当前缺陷 | `AgentActionWorkflowService` 有意保持非事务，跨 Spring 代理调用补偿方法时默认 REQUIRED 已开启新事务；真实 PostgreSQL 双事务验证 FAILED 可提交。未来假设性调用方变化不扩大本项。 | 避免不必要地引入 `REQUIRES_NEW` 及其独立提交语义。 |
| S3 | 不作为阻塞项 | 主路径由 Workflow/Action 单元测试和真实双事务覆盖；相同 key replay、未知异常保留 APPROVED、非 UPDATE 捕获范围已有相邻测试/类型门卫。Graph sync 表为来源唯一 upsert，计数 1 联合 Task/version/审计断言足以证明失败事务未产生可观察额外事实。 | 建议级，不触发复审。 |
| N1 | 同意 | 无需处理。 | 异常分类、授权、幂等、锁与成功原子性均保持。 |

## 五、结论

本次 diff 针对已确认的 flush 期乐观锁事务缺陷给出了可运行、可测试且收敛的修复，未发现必须修改项；S1–S3 为建议项，请在提交前至少确认 S1 的 409 映射，以免公共 API 契约与文档声明不一致。判定：**REVIEW_RESULT: PASS**。
