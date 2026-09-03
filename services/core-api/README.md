# AgentForge Core API

Java 21 + Spring Boot 3.5.16 的确定性业务服务。Day 1 实现 User / Project 的最小创建与查询链路，数据由 PostgreSQL 保存并由 Flyway 迁移。

## 包结构

每个业务能力内部按 `api`、`application`、`domain`、`infrastructure` 分层；详细职责见 [Java Core API 架构](../../docs/02-architecture/backend-architecture.md)。

## 运行

先启动根目录 `infra/compose.yaml` 的 PostgreSQL，再使用 Java 21 运行：

```text
# Windows
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

仓库已固定 Maven Wrapper 3.3.4 与 Maven 3.9.11，不要求全局安装 Maven。环境变量和排错见 [本地开发](../../docs/05-development/local-development.md)。
