# Pi 代码审查报告：v2-06-approval-idempotency-audit / Attempt 2

- 日期：2026-09-09
- 审查阶段：v2-06-approval-idempotency-audit
- 审查对象：INDEX@97779c0（基线：97779c0970b4c36e867941b377e00263ad029750）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立只读代码审查报告

- 审查阶段：v2-06-approval-idempotency-audit
- 审查模式：Milestone
- 审查轮次：2 / 3
- 审查目标：INDEX@97779c0
- 固定模型：deepseek/deepseek-v4-pro

## 一、概述与总体结论

本轮为 Attempt 1 结论 `NEEDS_FIX` 后的第二轮复核。Attempt 1 的必须项 M1（reject 与 confirm 终态 replay 未执行服务端 Tool Policy 复核）已在当前 diff 中修复：

- `confirm(...)` 在 `projectAccess.requireAccess` + `findForDecision` 之后、`REJECTED / EXECUTED / FAILED` 任何终态早返回之前，先统一执行 `riskEngine.authorize(operationFor(action), ...)`；
- `reject(...)` 同样在 `findForDecision` 之后、所有状态分支之前补齐 `riskEngine.authorize(...)`；
- `AgentActionServiceTest` 新增了 `verify(riskEngine, times(3/4))` 调用次数断言，把“每次复核”从文档契约变成了可回归的行为锁。

四条建议项处置与 Attempt 1 回填一致：S1（`saveAndFlush` → `save`）、S4（APPROVED 仅为事务内过程态）已在代码/文档落实；S2（审计外键删除语义）已用文档明确“不迁移、留待真实用户删除决策”；S3 已补充不同 key 409 与 FAILED 同 key 200 的 HTTP 测试（HTTP seam 达 6 项），其余状态组合由 Application 与 PostgreSQL 公共 seam 覆盖，属合理分层。

本轮未发现新增“必须修改”项。发现 3 项低/中风险建议：同事务内审计事件排序缺乏确定性键导致集成测试排序断言潜在 flaky（F1）、flush 期乐观锁竞态未纳入 `FAILED`/文档措辞存在细微偏差（F2）、EXECUTED replay 依赖 resultTask 可读取（F3）。三项均不阻塞交付，但 F1 建议在收敛本节点前处理以保证 Core 门禁可重复。

## 二、详细发现清单

### 必须修改

无。Attempt 1 的 M1 已修复并由单测锁定。

### 建议修改

| ID | 严重级别 | 文件 | 行号（新文件） | 核心问题 |
| --- | --- | --- | --- | --- |
| F1 | 中 | services/core-api/.../agent/application/AgentActionService.java、domain/AgentAuditEvent.java、PersistenceIntegrationTest.java | `confirm(...)` 的 APPROVED/EXECUTED/FAILED 三段 `auditEvents.save(AgentAuditEvent.record(..., Instant.now(clock)))`；集成测试 `order by created_at, id` + `containsExactly` | 审计事件在单一事务内用独立 `Instant.now(clock)` 打点，毫秒级时钟下多事件同刻且 `id` 为随机 UUIDv4，排序断言非确定，`clean verify` 存在间歇性失败风险；追加式审计本身也缺少单调序列键 |
| F2 | 低 | services/core-api/.../task/application/TaskService.java、agent/application/AgentActionService.java | `@Transactional(noRollbackFor = ConflictException.class)`；`confirm(...)` 仅 `catch (ConflictException)` | flush 期 `@Version` 冲突抛 `OptimisticLockingFailureException`，不在 `noRollbackFor` 内，极端并发下批准后版本冲突整体回滚为 PENDING + 409 而非 FAILED，与“批准后发生的版本冲突记录为 FAILED”措辞有细微偏差 |
| F3 | 低 | services/core-api/.../agent/application/AgentActionService.java | `confirm(...)` 的 EXECUTED 早返回分支 | EXECUTED replay 通过 `taskService.get(resultTaskId)` 重新物化 resultTask，若结果 Task 已被删除则 replay 返回 404 而非稳定 EXECUTED 事实，存在幂等 replay 边界瑕疵 |

### 无需修改

- M1 修复本身正确；`operationFor` 映射与 `riskEngine.authorize` 前置于所有终态分支是正确修法。
- S2 处置合理：不改迁移、监控删除语义并留待真实用户删除需求，避免本节点预判 soft delete/快照。
- S3 分层决策合理：HTTP Controller mock 层不必重复 Application/PostgreSQL 已覆盖的完整状态机。
- 保留的 3 参 `confirm/reject` 重载无 HTTP 暴露路径，仅服务测试，不构成绕过面。
- `AgentAuditEvent.result` 取值等于 `eventType.name()` 虽有冗余，但非空约束满足、不影响审计事实。

