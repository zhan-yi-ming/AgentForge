# Agent Chat

- 状态：Implemented
- 阶段：V1 Day 3

认证用户在项目内提交 message 与可选 conversationId。Core API 校验 owner/admin 权限并调用 Agent Service；LangGraph 把请求规范化后生成回答，Java 返回 answer、conversationId 和 requestId。

Graph 为 `START -> prepare -> respond -> END`。State 包含 project_id、user_id、message、conversation_id、request_id、normalized_message 与 answer。message 去空白后 1–8000 字符；conversationId 缺失时 Python 生成 UUID。

未认证 401；无项目权限 403；无效输入 400；Agent Service 不可用 503。不保存聊天记录，不检索 Wiki/Task，不产生 Tool 意图。
