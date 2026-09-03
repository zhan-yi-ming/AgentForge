# 仓库初始化与 Day 1 Java 基础

- 日期：2026-09-03
- 状态：Implemented（真实 PostgreSQL 集成验证待可用 Docker 环境补跑）
- 阶段：V1 / Day 1

## 背景

仓库是一个尚无提交的空 Git 仓库。产品规划要求先建立可扩展的 Web、Java、Python 和基础设施整体结构，再完成 Day 1 的 Spring Boot、PostgreSQL、User / Project 基础，并确保项目可由用户和后续 AI 理解。

项目维护者随后确认 AI 已具备 Git 提交能力，因此将交付方式调整为：AI 在完成并验证一个阶段后创建提交；提交成功后必须立即中断，汇报完成事项、非必需的用户介入项和下一阶段计划；只有用户明确确认后才允许开始下一阶段。

## 目标

- 把“任何调整都先写文档”写入根目录项目级指令。
- 建立有清晰导航、按职责分区、可追溯的文档系统。
- 建立单仓库整体骨架，预留 Web 与 Python Agent 边界。
- 创建 Java 21 + Spring Boot + PostgreSQL 的 Core API。
- 完成 User / Project 的基础数据模型和最小创建、查询能力。
- 用 Flyway 管理数据库结构，并提供本地容器配置。

## 非目标

- 不实现登录、JWT 或完整 RBAC；它们属于 Day 2，开始前必须新增登录鉴权功能文档。
- 不实现 Wiki、Task、AI Chat、RAG、Tool Calling 或前端页面。
- 不引入 Neo4j、Langfuse、LiteLLM、MCP 或复杂微服务治理。
- 不在用户确认前跨越检查点进入下一阶段。

## 设计与文档计划

- 创建项目级规则与治理文档。
- 创建产品范围、路线图、整体架构、仓库结构、后端和数据架构文档。
- 创建 User / Project 功能文档与 Core API 契约。
- 创建单仓库、模块化单体、数据库迁移等 ADR。
- 创建本地开发、测试、Git 工作流和本地基础设施文档。

## 实现计划

- 根目录工程配置、环境变量示例和 Docker Compose。
- `apps/web/`：仅保留职责与后续启动说明，不提前实现 Day 6 页面。
- `services/core-api/`：Day 1 Java 实现。
- `services/agent-service/`：仅保留职责与后续接口边界，不提前实现 Day 3 Agent。
- 质量检查脚本：检查代码或配置变化时是否同时存在变更记录变化。

## 预期验证

- 检查文档导航和链接目标。
- 检查仓库目录与架构文档一致。
- 使用 Java 21 环境运行 Core API 单元测试与 Maven 构建。
- 启动 PostgreSQL 后运行 Flyway 并进行最小接口冒烟测试。

## 实际实现

- 根目录新增 `AGENTS.md`，把文档先行写成不可跳过的项目级规则。
- 建立 `docs/00-governance` 至 `docs/07-changes`、模板和 ADR 索引；产品规划的 V1/V2/V3 与 Day 1 边界已转成仓库内可维护文档。
- 建立 `apps/web`、`services/core-api`、`services/agent-service`、`infra` 和 `scripts` 单仓库结构；尚未进入日程的应用只保留边界说明。
- Core API 固定 Java 21、Spring Boot 3.5.16、Maven Wrapper 3.3.4、Maven 3.9.11，并校验 Maven 分发包 SHA-256。
- Java 采用 `user` / `project` 按业务能力分包，每个模块区分 API、应用、领域和基础设施。
- 实现 User / Project 创建与查询 HTTP API、DTO 校验、统一 Problem Detail、request_id、Service 事务与 Repository 适配器。
- 新增 Flyway `V1__create_users_and_projects.sql`，包含 UUID 主键、外键、唯一约束、检查约束和 owner 索引；JPA 使用 `ddl-auto=validate`。
- 新增 PostgreSQL / Redis Compose、本地环境变量示例、文档门禁与 Core API GitHub Actions。
- 固定跨平台文本为 LF，仅 Windows 启动脚本使用 CRLF，避免不同开发环境反复产生整文件换行差异。
- 新增 13 个无需数据库的 Service/Web 测试和 1 个 Testcontainers PostgreSQL 集成测试。
- 更新项目治理规则：后续由 AI 创建阶段提交；每次提交后强制停止，等待用户确认下一阶段计划。

## 验证结果

- `mvnw.cmd --batch-mode --no-transfer-progress verify`：通过。使用临时 Temurin Java 21.0.12.1；14 个测试，0 失败、0 错误、1 跳过；JAR 成功打包。
- 跳过项：`PersistenceIntegrationTest`。Testcontainers 检测到 Docker daemon 未运行。
- 已尝试后台启动 Docker Desktop；后台进程启动，但 `com.docker.service` 因当前进程无系统服务访问权限无法启动，因此没有伪造 PostgreSQL/Flyway 通过结果。
- `docker compose --env-file .env.example -f infra/compose.yaml config --quiet`：通过，Compose 结构与变量插值有效。
- Markdown 本地链接检查：通过。
- DOCX 规划书内容已完整提取并用于阶段/技术边界；本机缺少 LibreOffice，因此未完成来源文档的页面渲染检查，但未修改该 DOCX。

## 待补验证

在 Docker Desktop 服务可用的环境运行 `services/core-api/mvnw.cmd verify`，确认 PostgreSQL 容器启动、Flyway schema history、JPA validate 和 User → Project 真实持久化链路。该用例已存在，会在 Docker 可用时自动执行。

## 回滚思路

当前为新仓库首批文件。如设计在首个提交前被否决，可直接调整文档和骨架；提交后通过新的变更记录和反向提交恢复，不重写已共享历史。
