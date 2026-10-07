# ADR-0043：已恢复审批轮次的历史事实重放

- 状态：Accepted
- 日期：2026-10-06
- 关联：ADR-0022、ADR-0037

## 问题

Python RESUMED 和 Java EXECUTED 分属不同事务。前者已完成而后者尚未提交时，同会话下一轮会成为最新 checkpoint；仅查询最新状态使 Java 原 key 重试永远冲突。

## 决策

保留现有物理 thread 和 state schema v2。当前 workflow 匹配时沿用现有恢复/重放；携带非空 workflow ID 且与当前轮不同时，仅在该完整 Namespace 对应的 checkpoint 历史中倒序查找相同 workflow 的已终结 RESUMED 状态。必须再次校验受支持版本、完整 Namespace、workflow、action、decision 和 idempotency key，返回原恢复结果及原 requestId。

历史查询不调用 invoke/update_state，也不把历史 checkpoint config 用作新执行入口。历史 WAITING、ABORTED、未知 workflow、任一身份/决定/key 不匹配均冲突，不能影响最新轮。只有 Java 在重放成功后按现有事务、权限和幂等规则执行业务。

## 取舍与兼容

复用已经持久化的 LangGraph 历史，无需新表、外部契约字段或 checkpoint 迁移；升级前已写入的 v2 RESUMED 同样可读。v1 无唯一 workflow ID，继续仅允许当前轮兼容恢复，不猜测历史身份。检索为同 thread 历史倒序分页读取（每页最多 64 条），成本随历史增长；本次不引入新索引或清理策略。未来删除 checkpoint 前必须保护未终结 Approval 的恢复事实。

不选择忽略 workflow 校验（可能消费新轮）、跳过 Python 恢复直接执行业务（缺少恢复凭据），也不新增回调或分布式事务。

## 验证

真实 PostgreSQL 公共运行时测试覆盖新轮开始后旧 APPROVE/REJECT 重放、运行时重启、原 key/身份校验和新轮保持独立；内部 HTTP 与 Java→Python 契约验证调用方收到原恢复事实，业务权威继续留在 Java。
