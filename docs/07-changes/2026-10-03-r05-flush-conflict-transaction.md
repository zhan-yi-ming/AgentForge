# R05：真实乐观锁冲突的审批终态事务

- 状态：Verified（待提交）
- 日期：2026-10-03
- 基准：`codex/main-review-fixes` / `3776ecb1f3a84b541a827106e29f7055e0dec720`
- 风险：L3（Approval 状态机、数据库事务、Task/Graph sync 原子性）
- 影响域：Core API Action Workflow、Task 更新、审批审计、真实 PostgreSQL 并发测试、文档

## 背景与已确认问题

外部审查 R05 指出：`TaskService.update` 的版本预检查冲突与 Hibernate `saveAndFlush` 期间的真实乐观锁冲突具有不同事务状态。当前 `TaskService` 在共享执行事务内把 `OptimisticLockingFailureException` 转成 `ConflictException`，`AgentActionService.executeApproved` 又在同一事务中尝试保存 `FAILED` 与审计；底层持久化异常可能已将事务标成 rollback-only，因而最终抛出 `UnexpectedRollbackException`，无法保存终态。

静态调用链使该风险具有可疑性，审查原文明确没有真实复现。V2-07 已将 `APPROVED` 在独立短事务中先行提交；真实双事务测试在撤回补偿原型后稳定复现 `ObjectOptimisticLockingFailureException` 从 FAILED 保存阶段逃逸，确认 finding 成立。

## 目标

- 真实 PostgreSQL 双事务都通过版本预检查、后 flush 者发生 Hibernate 乐观锁冲突时，不出现未处理的持久化异常或 `UnexpectedRollbackException`。
- 冲突 Action 最终持久化为 `FAILED` 并追加 FAILED 审计；相同 key replay 稳定返回该终态。
- 验证竞争获胜的 Task 更新只提交一次，失败 Action 不提交 Task 变更、`EXECUTED` 审计或额外 Graph source sync 事实。
- 普通 Task HTTP 更新继续把 flush 乐观锁冲突映射为 409；数据库/网络等未知异常仍传播并保留 Action 为 `APPROVED`。

## 非目标

- 不给通用业务方法添加 `REQUIRES_NEW`，不拆散 Task、Graph sync 与 `EXECUTED` 的成功原子性。
- 不改变 Action 状态枚举、数据库 schema、Python/MCP/前端协议或 Task 公共 DTO。
- 不处理 R06 及后续审查项。

## 公共 seam 与设计

1. 集成测试以 repository spy 只放置 latch 协调执行时序，不由 Mockito 抛出异常：Action 事务读取 version 0 并停在真实 `saveAndFlush` 前，第二个真实事务读取同一 version、先 flush 并提交，随后放行 Action 事务。
2. `TaskService.update` 不在已损坏的事务内把 flush 期 `OptimisticLockingFailureException` 包装成普通业务冲突；异常直接越过 `AgentActionService.executeApproved`，触发执行事务完整回滚。全局 HTTP 异常处理器仍将其映射为 409。
3. 非事务的 `AgentActionWorkflowService` 在 `executeApproved` 事务已经退出后只捕获 UPDATE_TASK 的乐观锁异常，再调用 `AgentActionService` 的独立短事务收敛方法。
4. 收敛事务重新锁定并授权 Action、校验同一幂等键；仅当仍为 `APPROVED` 时写 `FAILED` 与审计。若并发重试已经写入 `EXECUTED/FAILED`，返回既有终态，不覆盖事实。

## 计划验证

- Workflow 单元红灯：`executeApproved` 抛 `OptimisticLockingFailureException` 时原实现直接逃逸；实现后必须调用独立失败收敛方法并返回 FAILED。
- 真实 PostgreSQL 红灯：通过 repository spy 只协调时序、不伪造异常；两个真实事务先读取 version 0，竞争更新先提交，Action 事务随后由 Hibernate `@Version` 检测冲突。撤回补偿原型后实际从 FAILED 保存阶段抛出 `ObjectOptimisticLockingFailureException`。
- 集成断言：无 `UnexpectedRollbackException`；Task 仅含竞争获胜值/version 1；Action 为 FAILED；审计恰有 REQUESTED、APPROVED、FAILED 且无 EXECUTED；相同 key replay 稳定；Graph source sync 不包含失败写的额外事实。
- 回归：普通 Task 乐观锁/409、R04 目标删除、版本预检查、成功执行、并发幂等；Java clean verify、diff/文档/敏感扫描、Pi Milestone Review。

