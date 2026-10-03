# R03：管理员代审批恢复原请求人的 checkpoint

- 状态：Verified（待提交）
- 日期：2026-10-03
- 基准：`codex/main-review-fixes` / `5542224a3e37e9b9b9493ec41c7afc8398fde135`
- 风险：L3（权限、审批状态机、跨服务身份语义）
- 影响域：Core API、Agent Service 内部 Resume 契约、Action workflow、文档

## 背景与已确认问题

外部审查 R03 指出管理员代替原请求人批准或拒绝 Chat Action 时，Core 将当前管理员的 user ID 作为 Agent Service Resume 的 `userId`。当前代码确认该行为存在：Java 的审批授权允许 ADMIN 决策其他用户发起的 Action，但 Python checkpoint 的完整 Namespace 使用 Action 原请求人的 user ID。结果是 Java 已提交 `APPROVED` 或 `REJECTED`，随后 Resume 因 Namespace 不匹配失败。

## 目标

- 明确区分 Action 原请求人（checkpoint owner）与当前审批执行者（decision actor）。
- Chat Action Resume 始终使用已持久化的 `requestedByUserId` 定位 checkpoint；授权、审计和执行继续使用当前认证 actor。
- 管理员代批准和代拒绝均可恢复原请求人的精确 workflow，且公共 HTTP 请求不能指定或伪造 checkpoint owner。
- 普通用户审批、旧无 checkpoint Action、MCP Action 与公共响应 schema 保持不变。

## 非目标

- 不把 Python 变成审批授权或审计权威，不向 Python 发送审批者 user ID。
- 不新增审批人分派、多级审批或公共 owner 字段。
- 不改变 MCP Action；它没有 Chat checkpoint，继续只走 Java 决策链路。
- 不处理 R04 及后续审查项。

## 公共测试 seam 与设计

1. Java 从持久化 `AgentTaskAction.requestedByUserId` 形成仅应用层可见的 checkpoint owner；公共 `AgentActionResponse` 不映射该字段。
2. `AgentActionWorkflowService.confirm/reject` 用 checkpoint owner 调用 Agent Service Resume，同时用当前 actor 完成 `findForDecision`、审计和批准后执行。
3. Java client 将 Resume 参数命名为 `checkpointUserId`，但保持内部 JSON 字段 `userId`，避免无必要的跨服务 wire 变更；`actorAdmin` 仍描述当前决策 actor 的角色。
4. workflow v2 若缺失持久化 checkpoint owner 必须失败关闭；旧 workflow-null Action 和 MCP Action 不调用 Resume。

## 计划验证

- Java 先红后绿：管理员批准与拒绝必须向 Resume 发送原请求人 ID，同时批准执行和审计仍使用管理员 actor。
- 公共 HTTP 响应与请求不暴露或接受 checkpoint owner。
- 普通用户、旧 Action、MCP Action 回归。
- Java clean verify、真实 Java→Python Resume 契约、diff/文档/敏感扫描、Pi Milestone Review。

## 门禁规划

预计路径 planner 在 `-Milestone` 下退出 0：L2 / CoreApi、Docs / Milestone Review；最低门禁 `java-clean-verify`、docs/diff/Gitleaks。由于本项实际改变管理员权限、审批状态机与跨服务身份语义，Codex 按治理规则手动上调为 L3，并增加真实 Java→Python Resume 契约。

## 验证回填

### 实现

- `AgentActionView.from` 从持久化 `AgentTaskAction.requestedByUserId` 携带仅应用层使用的 checkpoint owner；原有构造器保留兼容，公共 `AgentActionResponse` 继续显式映射既有字段，不输出 owner。
- confirm、auto-confirm 和 reject 仅在存在 workflow checkpoint 时要求 owner 非空，并用该 owner 调用 Resume；缺失 owner 返回依赖不可用且不猜测当前 actor。
- Agent Service Java client 的参数名改为 `checkpointUserId`，内部 JSON 仍使用兼容字段 `userId`；`actorAdmin` 继续来自当前审批 actor。
- Action 授权、APPROVED/REJECTED/EXECUTED 审计和批准后 Task 执行仍接收当前认证 actor。旧无 workflow Action 与 MCP Action 不调用 Resume，路径未改变。

