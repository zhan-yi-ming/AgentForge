# R01：绑定审批 Action 与 checkpoint 操作轮次

- 状态：Verified（待提交）
- 日期：2026-10-03
- 基准：`origin/main` / `ff1a0a52317c69dba3d51e61e12e56dbf46f2bca`
- 风险：L3（审批状态机、跨服务内部契约、数据库约束）
- 影响域：Agent Service、Core API、PostgreSQL、跨进程恢复契约、文档

## 背景与已确认问题

外部深层审查 R01 指出：LangGraph Action checkpoint 只按完整 Memory Namespace 定位当前状态，并以 proposal 指纹判断等待中的重复请求；它没有绑定具体 Java Action 或操作轮次。当前机器上的最小复现已证明，第一轮拒绝完成后进入第二轮相同 proposal，旧拒绝重放会被第二轮 checkpoint 接受并将其错误标记为 `RESUMED`。

## 目标

- 为每一轮 Chat Action workflow 建立稳定、不可复用的 workflow ID。
- Java PENDING Action 持久化该 ID，并以它幂等复用同一轮 proposal。
- Resume 同时校验完整 Namespace、workflow ID、Action ID、decision 与 Idempotency Key。
- 保留既有 workflow v1、`action_workflow_version = null` legacy Action 和无 Chat checkpoint 的 MCP Action 兼容路径。

## 范围

- Agent Service Action Runtime state、内部 Chat proposal 与 Resume schema。
- Core API 内部 Agent DTO、Action 创建去重、Action 持久化与 Resume 编排。
- 新增 Flyway 迁移，不修改已发布迁移。
- Python/Java 单元、HTTP 契约、PostgreSQL 并发与跨进程恢复回归。
- Agent Runtime、API、数据架构和 ADR 目标状态文档。

## 非目标

- 不改变 Java 对权限、审批、审计和业务写入的最终权威。
- 不改变公共 Web `pendingAction` 响应字段。
- 不处理 R02 的 checkpoint 创建补偿、R03 管理员恢复身份、R06 刷新恢复 UI 或其他审查项。
- 不清理或重写既有 LangGraph checkpoint 历史。

## 公共测试 seam

- Python `ActionWorkflowRuntime.interrupt/resume` 与 `/internal/v1/chat`、`/internal/v1/chat/stream`、`/internal/v1/agent/resume`。
- Java `AgentActionService`、`AgentActionWorkflowService`、内部 HTTP client 契约。
- 真实 PostgreSQL 上的 Flyway/JPA 与同 workflow 并发创建。
- 真实 Java → Python Resume 跨进程 smoke。

## 计划设计

1. Action workflow state schema v2 生成并持久化 `workflow_id`；同一 request/proposal 重放返回同一 ID，不同 request 不得复用等待轮次。
2. Python 只在内部 `toolProposal` 返回 `actionWorkflowId`，Resume 请求必须带回该 ID；v1 checkpoint 继续接受没有该字段的 legacy 恢复。
3. Java 为新 Chat Action 持久化唯一 workflow ID，并在同 project/user/conversation/workflow 作用域串行化创建；同 ID 同 intent 返回同一 Action，同 ID 不同 intent 冲突。
4. 新 Chat Action 使用 `action_workflow_version = 2`；既有 v1/null 与 MCP 路径不改变。

## 当前证据

### Git preflight