## 验证回填

### 实现

- `TaskService.update` 不再把 flush 期 `OptimisticLockingFailureException` 包装为可在共享事务内继续处理的 `ConflictException`；原异常使执行事务完整回滚，普通 HTTP 仍由全局异常处理器映射为 409。
- 非事务 `AgentActionWorkflowService` 只对 `UPDATE_TASK` 的该异常做事务外协调，再调用 `AgentActionService.failApprovedAfterOptimisticConflict`。
- 新短事务重新执行 project/actor/Tool Policy/幂等键检查并锁定 Action；仍为 APPROVED 才提交 FAILED 与审计，若并发请求已写 EXECUTED/FAILED 则返回既有事实。
- 未修改数据库 schema、Task repository、Graph queue、Python/MCP/前端协议或状态枚举；成功执行仍由原事务原子提交 Task、Graph sync、EXECUTED 与审计。

### TDD 与排障证据

- Workflow 单元红灯：10 个测试中 1 个错误；`OptimisticLockingFailureException` 从 `confirm` 直接逃逸。加入事务外收敛调用后 10/10 通过。
- 真实 PostgreSQL 测试只用 `TaskItemRepository` spy 的 latch 协调时序，不伪造异常：Action 与竞争事务均读取 version 0，竞争事务先提交 version 1，再放行 Action 的真实 `saveAndFlush`。
- 为排除补偿代码掩盖问题，撤回补偿原型复跑同一测试；测试实际红灯，`ObjectOptimisticLockingFailureException` 从 `AgentTaskActionRepositoryAdapter.save` 的 FAILED 保存阶段逃逸，确认 R05 finding 成立。
- 恢复最终设计且让 Task flush 异常直接退出执行事务后，定向真实 PostgreSQL 测试 1/1 通过：Task 仅保留竞争方标题/version 1，Action 为 FAILED，相同 key replay 稳定，审计仅有 REQUESTED/APPROVED/FAILED，无 EXECUTED，Graph sync 仅保留该来源的一条事实。

### 回归结果

- `AgentActionWorkflowServiceTest, AgentActionServiceTest, TaskServiceTest, ResourceApiTest`：44 个测试、0 失败、0 错误、0 跳过。
- 完整 `PersistenceIntegrationTest`：11 个测试、0 失败、0 错误、0 跳过；repository spy 未干扰其它持久化用例。
- `services/core-api/.\\mvnw.cmd clean verify`：退出码 0；223 个测试、0 失败、0 错误、12 个按配置跳过；BUILD SUCCESS（Java 21.0.12.1、Maven Wrapper）。
- 全量日志仍有既有的测试上下文关闭后 Graph 调度线程访问已停止 Testcontainer 的连接告警；未形成测试失败，本项未修改调度生命周期。
- `git diff --check`：退出码 0。
- `plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Milestone`：退出码 0；识别 `CoreApi, Docs`、Review `Milestone`，要求 `diff-check/docs-consistency/gitleaks-final/java-clean-verify`。脚本给出 L2；因本项影响 Approval 状态机与事务一致性，人工按 L3 执行。
- 暂存区仅包含本记录列出的 12 个 R05 文档、实现与测试文件。
- `gitleaks v8.30.1` 对暂存 diff 扫描：退出码 0；约 32.84 KB，未发现泄漏。
- Pi Milestone Review Attempt 1：`PASS`，无阻塞项。S1 已由 `ApiExceptionHandler.handleOptimisticLockingFailure` 的通用 409 映射和本轮通过的 9 个 `ResourceApiTest` 覆盖；S2 是未来调用方误加外层事务的防御性建议，当前 Workflow 明确非事务且真实双事务已证明默认 REQUIRED 会开启独立短事务，不增加额外 `REQUIRES_NEW`；S3 为非阻塞测试增强，当前已有单元、replay、未知异常与真实数据库竞态证据。纯建议不触发第二轮 Pi。
- 待完成：最终差异复核、提交与远端核验。
