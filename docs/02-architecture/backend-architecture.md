# Java Core API 架构

- 状态：Accepted
- 技术：Java 21、Spring Boot 3.5.16、Spring MVC、Spring Security、OAuth2 Resource Server JWT、Spring Data JPA、Flyway、PostgreSQL
- 相关 ADR：`decisions/ADR-0003-java-modular-monolith.md`、`decisions/ADR-0005-security-jwt-and-passwords.md`

## 选择：按业务能力分包的模块化单体

Java 服务是一个部署单元，但代码先按 `user`、`project`、后续 `wiki`、`task`、`security` 等业务能力分组。每个能力内部再区分 API、应用、领域和基础设施职责。

```text
com.agentforge.core
├─ AgentForgeApplication.java
├─ user/
│  ├─ api/             # Controller、HTTP DTO、请求/响应映射
│  ├─ application/     # 用例编排、事务边界
│  ├─ domain/          # Entity、领域规则
│  └─ infrastructure/  # Spring Data Repository、持久化细节
├─ security/           # 注册登录、JWT、认证 actor 与 HTTP 安全边界
├─ wiki/               # 项目 Wiki Page 用例与持久化
├─ task/               # 项目 Task 用例与持久化
├─ project/
│  ├─ api/
│  ├─ application/
│  ├─ domain/
│  └─ infrastructure/
└─ shared/
   ├─ error/           # 统一 Problem Detail 错误
   └─ web/             # request_id 等横切 Web 能力
```

## Day 1 四类职责

### Controller

接收 HTTP、校验请求格式、调用一个应用服务用例、映射状态码。Controller 不写业务规则、不直接访问 Repository。

### Service

表达创建用户、创建项目、查询项目等用例，组织领域校验和 Repository，定义事务边界。Service 不依赖 Servlet 或 HTTP 状态码。

### Repository

封装持久化查询。业务层只通过语义化方法访问数据，不在 Controller 中拼查询。

### Entity

表达业务数据和实体级不变量。Day 1 使用 JPA 注解减少样板代码，但禁止把 Entity 直接作为 HTTP 请求或响应，避免数据库结构绑死公共 API。

## 模块边界

- `project` 保存 `ownerId`，不使用跨模块 JPA 对象图；数据库外键保证引用完整性。
- `project` 通过稳定的用户查询入口确认 owner 存在，不读取 `user` 的 Repository 实现细节。
- 共享目录只放真正跨业务的技术能力，不能演变为“所有东西都放 shared”。
- 后续模块默认不能循环依赖。出现依赖争议先写 ADR。
- `security` 通过 user 模块的公开账号入口读取凭据，不直接访问 user Repository 实现。
- `wiki` 与 `task` 通过 project 模块的公开授权入口校验 actor，不复制 owner 判断，也不直接访问 project Repository 实现。
- 认证只是身份入口；资源权限必须在应用服务里结合数据库中的 Project 再次判断。

## Day 2 安全请求链路

```text
HTTP Bearer token
  -> Spring Security 验证签名 / issuer / 时效
  -> JwtAuthenticationConverter 映射 USER / ADMIN
  -> Controller 构造 AuthenticatedActor
  -> Application Service 调用 ProjectAccess
  -> owner-or-admin 校验
  -> Repository 确定性读写
```

注册 / 登录不进入 Bearer 校验，但密码只在请求 DTO 到 PasswordEncoder 的短链路中存在。401 / 403 由安全处理器输出 Problem Detail；业务 400 / 404 / 409 继续由统一异常处理器输出。

## API 与错误

- 使用 `/api/v1` 前缀。
- 输入 DTO 使用 Bean Validation。
- 找不到资源返回 RFC 9457 Problem Detail 风格的 `404`。
- 唯一约束冲突返回 `409`，无效输入返回 `400`。
- 响应包含或回显 `X-Request-Id`，用于从客户端定位日志。

## 事务

写用例在 Service 层开启事务；只读查询标记只读事务。数据库唯一约束是并发下的最终保障，应用层预检查只用于提供更友好的错误。

## 演进路线

- Day 2：实现 security、wiki、task 与基础 RBAC。
- Day 3：定义 Core API ↔ Agent Service 契约。
- V2：根据真实复杂度考虑 Spring Modulith 的结构验证、模块事件和更严格的可见接口。

## 参考

- [Spring Modulith](https://github.com/spring-projects/spring-modulith)：官方建议把业务模块作为应用根包的直接子包，并支持验证模块结构。当前采用其分包思想，暂不引入额外运行时复杂度。
- [Spring Modulith fundamentals](https://github.com/spring-projects/spring-modulith/blob/main/src/docs/antora/modules/ROOT/pages/fundamentals.adoc)：参考模块公开 API 与内部包的边界思想。
- [Spring PetClinic REST](https://github.com/spring-petclinic/spring-petclinic-rest)：参考 Controller、Service、Repository 及 DTO 分离的可学习结构。
