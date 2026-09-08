# ADR-0022：PostgreSQL 持久化 LangGraph 待决 Action

- 状态：Accepted
- 日期：2026-09-09
- 决策者：项目维护者

## 背景

V2-06 已将 Approval、幂等与审计事实放在 Java/PostgreSQL，但 Python 只返回一次性 Tool proposal，没有 LangGraph interrupt 或可跨进程恢复的 checkpoint。可以把完整 Chat/Context 状态全部迁入 LangGraph、只持久化待决 Action workflow，或由 Java 自建一套 Python 运行态表。

## 决策

使用官方 `langgraph-checkpoint-postgres` checkpointer，只持久化需要人工决定的 Action workflow。同步和流式 Chat 仍复用现有 Context/Responder；产生 Tool proposal 后启动小型 workflow，在节点内调用动态 `interrupt()`。LangGraph 的物理 `thread_id` 由 tenant/workspace/project/user/conversation 完整 Namespace 的规范化值确定性派生，对外仍使用 conversation UUID。Resume 使用 `Command(resume=...)`，并在应用层再次校验完整 Namespace、proposal 指纹、action、decision、Idempotency Key 与 state schema version。

LangGraph 自有表位于独立 `agent_checkpoint` schema，由 Agent Service 在启动时执行 checkpointer 的幂等 setup；Flyway 仅创建该 schema，不管理第三方内部表结构。Python 数据库角色只应写 `agent_checkpoint` 和既有可重建的 RAG 数据，不写 Java 业务表。

Java 继续持有业务状态机。批准拆分为“提交 APPROVED → Resume Python → Java 执行 APPROVED action”，跨服务调用不持有数据库行锁。V2-06 的 Idempotency Key 同时关联恢复与执行；checkpoint 只能证明 Agent 恢复，不能授权或替代业务审计。V8 同时增加可空 `action_workflow_version`：升级前记录为空并沿用原 Java 决策链路，新记录为 1 并强制 Resume，避免旧 PENDING Action 因不存在 checkpoint 而无法完成。

## 备选方案

- 持久化完整 Chat graph：恢复信息最完整，但会把 Prompt、检索正文、Context dataclass 与生成过程长期写入数据库，扩大敏感面和 schema 兼容成本。
- Java 自建 checkpoint 表：迁移统一，但会重复实现 LangGraph checkpoint/interrupt 语义，无法证明框架级恢复。
- 内存或 SQLite saver：实现简单，但不能满足容器重启和多实例共享恢复的验收。
- Python 在 Resume 后直接写 Task：链路短，但破坏 Java 权限、审批和确定性写入边界。

## 结果与取舍

待决 Action 能跨 Python 进程恢复，且 checkpoint 内容保持最小。代价是引入新的 Python 包、第三方管理表与跨服务三阶段编排；APPROVED 可能在 Python 暂时不可用时保持为可重试过程态。后续 state 变化必须显式递增 schema version并提供兼容或失败关闭策略。

## 取代关系

扩展 ADR-0009、ADR-0011、ADR-0017、ADR-0018 与 ADR-0021；不改变 Java 业务执行/Python Agent Intent 的职责边界。
