# ADR-0039：分离 checkpoint owner 与审批执行者身份

- 状态：Accepted
- 日期：2026-10-03

## 背景

Chat Action 的 Python checkpoint Namespace 在提案时绑定原请求人的 user ID；Java Approval 则允许管理员代表该用户批准或拒绝。若 Resume 使用当前管理员的 user ID，合法代审批会查询另一个 Namespace，并在 Java 已持久化决定后失败。

## 决策

- `AgentTaskAction.requestedByUserId` 同时是该 Chat Action 的 checkpoint owner，属于 Java 持久化事实，客户端不能提供或覆盖。
- Java 用当前认证 actor 完成审批授权、追加审计和批准后的业务执行；管理员身份不会替换 Action owner。
- Java 调用 Agent Service Resume 时，内部 body 的 `userId` 表示 checkpoint owner，而 `actorAdmin` 表示当前决策 actor 是否为管理员。Python 只用 owner 构造 Namespace，不据此授权审批。
- 任何带 checkpoint 的 Action（workflow version 非空，包含兼容 v1 与当前 v2）在 Resume 时都必须有持久化 owner；缺失时失败关闭。没有 checkpoint 的旧 Action 和 MCP Action 不调用 Resume。
- 公共 Action API 不增加 owner 或决策 actor 字段，避免外部请求控制内部恢复身份。

## 后果

- 管理员可在 Java 权限边界内代审批，并准确恢复原请求人的 checkpoint；审计 actor 仍是管理员。
- 内部 Resume JSON 字段不变，但 `userId` 的语义被明确为 checkpoint owner。未来若 Python 需要审批者身份，必须新增独立字段，不能复用 owner。
- 普通用户自审批时 owner 与 actor 相同，行为不变；MCP 与旧 Action 无新增依赖。
