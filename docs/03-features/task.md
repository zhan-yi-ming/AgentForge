# Task

- 状态：Implemented
- 所属阶段：V1 / Day 2
- 相关 ADR：ADR-0003、ADR-0004、ADR-0005

## 用户价值与场景

项目 owner 在项目内维护可执行工作项，为后续 Agent 查询和经确认的 Tool Calling 写回提供确定性业务对象。

## 范围

- 在项目中创建、读取、列表、更新、删除 Task。
- 标题 1–200 字符，描述最多 10,000 字符。
- 状态固定为 `TODO`、`IN_PROGRESS`、`DONE`。
- 优先级固定为 `LOW`、`MEDIUM`、`HIGH`，创建默认 `MEDIUM`。
- 使用整数 `version` 做乐观锁；列表按更新时间降序。

## 关键流程

所有操作先校验 actor 对 Project 的 owner-or-admin 权限。具体 Task 用 `projectId + taskId` 查询。创建时默认状态 `TODO`；更新请求提交完整可变字段和当前 version，成功后递增；删除也要求 version。

## 接口与数据

接口见 `../04-api/core-api.md`，数据结构见 `../02-architecture/data-architecture.md`。路径统一为 `/api/v1/projects/{projectId}/tasks`。

## 权限、异常与排查

- create、list、get、update、delete 必须在任何仓库访问前校验项目访问权；测试覆盖正向调用和拒绝后不查询仓库。
- 更新依赖 JPA `@Version` 和 flush 时的原子乐观锁；PostgreSQL 集成测试证明两个旧快照中后提交者失败。
- 若并发冲突直到事务 flush/commit 才由 Spring 抛出 `OptimisticLockingFailureException`，统一异常处理器仍返回 HTTP 409，而不是泄漏为 500。

- 未认证 401；跨用户 403。
- 项目或 Task 不存在、路径不匹配 404。
- version 过期 409；未知状态 / 优先级或字段无效 400。
- 409 后读取最新 Task，再决定是否重新提交。

## 测试与验收

覆盖默认值、CRUD、状态 / 优先级转换、版本冲突、路径项目不匹配、跨用户拒绝与 ADMIN 成功。

## Day 5 Agent 写回约束

Agent 不能直接访问 Task Repository 或写 API。Python 只返回补丁式 proposal；用户确认后，Java action 服务读取当前 Task，并调用本服务的 `create` / `update`。因此标题、枚举、项目权限与 version 规则与人工 Task API 完全相同。

## 已知限制

没有负责人、截止日期、评论、标签、子任务、分页或自定义状态。Day 5 的 Agent 修改必须复用相同应用服务，不得直接写 Repository。
