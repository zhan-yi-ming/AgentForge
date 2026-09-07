# 公网 Demo 保护

- 状态：Accepted
- 阶段：V1.1
- 相关 ADR：ADR-0013、ADR-0015

## 用户价值

维护者可以把 AgentForge 公网地址安全地交给面试官体验，同时把访问范围、请求突发和每日模型费用控制在明确边界内。

## 行为

- 生产环境默认拒绝新用户注册；登录保持可用。
- Demo 账号是普通 USER，只能访问自己创建的项目、Wiki 和 Task。
- 固定面试账号是可重置、无敏感数据的普通 USER；登录页仅提示账号是简历上的邮箱、密码是微信号，不显示或内置凭据明文。初始化脚本同时生成服务器专有的随机备用账号。
- 公开固定凭据不得用于管理员、维护、数据库、JWT、内部服务、模型 Provider 或真实业务环境；固定账号仍受项目隔离、每日 AI 配额和 Nginx IP 限速。
- 每个已认证用户每天最多发起配置数量的 AI Chat；达到限制返回 HTTP 429 和可读 Problem Detail，不调用模型。
- UTC 日期变化后自动进入新的计数周期。
- Nginx 对登录使用更严格的 IP 限速，对其他 API 使用一般限速。
- 模型响应受 `max_tokens` 限制，检索上下文继续受字符预算限制。

## 配置

- `AGENTFORGE_REGISTRATION_ENABLED`：是否开放注册；生产为 `false`。
- `AGENTFORGE_AI_DAILY_LIMIT`：每用户 UTC 日 Chat 上限；`0` 表示关闭限制，仅用于本地开发。
- `AGENTFORGE_AGENT_LLM_MAX_TOKENS`：单次模型最大输出 Token。
- `AGENTFORGE_DEMO_FIXED_EMAIL`、`AGENTFORGE_DEMO_FIXED_PASSWORD`：服务器固定 Demo 凭据；仅由部署和 seed 使用，不得进入浏览器产物。任何其他生产凭据同样不得进入浏览器或 Git。
- 固定账号密码同步使用 Compose PostgreSQL 镜像提供的 `pgcrypto`，由初始化 `POSTGRES_USER` 创建扩展并只更新匹配邮箱且角色为 `USER` 的记录；脚本启用错误即停并在同步后通过真实登录验证结果。

## 测试与验收

- 注册关闭返回 403，登录不受影响。
- 限额 N 次均可进入 Chat；第 N+1 次返回 429，且下游调用次数仍为 N。
- 并发计数由数据库唯一键与条件更新保证不会超过限额。
- 配额错误不包含密码、token、模型 key 或内部连接信息。
- 固定账号重复初始化会把密码同步为当前部署配置，保持可登录且不复制 workspace；随机备用账号每次独立生成。两个账号的权限与 AI 日配额相互独立。
- 环境校验只允许指定公开 Demo 密码使用 8 字符；任何其他固定 Demo 密码仍要求 12–72 字符。
