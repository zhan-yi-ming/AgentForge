# 本地开发

- 状态：Accepted
- 当前可运行应用：Core API、Agent Service

## 前置条件

- Git
- Java 21（当前机器检测到的 Java 8 不能构建本项目）
- Python 3.12、3.13 或 3.14
- Docker Desktop 与 Docker Compose

Maven Wrapper 3.3.4 已固定使用 Maven 3.9.11，因此无需全局安装 Maven；Wrapper JAR 与 Maven 分发包都使用固定 SHA-256 校验，避免下载内容静默变化。Web 与 Agent Service 尚未进入实现日程，它们的 README 只说明边界。

## 环境变量

复制根目录 `.env.example` 为本地 `.env`，按需修改。`.env` 不提交。Core API 主要变量：

- `AGENTFORGE_DB_URL`
- `AGENTFORGE_DB_USERNAME`
- `AGENTFORGE_DB_PASSWORD`
- `AGENTFORGE_SERVER_PORT`
- `AGENTFORGE_JWT_SECRET`：必填，至少 32 随机字节的 Base64；各环境不同，禁止提交真实值。
- `AGENTFORGE_JWT_ISSUER`：可选，必须是 URI，默认 `https://agentforge.local/core-api`；它只是 V1 本地签发者标识，不要求该域名可访问。
- `AGENTFORGE_JWT_TTL`：可选 ISO-8601 Duration，默认 `PT30M`。

生成本地 JWT secret：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

把输出只写入被忽略的 `.env`。`.env.example` 中的 `REPLACE_...` 不是可用密钥，服务对缺失、无法 Base64 解码或短于 32 字节的配置快速失败。规则见 `../00-governance/public-repository-security.md`。

## 预期启动顺序

1. `docker compose --env-file .env -f infra/compose.yaml up -d postgres redis`
2. Windows：`cd services/core-api` 后运行 `mvnw.cmd spring-boot:run`
3. macOS/Linux：运行 `./mvnw spring-boot:run`
4. 在 `services/agent-service` 创建虚拟环境并运行 `python -m pip install -e .[test]`。
5. 设置与 Java 相同的 `AGENTFORGE_AGENT_INTERNAL_TOKEN`，运行 `uvicorn agentforge_agent.main:app --reload --port 8000`。
6. 访问 `http://localhost:8080/actuator/health` 与 `http://localhost:8000/health`。

## 故障排查

- `Unsupported class file` 或 Java 版本错误：确认 `java -version` 为 21。
- 数据库连接失败：运行 `docker compose ... ps`，检查 5432 端口和 `.env`。
- Flyway 校验失败：检查是否修改了已执行迁移；不要使用 `ddl-auto=update` 绕过。
- API 错误：记录响应的 `X-Request-Id`，在 Core API 日志中搜索相同值。
- JWT 配置错误：检查 secret 是否已经替换、是否为 Base64、解码后是否至少 32 字节；不要把值贴入 Issue 或日志。
- Bearer 请求 401：检查 token 是否过期、issuer 与系统时间；登录重新获取 token，不要关闭签名校验。

## 当前验证状态

Java 21 下的 Maven 构建、单元测试与 Web 切片测试已通过。当前开发机的 Docker 系统服务无法启动，真实 PostgreSQL/Flyway 集成测试尚未运行；可在 Docker 权限正常的机器或 CI 上执行 `mvnw.cmd verify` 补齐。
