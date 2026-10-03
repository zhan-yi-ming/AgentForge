# Core API 契约

- 状态：Implemented（Day 1–2）
- 基础路径：`/api/v1`
- 内容类型：`application/json`

## 通用约定

- UUID 使用标准字符串，时间使用 ISO-8601 UTC。
- 除注册、登录和 `GET /actuator/health` 外，接口必须发送 `Authorization: Bearer <accessToken>`。
- 成功响应使用 DTO，不暴露 JPA Entity、`passwordHash` 或安全配置。
- 错误使用 `application/problem+json`，至少包含 `type`、`title`、`status`、`detail`、`instance`、`requestId`；字段校验错误额外包含 `errors`。
- 每个响应返回 `X-Request-Id`。请求可提供该头，缺失或格式不可接受时由服务生成。
- 集合按 `updatedAt` 降序（Project 保留按 `createdAt` 降序）；Day 2 不分页。

## 身份与错误语义

| 情况 | 状态 | 说明 |
| --- | --- | --- |
| JSON / 字段格式无效 | 400 | Bean Validation 或枚举解析失败 |
| 未提供、过期、签名或 issuer 无效的 token | 401 | 不返回 token 验证内部细节 |
| 登录邮箱或密码不匹配 | 401 | 统一文案，避免枚举账号 |
| 已认证但不是 owner / ADMIN | 403 | 不返回资源内容 |
| 生产环境关闭公共注册 | 403 | 登录保持可用，Demo 账号由受控脚本创建 |
| 项目或项目内资源不存在 | 404 | 嵌套路由必须同时匹配 projectId 与资源 ID |
| 唯一约束或乐观锁冲突 | 409 | 服务层冲突及事务提交阶段的 `OptimisticLockingFailureException` 均统一映射；重新读取资源再决定是否重试 |
| AI UTC 日配额已用完 | 429 | 超额请求不会调用 Agent Service |

## Auth

### `POST /api/v1/auth/register`

无需认证。仅在 `AGENTFORGE_REGISTRATION_ENABLED=true` 时可用；生产默认关闭并返回 403。请求：

```json
{
  "email": "owner@example.com",
  "displayName": "Project Owner",
  "password": "correct-horse-battery"
}
```

约束：email 合法且不超过 320；displayName 去除首尾空白后 1–100；password 8–72 字符。成功返回 201、`Location: /api/v1/users/me` 和 AuthResponse。重复邮箱返回 409。

### `POST /api/v1/auth/login`

无需认证。请求：

```json
{
  "email": "owner@example.com",
  "password": "correct-horse-battery"
}
```

成功返回 200 AuthResponse；邮箱不存在、passwordless 遗留账号或密码不匹配统一返回 401。

