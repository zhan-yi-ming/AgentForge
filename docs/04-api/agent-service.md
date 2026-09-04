# Agent Service API

- 状态：Implemented
- 阶段：V1 Day 3

## Core API 公共入口

`POST /api/v1/projects/{projectId}/agent/chat`，Bearer JWT 必填。请求：`{"message":"Summarize this project","conversationId":null}`。成功 200：`{"conversationId":"uuid","answer":"...","requestId":"uuid"}`。400 输入无效，401 未认证，403 无项目权限，404 项目不存在，503 Agent Service 不可用。

## Python 内部入口

`POST /internal/v1/chat`，必须携带 `X-AgentForge-Internal-Token` 与 `X-Request-Id`。Body：`{"projectId":"uuid","userId":"uuid","message":"...","conversationId":null,"requestId":"uuid"}`。返回字段与公共响应一致。内部 token 错误返回 401。`GET /health` 返回服务状态，不包含密钥或环境值。