## 三、Issue 展开

### F1 — 审计事件同事务内排序非确定，集成测试排序断言潜在 flaky

- **Severity**：中（建议修改）
- **File & Line**：`AgentActionService.java` confirm 流程、`AgentAuditEvent.java`、`PersistenceIntegrationTest.java::pendingAgentActionWritesTaskOnlyOnceAfterConfirmation` / `approvedActionPersistsFailedStateWhenTheTargetVersionChanged`
- **Evidence**：

```java
// APPROVED 事件
auditEvents.save(AgentAuditEvent.record(
        action, actor.userId(), AgentAuditEventType.APPROVED,
        requestId, idempotencyKey, Instant.now(clock)));   // t_A

// ... 此后 executeCreate 仅 persist(延迟 INSERT，无 flush)

// EXECUTED 事件
auditEvents.save(AgentAuditEvent.record(
        action, actor.userId(), AgentAuditEventType.EXECUTED,
        requestId, idempotencyKey, Instant.now(clock)));   // t_E

// AgentAuditEvent 构造器：this.id = UUID.randomUUID(); this.createdAt = ...;
```

```java
// 集成测试断言
assertThat(jdbcTemplate.queryForList(
    "select event_type from agent_action_audit_event where approval_id = ? order by created_at, id",
    String.class, pending.id()))
    .containsExactly("REQUESTED", "APPROVED", "EXECUTED");
```

- **Description**：APPROVED 与 EXECUTED（以及 APPROVED 与 FAILED）事件在同一个 `@Transactional` confirm 内生成，两者之间只有 `actions.save`/`tasks.save` 的内存操作与一次 merge 存在性 SELECT，没有任何显式取序。`Clock.systemUTC().instant()`（`Instant.ofEpochMilli(System.currentTimeMillis())`）只到毫秒，极大概率落在同一毫秒。排序键 `(created_at, id)` 中 `id` 是 `UUID.randomUUID()`（v4，字节级随机），PostgreSQL 对相同时刻的事件按随机 UUID 决序，因此 `containsExactly("REQUESTED", "APPROVED", "EXECUTED")` 的次序不是确定性的（APPROVED/EXECUTED 顺序约 50%），存在 `clean verify` 偶发失败的风险；同时追加式审计日志本身也没有单调序列可供确定性重建事件先后。
- **Suggested Fix**：二选一或同时：
  1. 数据层：给 `agent_action_audit_event` 增加单调序列列（如 `seq BIGINT GENERATED ALWAYS AS IDENTITY` 或数据库 sequence），查询固定 `ORDER BY seq`（或 `approval_id, seq`），使追加审计可确定性排序；
  2. 测试层最小改动：将 `containsExactly` 改为 `containsExactlyInAnyOrder`，并单独断言顺序约束（如 APPROVED.createdAt <= EXECUTED/FAILED.createdAt），或对同一 confirm 内的多次 `record(...)` 复用同一 `Instant now` 变量并为此命题增加显式测试。

### F2 — flush 期乐观锁冲突未纳入“批准后版本冲突=FAILED”路径

- **Severity**：低（建议修改）
- **File & Line**：`TaskService.java::update`（`@Transactional(noRollbackFor = ConflictException.class)`）；`AgentActionService.java::confirm`（`catch (ConflictException exception)`）
- **Evidence**：

```java
@Transactional(noRollbackFor = ConflictException.class)
public TaskView update(...) {
    ...
    requireVersion(task, expectedVersion);   // 应用层检查 → ConflictException → 被 confirm catch → FAILED
    ...
    return TaskView.from(tasks.save(task));  // flush 期 @Version 冲突 → OptimisticLockingFailureException
}
```

```java
try {
    result = action.getActionType() == AgentActionType.CREATE_TASK
            ? executeCreate(...) : executeUpdate(...);
}
catch (ConflictException exception) {       // 未捕获 OptimisticLockingFailureException
    action.markFailed(...);
    ...
}
```

