# ADR-0011：Day 5 Tool 意图与确认写入边界

- 状态：Accepted
- 日期：2026-09-05

## 背景

Python Agent 的输出具有概率性且不能成为业务授权。create/update task 需要跨一次用户确认，同时要在多个 HTTP 请求之间保持待确认状态，并避免重复确认造成重复写入。

## 决策

1. Python 仅返回白名单结构 `ToolProposal`，不持有业务写权限，也不调用 Task 写接口。
2. Java 在已完成 JWT 与 Project 授权后校验 proposal，并把规范化结果保存到 PostgreSQL `agent_task_action`。
3. action 绑定 `project_id`、`requested_by_user_id` 和 `conversation_id`；普通用户只能决策自己发起且仍属于可访问项目的 action，ADMIN 仍需通过项目访问规则。
4. 状态只有 `PENDING`、`EXECUTED`、`REJECTED`。confirm/reject 在 Java 事务内锁定 action；已执行 action 返回既有结果，绝不再次调用 Task 写入。
5. `UPDATE_TASK` 保存目标 Task ID 与 `expected_task_version`。确认时重新读取 Task、合并提案字段并通过 `TaskService.update` 校验版本；冲突返回 409，action 保持 `PENDING`。
6. Day 5 使用数据库持久化的确认票据，不引入 Redis/LangGraph checkpointer。V2 再增加通用 Approval Request、risk level、expiry、idempotency key 与完整 Audit Log。

## 理由

- 权限、并发和业务不变量继续集中在 Java，避免 Python 复制安全规则。
- PostgreSQL 能跨请求保存确认状态，并用事务锁解决同一 action 的并发确认。
- 补丁式 update proposal 便于 Agent 只表达要改变的字段；Java 以确认时的真实 Task 为基线，并用显式 version 防止静默覆盖。

## 后果

- Core API 新增 action 模块、V4 迁移和 confirm/reject API。
- Chat response 增加可空 `pendingAction`；现有只读调用仍兼容。
- action 表属于业务事实，只能由 Java 访问；Python 仍只可写可重建的 `rag_chunk`。
- Day 5 的确定性 parser 是无外部 LLM 密钥的演示实现；替换为真实模型时必须保持同一 schema 与 Java 校验边界。

## 被拒绝方案

- Python 直接调用 Task API：把概率性输出误当授权，并扩大内部凭据权限。
- 只把 interrupt 存在进程内：重启或多实例会丢失确认票据。
- 本阶段引入 Redis checkpoint/通用审批平台：超出 V1 Day 5 的最小闭环并提前引入 V2 复杂度。