### 红灯与回归证据

- TDD 红灯：管理员批准/拒绝测试先因 `AgentActionView` 不存在 checkpoint owner 构造参数而在 test compile 真实失败；加入持久化 owner seam 和最小编排修改后转绿。
- 定向回归：`AgentActionWorkflowServiceTest` 9、`AgentActionServiceTest` 15、`AgentActionApiTest` 7，共 31 tests，0 failures/errors/skipped。覆盖管理员批准/拒绝使用原请求人 owner、Java 决策/执行仍使用管理员、APPROVED 审计 actor 为管理员、workflow owner 缺失失败关闭，以及公共响应不出现 `requestedByUserId`。
- Core clean verify：Java 21.0.12.1，退出 0，`215 tests`、0 failures/errors、12 skipped。skip 为环境开关控制的 9 项真实 Agent 契约及其它既有可选契约；日志保留 Mockito 动态 agent warning、测试故意触发的图同步 warning，以及旧 Spring 测试上下文停止后调度线程访问已关闭 Testcontainers 数据库的 warning，均未造成失败。
- 真实 Java→Python 契约：第一次调用仓库标准阶段因隔离 worktree 没有自己的 `.venv`，在启动任何服务前失败，不计为测试结果。随后复用项目既有虚拟环境，但显式以隔离 worktree `src` 作为 `PYTHONPATH`，启动一次性 PostgreSQL/schema 与当前源码 uvicorn；`AgentServiceHttpContractIntegrationTest` 为 `9 tests`、0 failures/errors/skipped。Resume 用原请求人 `userId` 与 `actorAdmin=true` 恢复真实 WAITING，证明 wire 语义兼容。uvicorn、Compose project、容器、网络、卷和临时日志已清理。
- 暂存 INDEX planner：退出 0，L3 / CoreApi、Docs / Milestone Review；门禁为 Java clean verify、docs/diff/Gitleaks；fingerprint `dd390019a783b56cfca78197ec5bd5f400dc47afa82f70b453a584faeb93827a`。planner 因跨服务 API 路径自动识别为 L3，与手动评估一致。
- `git diff --cached --check`、ADR 文件/索引一致性和 staged-only 范围检查退出 0。复用本机已有 `zricethezav/gitleaks:v8.30.1` 扫描暂存 diff 约 40.57 KB，退出 0、no leaks found。

### 待完成门禁

- 提交与远端核验。

### Pi Milestone Review Attempt 1 评估

Pi 使用 `deepseek/deepseek-flash` 对已扫描 INDEX 做一次只读审核，结论 `PASS`，报告为 `docs/08-reviews/2026-10-03-review-r03-admin-checkpoint-owner-attempt-1.md`，无必须修改项。Codex 逐条判断如下：

- `S-1` 不构成当前缺陷：`requested_by_user_id` 从 V4 起数据库 `NOT NULL`，JPA 字段 `nullable=false`，领域构造器 `requireNonNull`，且所有生产审批/拒绝返回均统一调用 `AgentActionView.from`。owner 缺失 guard 只防御测试桩或未来错误映射；为不可复现的假设路径重排审批事务会扩大状态机范围。
- `S-2` 为非阻断测试建议：approve、auto-confirm 和 reject 都通过同一个 `AgentActionView.from`，workflow 测试已分别覆盖自动批准与管理员拒绝 owner 的使用，未发现独立映射分支。
- `S-3` 为非阻断测试建议：公共 Action、Chat/SSE 均通过显式 `AgentActionResponse.from` 字段白名单，MCP 也手工构造结果，不直接序列化 `AgentActionView`；现有公共 confirm 测试已锁定 owner 不外泄。
- `S-4` 同意文档精度问题：ADR 已改为任何 workflow version 非空（兼容 v1 与当前 v2）的 Action 都必须使用持久化 owner。该纯文档修正不改变已测试生产输入，不触发第二轮审核。
- `N-1/N-2` 无需处理。

最终报告、ADR 校正和本段评估纳入暂存区后，仅重跑 diff/INDEX planner/scoped Gitleaks；生产源码和测试输入未变化，复用本次已通过的 Java clean verify 与真实跨进程契约证据。