- **Description**：`executeUpdate` 先 `taskService.get` 再 `taskService.update`。当目标 Task 在 confirm 事务读取版本之后、本事务提交 flush 之前被另一事务提交修改时，应用层 `requireVersion` 判定通过，但 flush 期 `@Version` 检测以 `OptimisticLockingFailureException` 失败。该类异常不属于 `noRollbackFor = ConflictException.class`，也不是 `catch (ConflictException)` 的捕获对象，会整体回滚（action 保持 PENDING、无 APPROVED/FAILED 审计），由既有乐观锁处理器返回 409。行为本身安全可重试，但与“批准后发生的版本冲突记录为 FAILED”文档措辞不完全一致（实际上只覆盖了“确认时已 stale”的场景，不覆盖“确认与并发修改竞态”）。
- **Suggested Fix**：二选一：将 `TaskService.update` 的 `noRollbackFor` 扩展为 `{ ConflictException.class, OptimisticLockingFailureException.class }`，并在 confirm 的 catch 中一并处理（或在 `update` 内把 flush 期乐观锁冲突转译为 `ConflictException`）；若认为竞态回滚 PENDING 是刻意边界，则显式修改 `docs/03-features/tool-calling-and-confirmation.md` 与数据架构文档，区分“确认时已 stale → FAILED”与“确认与并发修改竞态 → 回滚 PENDING + 409 可重试”两种结果。

### F3 — EXECUTED replay 依赖 resultTask 可读取

- **Severity**：低（建议修改）
- **File & Line**：`AgentActionService.java::confirm` EXECUTED 早返回分支
- **Evidence**：

```java
if (action.getStatus() == AgentActionStatus.EXECUTED) {
    requireMatchingKey(action, idempotencyKey);
    return AgentActionView.from(action, taskService.get(projectId, action.getResultTaskId(), actor));
}
```

- **Description**：同 key replay 的“返回既有事实”被实现为重新读取 `resultTaskId` 对应的 Task 并物化 `resultTask`。V1 已存在 Task 删除能力（V2-05 策略矩阵明确 `Wiki/Task delete | HIGH | ADMIN` 是直接 API 能力）。一旦结果 Task 在批准执行之后被删除，终态 replay 会经 `taskService.get` 抛 `ResourceNotFoundException`（404），而不是稳定返回 `status=EXECUTED` 的既有事实。不会造成重复写入或越权，但使幂等 replay 契约在该边界出现缺口，且 Web 端（网络失败重试路径）会看到 404 而非稳定成功。
- **Suggested Fix**：在 EXECUTED replay 分支单独捕获 `ResourceNotFoundException`，返回 `AgentActionView.from(action, null)`（保留 `status=EXECUTED`、`resultTask=null`）或明确返回既有事实；或在 `core-api.md`/`approval-idempotency-and-audit.md` 明确该边界及客户端处理方式。

## 四、主开发（Codex）最终处置

- F1：采纳测试层建议。当前没有 Audit 查询 API 或全序契约，集成测试改为验证完整事件集合，不再依赖相同 timestamp 下随机 UUID 的伪顺序；数据层序列留待真实全序查询需求。
- F2：保持现有安全回滚。flush 期乐观锁异常表示事务从未提交，继续回滚为 `PENDING` + 409；功能、数据与 API 文档已区分确认前业务冲突和 flush 期竞态。
- F3：采纳并以 TDD 修复。新增结果 Task 后续删除的 replay 用例先因 404 红灯，最小实现后返回稳定 `EXECUTED` + null result；最终 Core clean verify 102 tests 通过。
- 上述均为 Attempt 2 明确的非阻断建议，按项目规则不触发 Attempt 3。

## 四、主开发（Codex）评估回填区

| ID | Codex 是否采纳 | 修复/调整说明 | 对应提交 |
| --- | --- | --- | --- |
| F1 | 待评估 | 建议：为 `agent_action_audit_event` 增加单调序列列并固定 `ORDER BY seq`，或放宽 `containsExactly` 断言并单独断言时间顺序；至少保证 `clean verify` 可重复。 | 待本节点提交 |
| F2 | 待评估 | 建议：将 `OptimisticLockingFailureException` 纳入 `noRollbackFor` 并在 confirm 捕获转 `FAILED`，或明确文档“并发竞态回滚 PENDING + 409”边界。 | 待本节点提交 |
| F3 | 待评估 | 建议：EXECUTED replay 在 resultTask 缺失时返回稳定 EXECUTED 事实或显式文档化 404 边界。 | 待本节点提交 |

> 回填要求：F1–F3 均非阻塞项，Codex 可评估后采纳或记录不采纳理由；其中 F1 建议优先，以避免 Core 门禁偶发失败。
