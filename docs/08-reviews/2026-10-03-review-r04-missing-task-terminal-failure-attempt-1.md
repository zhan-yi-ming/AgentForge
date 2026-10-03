# Pi 代码审查报告：r04-missing-task-terminal-failure / Attempt 1

- 日期：2026-10-03
- 审查阶段：r04-missing-task-terminal-failure
- 审查对象：INDEX@2c990b7（基线：2c990b7ae83ca740cbbf9e5f6e7217e3c88d4bec）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立审查报告 — r04-missing-task-terminal-failure（Milestone / 第 1 轮）

## 一、概述与总体结论

- **审查目标**：Commit `INDEX@2c990b7`，8 个文件，`+163 / -6`。
- **变更目的**：已审批 `UPDATE_TASK` 在执行前发现目标 Task 被删除时，将 Action 收敛到终态 `FAILED` 并写审计；未知基础设施异常保持 `APPROVED` 可重试。
- **总体结论**：**通过（可交付）**。核心修复方向正确且证据链完整：
  - `AgentActionService.executeApproved` 仅将 `ConflictException | ResourceNotFoundException` 归类为确定性业务失败，复用既有 `markFailed` / FAILED 审计，未吞并其它运行时异常。
  - `TaskService.get/update` 增加 `noRollbackFor = ResourceNotFoundException`，解决了嵌套 `@Transactional` 参与事务被标记 rollback-only 导致的 `UnexpectedRollbackException`，且不影响公共 Task API 的 404 契约。
  - 幂等重放语义（同 key 返回既有 `FAILED`、不同 key 409）未被破坏。
  - 单元测试 + 真实 PostgreSQL 集成测试覆盖了主要分支。
- **未发现具备明确证据的“必须修改”问题**；存在若干可改进项（建议级），不阻塞交付。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| R04-01 | 建议（Low） | `services/core-api/.../agent/application/AgentActionService.java` | 298–308 | 捕获范围按异常类型而非按操作类型，`CREATE_TASK` 分支的 `ResourceNotFoundException` 也会被判为永久 `FAILED`，超出本次“目标 Task 删除”目标 |
| R04-02 | 建议（Low） | `docs/04-api/core-api.md` | 224 段 | 公共契约删除了 flush 期乐观锁冲突“回滚为 PENDING 并返回 409”的表述，改为“由独立审查项处理”，R05 未闭环，契约出现空白 |
| R04-03 | 建议（Low） | `docs/07-changes/2026-10-03-r04-missing-task-terminal-failure.md` / `docs/04-api/core-api.md` | 目标/契约段 | 文档只描述 `UPDATE_TASK`，但代码对 `CREATE_TASK` 同样生效，文档与实现范围不一致 |
| R04-04 | 建议（Low） | `services/core-api/.../agent/application/AgentActionServiceTest.java` | 405–438 | 暂时性基础设施异常仅有 mock 单元测试（无事务语义），缺少“真实事务下 APPROVED 保持且同 key 重试成功”的集成证据 |
| R04-05 | 建议（Low） | `services/core-api/.../agent/application/AgentActionServiceTest.java` | 421–438 | 未断言失败瞬态分支未调用 `actions.save` / 未产生 EXECUTED 审计，断言强度略低 |

> 无需修改：`noRollbackFor` 的设计正确性、Action 行锁 + 幂等键并发语义、`FAILED` 不再阻塞会话删除、审计三事件 `REQUESTED/APPROVED/FAILED` 一致性。

---

## 三、逐项展开

### R04-01（建议 / Low）异常捕获范围宽于任务目标

- **File & Line**：`AgentActionService.java`，`executeApproved` try 块（diff 行 298–308）
- **Evidence**：
```java
try {
    result = action.getActionType() == AgentActionType.CREATE_TASK
            ? executeCreate(projectId, actor, action)
            : executeUpdate(projectId, actor, action);
}
catch (ConflictException | ResourceNotFoundException exception) {
    action.markFailed(Instant.now(clock));
    ...
}
```
- **Description**：`ResourceNotFoundException` 的捕获是按类型而非按 `actionType` 判定。`CREATE_TASK` 分支（`executeCreate` → `taskService.create` → `riskEngine.authorize`）若抛出 `ResourceNotFoundException`（例如项目/参与者查找失败），同样会被永久标记 `FAILED`，与本次目标“目标 Task 已删除属确定性失败”不是同一语义。方向性问题，但当前无法证明存在可触发的真实路径，故不阻塞。
- **Suggested Fix**：将判定限定在更新路径，或显式区分：
```java
catch (ConflictException exception) { markFailed(...); return failed; }
catch (ResourceNotFoundException exception) {
    if (action.getActionType() != AgentActionType.UPDATE_TASK) throw exception;
    markFailed(...); return failed;
}
```

### R04-02（建议 / Low）公共契约删除 flush 期语义但未闭环

- **File & Line**：`docs/04-api/core-api.md`，confirm 段落
- **Evidence**：
  - 变更前：`flush 期并发乐观锁冲突回滚为 PENDING 并返回 409。`
  - 变更后：`flush 期并发乐观锁冲突的事务语义由独立审查项处理。`
