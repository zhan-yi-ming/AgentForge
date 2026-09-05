# Tool Calling 与人工确认

- 状态：Accepted
- 所属阶段：V1 / Day 5
- 相关 ADR：ADR-0009、ADR-0011

## 用户价值

用户可以在项目 Chat 中让 Agent 整理并创建任务，或提出对现有任务的修改。Agent 先展示结构化预览；只有用户明确确认后，Java 才执行写入。

## 支持范围

- `CREATE_TASK`：title 必填；description、status、priority 可选，默认值沿用 Task API。
- `UPDATE_TASK`：taskId、expectedVersion 必填；title、description、status、priority 至少提供一项。
- V1 planner 支持明确的中英文创建表达，以及包含 Task UUID 与 version 的明确更新表达。无法唯一确定动作、目标或版本时返回普通回答而不生成 proposal。

## 关键流程

1. Core API 完成 JWT 与 Project 权限校验后调用 Python Chat。
2. Python 先执行 Day 4 RAG，再生成可选 `toolProposal`；此时没有业务写入。
3. Java 白名单校验并保存 action，Chat response 返回 `pendingAction.status=PENDING`。
4. 用户调用 confirm 或 reject。Java 再校验 actor/project/action 归属。
5. confirm 锁定 action并复用 `TaskService` 创建或更新；update 同时校验 Task 当前 version。
6. 成功返回 `EXECUTED` 和 resultTask；reject 返回 `REJECTED` 且不写 Task。

## 安全与并发

- 客户端和 Python 不能指定 action owner/project；Java 从认证上下文与路径注入。
- Python 提供的枚举、长度、组合和 UUID 由 Java 再校验，不可信字段返回普通 Chat 且不保存 action，或在公共 action API 返回 400/409。
- action 必须用 `projectId + actionId` 查询；普通用户还必须匹配 `requestedByUserId`。
- 同一 action 并发/重复确认最多执行一次；执行结果持久化后重复 confirm 返回同一结果。
- stale update 返回 409 且不改变 Task；用户应读取最新 Task 后重新发起提案。

## 测试边界

- Agent Service HTTP Chat 是 proposal 行为 seam。
- Core API Chat、confirm、reject 是公共 HTTP seam。
- 真实 PostgreSQL 验证 Flyway、action 状态与重复确认原子性。
- 双服务 E2E 证明未确认不写、确认写入、拒绝不写和项目隔离；目标 Task 的 version conflict 由 Java 服务测试覆盖。

## 已知限制

没有删除 Tool、通用工具注册、过期时间、人工审批人分派、完整审计事件或跨重启 LangGraph resume；没有真实 LLM provider。Day 6 才提供 Web 确认界面。
