# ADR-0038：以精确 Abort 补偿未形成 Approval 的 checkpoint

- 状态：Accepted
- 日期：2026-10-03

## 背景

Action proposal 跨越 Python checkpoint 与 Java Approval 两个独立事务。Python 先建立 WAITING，Java 才能执行可信校验和持久化；校验拒绝、Task version 冲突或数据库异常会令 WAITING 没有对应 Action。仅统一字段校验不能覆盖 Java 独有的授权、版本和持久化失败。

## 决策

- 保持 Java 是权限、Approval、审计和业务写入的唯一权威，不引入分布式事务。
- workflow schema v2 增加终态 `ABORTED`。Core 仅在 proposal 未形成 PENDING Action 时，通过内部 Abort 请求补偿当前 WAITING。
- Abort 必须匹配完整 Memory Namespace 与 request ID；已取得 workflow ID 时还必须匹配该 ID。它不能处理 RESUMED 状态，也不能影响另一请求或另一轮。
- Core 在新会话调用 Python 前按 project/user/request ID 确定性派生 conversation ID，使响应丢失后的同 request 重试仍能定位相同 Namespace。同 workflow 的 Action 创建继续依赖 ADR-0037 的唯一约束和幂等查询。
- 补偿失败作为跨服务不一致显式失败；不得把它降级为普通无 Tool 回答。checkpoint 历史保留 ABORTED 事实，新请求可在同 Thread 建立新轮次。

## 后果

- Java 校验/落库失败不再永久占用会话；合法 proposal 和审批执行路径不变。
- 内部 API 新增 Abort 契约，Python 状态机新增 ABORTED；公共 Chat/Action schema 不增加内部字段。
- 该方案提供可重试 Saga，而非跨数据库原子提交。极端进程崩溃仍依赖相同 request ID 重试触发幂等恢复；普通回答历史的 exactly-once 不属于本 ADR。
