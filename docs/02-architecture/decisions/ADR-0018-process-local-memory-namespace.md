# ADR-0018：进程内 Memory 使用完整 Namespace

- 状态：Accepted
- 日期：2026-09-08
- 决策范围：Python Agent Service 的会话记忆归属和隔离键

## Context

V2-03 的会话 store 以 conversationId 索引，再校验 projectId/userId 绑定。路线要求 V2-04 建立 `tenant → workspace → project → user → thread` Namespace，但当前业务数据模型只有 owner project，没有 tenant、workspace 或 membership 实体。立即新增这些业务表会越过 Memory 隔离目标，并提前占用后续权限模型设计；继续传递一组松散 ID 则无法让 Context、Memory 与 Retrieval 共享同一个隔离边界。

## Decision

- Agent Service 新增不可变 `MemoryNamespace`，包含服务端配置的 deployment tenant/workspace，以及已由 Java 授权后传入的 projectId、userId 和 threadId。
- tenant/workspace 不进入 HTTP body，不接受浏览器或模型控制。当前部署默认只有一个 deployment tenant/workspace；它们是命名域，不是业务 Workspace 或 Membership 声明。
- ConversationMemory 以完整 Namespace 为 key。同一 threadId 位于不同 project/user/tenant/workspace 时是不同 session，读取结果为空且互不覆盖。
- load 产生绑定 Namespace 与 generation 的 lease；commit 必须携带该 lease。session 已淘汰或 lease 不匹配时拒绝提交。
- ContextBundle 保存同一个 Namespace；Retrieval 只从中读取 projectId/userId。既有 `rag_chunk.project_id` 继续提供数据库隔离，因为 project UUID 是当前数据模型中的全局标识。
- HTTP schema、Java 授权与业务数据库保持不变。

## Alternatives

- 保留 conversationId 主键并追加更多相等性判断：改动较小，但隔离仍是分散约定，调用方可以漏传某一层。
- 新建 tenant/workspace/membership 表并跨服务传播：可表达完整业务多租户，但超出本节点，且会与 V2-05 权限模型耦合。
- 客户端回传完整 Namespace：实现简单，但会把安全边界交给不可信输入。

## Trade-offs

完整 Namespace 和强制 lease 增加少量内部类型与调用参数，却让读写归属可审查、可测试。deployment tenant/workspace 当前不能隔离同一部署中的多个真实 Workspace；文档必须保留这一限制。进程内 store 仍会在重启或切换实例时丢失。

## Consequences

- Project/User/Thread 的负向隔离可以从 store、Context 和 HTTP 公共 seam 证明。
- 后续引入真实 tenant/workspace 时可替换服务端 Namespace resolver，而不改变 ConversationMemory seam。
- V2-05 仍由 Java 实现确定性 RBAC/Risk；Namespace 不能被当作授权结果。
