# Tool Calling 与人工确认

- 状态：Accepted
- 所属阶段：V1 / Day 5
- 相关 ADR：ADR-0009、ADR-0011、ADR-0039

## 用户价值

用户可以在项目 Chat 中让 Agent 整理并创建任务，或提出对现有任务的修改。Agent 先展示结构化预览；只有用户明确确认后，Java 才执行写入。

## 支持范围

- `CREATE_TASK`：title 必填；description、status、priority 可选，默认值沿用 Task API。
- `UPDATE_TASK`：taskId、expectedVersion 必填；title、description、status、priority 至少提供一项。
- V1 planner 支持明确的中英文创建表达，以及包含 Task UUID 与 version 的明确更新表达。无法唯一确定动作、目标或版本时返回普通回答而不生成 proposal。
- P3-04 在启用 LLM 时增加自然语言意图规划：模型只能输出 CREATE_TASK、UPDATE_TASK 或无动作。更新目标必须映射到当前项目检索到的已授权 Task 身份与版本，无法唯一确定时不生成 proposal；显式命令与无模型模式仍可走原确定性 planner。Java 对一切提案继续执行白名单、权限、版本、风险和人工确认，不接受模型的可信策略字段。

## 关键流程

1. Core API 完成 JWT 与 Project 权限校验后调用 Python Chat。
2. Python 先执行 Day 4 RAG，再生成可选 `toolProposal`；此时没有业务写入。
3. Java 白名单校验并保存 action，Chat response 返回 `pendingAction.status=PENDING`，同时追加 REQUESTED 审计。
4. 用户调用 confirm 或 reject。Java 再校验 actor/project/action 归属。
5. confirm 携带 `Idempotency-Key`，锁定 action、重新执行 Tool Policy/RBAC 后进入 `APPROVED`，再复用 `TaskService` 创建或更新；update 同时校验 Task 当前 version。
6. 成功返回 `EXECUTED` 和 resultTask；业务冲突返回稳定 `FAILED`；reject 返回 `REJECTED` 且不写 Task。每次状态变化均追加结构化审计。
7. Web 可读取当前用户在项目内仍为 `PENDING` / `APPROVED` 的可恢复 Action；历史会话按 conversationId 恢复 Chat Action，项目级读取同时覆盖 MCP Action。恢复的 Action 一律回到人工处理，不重新启动自动确认计时；`APPROVED` 恢复只提供原决定键继续 confirm，不允许改用新键或重新 reject。处理完成后 Web 重新读取并推进下一条。

## 安全与并发

- 客户端和 Python 不能指定 action owner/project；Java 从认证上下文与路径注入。
- Python 提供的枚举、长度、组合和 UUID 由 Java 再校验，不可信字段返回普通 Chat 且不保存 action，或在公共 action API 返回 400/409。
- action 必须用 `projectId + actionId` 查询；普通用户还必须匹配 `requestedByUserId`，ADMIN 可代审批。代审批的授权、审计和批准后执行使用当前管理员 actor；Chat checkpoint 恢复仍使用不可由客户端覆盖的原 `requestedByUserId`。
- 同一 action 并发/重复确认最多执行一次；同一 key 在响应丢失后返回既有结果，不同 key replay 返回 409。
- 可恢复 Action 查询仍受 JWT、项目访问和 `requestedByUserId` 隔离；普通用户与管理员都只读取自己发起的恢复队列，避免查询接口泄露其他用户的提案或决定键。
- proposal 创建时已 stale 返回 409 且不保存；批准后发生的版本冲突或目标 Task 已删除属于确定性业务失败，记录为 `FAILED` 且不改变 Task。若冲突直到 Hibernate flush 才出现，业务执行事务先完整回滚，再由事务外 Workflow 以短事务提交 FAILED 与审计；数据库、网络等未知基础设施异常不转为 FAILED，保留 APPROVED 供相同 key 重试。

## 测试边界

- Agent Service HTTP Chat 是 proposal 行为 seam。
- Core API Chat、confirm、reject 是公共 HTTP seam。
- 真实 PostgreSQL 验证 Flyway、action 状态与重复确认原子性。
- 双服务 E2E 证明未确认不写、确认写入、拒绝不写和项目隔离；目标 Task 的 version conflict 由 Java 服务测试覆盖。

## 已知限制

没有删除 Tool、通用工具注册、过期时间、人工审批人分派、多级审批或审计查询 UI；没有真实 LLM provider。V2-07 正在增加跨重启 LangGraph resume，目标流程和失败语义见 `agent-runtime.md`。
