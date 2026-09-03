# 用户与项目基础

- 状态：Implemented（Day 1 基础与 Day 2 安全迁移均已完成）
- 所属阶段：V1 / Day 1–2
- 相关 ADR：ADR-0003、ADR-0004、ADR-0005

## 用户价值与场景

用户和项目是后续 Wiki、Task、对话与权限的最小归属边界。Day 1 提供创建/查询用户、创建/查询用户所拥有项目的基础能力，用来验证 Controller → Service → Repository → Entity → PostgreSQL 链路。

## 范围

- 创建用户，邮箱规范化为小写并保证唯一。
- 按 ID 查询用户。
- 为已存在用户创建项目。
- 按 ID 查询项目。
- 按 owner 查询项目列表。

## Day 2 安全迁移

- 用户创建迁移到 `POST /api/v1/auth/register`，必须提供密码；原 `POST /api/v1/users` 移除，避免创建不可登录账号。
- 用户读取收敛为 `GET /api/v1/users/me`，不再允许匿名按任意 ID 查询。
- 创建项目不再接收 `ownerId`；owner 取自 token `sub`。
- `GET /api/v1/projects` 只列出当前 owner 的项目；具体项目仅 owner 或 ADMIN 可读。
- 仍不实现项目更新/删除、workspace、成员或分页。

## 关键流程

### 创建用户

创建用户的 Day 1 流程由注册流程取代，见 `authentication-and-authorization.md`。

### 创建项目

1. Spring Security 验证 JWT，Controller 取得 actor userId。
2. Controller 校验 name 与 description，ProjectService 以 actor userId 作为 owner。
3. 检查同一 owner 下名称是否重复。
4. 保存仅包含 `ownerId` 的 Project Entity，数据库外键提供最终引用完整性。
5. 返回 ProjectResponse，HTTP 201。

## 接口

完整字段与错误见 `docs/04-api/core-api.md`：

- `POST /api/v1/auth/register`
- `GET /api/v1/users/me`
- `POST /api/v1/projects`
- `GET /api/v1/projects/{id}`
- `GET /api/v1/projects`

## 数据与约束

数据结构见 `docs/02-architecture/data-architecture.md`。API DTO 与 JPA Entity 分离；project 只持有 owner UUID，避免跨模块对象图。

## 权限与安全

Day 2 业务接口默认要求 JWT。ProjectService 使用认证 actor 并在服务端校验 owner 或 ADMIN；客户端不能声明或切换 owner。

## 失败与排查

- 请求字段无效：400 Problem Detail，查看 `errors` 字段。
- 用户或项目不存在：404 Problem Detail。
- 邮箱或 owner 下项目名重复：409 Problem Detail。
- 数据库不可用或迁移失败：服务启动失败；先检查 Compose、连接环境变量与 Flyway 日志。
- 客户端提供的 `X-Request-Id` 会被校验长度；缺失时服务生成，响应头中返回，用于关联日志。

## 测试与验收

- Service 单元测试覆盖规范化、重复、owner 不存在和成功创建。
- Controller 切片测试覆盖状态码、校验与 DTO。
- 在 Java 21 + PostgreSQL 环境执行 Maven 测试和启动冒烟测试。

## 已知限制

认证、更新、删除、分页、成员关系与审计均未实现。项目列表在 Day 1 预计规模下不分页；加入 Task 前必须补充分页决策。

当前开发机无法启动 Docker 系统服务，因此 Testcontainers 的 PostgreSQL 集成用例已编译但在本次验证中跳过。单元与 Web 切片测试已通过；迁移必须在 Docker/CI 可用后再次验证，详见当前变更记录。
