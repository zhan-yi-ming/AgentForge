# Day 2 鉴权、Wiki 与 Task

- 日期：2026-09-03
- 状态：Implemented
- 阶段：V1 / Day 2
- 交付目标：`origin/main`

## 背景

Day 1 已建立 User / Project 基础和模块化 Java Core API，并以提交 `17b43f4` 推送到公开仓库。当前接口没有认证，owner 由客户端参数提供，尚不能安全承载 Wiki、Task 或后续 Agent 写回。公开仓库还需要把密钥边界和远程交付规则固化为项目制度。

## 目标

- 建立公开仓库的密钥、防泄漏和事故处置制度，并在 GitHub Actions 扫描完整 Git 历史。
- 提供注册、登录和短期 JWT access token。
- 使用 Spring Security 保护业务 API，支持 `USER` / `ADMIN` 基础角色。
- owner 身份来自已验证令牌；所有项目资源在服务端再次校验 owner 或管理员权限。
- 实现 Wiki Page 与 Task 的创建、查询、列表、更新和删除。
- 用新 Flyway 迁移扩展用户凭据、Wiki 和 Task 数据，不改动已共享的 V1 迁移。

## 非目标

- 不实现 Refresh Token、登出黑名单、OAuth、SSO、找回密码、邮箱验证或复杂成员关系。
- 不实现 Wiki 历史版本、附件、全文搜索、Task 评论或复杂工作流。
- 不实现前端、Python Agent、RAG、Tool Calling或审计平台。
- 不把 ADMIN 自助提升暴露为公共 API；本地演示只能通过受控数据库操作提升角色。

## 受影响文档

- `AGENTS.md`、`docs/00-governance/*`：公开仓库安全、详细提交/推送和阶段停止规则。
- `docs/01-product/roadmap.md`、`README.md`：当前阶段和已实现边界。
- `docs/02-architecture/*`、ADR-0005：安全边界、模块依赖、JWT、密码和数据模型。
- `docs/03-features/*`：注册鉴权、Wiki、Task，以及 Day 1 接口迁移。
- `docs/04-api/core-api.md`：Day 2 HTTP 契约和错误语义。
- `docs/05-development/*`：本地密钥配置、测试与 GitHub 交付。

## 设计决定

- 使用 Spring Security 的无状态过滤链与 OAuth2 Resource Server JWT 支持；HS256 仅用于 V1 单服务，由至少 256 bit、Base64 编码且只通过环境变量注入的密钥签名。
- 使用 `DelegatingPasswordEncoder`，新密码以 `{bcrypt}` 格式保存，避免明文或可逆加密，并为以后算法升级保留格式标识。
- access token 默认有效期 30 分钟，使用 URI issuer `https://agentforge.local/core-api`，包含 `iss`、`sub`、`iat`、`exp`、`roles`；数据库仍是用户角色的事实来源，角色变化在旧 token 过期前不会立即生效，这是 V1 已知限制。
- Day 1 的 passwordless 用户保持可迁移：`password_hash` 允许为空，这些用户不能登录；新注册用户必须有密码哈希。
- Project 创建不再接收 `ownerId`，列表默认只返回当前用户拥有的项目；ADMIN 可访问任意项目资源。
- Wiki / Task API 使用项目嵌套路由，同时在数据库查询和应用服务中验证资源确实属于路径中的项目。
- Wiki / Task 使用乐观锁版本字段；更新请求携带 `version`，过期写入返回 409。
- GitHub Actions 使用固定 commit SHA 的 Gitleaks Action 扫描完整历史；它是预防门禁，不替代泄露后立即吊销密钥。

相关长期决定见 `docs/02-architecture/decisions/ADR-0005-security-jwt-and-passwords.md`。

## 实现计划

- 新增 security 模块、认证端点、token 服务、统一 401/403 Problem Detail。
- 扩展 user 模块的凭据和角色查询入口；调整 project 用例只相信认证身份。
- 新增 wiki / task 业务模块、V2 数据库迁移和 CRUD API。
- 新增安全、跨用户隔离、Wiki / Task、迁移集成测试和公开仓库密钥扫描工作流。

## 预期验证