- **Description**：文档删除了明确的公共契约，而 R05 在本次 non-goal 中被显式推迟。当前实现仍可能因 `ConflictException` 上的 `noRollbackFor` + 脏实体在提交期再次 flush 产生未定义行为。公共 API 文档出现语义空白，可能误导调用方。
- **Suggested Fix**：在 R05 独立修复完成前，文档保留“当前已知不稳定、待 R05 修复”的显式标记与跟踪链接，避免契约悬空。

### R04-03（建议 / Low）文档范围与实现范围不一致

- **File & Line**：`docs/07-changes/2026-10-03-r04-missing-task-terminal-failure.md` 目标段；`docs/04-api/core-api.md`
- **Evidence**：文档写“已审批 UPDATE_TASK … 目标已删除或业务版本冲突，返回 `FAILED`”，而代码分支同时覆盖 `CREATE_TASK`。
- **Description**：契约文档与实现清单存在范围偏差，影响后续审核与前端预期。
- **Suggested Fix**：或在代码侧收敛为仅 UPDATE_TASK（见 R04-01），或在文档中补一句“实现对所有 Action 类型在确定性 `ResourceNotFoundException` 下统一进入 FAILED”。

### R04-04（建议 / Low）瞬态失败缺少事务级证据

- **File & Line**：`AgentActionServiceTest.java` `transientTaskLookupFailureKeepsTheApprovedActionRetryable`
- **Evidence**：
```java
when(taskService.get(projectId, taskId, actor))
        .thenThrow(new DataAccessResourceFailureException("temporary database outage"));
assertThat(action.getStatus()).isEqualTo(AgentActionStatus.APPROVED);
```
- **Description**：该断言基于 mock，无事务回滚/rollback-only 语义参与，不能等价证明真实事务链下“异常回滚后 APPROVED 保留、同 key 可重试”。变更记录的计划验证项“数据访问暂时失败传播且 Action 保持 APPROVED”在集成层缺少对应证据。
- **Suggested Fix**：在 `PersistenceIntegrationTest` 中增加用例：先 `approve` 提交，再以受控方式让执行阶段抛 `DataAccessException`，断言 DB 中 Action 仍为 `APPROVED` 且同 key 再次 confirm 可继续执行。

### R04-05（建议 / Low）负向断言强度不足

- **File & Line**：`AgentActionServiceTest.java` 瞬态用例
- **Evidence**：仅 `verify(auditEvents, never()).save(argThat(FAILED))`，未校验 `actions.save` 未被调用、未产生 `EXECUTED` 审计。
- **Description**：无法排除“状态未变但误执行/误持久化”的隐性回归。
- **Suggested Fix**：补充 `verify(actions, never()).save(any())`、`verify(auditEvents, never()).save(argThat(e -> e.getEventType() == AgentAuditEventType.EXECUTED))`。

---

## 四、无需修改（确认项）

1. **`noRollbackFor` 正确性**：`TaskService.get`/`update` 作为参与事务被外层调用时，`noRollbackFor` 阻止参与事务失败时对全局事务标记 rollback-only，正是解决 `UnexpectedRollbackException` 的正确手段；`get` 为只读，无副作用。
2. **并发/幂等未退化**：`findByProjectIdAndIdForUpdate` 行锁 + `requireMatchingKey` 保证同 key 重放返回既有 `FAILED`，不同 key 409；新增分支不改动这些路径。
3. **审计一致性**：`REQUESTED/APPROVED/FAILED` 由同一事务写入，集成测试 `containsExactlyInAnyOrder` 验证通过。
4. **会话删除解锁**：`FAILED` 不在 `PENDING/APPROVED` 阻止集合内，集成测试实际执行删除并断言列表为空。
5. **契约枚举未变**：未新增 Action 状态或审计类型，未触碰 Python/MCP/前端契约。

---

## 五、主开发（Codex）评估回填区

| Finding ID | 是否认可 | 处理方式（修复/拒绝+理由） | 对应提交 | 备注 |
|------------|----------|----------------------------|----------|------|
| R04-01 | 是 | 修复：新增红灯测试并把 `ResourceNotFoundException` 的终态分类限制到 `UPDATE_TASK`；`CREATE_TASK` 保持原传播/回滚语义。 | 本次提交 | 避免影响无关且原本正常的创建路径。 |
| R04-02 | 是（跟踪项） | 本次不修改：flush 期乐观锁事务语义是报告 R05 的独立问题，按变更隔离要求紧接着处理。 | R05 待提交 | R04 文档已明确标注非目标，避免把两个事务缺陷混入同一提交。 |
| R04-03 | 是 | 随 R04-01 修复，代码现在与仅描述 `UPDATE_TASK` 的文档范围一致。 | 本次提交 | 无需扩大公共契约。 |
| R04-04 | 部分认可 | 不扩展本项：受控真实数据库中断注入会扩大测试基础设施范围；现有单元测试证明未知异常不被捕获，真实 PostgreSQL 用例证明目标缺失可提交 FAILED。 | 无 | 属建议级，后续若建设故障注入 seam 再补事务级恢复测试。 |
| R04-05 | 否（非缺陷） | 保留现有断言：异常传播、Action 仍为 APPROVED、无 FAILED 审计已经约束本项分类边界；额外 mock 调用次数不提供新的事务事实。 | 无 | 全量回归与真实 PostgreSQL 用例均通过。 |

---

**结论**：本次 Milestone 目标实现正确、边界清晰、证据充分，未发现需阻塞交付的 Bug、权限、并发、幂等或契约冲突。建议在后续轮次或 R05 独立修复中处理上述建议项。
