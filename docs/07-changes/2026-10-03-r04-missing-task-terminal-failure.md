# R04：已审批更新的目标消失时进入终态失败

- 状态：Verified（待提交）
- 日期：2026-10-03
- 基准：`codex/main-review-fixes` / `2c990b7ae83ca740cbbf9e5f6e7217e3c88d4bec`
- 风险：L3（Approval 状态机、幂等执行、审计与会话删除阻塞）
- 影响域：Core API Action 执行、Task 更新、会话删除语义、文档

## 背景与已确认问题

外部审查 R04 指出 UPDATE_TASK Action 在审批前或 Python Resume 后、Java 执行前目标 Task 被删除时，`TaskService.get` 抛 `ResourceNotFoundException`，但 `AgentActionService.executeApproved` 只捕获 `ConflictException`。当前代码确认该路径存在：APPROVED 已在前一事务提交，异常使本次执行事务回滚但不改变已有决定；同 key 重试继续得到 404，reject 不再允许，而会话删除持续被 APPROVED Action 阻止。

## 目标

- 目标 Task 已不存在属于不可通过重试恢复的确定性业务失败，Action 必须持久化为 `FAILED` 并追加现有 `FAILED` 审计。
- 版本过期继续形成 `FAILED`；相同 key 重试稳定返回已有失败事实且不写 Task。
- 数据库、网络或其它未知基础设施异常不得被误分类为永久失败；事务回滚后 Action 保持 `APPROVED`，允许相同 key 重试。
- FAILED 不再阻止既有会话删除；公共状态枚举和前端响应结构不变。

## 非目标

- 不修复 R05 的 Hibernate flush 期乐观锁/rollback-only 风险；该项需要独立真实 PostgreSQL 复现与事务边界设计。
- 不新增 Action 状态或审计类型，不把所有运行时异常统一吞并为 FAILED。
- 不改变 Task 删除接口、Python workflow、MCP 协议或前端决定流程。
- 不处理 R06 及后续审查项。

## 公共测试 seam 与设计

1. 在 `executeApproved` 的确定性业务执行边界并列捕获 `ConflictException` 与 `ResourceNotFoundException`，复用现有 `markFailed`、持久化和 FAILED 审计逻辑。
2. `TaskService.get/update` 将 `ResourceNotFoundException` 声明为不回滚的业务异常，避免嵌套事务先把共享执行事务标成 rollback-only；异常仍向公共 Task API 传播为既有 404。
3. 授权、Action 查找、状态/幂等键检查仍在该边界之外；这些错误不改变 Action 状态。
4. 未列入的异常继续传播，由 `@Transactional` 回滚本次执行事务；先前独立提交的 APPROVED 事实保持可重试。
5. 会话删除已有查询只阻止 `PENDING/APPROVED`，因此 Action 进入 FAILED 后无需修改 Conversation 模块。

## 计划验证

- Java 先红后绿：目标在批准前已删除、批准后执行前删除，均形成 FAILED 与 FAILED 审计；相同 key replay 稳定。
- 数据访问暂时失败传播且 Action 保持 APPROVED，不产生 FAILED 审计。
- 既有版本过期、成功执行、MCP/旧 Action 与会话删除回归。
- Java clean verify、真实 PostgreSQL 状态持久化、diff/文档/敏感扫描、Pi Milestone Review。

## 验证回填

### 实现

- `AgentActionService.executeApproved` 把业务版本 `ConflictException` 归类为确定性失败，并且仅在 `UPDATE_TASK` 路径把 `ResourceNotFoundException` 归类为目标消失；复用现有 `FAILED` 状态和审计，其它异常及 `CREATE_TASK` 的资源缺失继续传播并回滚。
- `TaskService.get/update` 对 `ResourceNotFoundException` 使用 `noRollbackFor`，使外层 Action 事务能够捕获 404 并提交 `FAILED`，而公共 Task API 的 404 契约保持不变。
- 未修改状态枚举、审计枚举、Conversation 查询、Python/MCP 契约或前端。

### TDD 与排障证据

- 首次运行 `AgentActionServiceTest`：新增缺失目标用例实际红灯，17 个测试中 1 个错误；`ResourceNotFoundException` 从执行边界逃逸。
- 加入最小多异常捕获后再次运行 `AgentActionServiceTest`：17/17 通过；暂时性 `DataAccessResourceFailureException` 仍传播，Action 保持 `APPROVED` 且不写 FAILED 审计。
- 首次真实 PostgreSQL 集成用例因测试删除者不是管理员而失败；修正测试夹具后，第二次运行暴露 `UnexpectedRollbackException`，证明嵌套 `TaskService.get` 已把共享事务标为 rollback-only。
- 为缺失资源业务异常补充 `noRollbackFor` 后，定向 `PersistenceIntegrationTest#approvedUpdatePersistsFailedWhenItsTargetIsDeletedAndReleasesConversationDeletion` 通过：1 个测试、0 失败、0 错误、0 跳过。验证了先批准、再删除目标、随后执行会持久化 FAILED，replay 稳定，且会话可删除。
- Pi 建议指出最初的多异常捕获也覆盖 `CREATE_TASK`，与本项范围不完全一致。新增范围保护测试后实际红灯：18 个测试中 1 个失败；把资源缺失分类限制到 `UPDATE_TASK` 后，`AgentActionServiceTest` 18/18 通过。

### 回归结果

- 最初 `services/core-api/.\\mvnw.cmd clean verify`：退出码 0；218 个测试、0 失败、0 错误、12 个按配置跳过；BUILD SUCCESS（Java 21.0.12.1、Maven Wrapper）。
- 收紧 `CREATE_TASK` 边界后的最终 `services/core-api/.\\mvnw.cmd clean verify`：退出码 0；219 个测试、0 失败、0 错误、12 个按配置跳过；BUILD SUCCESS。
- 全量日志仍出现既有的 Spring 测试上下文关闭后 Graph 调度线程访问已停止 Testcontainer 的连接告警；未形成测试失败，本变更未触及 Graph/调度生命周期。
- `plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Milestone`：退出码 0；识别 `CoreApi, Docs`、Review `Milestone`、要求 `diff-check/docs-consistency/gitleaks-final/java-clean-verify`。脚本给出 L2；因本项直接影响 Approval 状态机，人工上调并按 L3 验证。
- `git diff --cached --check`：退出码 0；暂存区仅包含本记录列出的 8 个 R04 文档、实现与测试文件。
- `gitleaks v8.30.1` 对首次暂存 diff 扫描：退出码 0；约 19.37 KB，未发现泄漏；完成 Pi 处置和审核报告回填后的提交前快照再次扫描约 33.12 KB，仍未发现泄漏。
- Pi Milestone Review Attempt 1：`PASS`，无阻塞项。接受 R04-01/R04-03 并收紧资源缺失分类到 `UPDATE_TASK`；R04-02 属于紧接着独立处理的 R05；R04-04/R04-05 为非阻塞的测试增强建议，当前已有瞬态异常单元证据、真实 PostgreSQL 终态持久化证据和全量回归，不扩大本项范围。按制度，纯建议修正不启动第二轮 Pi。
- 待完成：最终差异复核、提交与远端核验。
