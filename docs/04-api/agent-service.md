# Agent Service API

- 状态：Implemented
- 阶段：V1 Day 3

## Core API 公共入口

`POST /api/v1/projects/{projectId}/agent/chat`，Bearer JWT 必填。请求：`{"message":"Summarize this project","conversationId":null}`。成功 200：`{"conversationId":"uuid","answer":"...","requestId":"uuid","sources":[{"sourceType":"WIKI","sourceId":"uuid","title":"Architecture","excerpt":"..."}]}`。`sources` 最多 6 项并按融合排名去重。400 输入无效，401 未认证，403 无项目权限，404 项目不存在，503 Agent Service 或 RAG 依赖不可用。

## Python 内部入口

`POST /internal/v1/chat`，必须携带 `X-AgentForge-Internal-Token` 与 `X-Request-Id`。Body：`{"projectId":"uuid","userId":"uuid","actorAdmin":false,"message":"...","conversationId":null,"requestId":"uuid"}`。返回字段与公共响应一致。内部 token 错误返回 401。`GET /health` 返回服务状态，不包含密钥或环境值。

## Core API 内部来源入口

`POST /internal/v1/rag/sources` 必须携带 `X-AgentForge-Core-Internal-Token` 与 `X-Request-Id`，且不接受 Bearer JWT 代替。Body：

```json
{
  "projectId": "uuid",
  "userId": "uuid",
  "actorAdmin": false,
  "requestId": "uuid"
}
```

成功 200：

```json
{
  "projectId": "uuid",
  "sources": [
    {
      "sourceType": "WIKI",
      "sourceId": "uuid",
      "version": 1,
      "title": "Architecture",
      "content": "# Architecture\n..."
    }
  ],
  "requestId": "uuid"
}
```

Wiki `content` 原样返回；Task `content` 是由 Java 从 title/status/priority/description 形成的稳定文本。缺失或错误内部 token 返回 401，用户不存在返回 401，无项目权限返回 403，项目不存在返回 404。授权必须先于 Wiki/Task Repository 读取。