- 原工作区分支：`codex/v3-full-impact-audit`，HEAD `e385ff78d361085b7c101565ca0deca4cf71c081`。
- 原工作区已有内容：修改 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md`；未跟踪 `.worktrees/` 与 `AgentForge_产品规划与三阶段迭代路线.docx`。本变更在独立 worktree/分支 `codex/main-review-fixes` 中进行，不触碰这些内容。
- 目标基准：`origin/main` `ff1a0a52317c69dba3d51e61e12e56dbf46f2bca`。

### 门禁规划

命令（仓库根目录）：

```powershell
.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths <R01 预计路径>
```

结果：退出码 0；`Risk: L3`；`Areas: AgentService, CoreApi, Docs`；最低门禁为 `python-test`、`java-clean-verify`、`cross-process-smoke`、文档/diff/敏感扫描与 Pi Milestone Review；fingerprint `0195fd80d92ca002fbad5696c62cb98179266541fc181bfbdcb7952280a4567d`。

### 红灯前最小复现

使用当前项目既有虚拟环境、把 `PYTHONPATH` 指向本 worktree 源码，依次执行“第一轮 reject → 第二轮相同 proposal interrupt → 重放第一轮 reject”。命令退出码 1，精确失败为：

```text
Failed: DID NOT RAISE <class 'agentforge_agent.action_runtime.ActionWorkflowConflict'>
```

这证明旧决定可错误消费新一轮 checkpoint。首次用系统 Python 执行因缺少 pytest 失败，未触达缺陷，不计为复现证据。

## 验证回填

### 实现结果

- Agent Service Action state 升级为 schema v2，每轮生成 UUID `workflow_id`；同 request/proposal 重放复用同一 ID，不同 request 或 proposal 不能占用当前等待轮次。
- In-memory runtime 以进程内锁串行化 checkpoint 读写；PostgreSQL runtime 以 namespace thread key 的事务级 advisory lock 串行化同一 workflow，模型与检索不在锁内执行。
- 内部 Chat/Resume 契约传递 `actionWorkflowId`；Java Action 持久化该 ID、以唯一索引与事务锁保证同轮只产生一个 PENDING Action，并在执行前校验 Resume 返回的 conversation/workflow/action/decision/status。
- 新 Chat Action 标记 workflow version 2；v1 checkpoint、version-null legacy Action 与 MCP 路径保留兼容分支。公共 `pendingAction` 响应未新增内部字段。

### 红绿与回归证据

- Python 最小复现（旧 decision 消费新一轮同内容 proposal）在实现前退出 1，`DID NOT RAISE ActionWorkflowConflict`；新增回归后通过。
- Python HTTP 契约新增 `actionWorkflowId` 往返断言，实现前因缺字段 `KeyError` 红灯，实现后通过。
- 真实 PostgreSQL 同 namespace 并发 interrupt 测试实现前得到 2 个 workflow ID，加入 advisory lock 后得到唯一 ID并通过。
- Java PostgreSQL 并发创建同 workflow 测试实现前因 DTO 缺少 workflow ID 无法编译，完成最小实现后通过，并断言同一 Action、单行持久化、单条 REQUESTED audit 与 workflow version 2。
- `services/agent-service`：项目 Python `3.14.3`，`PYTHONPATH=src`、工作树内 `--basetemp`、localhost 绕过主机代理后执行 `pytest -q`，退出 0，`203 passed`、4 个既有依赖弃用/类型 warning。第一次全量因系统 pytest temp ACL 出现 13 errors，并因主机代理拦截 localhost 出现 1 failure；隔离复现后确认均为环境条件，调整运行参数后全量通过。
- `services/core-api`：Java `21.0.12.1`，`mvnw.cmd clean verify` 退出 0，重新编译当前源码，`203 tests`、0 failures/errors、11 skipped；skip 为环境变量控制的可选真实契约。测试日志保留 Mockito 动态 agent 未来兼容 warning、测试故意触发的持久化/图同步 warning。
- 跨进程 smoke：`scripts/validation/v2-07-resume-e2e.ps1` 使用当前 jar、当前 worktree Python 源码与一次性 PostgreSQL，第一次因 Windows 保留端口 55437 在容器启动前退出 1并完成清理；改用已核验端口 56437/18087/18007 后退出 0，`PENDING -> EXECUTED`、同 key replay 仍 `EXECUTED`、matching task 1、checkpoint 3。脚本停止 Java/Python 并删除容器、卷、网络和日志；临时 `.venv` junction 已删除。
- 最终 WORKTREE planner：退出 0，L3 / AgentService、CoreApi、Docs / Milestone Review；门禁为 Python、Java clean verify、database integration、cross-process smoke、docs/diff/Gitleaks；fingerprint `f843dc1060ce735e3223180f2cb1afa421f002ba03ae03671d0d5345b9f60a48`。
- `git diff --check` 退出 0；pytest 工作树临时目录已在验证绝对路径后删除。原工作区用户修改和未跟踪文件始终未进入本 worktree。
- Pi DeepSeek V4.1 Flash Milestone Review Attempt 1 退出 0、结论 PASS、无必须修改项。采纳 S1/S4/S5：补真实流式 workflow ID 断言、删除随机 ID 工厂重载、修正 legacy Mockito 断言；S2 是无 PoolTimeout 复现的容量建议，记录剩余风险但不改连接池拓扑；S3 与 R02 完全重合，留到下一独立变更。
- 采纳建议后的真实 Java→Python 契约：一次性 PostgreSQL + 当前 Python 服务，`AgentActionServiceTest`、`AgentActionWorkflowServiceTest`、`AgentServiceHttpContractIntegrationTest` 共 28 tests、0 failures/errors/skipped，覆盖同步、NDJSON 流式 workflow ID 与 Resume；最终 `mvnw.cmd clean verify` 再次退出 0，`203 tests`、0 failures/errors、11 skipped。
- 补充契约环境曾三次在测试前失败并均完整清理：第一次缺必填 RAG DSN，第二次 checkpoint DB 不可达，第三次未先建立由 Core Flyway V8 负责的 `agent_checkpoint` schema；按生产拓扑补齐一次性 PostgreSQL/schema 后测试通过。没有把这些环境失败计作代码通过证据。

### 剩余边界

- R01 只绑定审批与精确 workflow round；Python checkpoint 已创建但 Java 拒绝/写入失败时的补偿属于 R02，管理员身份恢复属于 R03，均不在本提交混入。
- 最终 INDEX planner/fingerprint、Gitleaks、暂存 diff 核对、提交与远端核验在本记录末次回填后完成。
