# 测试策略

- 状态：Accepted

## 测试分层

- 领域/Service 单元测试：快速覆盖业务规则、规范化、重复和不存在分支。
- Web 切片测试：验证路由、Bean Validation、状态码、DTO 和统一错误。
- Repository 集成测试：使用真实 PostgreSQL/Testcontainers 验证 SQL、约束和查询；Day 1 若环境不可用需明确记录未运行。
- 端到端测试：跨 Web、Java、Python 的关键闭环，进入对应天次后建立。

## Day 2 质量门槛

- Java 编译通过。
- Service 的成功与主要失败分支有测试。
- HTTP 契约的创建、查询、400、404、409 有代表性测试。
- Flyway 迁移在 PostgreSQL 上成功，JPA `validate` 通过。
- 不以 H2 成功替代 PostgreSQL 特有行为验证。
- 注册保存 BCrypt 哈希，登录成功 / 失败和 JWT 签名、issuer、时效有测试。
- 匿名请求 401、跨用户请求 403、owner 与 ADMIN 成功路径有测试。
- Wiki / Task CRUD、默认值、嵌套项目匹配和乐观锁冲突有测试。
- 密钥、token 与 passwordHash 不出现在 HTTP 响应、测试快照或提交内容中。

## Day 3 质量门槛

- Pi 执行 Agent Service pytest，覆盖 health、内部 token、输入校验、conversationId 与 graph 输出。
- Pi 执行 Core API verify，覆盖项目授权先于下游调用、请求字段映射和下游失败 503。
- Java/Python 字段名、UUID、requestId 和 400/401/403/503 契约一致。
- 真实 token/LLM key 不进入仓库或测试输出。

## 命令

在 `services/core-api`：

```text
mvnw.cmd test
mvnw.cmd verify
```

构建和数据库验证的实际结果写入 `docs/07-changes/` 当前记录。若环境缺少 Java 21 或容器不可用，记录为“未运行”和具体原因。

## 测试命名

测试名描述场景与结果，如 `createUser_normalizesEmail`、`createProject_rejectsMissingOwner`。测试应验证公共行为，避免绑定无意义的实现细节。
