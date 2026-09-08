# Pi 代码审查报告：v2-06-approval-idempotency-audit / Attempt 1

- 日期：2026-09-08
- 审查阶段：v2-06-approval-idempotency-audit
- 审查对象：INDEX@97779c0（基线：97779c0970b4c36e867941b377e00263ad029750）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立只读代码审查报告

- 审查阶段：v2-06-approval-idempotency-audit
- 审查模式：Milestone
- 审查轮次：1 / 3
- 审查目标：INDEX@97779c0
- 固定模型：deepseek/deepseek-v4-pro

## 一、概述与总体结论

本次 V2-06 变更将 Day 5 的三态确认票据正确演进为五态 Approval（`PENDING/APPROVED/REJECTED/EXECUTED/FAILED`），核心状态机、幂等键绑定、悲观行锁、追加式审计与 `FAILED` 终态落库的整体实现是可靠的。`Idempotency-Key` 请求头校验（长度/字符集）、统一 Problem Details、`noRollbackFor = ConflictException.class` 配合 `markFailed` 使版本冲突从“不可用重试”演进为稳定 `FAILED`，这一设计成立并被真实 PostgreSQL 测试覆盖。

但在本节点自身文档声明的安全契约上存在一处明确的实现缺口：**reject 与 confirm 的终态 replay 分支未执行服务端 Tool Policy 复核**，与变更文档 Scope 中的“confirm/reject 每次都重新执行 ProjectAccess、请求者归属和服务端 Tool Policy 校验”不一致。鉴于本节点是权限/审批安全节点且该缺口有 diff 直接证据，判定为“必须修改”。其余发现为测试覆盖与实现细节层面的建议。

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（新文件） | 核心问题 |
| --- | --- | --- | --- | --- |
| M1 | 中 | services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java | `reject(...)` 方法体、`confirm(...)` 终态早返回分支 | reject 与 confirm 终态 replay 未执行 `riskEngine.authorize`，与本节点文档声明的“每次复核 Tool Policy”契约不一致 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（新文件） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | 低 | services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/AgentAuditEventRepositoryAdapter.java | `save()` | 使用 `saveAndFlush` 在事务中途强制 flush，无必要并可能提前暴露唯一约束冲突 |
| S2 | 低 | services/core-api/src/main/resources/db/migration/V7__add_approval_idempotency_and_audit.sql | `agent_action_audit_event` DDL | `actor_user_id` 外键未与 `approval_id`/`project_id` 的 `ON DELETE CASCADE` 保持一致，删除语义未显式定义 |
| S3 | 低 | services/core-api/src/test/java/com/agentforge/core/agent/api/AgentActionApiTest.java | 新增测试集 | HTTP seam 缺少不同 key replay 409、reject 后 confirm 409、终态同 key replay 等契约覆盖 |
| S4 | 低 | docs/03-features/approval-idempotency-and-audit.md | - | `APPROVED` 在本实现中只是事务内过程态（提交后行状态必为终态），文档未显式说明 |

### 无需修改

- 保留的 3 参 `confirm/reject` 重载仅服务既有测试，无 HTTP 暴露路径，不构成绕过面。
- `AgentAuditEvent.result` 取值等于 `eventType.name()`，虽有冗余但非空约束满足且不影响审计事实。
- Web 端 `decisionKeys` 生命周期（网络失败保留 key、终态清理 key）逻辑正确，与后端幂等契约一致。

## 三、Issue 展开

### M1 — reject 与 confirm 终态 replay 未执行 Tool Policy 复核

- **Severity**：中（必须修改）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java`，`reject(UUID, UUID, AuthenticatedActor, String, String)` 与 `confirm(UUID, UUID, AuthenticatedActor, String, String)`
- **Evidence**：

```java
// reject 方法体：只有 ProjectAccess 与发起者归属，无 riskEngine.authorize
public AgentActionView reject(...) {
    projectAccess.requireAccess(projectId, actor);
    AgentTaskAction action = findForDecision(projectId, actionId, actor);
    if (action.getStatus() == AgentActionStatus.REJECTED) {
        requireMatchingKey(action, idempotencyKey);
        return AgentActionView.from(action, null);
    }
    if (action.getStatus() != AgentActionStatus.PENDING) {
        throw new ConflictException("The Agent action can no longer be rejected.");
    }
    ...
}

