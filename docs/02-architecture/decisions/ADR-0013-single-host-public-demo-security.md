# ADR-0013：单机公网 Demo 的入口、配额与发布边界

- 状态：Accepted
- 日期：2026-09-05

## 背景

V1.1 要在一台 2C4G ECS 上通过公网 IP 向 HR 提供低流量 Demo。该环境必须避免暴露内部端口、浏览器密钥和无上限模型费用，同时保留足够简单、可人工理解的升级与回滚路径。

## 决策

1. 使用 Docker Compose 管理 PostgreSQL、Core API、Agent Service、Web 与 Nginx gateway。只有 gateway 发布 80/443；服务间通过私有 Docker network 和容器 DNS 通信。
2. TLS 在 gateway 终止。无域名阶段使用受信 CA 签发的短期公网 IP 证书，证书在宿主机自动续期；HTTP 只保留 ACME challenge 与 HTTPS 跳转。
3. Nginx 使用客户端 IP 对登录和 `/api/` 设置令牌桶限速；Core API 使用 PostgreSQL 原子日计数对已认证用户限制 AI Chat，保证容器重启后配额仍存在。
4. 生产环境通过配置关闭公共注册。Demo 用户由受控脚本通过公共 API 在临时开启注册时初始化，角色固定为 USER，并创建自己的演示 workspace；脚本生成的密码只显示一次。
5. Agent Service 为所有兼容模型设置最大输出 Token。完整 V2 风险引擎和策略中心不提前引入。
6. 发布使用可读 Git commit/tag；更新前备份 PostgreSQL，构建按服务顺序执行，失败或验收不通过时切回上一 commit。Docker 日志设置大小与保留文件数上限。

## 取舍

数据库配额比内存计数多一次轻量写入，但能跨进程、跨重启正确执行，且无需提前引入 Redis。Nginx IP 限速不能准确表示用户身份，因此只作为抗突发第一层；费用边界由认证后的数据库配额保证。短期 IP 证书续期频率高于域名证书，但在暂不购买域名的约束下仍可提供可信 HTTPS。

## 后果与限制

- 当前日界线使用 UTC，文档和 UI 必须按 UTC 解释配额重置。
- 日配额默认只在生产显式启用；本地测试可设为 0 表示关闭。
- 单 IP 后面的多位面试者会共享 Nginx 突发额度；限速值应保留合理 burst。
- Demo 数据自动每日恢复不在本次范围，使用人工可重复初始化/重置命令。
