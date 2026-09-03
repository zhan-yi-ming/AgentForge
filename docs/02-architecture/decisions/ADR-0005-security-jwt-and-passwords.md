# ADR-0005：V1 认证、授权与密码存储

- 状态：Accepted
- 日期：2026-09-03
- 决策者：项目维护者与 AI 开发代理

## 背景

Day 1 API 没有认证，Project owner 来自请求参数。Day 2 必须在 Wiki / Task 写入前建立可信身份、资源所有权校验和安全密码存储，同时保持 V1 可在一个 Java 服务内学习和运行。

## 决策

1. Core API 使用 Spring Security 无状态 `SecurityFilterChain`；除注册、登录和健康检查外，API 默认需要认证。
2. Core API 同时签发和验证 HS256 JWT access token。签名密钥必须是至少 256 bit 随机值的 Base64 表示，通过 `AGENTFORGE_JWT_SECRET` 注入，不写入仓库。
3. token 默认 30 分钟有效，默认 issuer 为 URI `https://agentforge.local/core-api`，验证签名、`iss`、`exp`、`nbf`；`sub` 是用户 UUID，`roles` 只允许 `USER` / `ADMIN`。
4. 密码使用 Spring Security `DelegatingPasswordEncoder`，当前写入 `{bcrypt}`；数据库只保存哈希，不保存或记录原文。
5. owner 从已验证 token 的 `sub` 获取。Controller 把认证 actor 传给应用服务，应用服务仍然查询 Project 并校验 owner；ADMIN 可绕过 owner 判断，但不能绕过资源存在性和数据约束。
6. 不采用客户端可声明 owner、仅靠前端隐藏按钮或仅靠 URL 猜测防护。

## 为什么选择

- Spring Security Resource Server 提供标准 Bearer token 解析、签名和时效校验，避免自写 JWT 解析器。
- V1 只有一个 Core API，HS256 部署最少；到多服务或外部签发者阶段再评估非对称密钥 / OIDC。
- `DelegatingPasswordEncoder` 保留算法标识，当前使用 BCrypt，同时允许后续逐步升级哈希格式。
- 应用服务所有权检查让未来来自 Web、Agent 或内部接口的写入遵守同一规则。

## 被否决方案

- **Session / Cookie**：适合同源 Web，但 Day 3 Python 服务与后续 API 客户端需要清楚的 Bearer 边界；V1 选 JWT。
- **自定义 JWT 库和过滤器**：代码较短但容易遗漏算法、issuer、时钟和错误处理；使用 Spring Security 官方组件。
- **在 JWT 中保存完整权限快照**：token 会膨胀且角色变化难以及时生效；V1 只放最小角色，资源权限实时查库。
- **明文或可逆密码**：泄漏风险不可接受。
- **立即引入 OAuth / OIDC 服务**：超出 V1 Day 2，增加部署和学习成本。

## 影响与限制

- 所有既有业务 API 从匿名可用变为必须 Bearer token；这是进入安全 V1 的有意破坏性变化。
- Day 1 已存在且没有 `password_hash` 的用户不能登录，需要重新注册或未来的密码设置流程。
- access token 在到期前不能主动撤销；角色修改可能最多延迟 30 分钟生效。
- 单一对称密钥不适合让多个不互信服务签发 token。引入外部身份服务时新建 ADR 取代本决策。

## 失败排查

- 启动时报密钥缺失或长度不足：生成至少 32 随机字节，Base64 后设置 `AGENTFORGE_JWT_SECRET`。
- 所有 token 都返回 401：核对签发与验证的 secret、issuer、时间和 Base64 格式；不能在日志打印 token。
- 已登录却返回 403：核对 `roles` claim 映射以及 Project owner；用 `X-Request-Id` 关联服务日志。
- 密码一直不匹配：数据库值应有 `{bcrypt}` 前缀；不要改用 NoOp 编码器绕过。

## 参考

- [Spring Security JWT Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Spring Security Password Storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