// confirm 终态早返回分支在 riskEngine.authorize 之前
if (action.getStatus() == AgentActionStatus.EXECUTED) {
    requireMatchingKey(action, idempotencyKey);
    return AgentActionView.from(action, taskService.get(...));
}
if (action.getStatus() == AgentActionStatus.FAILED) {
    requireMatchingKey(action, idempotencyKey);
    return AgentActionView.from(action, null);
}
...
ToolOperation operation = switch (...) { ... };
riskEngine.authorize(operation, projectId, actor);   // 仅 PENDING 路径可达
```

对照文档 `docs/07-changes/2026-09-08-v2-06-approval-idempotency-audit.md` Scope 明确声明：

> confirm/reject 每次都重新执行 ProjectAccess、请求者归属和服务端 Tool Policy 校验。

- **Description**：reject 完全不调用 `riskEngine.authorize`；confirm 的 `EXECUTED`/`FAILED` 终态 replay 也早返回于 authorize 之前。虽然 reject 与终态 replay 不执行业务写入、实际越权利用面低（且 `EXECUTED` replay 会经 `taskService.get` 间接触发 `GET_TASK` 策略），但审批/权限节点自身的文档契约未满足，且 `FAILED` replay 分支连任何策略复核都没有。这是明确证据的契约缺口，而非猜测。
- **Suggested Fix**：将 Tool Policy 复核提前到 `findForDecision` 之后、所有状态早返回之前统一执行；reject 同样补上：

```java
// confirm 内部：把 authorize 移到 REJECTED/EXECUTED/FAILED 早返回之前
AgentTaskAction action = findForDecision(projectId, actionId, actor);
ToolOperation operation = switch (action.getActionType()) {
    case CREATE_TASK -> ToolOperation.CREATE_TASK;
    case UPDATE_TASK -> ToolOperation.UPDATE_TASK;
};
riskEngine.authorize(operation, projectId, actor);
// 再处理 REJECTED/EXECUTED/FAILED 早返回与审批/执行流程

