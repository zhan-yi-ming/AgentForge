# ADR-0025：会话展示历史删除保留不可复用 tombstone

- 状态：Accepted
- 日期：2026-09-18

## 背景

Core 保存完成问答供用户恢复。Action 审批、追加式审计和 Python checkpoint 以 conversationId 关联运行过程；直接删除会话主键会允许同一 ID 重新建档，也可能令未决审批与用户可见历史脱节。

## 决策

DELETE 由 Java Core 鉴权并在事务内锁定当前 project/user 会话。若存在 PENDING 或 APPROVED Action，返回 409。否则删除展示消息，清空预览和消息计数，并在会话主行写 `deleted_at`。创建待审批 Action 也锁定已存在的会话主行并拒绝 tombstone，与删除事务串行化。列表、详情与后续 append 均拒绝该 tombstone；终态 Action/审计以及独立 Python checkpoint 不由本操作删除。Web 删除前要求用户明确确认。

## 结果

用户不能再看到或继续使用已删除会话，消息正文与来源不再留在展示历史表。审计链和 ID 防重用边界仍成立。checkpoint 的保留期属于后续数据生命周期管理，不由 Web 删除操作隐式触发。

## 关系

补充 ADR-0020，保留 Core 拥有展示历史的职责；不改变 Java 审批权威与 Python 运行态边界。
