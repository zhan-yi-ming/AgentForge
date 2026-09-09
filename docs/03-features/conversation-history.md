# 持久化历史会话

- 状态：Implemented
- 所属阶段：V2-05
- 相关 ADR：ADR-0020

## 用户价值与使用场景

用户可以在当前项目的历史入口看到自己完成过的会话，选择后进入对应聊天内容页，并继续使用同一个 conversationId。

## 范围与非目标

Core API/PostgreSQL 保存完成的 user/assistant exchange，提供项目内会话列表和详情。历史读取严格绑定认证用户、projectId 与 conversationId。它不提供删除、搜索、标题编辑、跨用户共享，也不等同于 V2-07 Agent checkpoint/recovery。

## 关键流程

1. Core 在 Chat 开始前完成 JWT、ProjectAccess 和配额校验。
2. 同步 Chat 成功后一次事务保存 user/assistant exchange；SSE 只在收到完整 `complete` 后保存。
3. 列表从认证 actor 和路径 projectId 推导作用域，不接受 userId。
4. 详情以 projectId、actor userId、conversationId 联合查询；不匹配返回 404/403 且不泄露正文。
5. Web 选择记录后按消息角色顺序恢复可配对的问答并复用 conversationId；遗留的不完整 USER 记录不会让后续完整 AI 回答被整组跳过。

## 接口

- `GET /api/v1/projects/{projectId}/agent/conversations`
- `GET /api/v1/projects/{projectId}/agent/conversations/{conversationId}`

列表按最后完成时间倒序返回 conversationId、首条用户消息摘要、消息数和更新时间。详情按 exchange 顺序返回 user/assistant 文本和可公开来源。列表和详情都不返回内部 Prompt、Summary、Tool Context 或凭据。

## 数据

`agent_conversation` 以 conversation UUID 为业务标识，并保存 project、user、preview、message_count、created_at、updated_at；`agent_message` 保存 conversation、sequence、role、content、sources JSON 与 created_at。数据库唯一约束保护 conversation 作用域和 sequence，索引支持按 project/user/updated_at 列表。

## 权限与安全

普通用户只能读取自己在自己 Project 下的历史。ADMIN 的跨项目访问仍经过 ProjectAccess，但详情也不允许通过伪造 conversationId 跨作用域读取。消息正文按不可信 Markdown 处理。

## 测试与验收

通过公共 HTTP seam 验证列表、详情、选择恢复、跨 Project/User/Thread 负向矩阵、同步成功、流完成、流失败不提交和稳定排序；通过真实 PostgreSQL 验证迁移、约束和 JPA validate。

## 已知限制与后续计划

历史用于展示和继续发送，不会在 Agent Service 重启后自动重建 Python Conversation Summary。该恢复能力属于 V2-07。
