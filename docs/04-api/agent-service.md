# Agent Service API

- 状态：Implemented
- 阶段：V1 Day 3

## Core API 公共入口

`POST /api/v1/projects/{projectId}/agent/chat`，Bearer JWT 必填。请求：`{"message":"Summarize this project","conversationId":null}`。成功 200 保留 `conversationId`、`answer`、`requestId`、`sources`，并新增可空 `pendingAction`。普通问答为 `null`；有效写意图由 Java 保存后返回 action ID、状态、类型和预览。`sources` 最多 6 项并按融合排名去重。400 输入无效，401 未认证，403 无项目权限，404 项目不存在，429 用户 UTC 日配额已用完，503 Agent Service 或 RAG 依赖不可用。

`POST /api/v1/projects/{projectId}/agent/chat/stream` 使用同一请求、Bearer、授权、配额和错误语义。成功响应为 `text/event-stream`，事件顺序为一个 `metadata`、零到多个 `delta`、最后一个 `complete`；响应开始后的失败以 `error` 终止。每个 SSE `data` 是单行 JSON：

```text
event: metadata
data: {"conversationId":"uuid","requestId":"uuid","sources":[]}

event: delta
data: {"text":"增量文本"}

event: complete
data: {"pendingAction":null}
```

`metadata` 到达不表示回答完成；客户端拼接全部 `delta`。`complete.pendingAction` 只能是 Java 已验证并持久化的待确认操作；没有操作时其值为 `null`，客户端也应兼容 JSON 序列化器省略该可空字段。所有事件禁止包含模型 key、内部 token 或上游原始错误正文。

## Python 内部入口

`POST /internal/v1/chat`，必须携带 `X-AgentForge-Internal-Token` 与 `X-Request-Id`。Body：`{"projectId":"uuid","userId":"uuid","actorAdmin":false,"message":"...","conversationId":null,"requestId":"uuid"}`。Python 响应在既有字段上新增可空 `toolProposal`：

```json
{
  "actionType": "UPDATE_TASK",
  "taskId": "uuid",
  "expectedVersion": 2,
  "title": null,
  "description": null,
  "status": "DONE",
  "priority": null
}
```

Python 不返回 actionId/status，也不执行写入。Java 不信任 proposal，必须重新校验组合、长度、枚举、actor、project 和 Task version。内部 token 错误返回 401。`GET /health` 返回服务状态，不包含密钥或环境值。

`POST /internal/v1/chat/stream` 使用相同 header 与 body，返回 `application/x-ndjson`。事件为 `metadata`、`delta`、`complete` 或 `error`，每行一个 JSON 对象。`complete` 可包含 Python `toolProposal`，但不包含 actionId/status；Java 消费并执行与 JSON 入口完全相同的白名单校验和 pending action 持久化。内部流不得直接暴露给浏览器。

当 `AGENTFORGE_AGENT_LLM_PROVIDER` 为 `deepseek`、`zhipu` 或 `qwen` 时，`answer` 来自对应 OpenAI-compatible Chat Completions 服务；`disabled` 时为确定性回退回答。`AGENTFORGE_AGENT_LLM_MAX_TOKENS` 统一限制三家模型的最大输出，默认 800、允许 64–4096。provider 缺少 key、模型服务不可达、认证/限流失败或响应不含有效文本时内部入口返回 503，Core API 继续向浏览器输出通用 503，不透传上游正文或凭据。

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
