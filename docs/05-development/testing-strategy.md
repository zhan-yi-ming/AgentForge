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

- Codex 执行 Agent Service pytest，覆盖 health、内部 token、输入校验、conversationId 与 graph 输出。
- Codex 执行 Core API clean verify，覆盖项目授权先于下游调用、请求字段映射和下游失败 503。
- Java/Python 字段名、UUID、requestId 和 400/401/403/503 契约一致。
- Codex 在隔离 Python 环境启动真实 uvicorn，并设置 `AGENTFORGE_AGENT_CONTRACT_TEST=true` 后运行 Java verify，验证真实 HTTP header 与 JSON 往返；普通 Java 测试环境不依赖 Python 进程。
- 跨进程契约测试通过 Spring 测试上下文取得 Boot 自动配置的 `RestClient.Builder`，不得以静态裸 builder 代替生产序列化配置。
- 跨进程契约至少覆盖 200、内部 token 错误和下游不可达；出站请求断言必须验证 null 字段省略，以及 requestId header/body 一致，不能只凭 Python 的宽松解析推断序列化正确。
- Day 3 收口及后续阶段验证必须先确认 Docker daemon 可用，并真实执行 PostgreSQL/Testcontainers 测试；不得把容器测试跳过视为通过。验证记录需包含容器测试数量、跳过数和清理状态。
- 真实 token/LLM key 不进入仓库或测试输出。

## 命令

在 `services/core-api`：

```text
mvnw.cmd test
mvnw.cmd verify
```

构建和数据库验证的实际结果写入 `docs/07-changes/` 当前记录。Docker 已作为阶段验证前置条件；若 daemon 不可用，本轮不得以“未运行”或跳过容器测试交付。

## 测试命名

测试名描述场景与结果，如 `createUser_normalizesEmail`、`createProject_rejectsMissingOwner`。测试应验证公共行为，避免绑定无意义的实现细节。

## Day 4 质量门槛

- Codex 执行 Agent Service pytest，覆盖 Chunk、hash Embedding、BM25、RRF、字符预算、来源去重、索引版本替换/删除和跨项目隔离。
- Codex 使用 pgvector PostgreSQL 17 容器执行 Python 索引集成测试；`vector` 扩展、384 维写入与 cosine 查询必须真实运行，不得 mock 后宣称通过。
- Codex 执行 Core API `clean verify`，覆盖内部来源 token、用户存在、owner/admin、跨用户拒绝、授权先于 Repository、Wiki/Task DTO 映射和公共 Chat sources 透传。
- Codex 启动真实 Core API、Agent Service 与 pgvector 容器，执行至少一个从公共 Chat 到来源回调、索引、混合检索和 sources 返回的跨进程闭环；所有进程和容器必须清理。
- 固定语料必须证明 Wiki 与 Task 都可召回、无匹配不伪造来源、另一个项目的 Chunk 不会泄漏。
- Docker/PostgreSQL 测试、跨进程测试不得跳过；报告记录命令、版本、测试数量、0 失败/错误/跳过和清理状态。
- 敏感信息扫描覆盖两个内部 token、JWT secret、数据库密码和 Embedding API key；真实值不得进入仓库或报告。

Day 4 的约定测试 seam 是：Agent Service HTTP Chat、Core API 公共/内部 HTTP 契约，以及 `RagStore.synchronize/search` 对真实 pgvector 的公开适配器接口。回归测试从这些边界观察行为，不测试私有函数；外部 HTTP 可以 stub，数据库优先使用真实容器。

Day 4 跨进程闭环由仓库脚本执行：

```powershell
.\scripts\validation\day4-e2e.ps1
```

脚本使用独立 Compose project 和专用端口，启动 pgvector、Core API 与 Agent Service；通过公共 HTTP 创建两名用户、两个项目、Wiki 与 Task，验证两类召回、跨项目隔离、来源版本替换、删除清理和无匹配不伪造来源。无论成功失败都按精确 PID 停止服务并执行 `docker compose down -v`；只清理由本轮创建的临时日志和隔离资源。

## 2026-09-05 生效：停用 Pi

用户已撤销 Pi 审查与测试授权。Codex 直接运行构建、格式和测试，核对机器产物并记录退出码、测试数量、失败、跳过与清理。旧 Pi 流程仅供历史追溯，不得启动 monitor、调用 Pi 或凭旧 PASS 自动推进。详见 docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md。