### AuthResponse

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": {
    "id": "uuid",
    "email": "owner@example.com",
    "displayName": "Project Owner",
    "role": "USER",
    "createdAt": "2026-09-03T12:00:00Z",
    "updatedAt": "2026-09-03T12:00:00Z"
  }
}
```

## User

### `GET /api/v1/users/me`

返回当前 token `sub` 对应的 UserResponse。用户已删除或不存在时返回 401。Day 1 的 `POST /users` 与 `GET /users/{id}` 被注册和当前用户接口取代。

## Project

### `POST /api/v1/projects`

请求：

```json
{
  "name": "AgentForge",
  "description": "AI-assisted engineering workspace"
}
```

owner 固定为当前 token 用户，客户端不能提供 ownerId。name 1–120；description 可空，最大 2000。成功返回 201 和 `Location`；同一 owner 下项目名重复返回 409。

### ProjectResponse

```json
{
  "id": "uuid",
  "ownerId": "uuid",
  "name": "AgentForge",
  "description": "AI-assisted engineering workspace",
  "createdAt": "2026-09-03T12:00:00Z",
  "updatedAt": "2026-09-03T12:00:00Z"
}
```

### `GET /api/v1/projects/{projectId}`

owner 或 ADMIN 返回 200；不存在 404；其他用户 403。

### `GET /api/v1/projects`

USER 返回自己拥有的项目数组；ADMIN 仍只返回自己拥有的项目，避免无边界全表读取。原 `ownerId` 查询参数不再接受。

## Wiki Page

基础路径：`/api/v1/projects/{projectId}/wiki-pages`。

### `POST /api/v1/projects/{projectId}/wiki-pages`

```json
{
  "title": "Architecture",
  "content": "# System\n\nCore API owns writes."
}
```

title 去除首尾空白后 1–200；content 0–100,000。同项目标题重复返回 409。成功返回 201 和资源 `Location`。

### `GET /api/v1/projects/{projectId}/wiki-pages`

返回项目内页面数组，按 `updatedAt` 降序。

### `GET /api/v1/projects/{projectId}/wiki-pages/{wikiPageId}`

路径项目与页面所属项目必须同时匹配，否则 404。

### `PUT /api/v1/projects/{projectId}/wiki-pages/{wikiPageId}`

```json
{
  "title": "Architecture",
  "content": "# Updated system",
  "version": 0
}
```

请求提交完整可变字段和当前 version。成功返回更新后的 WikiPageResponse；版本或标题冲突返回 409。

### `DELETE /api/v1/projects/{projectId}/wiki-pages/{wikiPageId}?version=0`

成功返回 204；version 必填且必须与当前版本一致，冲突返回 409。

### WikiPageResponse

```json
{
  "id": "uuid",
  "projectId": "uuid",
  "title": "Architecture",
  "content": "# Updated system",
  "version": 1,
  "createdAt": "2026-09-03T12:00:00Z",
  "updatedAt": "2026-09-03T12:10:00Z"
}
```

## Task

基础路径：`/api/v1/projects/{projectId}/tasks`。

### `POST /api/v1/projects/{projectId}/tasks`

```json
{
  "title": "Add login",
  "description": "Implement JWT login",
  "status": "TODO",
  "priority": "HIGH"
}
```

title 1–200；description 可空且最大 10,000。status 可省略，默认 `TODO`；priority 可省略，默认 `MEDIUM`。成功返回 201 和 `Location`。

### `GET /api/v1/projects/{projectId}/tasks`

返回按 `updatedAt` 降序的 TaskResponse 数组。

### `GET /api/v1/projects/{projectId}/tasks/{taskId}`

路径项目与 Task 所属项目必须同时匹配，否则 404。

### `PUT /api/v1/projects/{projectId}/tasks/{taskId}`

```json
{
  "title": "Add login",
  "description": "JWT implemented",
  "status": "DONE",
  "priority": "HIGH",
  "version": 0
}
```

提交完整可变字段和当前 version。成功返回更新后的 TaskResponse；版本冲突返回 409。

### `DELETE /api/v1/projects/{projectId}/tasks/{taskId}?version=0`

成功返回 204；version 必填且必须与当前版本一致。

### TaskResponse

```json
{
  "id": "uuid",
  "projectId": "uuid",
  "title": "Add login",
  "description": "JWT implemented",
  "status": "DONE",
  "priority": "HIGH",
  "version": 1,
  "createdAt": "2026-09-03T12:00:00Z",
  "updatedAt": "2026-09-03T12:10:00Z"
}
```

## 健康检查

`GET /actuator/health` 无需认证，用于本地和容器健康检查。默认只暴露 health 和 info，不暴露环境变量、堆信息或配置密钥。

## Agent Chat

`POST /api/v1/projects/{projectId}/agent/chat` 的请求与错误语义见 `agent-service.md`。Day 5 新增可空 `pendingAction`；未确认时 Task 数据不变。

`POST /api/v1/projects/{projectId}/agent/chat/stream` 使用同一请求校验与权限边界，成功返回 `text/event-stream`。Java 必须在开始流之前完成认证、项目授权和日配额消费；SSE 的 metadata/delta/complete/error 契约见 `agent-service.md`。流式传输不改变 pending action 的确认要求。

V1.1 在转发给 Agent Service 前原子消费一次用户 UTC 日配额；达到 `AGENTFORGE_AI_DAILY_LIMIT` 后返回 429。限制为 0 只表示本地开发关闭配额。

### `POST /api/v1/projects/{projectId}/agent/actions/{actionId}/confirm`

无 body；必须发送 1–100 字符的 `Idempotency-Key`，字符限于字母、数字、点、下划线、冒号和连字符。重新校验 JWT、project、action 发起者、服务端 Tool Policy 和目标 Task version。状态按 `PENDING → APPROVED → EXECUTED | FAILED` 变化。相同 key 重放返回既有结果且不重复写入；不同 key replay、已拒绝 action 返回 409；路径不匹配返回 404；其他用户返回 403。已审批 UPDATE_TASK 在执行时发现目标已删除、业务版本冲突或 flush 期真实乐观锁冲突，返回 `status=FAILED`、`resultTask=null` 并持久化失败审计；flush 冲突先回滚整个 Task/Graph sync/EXECUTED 事务，再以独立短事务收敛仍为 APPROVED 的 Action。未知基础设施异常不标记 FAILED，保留 APPROVED 供相同 key 重试。若已执行结果 Task 后续被删除，同 key replay 仍返回 `status=EXECUTED`，但 `resultTask=null`。

### `POST /api/v1/projects/{projectId}/agent/actions/{actionId}/reject`

无 body；`Idempotency-Key` 约束与 confirm 相同。成功返回 `status=REJECTED`，`resultTask=null`；Task 不变。相同 key 重放返回既有结果；不同 key replay 或非 PENDING action 返回 409。

V2-06 的 action status 枚举为 `PENDING / APPROVED / REJECTED / EXECUTED / FAILED`。每次请求、批准、拒绝、执行成功或业务失败由 Java 在同一事务中追加审计事实；HTTP 不接受 actor、状态、risk、result 或审计字段。

V2-07 目标语义中，confirm 先在短事务提交 `APPROVED`，再在事务外调用 Agent Service Resume，成功后由第二个短事务锁定 action、重新授权并执行。普通用户只能决定自己发起的 Action；ADMIN 可代审批。授权、审计和批准后执行始终使用当前认证 actor，而 Resume 的 Namespace user 使用 Action 创建时由 Java 持久化的原 `requestedByUserId`，客户端不能指定该 owner。Agent Service 暂时不可用时返回 503，但已提交的 APPROVED 不回滚；相同 Idempotency Key 重试继续 Resume/Execute。相同 key 的 `EXECUTED`/`FAILED` replay 保持 V2-06 语义。升级前已存在且没有 checkpoint 的 V2-06 Action 由内部 workflow version 标记识别，继续使用旧 Java 决策链路；该标记不加入响应。

新建 Chat Action 使用内部 workflow version 2：Python proposal 提供本轮 `actionWorkflowId`，Java 以该 ID 幂等复用唯一 Approval，并在 Resume 时原样带回。workflow ID、Action ID、conversation/project/user scope、decision 与决策幂等键必须共同匹配当前 checkpoint；旧 Action 决策不能消费新一轮等待点。既有 version 1 Action 继续兼容无 workflow ID 的恢复，MCP Action 仍不绑定 checkpoint。内部 workflow ID 不加入公共 Action 响应。

Core 在未传 conversationId 时按 project/user/request ID 稳定派生新 conversation ID，并在调用 Python 前持有该 Namespace。若 Python proposal 后续被 Java 文本校验、Task version 或持久化拒绝，Core 必须精确 Abort 对应 WAITING；同步入口可保持原有无 Action/冲突语义，流式入口在已开始响应后输出 error。Abort 目标不存在、轮次不匹配或已进入其它终态时返回公共 409；网络、5xx、空响应或响应身份不匹配返回 503。两类失败都不得把孤立 checkpoint 隐藏为普通回答。相同 `X-Request-Id` 的响应丢失重试复用 conversation/workflow/Action；不同 request 不能补偿或复用该轮。

reject 先提交 `REJECTED` 再恢复 Agent wait。相同 key 可重试恢复；相反 decision 或不同 key 返回 409。Python Resume 不能自行批准或执行业务 Tool，Java 仍是 Approval、Audit 与 Task 的唯一写入边界。

若 Python 明确返回 workflow 不存在或状态冲突，Core 将内部 404/409 收敛为公共 409 且不暴露下游正文；网络、超时或 5xx 仍返回 503。

## V2-05 Tool Policy 与历史会话

所有 Agent Intent 和直接 Wiki/Task 操作由 Java 服务端映射到固定 Tool Policy。HTTP 请求不接受可信的 `requiredRole`、`riskLevel` 或 `needApproval`；即使下游返回同名未知字段也不能覆盖策略。角色或项目策略拒绝返回 403，未知 Agent Tool 不创建 pending action。

### `GET /api/v1/projects/{projectId}/agent/conversations`

返回认证 actor 在当前授权项目内的历史会话摘要，按 `updatedAt DESC, conversationId` 稳定排序。每项包含 `conversationId`、`preview`、`messageCount`、`createdAt`、`updatedAt`。请求不接受 userId。

### `GET /api/v1/projects/{projectId}/agent/conversations/{conversationId}`

返回同一 project/user/conversation 作用域内的会话详情，包含摘要字段和按 sequence 排序的 `messages`。消息字段为 `role`（`USER` / `ASSISTANT`）、`content`、`sources`、`createdAt`。不返回 Prompt、Summary、Tool Context、内部 token 或其他用户信息。跨 Project/User/Thread 不能读取正文。

### `DELETE /api/v1/projects/{projectId}/agent/conversations/{conversationId}`（P3-02）

认证用户只能删除当前项目中自己的会话展示历史；成功返回 204。项目无权限为 403，会话不存在、不属当前用户或已删除为 404。存在 `PENDING`/`APPROVED` Action 时返回 409，需先手动完成审批决策。删除保留不可复用的会话 tombstone 与终态 Action/审计，清除历史消息正文和来源；旧 conversationId 不能继续 append。

同步或流式 Chat 携带已删除的 conversationId 时，Core 在调用 Agent Service 和消耗日配额之前返回 409；携带属于其他 project/user 的会话 ID 返回 404，不泄露其存在性。

`/internal/v1/rag/sources` 是 Agent Service 专用只读接口，不属于浏览器公共 API。它使用独立 Core 内部 token，并在读取 Wiki/Task 前再次执行用户存在和项目权限校验。

## 兼容性

Day 2 在首个可用版本形成前有意替换了 Day 1 匿名 User / Project 契约，迁移理由记录在 ADR-0005 和当前变更记录。后续新增可选响应字段视为兼容；删除或重命名字段、改变含义或状态码属于破坏性变化，必须先更新功能/API 文档并写 ADR 或迁移说明。

Chat 同步与 SSE 请求的 `message` 均要求非空且最多 16,000 字符（包含 AI 整理前置指令）；超限返回 400 Problem Details，不进入 Agent、配额或写入链路。

## V3-01 MCP Streamable HTTP

`/mcp` 使用官方 Java SDK 2.0.1 支持的 MCP `2025-11-25` Streamable HTTP。它不在 `/api/v1` 下。除初始化协商所需的协议头外，每个请求必须携带有效的 `Authorization: Bearer <accessToken>`；首版客户端预配置 AgentForge JWT，不提供 OAuth Authorization Server 或自动发现。

首批 `tools/list` 暴露固定 Tool：`search_wiki`、`get_task`、`create_task`、`update_task`。客户端不得提供或覆盖 actor、role、risk、approval 状态、result、audit 字段。`projectId` 必填并由 Java 重新执行项目权限校验。

- `search_wiki`：输入 `projectId`、`query`，对当前项目已授权 Wiki 的标题和正文做不区分大小写的子串匹配，返回页面摘要列表；首版不保证相关性排序，也不分页或限制结果数，与内部 Agent 的混合检索语义不同。
- `get_task`：输入 `projectId`、`taskId`，返回已授权 Task DTO。
- `create_task`：输入 `projectId`、`idempotencyKey` 与 Task 创建字段，首次调用返回 `PENDING` Approval；同键重试返回原 Approval 的当前状态。
- `update_task`：输入 `projectId`、`idempotencyKey`、`taskId`、`expectedTaskVersion` 和更新补丁，首次调用返回 `PENDING` Approval；同键重试返回原 Approval 的当前状态。

写 Tool 的 `idempotencyKey` 为 1–100 字符且只允许字母、数字、点、下划线、冒号和连字符；相同 project/user/key 的相同提案返回既有 Approval，不同提案返回冲突。MCP Action 不允许调用 `auto-confirm`。

写 Tool 可选的 `description` 若提供，必须为 1–10,000 字符的非空文本。写 Tool 的结果至少包含 `approvalId`、`status`、`actionType`、`riskLevel` 和安全预览；首次调用时 `status=PENDING` 且 Task 不变。同键重试即使原 Approval 已执行或拒绝，也只返回其当前状态，不重复写 Task。人工决定继续使用 `POST /api/v1/projects/{projectId}/agent/actions/{actionId}/confirm|reject`，confirm 仍要求 `Idempotency-Key`，执行前重新检查 actor、project、Tool Policy 和 Task version。MCP Action 不绑定 Chat conversation/checkpoint，也不会调用 Python resume。

参数或可修正领域错误返回 MCP Tool Result `isError=true`；认证失败保持 HTTP 401；未授权项目、跨项目资源和内部异常不得泄漏资源正文、凭据、数据库细节或堆栈。首版只实现 Tools，不实现 Resources、Prompts、Sampling 或 MCP Client。

### V3 审核修复：任务模式

Chat 和 Chat stream 请求新增可选 taskType：FORMAT、REWRITE、PLAN、REVIEW、ANSWER，省略或 null 为 ANSWER；未知值返回校验错误。模式仅影响部署配置内模型排序，不提供权限；消息正文不控制路由，Tool 意图固定 PLAN。

## V3-04 项目领域图（Implemented）

前缀 `/api/v1/projects/{projectId}/graph`，所有接口 Bearer + owner/admin；详见 graph-domain-model 功能文档。

- PUT `/entities`：type（PROJECT/SERVICE/API/WIKI/TASK/ISSUE）、externalId（1..200）、displayName（1..200）、source（type=WIKI/TASK,id UUID,version>=0；PROJECT 可省略）、expectedVersion>=0；返回 id/projectId/type/externalId/displayName/source/version。PROJECT externalId 必须是路径项目 UUID，WIKI/TASK externalId 必须匹配 source id/type，显示名称取业务事实。
- GET `/entities?after={id}&limit=50`：返回 items、nextAfter（最后扫描 id，末页 null），候选扫描上限 100；来源失效实体隐藏。
- PUT `/relations`：type、fromId/toId UUID、evidence（source 同上、start>=0、end>start、excerpt 1..2000、chunkIndex 可空且>=0、confidence 0..1、expectedVersion>=0）；返回 relation id/type/fromId/toId/evidence list/hasMoreEvidence。每次只写一条 evidence。
- GET `/entities/{id}/neighbors?after={relationId}&limit=50`：返回 items（relation 与有效 evidence）、nextAfter；未知或失效节点 404，无有效 evidence 的关系隐藏。只遍历一跳，无 Cypher 参数。
- DELETE `?confirm=true`：人工清理路径项目派生图，204。未确认 400，清理不删除业务事实、不启动重建流水线。

400 格式/非法方向/原文不符；401 未认证；403 无权；404 来源或端点不属于项目/不存在；409 来源、CAS 或图写入后响应前来源并发变更；503 图关闭/Neo4j 故障。请求 source 字段不接受自由字符串 provenance。
图 source.version 与 expectedVersion 必须为 JSON 整数，拒绝小数或数字字符串，禁止截断为另一版本。

## V3-05 Graph Extraction（已实现）

`POST /api/v1/projects/{projectId}/graph/extraction/rebuild`：Bearer + 项目 owner/admin；无请求 body，返回 202，表示当前项目 Wiki/Task 来源已登记待办，不表示 Neo4j 已同步完成。401/403/404 沿用项目权限语义。Wiki/Task 既有 CRUD 响应契约不变；图抽取在后台最终一致，图关闭或不可用不阻止业务写入。提取语法、证据和限制见 ../03-features/graph-extraction.md。

`GET /api/v1/projects/{projectId}/graph/extraction/status`：Bearer + owner/admin；200 body 为 `{ "pending": number, "retrying": number }`，仅统计本项目的持久待办。

## V3-06 Entity Resolution（已实现）

前缀 `/api/v1/projects/{projectId}/graph/resolution`，所有接口均需 Bearer 与项目 owner/admin；项目、实体、规范目标和来源必须同项目且当前有效，Service/API/Issue 只能与同类型候选关联。请求携带的模型结果不构成授权。

- `POST /suggestions`：body `{ "entityId": "uuid" }`，返回当前有界候选、规则分数、可选推荐候选 ID、有限 0..1 confidence 及 `reviewRequired`。无合并副作用；Python/LLM 失败时仍可返回需人工审阅的候选。
- `PUT /decisions/{entityId}`：body 包含 `canonicalEntityId`、`canonicalName`（1..200）、aliases（有界、1..200）、受限 metadata、confidence（0..1）、`sourceVersion`、`canonicalSourceVersion`、`expectedVersion`；只由认证用户确认。Java 重新读取并验证两个实体来源、类型、项目和 CAS，返回 `{entityId,canonicalEntityId,canonicalName,aliases,metadata,confidence,status:"CONFIRMED",version}`；同 payload 重试幂等，过期/冲突 409。已有规范实体的 `canonicalName`/`metadata` 必须与存储值一致；改名或 metadata 更新须先调用规范实体 PUT。
- `GET /canonicals/{canonicalId}`：读取当前有效规范实体的名称、受限 metadata、来源版本和规范 CAS version。
- `PUT /canonicals/{canonicalId}`：body `{canonicalName,metadata,sourceVersion,expectedVersion}`；项目 owner/admin 修改规范名与受限 metadata，CAS 和当前来源版本检查后追加审计；同 payload 重试幂等；若名称与 metadata 已等于当前值，即使 `expectedVersion` 较旧也返回当前结果，实际变更仍严格 CAS。
- `GET /decisions/{entityId}`：返回当前规范映射或 `{entityId,status:"UNMAPPED",version}` 未映射状态，不泄露其他项目成员或失效 alias。
- `DELETE /decisions/{entityId}?expectedVersion=N`：在 CAS 验证后撤销该成员映射，保留追加式审计，204；原始图和 evidence 不变。

格式错误 400；未认证 401；无项目访问权限 403；候选/成员不存在或当前来源失效 404；来源或决策版本变化 409；图不可用 503。接口不接受任意 Cypher、模型生成的新目标 ID 或自动 merge。详见 ../03-features/entity-resolution.md。

## V3-07 内部 GraphRAG 只读入口（Implemented）

`POST /internal/v1/graph/retrieval` 只供 Agent Service 使用，沿用来源接口的独立 `X-AgentForge-Core-Internal-Token`；浏览器 JWT 不能替代。请求 `{projectId,userId,actorAdmin,requestId,query}`，其中 query 为 1–1000 字符；Java 先验证 token、用户和项目访问，再读取图。响应包含关联的 projectId/requestId 和有界 `matches`；每项给出跳数、实体、关系及当前有效 evidence 的 Wiki/Task 来源身份、版本、摘录和 confidence。详细字段和故障语义见 [Agent Service API](agent-service.md)。没有可信关系返回空数组；图关闭或故障返回通用 503，内部鉴权/项目错误保持 401/403/404，输入错误 400。

## V3-08 Chat 来源传递（Implemented）

Core API 仍先校验 Java 用户和项目权限，然后调用 Agent Service；不新增公开或内部请求字段。Agent 返回的 `sources` 可含 `sourceType=REPOSITORY`、确定性 UUID、标题和有界摘录；Core API 在同步 JSON 与 SSE complete 中按现有来源结构传递。历史回答沿用通用来源 JSON 存储路径，但本 Node 不声明其专项回读验收。Core API 不接收仓库路径、不提供仓库读取/下载接口，也不把仓库来源当作 Wiki/Task 的业务来源。
