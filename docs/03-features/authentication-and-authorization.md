# 注册、登录与基础授权

- 状态：Implemented
- 所属阶段：V1 / Day 2
- 相关 ADR：ADR-0005

## 用户价值与场景

用户通过邮箱和密码建立身份，登录后获得短期 access token，并只能访问自己拥有的项目资源。ADMIN 用于演示和运维级跨项目排查，不提供公共提权接口。

## 范围

- 邮箱、展示名、密码注册；邮箱规范化和唯一约束。
- 邮箱、密码登录；成功后签发 Bearer JWT。
- `USER` / `ADMIN` 两种基础角色。
- `/users/me` 返回当前用户；业务 API 默认需要认证。
- 未认证统一 401、已认证但无权限统一 403，均使用 Problem Detail 且包含 request ID。

## 非目标

Refresh Token、主动登出、找回密码、邮箱验证、验证码、锁定策略、OAuth / SSO、成员邀请和细粒度权限均不在 Day 2。

## 关键流程

### 注册

1. 校验 email、displayName 和 8–72 字符密码。
2. 规范化邮箱，拒绝已存在邮箱。
3. 使用 `DelegatingPasswordEncoder` 生成 `{bcrypt}` 哈希；原始密码不会进入 Entity、响应或日志。
4. 创建 `USER`，返回用户摘要与 access token，HTTP 201。

### 登录

1. 规范化邮箱并查找可登录用户。
2. 使用 PasswordEncoder 做恒定接口的哈希匹配；账号不存在、旧 passwordless 账号和密码错误都返回相同 401 文案。
3. 签发默认 30 分钟 access token，返回 token 类型、秒数和用户摘要。

### 授权

1. Spring Security 校验 Bearer token 签名、issuer 和时效。
2. `sub` 转为用户 UUID，`roles` 映射为 `ROLE_USER` / `ROLE_ADMIN`。
3. Controller 只传递已验证 actor；应用服务读取 Project 并执行 owner-or-admin 判断。
4. 跨用户资源返回 403，不通过 404 隐藏已知 ID；响应不得泄露资源内容。

## 接口与数据

接口见 `../04-api/core-api.md`，数据字段见 `../02-architecture/data-architecture.md`：

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `GET /api/v1/users/me`

## 安全与排查

- JWT 缺少 `sub` 或 `sub` 不是 UUID 时统一按无效凭证处理，返回 401，不能泄漏为空指针导致 500。
- JWT secret 在配置绑定阶段必须能以标准 Base64 解码且不少于 32 字节；非法值必须让应用以可读错误快速启动失败。

- JWT secret 只来自环境变量，规则见 `../00-governance/public-repository-security.md`。
- 登录失败不区分邮箱不存在与密码错误，日志只记录 request ID 和失败类别，不记录密码或 token。
- 401：检查 Authorization 格式、token 到期、issuer 与本机时间。
- 403：检查 actor 角色和项目 owner，不允许通过改请求 ownerId 绕过。

## 测试与验收

- 注册哈希不等于原始密码，且可由 PasswordEncoder 匹配。
- 重复邮箱 409，弱/无效输入 400，错误密码统一 401。
- 无 token 与无效 token 401，跨用户 403，owner 和 ADMIN 成功。
- 响应和日志不返回 passwordHash 或 secret。

## 已知限制

token 不可主动撤销，角色变更存在最长 30 分钟缓存窗口。Day 1 passwordless 用户不可登录。ADMIN 只能通过受控数据库操作配置，后续需要专门的管理流程。
