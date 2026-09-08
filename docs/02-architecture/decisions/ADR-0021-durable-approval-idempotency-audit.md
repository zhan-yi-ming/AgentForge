# ADR-0021：Java 持久化 Approval、幂等执行与追加审计

- 状态：Accepted
- 日期：2026-09-08
- 决策者：项目维护者

## 背景

Day 5 的 `agent_task_action` 已用 PostgreSQL 行锁避免同一进程中的重复确认，但三态模型不能表达批准与执行失败，HTTP 也没有可跨超时重试的显式幂等契约，且缺少持久化审计事实。

## 决策

继续由 Java/Core API 独占业务执行权，将现有 Task Action 演进为 Approval 的首个 payload：服务端持久化五态生命周期、决策幂等键和结果；confirm 在事务行锁内重新校验 ProjectAccess、actor 归属与 Tool Policy，再进入 `APPROVED` 并最多执行一次。所有状态变化同时追加结构化 Audit Event，数据库约束负责唯一性与引用完整性。

公共 confirm seam 使用 `Idempotency-Key` 请求头，requestId 仅用于追踪而不承担幂等语义。Python/LLM 不接收数据库写权限，也不能指定可信审批状态、actor、风险、幂等结果或审计字段。

## 结果与取舍

重试、响应超时和 replay 可返回已提交结果而不重复写入；拒绝后不可执行，执行失败有稳定终态和审计证据。代价是确认流程需要更严格的事务边界和迁移兼容。首版不抽象多种业务 payload 表，待真实第二类 Tool 出现后再提炼通用存储结构，避免为未来类型提前设计。

## 取代关系

扩展 ADR-0011 与 ADR-0019；不改变 Java 执行/Python Intent 的职责边界。
