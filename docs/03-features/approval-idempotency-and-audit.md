# Approval、Idempotency 与 Audit

- 状态：Implemented
- 所属阶段：V2-06
- 相关 ADR：ADR-0011、ADR-0019、ADR-0021

## 用户价值

用户确认 Agent 提出的业务动作时，可以安全地重试因超时或断网未得到响应的请求；系统不会重复创建或修改 Task，并能追溯谁在何时对哪个审批执行了什么决定与结果。

## 确定性流程

`Agent Intent → Java Tool Policy/RBAC → PENDING Approval → user decision → Java permission recheck → APPROVED → execute once → EXECUTED/FAILED → append Audit Event`。

状态只允许：`PENDING → APPROVED → EXECUTED | FAILED`，或 `PENDING → REJECTED`。`REJECTED`、`EXECUTED` 与 `FAILED` 均为终态；终态 replay 只能返回既有事实或明确冲突，不能再次执行业务写入。

V2-06 中 `APPROVED` 是单次 confirm 事务内的逻辑过程态：同一事务继续执行并最终提交 `EXECUTED`/`FAILED`，未知基础设施异常则整体回滚到此前的 `PENDING`。跨重启持久化 `APPROVED` 并恢复执行属于 V2-07，不在本节点提前实现。

确认开始前已经可见的 Task version 冲突属于确定性业务前置条件失败，提交为 `FAILED` 并追加审计；确认事务读取之后才发生的数据库 flush 期乐观锁竞态会安全回滚为 `PENDING` 并返回 409，允许客户端用同一 key 重试，不伪造未提交的 APPROVED/FAILED 事实。

## 幂等边界

确认请求携带客户端生成的不透明 Idempotency Key。Java 以认证 actor、project、approval 和 key 判定重放；key 不能改变 approval 归属或目标。相同 key 的已提交结果可安全重放，不同 key 不能重新执行同一终态 approval。数据库唯一约束与事务行锁是最终保障，Web 的按钮禁用不是安全边界。

`EXECUTED` replay 始终保留既有终态；若结果 Task 此后被独立删除，响应仍为 `EXECUTED`，但 `resultTask` 为空，避免把后续资源生命周期误判为审批从未成功。

## 审计边界

Audit Event 由 Java 追加写入，至少包含 actor、project、approval、action、target、requestId、result、timestamp。不得记录 JWT、内部 token、密码、完整 Prompt 或敏感日志。业务状态与对应审计事件必须在同一事务事实中提交，不能用仅有日志文本替代审计记录。

## 已知限制

首版复用 Task Action 作为 Approval payload，仅覆盖现有 `CREATE_TASK`、`UPDATE_TASK`。多级审批、审批委派、过期策略、通用查询 UI 与 V2-07 可恢复 Agent 执行不在本节点。

当前产品没有用户硬删除流程。审计表对 Project/Approval 删除沿用现有级联关系，对 actor 用户引用保持限制删除；未来若引入用户删除，必须先通过独立数据保留决策选择软删除、actor 快照或可空引用，不能在本节点推测处理。
