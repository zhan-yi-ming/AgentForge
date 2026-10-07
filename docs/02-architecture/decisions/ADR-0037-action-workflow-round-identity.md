# ADR-0037：审批 Action 绑定不可复用的 workflow 轮次

- 状态：Accepted
- 日期：2026-10-03
- 决策者：项目维护者

## 背景

ADR-0022 用完整 Memory Namespace 派生单个 LangGraph thread，并用 proposal 指纹识别等待中的重复请求。该设计能隔离 project/user/conversation，却不能区分同一会话中内容相同的不同操作轮次，也不能证明 Java Approval 对应当前等待点。旧 Action 决策在新一轮相同 proposal 等待时重放，可能错误恢复新 checkpoint；同一等待点的 Chat 响应重试也可能创建多个 Java Action。

## 决策

Action workflow state schema v2 为每次新等待轮次生成不可复用的 UUID workflow_id。Python 只通过内部 Chat proposal 把该 ID 交给 Java；Java 将其持久化到 Chat Action，并使用数据库唯一约束和同 key 串行化保证同一 workflow 只创建一个 Approval。内部 Resume 必须同时携带完整 Memory Namespace、workflow ID、Action ID、decision 与 Idempotency Key；任一关联不匹配均失败关闭，不消费当前等待点。

等待中的同 request/proposal 重放返回原 workflow ID；不同 request 即使 proposal 内容相同也不能复用当前等待点。恢复完成后，相同内容的新请求建立新的 workflow ID。新 Chat Action 使用 action_workflow_version = 2。既有 v1 Action/checkpoint 没有 workflow ID，继续按原恢复契约完成；升级前 action_workflow_version = null 的 Action 继续走纯 Java legacy 路径；MCP Action 不绑定 Chat checkpoint，workflow ID 必须为空。

workflow ID 是内部关联标识，不进入公共 pendingAction DTO，也不授予权限。Java 仍独占 Approval、RBAC、Risk、Audit 和 Task 写入；Python 仍只保存受限 Agent 运行态。

## 备选方案

- 只比较 proposal 指纹：无法区分相同内容的不同轮次，保留已复现缺陷。
- 只使用 HTTP request ID：request ID 是追踪字段，决策请求会生成新值，且其生命周期与 workflow/Approval 不一致。
- 以 Java Action ID 作为 checkpoint 主键：Python interrupt 发生在 Java 持久化之前，需要跨服务反向写 checkpoint 或新的两阶段协议，扩大 R01 范围。
- 每轮创建新的 LangGraph thread：会丢失现有 conversation Namespace 的恢复入口，并增加 checkpoint 定位与清理复杂度。

## 结果与取舍

新 workflow 能稳定形成 workflow ID ↔ Java Action ↔ checkpoint round 一对一关系；旧决定重放不能消费新等待点，同一 Chat 响应重试也不会创建重复 Approval。代价是内部 Chat/Resume 契约、Action schema 与状态 schema 同步升级，并需要保留 v1 兼容分支直到旧等待审批自然收敛。

## 取代关系

细化并扩展 ADR-0022；不改变 ADR-0021 的 Java 业务权威、决策幂等与审计边界。

## ADR-0043 补充：旧轮已完成事实

workflow ID 不匹配当前轮时，不允许恢复当前 interrupt；但允许严格匹配同 Namespace 历史中该 workflow 的 RESUMED/action/decision/key 后只读重放已提交结果，使尚未完成 Java 业务写入的 APPROVED Action 仍可重试。该路径不调用历史图、不改变当前轮。v1 无 workflow ID 的兼容恢复保持原边界。
