# ADR-0020：Core API 持久化会话展示历史

- 状态：Accepted
- 日期：2026-09-08
- 决策者：项目维护者

## 背景

V2-03/04 的 Python ConversationMemory 为有界进程内 Context 状态，浏览器也只保存当前页面消息。V2-05 需要可靠列表与详情，但不能提前把展示历史等同于 V2-07 LangGraph checkpoint。

## 备选方案

- 浏览器本地存储：无法跨设备，且客户端不能证明 Project/User 归属。
- Python 直接持久化完整历史：会让 Agent Service 写业务事实并扩大权限边界。
- Core API/PostgreSQL 保存展示历史：复用 JWT、ProjectAccess、Flyway 和确定性事务，但 Context 恢复仍需后续节点。

## 决策

Core API 在完整 Chat 成功边界保存 user/assistant exchange，并提供授权列表/详情。历史记录归属 project、requesting user 和 conversation；Web 不提交 owner。Python ConversationMemory 保持现状，V2-05 不从数据库重建 Summary/Context。

流式成功边界进一步明确为：Java 收到内部完整事件并完成 Action 处理后，先以 requestId 幂等提交展示历史，再向浏览器发送公共 `complete`。历史提交失败只能结束为公共 `error`；发送 `complete` 时连接断开不撤销已提交事实。数据库不在模型生成期间持有事务。

## 结果

用户能可靠重新打开历史，会话读取有统一 RBAC，且公共流式成功不再早于展示历史提交。代价是 Core 流式代理需要只为持久化收集本次完整回答，并在发送最后一个事件前承担一次短事务延迟；大文本仍受现有 Chat 长度边界保护。V2-07 再设计 checkpoint 和恢复。

## 取代关系

不取代 ADR-0017/0018；三者分别描述进程内 Context、Namespace 隔离和持久化展示历史。
