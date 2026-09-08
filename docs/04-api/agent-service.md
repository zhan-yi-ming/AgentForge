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

长文本生成客户端（包括 Web 的“AI 整理并预览”）优先使用该 SSE 入口，使模型在完整回答完成前交付 delta，避免同步 Chat 必须等待全部生成结果。整理调用不携带项目 Chat conversationId；客户端只消费回答文本，并忽略整理语境下意外出现的 pending action。该客户端选择不改变配额、鉴权、项目隔离或同步 JSON 入口的兼容契约。

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

V2-01 不改变上述 HTTP schema。Python 在内部 token 校验成功后用 body 的 `requestId`、`projectId` 和实际 `conversationId` 建立 Langfuse 关联；当请求未传 conversationId 时，Python 先生成一次 UUID，并保证 Trace 的 `thread_id`、metadata 事件/JSON 响应的 `conversationId` 一致。Langfuse trace id 不进入公共或内部 API。观测失败不得改变状态码、NDJSON 事件或 Java 的确定性处理。

V2-02 同样不改变 HTTP schema。上述请求字段在 Python prepare 阶段构建内部 `ContextBundle`；同步与流式路径共用 Working、Conversation、Project、Retrieved、Tool Context 的生产过程。ContextBundle 不序列化到响应，既有 conversationId、requestId、sources、answer/toolProposal 语义保持不变。

V2-03 仍不改变 HTTP schema。Python 把相同 conversationId 的已完成 user/assistant exchange 放入进程内有界 store，并在下一次请求中组合 Recent Messages 与 Conversation Summary。conversationId 首次使用时绑定 projectId/userId；同一 ID 被其他作用域复用，或 load 后在生成期间被 LRU 淘汰/重绑时，同步入口返回 422。流式响应若已开始则输出通用 `error` 并终止，不发送 `complete`。回答失败或 generation 冲突不提交历史。进程重启、session 淘汰或切换实例会丢失该历史，这是当前节点的显式限制。

V2-04 继续保持 HTTP schema 不变。Python 把服务端配置的 deployment tenant/workspace 与请求中的 projectId、userId、实际 conversationId 组合成内部 Memory Namespace；tenant/workspace 不接受客户端字段。相同 conversationId 位于不同 project/user 时属于不同空 session，不能读取或覆盖其他 Namespace 的历史。每次 load 返回绑定 Namespace 与 generation 的内部 lease，完成提交必须携带该 lease；淘汰或重建后的陈旧提交同步返回 422，已开始的流以通用 `error` 结束。Namespace 只提供记忆归属，不能替代 Java 的项目授权。

V2-05 不改变 Python Chat HTTP schema，也不让 Python 接收或决定 Tool Policy。Python `toolProposal` 仍只包含动作名与业务参数；Java 忽略任何未声明的权限 Metadata，并按服务端注册表重新确定 role/risk/approval。持久化展示历史由 Core API 在完整成功边界写入 PostgreSQL，Agent Service 的进程内 Context Memory 不读取该表。

V2-06 仍不改变 Python Chat HTTP schema。Approval 五态、Idempotency Key、执行前权限复核与 Audit Event 全部位于 Java/Core API；Python 继续只生成不可信 `toolProposal`，不接收或回传可信 approval、actor、risk、idempotency 或 audit 字段。

V2-07 新增 `POST /internal/v1/agent/resume`，仅供 Core API 使用并继续要求 `X-AgentForge-Internal-Token`。请求包含 `projectId`、`userId`、`actorAdmin`、`conversationId`、`actionId`、`decision`（`APPROVE | REJECT`）、`idempotencyKey` 与 `requestId`。Agent Service 使用配置中的 tenant/workspace 与请求 project/user/thread 组成完整 Namespace，从 PostgreSQL checkpoint 恢复动态 interrupt。

成功返回同一 conversation/action/decision、`status=RESUMED` 和 requestId；相同 action/decision/key replay 返回相同事实。Thread 不存在、仍无 interrupt、Namespace 或 action 不匹配、不同 key/decision、state schema version 不支持均失败关闭，不创建替代 Thread。Resume 只证明 Agent workflow 已恢复，不执行或授权 Task 写入。

最终模型输入受 `AGENTFORGE_AGENT_CONTEXT_TOKEN_BUDGET` 限制；最近轮数、摘要预算和最大 session 数分别由 `AGENTFORGE_AGENT_CONTEXT_RECENT_TURNS`、`AGENTFORGE_AGENT_CONTEXT_SUMMARY_TOKEN_BUDGET`、`AGENTFORGE_AGENT_CONTEXT_MAX_SESSIONS` 控制。预算只影响 Python 内部 Prompt，不改变响应字段、Java 授权、配额或 Tool confirmation 契约。

当 `AGENTFORGE_AGENT_LLM_PROVIDER` 为 `deepseek`、`zhipu` 或 `qwen` 时，`answer` 来自对应 OpenAI-compatible Chat Completions 服务；`disabled` 时为确定性回退回答。`AGENTFORGE_AGENT_LLM_MAX_TOKENS` 统一限制三家模型的最大输出，默认 800、允许 64–4096。`AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS` 限制模型请求和 Core 来源回调的单次等待，应用代码默认 10 秒，Compose 为真实模型显式配置 60 秒；Core 下游读取预算必须更长。provider 缺少 key、模型服务不可达、认证/限流失败、超时或响应不含有效文本时内部入口返回 503，Core API 继续向浏览器输出通用 503，不透传上游正文或凭据。

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