// reject 内部：在 findForDecision 之后补
riskEngine.authorize(switch (action.getActionType()) {
    case CREATE_TASK -> ToolOperation.CREATE_TASK;
    case UPDATE_TASK -> ToolOperation.UPDATE_TASK;
}, projectId, actor);
```

若团队评估认为终态 replay 无需复核是可接受边界，则必须反向修改 `docs/07-changes/...` 与 `docs/03-features/security-and-risk.md` 中的表述，消除文档与实现冲突；但本节点验收重点是权限复核，优先建议补齐代码。

### S1 — 审计仓库使用 `saveAndFlush` 强制中途 flush

- **Severity**：低（建议修改）
- **File & Line**：`AgentAuditEventRepositoryAdapter.java`，`save()`
- **Evidence**：

```java
@Override
public AgentAuditEvent save(AgentAuditEvent event) {
    return repository.saveAndFlush(event);
}
```

- **Description**：审计事件依赖“业务状态与审计事件同事务提交”，`save`（延迟到事务提交统一 flush）已能保证一致性。`saveAndFlush` 会在 `confirm` 流程中提前执行 INSERT，例如 APPROVED 事件在 Task 执行前就被 flush，可能让幂等唯一索引冲突等数据库异常在非预期位置抛出，且无性能收益。
- **Suggested Fix**：

```java
return repository.save(event);
```

依赖外部 `@Transactional` 提交时统一 flush；若确需立即 flush 以验证约束，请在 ADR/代码注释说明理由。

### S2 — 审计事件外键删除语义不对称

- **Severity**：低（建议修改）
- **File & Line**：`V7__add_approval_idempotency_and_audit.sql`，`agent_action_audit_event`
- **Evidence**：

```sql
project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
approval_id UUID NOT NULL REFERENCES agent_task_action(id) ON DELETE CASCADE,
actor_user_id UUID NOT NULL REFERENCES app_user(id),
```

- **Description**：`project_id`/`approval_id` 级联删除，而 `actor_user_id` 无级联策略（默认 RESTRICT）。当前项目未实现用户删除，不构成现实缺陷；但删除语义不对称会在未来引入用户删除时表现为对外键删除的阻塞或审计丢失，需要显式决策。审计事件通常应保留 actor 引用，建议采用“删除用户时保留审计（如 soft delete 或 SET NULL + actor 快照）”，并在文档写明。
- **Suggested Fix**：在数据架构文档中明确审计记录在 Project/Approval/User 删除时的保留策略，必要时为 `actor_user_id` 增加显式约束策略。

### S3 — HTTP seam 幂等契约测试覆盖不足

- **Severity**：低（建议修改）
- **File & Line**：`AgentActionApiTest.java`
- **Evidence**：本次 HTTP 测试新增仅覆盖：confirm 成功、reject 成功、缺少 key 400、非法 key 400。
- **Description**：`docs/07-changes/...` 声明的预确认 seam 1 包含“401/403/404/409 与 replay 契约”。当前 HTTP 层没有覆盖：不同 key 对同一终态 approval 的 replay 返回 409、REJECTED 后 confirm 返回 409、EXECUTED/FAILED 同 key replay 返回既有事实。这些行为虽在 `AgentActionServiceTest` 应用层有覆盖，但缺失 Controller + Problem Details 映射的 HTTP 层验证。
- **Suggested Fix**：在 `AgentActionApiTest` 补充：

```java
// 不同 key replay → 409 problem+json
// REJECTED 后 confirm → 409
// EXECUTED 同 key replay → 200 且 resultTask 复用
// FAILED 同 key replay → 200 且 status=FAILED、resultTask 缺失
```

### S4 — APPROVED 过程态在文档中未显式说明

- **Severity**：低（建议修改）
- **File & Line**：`docs/03-features/approval-idempotency-and-audit.md`
- **Evidence**：文档声明“状态只允许：`PENDING → APPROVED → EXECUTED | FAILED`”，但实现中 `approve()` 与执行在同一 `@Transactional confirm` 内完成，事务提交后行状态只可能是 `PENDING`（执行前回滚）或终态，`APPROVED` 不会作为已提交的持久行状态独立存在（只作为 APPROVED 审计事件被记录）。
- **Description**：不影响正确性，但 V2-07（Checkpoint/Interrupt/Resume）会需要 `APPROVED` 作为可恢复的持久行状态。当前文档未区分“逻辑状态枚举”与“事务内瞬态”，可能误导下一节点取值。
- **Suggested Fix**：在“已知限制”或“确定性流程”处注明：首版 `APPROVED` 是事务内过程态，持久化行状态只落 `PENDING` 或终态；跨重启的 `APPROVED → Resume` 留给 V2-07。

## 四、主开发（Codex）评估回填区

| ID | Codex 是否采纳 | 修复/调整说明 | 对应提交 |
| --- | --- | --- | --- |
| M1 | 采纳并修复 | 将 ToolOperation 映射与 `riskEngine.authorize` 提前到所有终态分支之前，confirm/reject 每次均复核。新增 mock 调用次数断言先以 2 项失败红灯，修复后 `AgentActionServiceTest` 8/8；最终 Core clean verify 101 tests、0 failures/errors。 | 待本节点提交 |
| S1 | 采纳 | 审计仓库改用 `save`，由外层事务统一 flush；真实 PostgreSQL/Flyway/JPA 7 项集成回归通过。 | 待本节点提交 |
| S2 | 记录约束，不改迁移 | 当前没有用户硬删除功能；文档明确 Project/Approval 沿用级联、actor 引用限制删除。未来用户删除必须单独做数据保留决策，避免本节点推测 soft delete/快照方案。 | 待本节点提交 |
| S3 | 部分采纳并增强 | 新增不同 key → 409 Problem Details、FAILED 同 key → 200 稳定终态的 HTTP 测试，HTTP seam 6/6。更深状态组合已由 Application 与 PostgreSQL 公共 seam 覆盖，不在 Controller mock 层重复状态机。 | 待本节点提交 |
| S4 | 采纳 | 功能文档明确 APPROVED 是 V2-06 单次事务内逻辑过程态；跨重启持久化与恢复留给 V2-07。 | 待本节点提交 |

> 回填要求：M1 必须处理（补齐代码或显式修改文档契约二选一并说明理由）；S1–S4 可由 Codex 评估后采纳或记录不采纳理由。
