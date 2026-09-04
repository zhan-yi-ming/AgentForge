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
- Pi 在隔离 Python 环境启动真实 uvicorn，并设置 `AGENTFORGE_AGENT_CONTRACT_TEST=true` 后运行 Java verify，验证真实 HTTP header 与 JSON 往返；普通 Java 测试环境不依赖 Python 进程。
- 跨进程契约测试通过 Spring 测试上下文取得 Boot 自动配置的 `RestClient.Builder`，不得以静态裸 builder 代替生产序列化配置。
- 跨进程契约至少覆盖 200、内部 token 错误和下游不可达；出站请求断言必须验证 null 字段省略，以及 requestId header/body 一致，不能只凭 Python 的宽松解析推断序列化正确。
- Day 3 收口及后续阶段的 Pi 验证必须先确认 Docker daemon 可用，并真实执行 PostgreSQL/Testcontainers 测试；不得把容器测试跳过视为通过。Pi 报告需记录容器测试数量、跳过数和清理状态。
- 真实 token/LLM key 不进入仓库或测试输出。

## 命令

在 `services/core-api`：

```text
mvnw.cmd test
mvnw.cmd verify
```

构建和数据库验证的实际结果写入 `docs/07-changes/` 当前记录。Docker 已作为阶段验证前置条件；若 daemon 不可用，Pi 必须把本轮标记为 `NEEDS_FIX`，不得以“未运行”或跳过容器测试交付。

## 测试命名

测试名描述场景与结果，如 `createUser_normalizesEmail`、`createProject_rejectsMissingOwner`。测试应验证公共行为，避免绑定无意义的实现细节。
