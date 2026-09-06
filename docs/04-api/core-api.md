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

无 body。重新校验 JWT、project、action 发起者和目标 Task version。成功返回 action 预览、`status=EXECUTED` 与 `resultTask`。同一 action 重复确认返回同一结果且不重复写入。已拒绝 action 或 stale update 返回 409；路径不匹配返回 404；其他用户返回 403。

### `POST /api/v1/projects/{projectId}/agent/actions/{actionId}/reject`

无 body。成功返回 `status=REJECTED`，`resultTask=null`；Task 不变。重复 reject 返回同一结果；已执行 action 返回 409。

`/internal/v1/rag/sources` 是 Agent Service 专用只读接口，不属于浏览器公共 API。它使用独立 Core 内部 token，并在读取 Wiki/Task 前再次执行用户存在和项目权限校验。

## 兼容性

Day 2 在首个可用版本形成前有意替换了 Day 1 匿名 User / Project 契约，迁移理由记录在 ADR-0005 和当前变更记录。后续新增可选响应字段视为兼容；删除或重命名字段、改变含义或状态码属于破坏性变化，必须先更新功能/API 文档并写 ADR 或迁移说明。
