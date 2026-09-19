# 持久化历史会话

- 状态：Implemented
- 所属阶段：V2-05
- 相关 ADR：ADR-0020

## 用户价值与使用场景

用户可以在当前项目的历史入口看到自己完成过的会话，选择后进入对应聊天内容页，并继续使用同一个 conversationId。

## 范围与非目标

Core API/PostgreSQL 保存完成的 user/assistant exchange，提供项目内会话列表和详情。历史读取严格绑定认证用户、projectId 与 conversationId。V2-05 首版不提供删除；P3-02 增加单会话删除。搜索、标题编辑、跨用户共享仍不在范围内，展示历史也不等同于 V2-07 Agent checkpoint/recovery。

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

## P3-02 单会话删除

认证用户可从当前项目历史抽屉删除自己的一条会话，Core 再次校验项目和 user 作用域。存在 `PENDING` 或 `APPROVED` Action 时返回 409，要求先由用户完成决策；终态 Action 与审计事实保留。成功删除后消息正文、来源与预览从展示历史清除，列表和详情不可见。带旧 conversationId 的 Chat 在转发 Agent 前拒绝，append 和待审批 Action 创建也拒绝 tombstone。Web 在单次确认后调用 DELETE，并在删除当前会话时进入空白 `/chat`。已有 AI 回复在后续发送期间保持展开可见；未完成的新回答只影响自身轮次。

删除与另一设备已经在途的 Chat 交错时，该轮请求可能已经消耗配额，最终持久化会被 tombstone 拒绝；已完成与未决审批的一致性仍由 Core 锁和状态检查保护。跨设备流中止与配额补偿需单独设计，不在 P3-02 隐式更改计费语义。

## 已知限制与后续计划

历史用于展示和继续发送，不会在 Agent Service 重启后自动重建 Python Conversation Summary。该恢复能力属于 V2-07。

## V3 前置修复（P3-01，Implemented）

浏览器使用 `/chat` 建立空白会话视图；首条消息创建新的 conversationId，后续消息仅使用该 ID。`/chat/:conversationId` 只加载路径指定且属于当前 project/user 的详情；点击另一历史会话替换当前会话内容并取消旧流，浏览器前进/后退及刷新遵守相同作用域。详情加载失败不回退为其他会话，也不在路由中放 userId。删除和未决 Action 清理由 P3-02 处理。
