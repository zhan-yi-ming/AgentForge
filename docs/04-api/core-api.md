# Core API 契约

- 状态：Implemented
- 基础路径：`/api/v1`
- 内容类型：`application/json`

## 通用约定

- UUID 使用标准字符串格式。
- 时间使用 ISO-8601 UTC 表示。
- 成功响应使用资源 DTO，不暴露 JPA 内部字段。
- 错误使用 `application/problem+json`，至少包含 `type`、`title`、`status`、`detail`、`instance`；字段校验错误额外包含 `errors`。
- 每个响应返回 `X-Request-Id`。请求可提供该头，缺失或格式不可接受时由服务生成。

## User

### `POST /api/v1/users`

请求：

```json
{
  "email": "owner@example.com",
  "displayName": "Project Owner"
}
```

约束：email 必须合法且不超过 320 字符；displayName 去除首尾空白后长度为 1–100。成功返回 201 和 `Location`。

响应：

```json
{
  "id": "uuid",
  "email": "owner@example.com",
  "displayName": "Project Owner",
  "createdAt": "2026-09-03T12:00:00Z",
  "updatedAt": "2026-09-03T12:00:00Z"
}
```

错误：400 输入无效；409 邮箱已存在。

### `GET /api/v1/users/{id}`

成功返回 200 UserResponse；不存在返回 404。

## Project

### `POST /api/v1/projects`

请求：

```json
{
  "ownerId": "uuid",
  "name": "AgentForge",
  "description": "AI-assisted engineering workspace"
}
```

约束：name 去除首尾空白后长度为 1–120；description 可空，最大 2000。成功返回 201 和 `Location`。

响应：

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

错误：400 输入无效；404 owner 不存在；409 同一 owner 下项目名已存在。

### `GET /api/v1/projects/{id}`

成功返回 200 ProjectResponse；不存在返回 404。

### `GET /api/v1/projects?ownerId={uuid}`

成功返回 200 JSON 数组，按 `createdAt` 降序。owner 不存在返回 404。Day 1 暂不分页。

## 健康检查

`GET /actuator/health` 用于本地和容器健康检查。默认只暴露 health 和 info，不暴露环境变量、堆信息等敏感端点。

## 兼容性

新增可选响应字段视为兼容；删除或重命名字段、改变含义或状态码属于破坏性变化，必须先更新功能/API 文档并写 ADR 或迁移说明。