- Maven `verify`：编译、单元、Web/安全测试和可用时的 PostgreSQL 集成测试。
- 未登录、无效 token、跨用户访问、ADMIN、版本冲突和 CRUD 主要路径均有自动化证据。
- 文档链接、Compose 配置、文档先行门禁和敏感信息扫描通过。
- 提交前确认 diff 只包含 Day 2；提交信息正文列出行为、迁移、测试和限制。

## 实际实现

- 在 `security` 模块建立无状态 Spring Security 过滤链，只按精确 HTTP 方法与路径公开注册、登录和健康检查，避免未来新增 `/auth/**` 端点被意外放行；统一输出带 `requestId` 的 401/403 Problem Detail。
- 新增注册、登录、`GET /users/me`，密码使用 `DelegatingPasswordEncoder` 的 `{bcrypt}` 格式保存；JWT 使用 HS256、URI issuer、用户 UUID subject、角色和最长 24 小时 TTL，启动时校验 Base64 密钥至少 256 bit。
- User 增加凭据与 `USER` / `ADMIN` 角色；Day 1 无密码历史用户保持可迁移但不能登录。Project 创建、列表和详情改为只相信 JWT actor，普通用户只能访问自己的项目，ADMIN 具备受控跨项目访问能力。
- 新增 Wiki Page 与 Task 的分层模块及项目嵌套 REST API。服务层先执行 owner-or-admin 校验，再通过 `projectId + resourceId` 定位资源；更新与删除使用版本号做乐观并发控制。
- 新增 Flyway `V2__add_security_wiki_and_tasks.sql`，扩展用户凭据并创建 Wiki / Task 表、外键、唯一约束、检查约束与查询索引；保留已经共享的 V1，不改写历史迁移。
- 增加 35 个自动化测试，覆盖认证、token、精确公开路由、未登录/无效 token、跨用户隔离、ADMIN、Project/Wiki/Task 主要用例、版本冲突，以及 PostgreSQL 真实持久化和 Flyway V1 → V2 迁移。
- 增加公开仓库密钥规范、`.env.example` 占位配置和固定 action commit SHA 的 Gitleaks 全历史扫描工作流；真实密钥、令牌、私钥与生产连接串不得提交。
- 落地 Codex → Pi 只读审查桥接，Pi 模型在参数校验层固定为 `deepseek/deepseek-v4-pro`，禁止 Flash 降级；桥接运行时状态均排除在 Git 提交之外。

## 验证结果

- `mvnw.cmd --batch-mode --no-transfer-progress test`：通过；Java 21.0.12.1，35 个测试，0 失败、0 错误、0 跳过；此前也以 `clean test` 从空构建目录得到相同结果。
- `mvnw.cmd --batch-mode --no-transfer-progress verify`：通过；35 个测试再次全量通过，并生成可执行 Spring Boot JAR。
- Testcontainers 连接 Docker Desktop 29.5.3，启动 PostgreSQL 17.11；Flyway 成功校验并应用 V1、V2，schema 到达 v2，JPA validate 与 User / Project / Wiki / Task 持久化链路通过。
- `docker compose --env-file .env.example -f infra/compose.yaml config --quiet`：通过。
- PowerShell AST 解析：`run-review.ps1`、`bridge-monitor.ps1` 均无语法错误；`pi --list-models v4-pro` 确认可用 ID 为 `deepseek/deepseek-v4-pro`。
- Markdown 本地链接检查、`git diff --check`、敏感内容启发式扫描与危险文件名扫描：通过。
- 本机未安装独立 Gitleaks CLI；远程采用固定 SHA 的 GitHub Action 在推送后扫描完整历史，该检查结果需在 GitHub Actions 中查看。仓库未提交任何真实 JWT secret，`.env.example` 仅含明显占位值。

## 风险与回滚

- 错误的授权查询可能造成跨用户数据访问：使用应用服务校验与跨用户测试双重约束。
- JWT 密钥泄漏会允许伪造身份：仓库不保存真实密钥，发现泄漏时先轮换/吊销，再清理历史并复盘。
- 数据迁移只前进不回写 V1；回滚应用前需先备份，Wiki / Task 数据表的清理必须由单独迁移完成。
