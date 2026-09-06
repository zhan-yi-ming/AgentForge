# 单机生产部署与运维

- 状态：Accepted
- 适用版本：V1.2
- 平台：Ubuntu 22.04 x86_64，Docker Compose v2

## 网络与目录

公网安全组仅开放维护来源的 22，以及公网 80/443。Compose 只有 `gateway` 发布主机端口；PostgreSQL、Core API、Agent Service 和 Web 不声明 `ports`。

服务器约定目录：

```text
/opt/agentforge/repo       Git 工作树
/opt/agentforge/env/.env   真实运行配置（0600，不进入 Git）
/opt/agentforge/backups    PostgreSQL 备份
/opt/agentforge/tls        ACME 证书与续期状态
```

## 初始化

以 root 执行 `scripts/deploy/bootstrap-ubuntu.sh`：安装 Docker Engine 与 Compose plugin、配置 4 GiB Swap、创建目录和 Docker 日志默认上限。脚本可重复执行。

## 配置与首次部署

运行 `scripts/deploy/generate-production-env.sh <公网IP或域名> <provider>` 生成随机数据库/JWT/内部密钥，并写入登录页公开展示的固定 Demo 邮箱/密码；只在服务器替换模型 key，文件保持 0600。`PUBLIC_HOST` 接受 IPv4、完整合法的 IPv6 或不带协议和路径的 DNS 域名；IPv6 在 `PUBLIC_HOST` 中不写方括号，但生成或手工填写 `AGENTFORGE_JWT_ISSUER` URL 时必须写成 `https://[IPv6]/core-api`。固定 Demo 凭据不是秘密，只能用于普通 USER 的无敏感演示 workspace；不得复用于管理员或任何服务密钥。除这组明确公开的 8 字符密码外，其他自定义固定 Demo 密码仍须为 12–72 字符。也可参考 `.env.production.example` 手工创建；部署前置检查会拒绝占位符、非法公网主机、弱内部 token、非 Base64 JWT、URL 不安全的数据库密码或不合格的固定演示凭据。随后执行：

```bash
scripts/deploy/deploy.sh
scripts/deploy/health-check.sh
scripts/deploy/seed-demo.sh
```

从 V1.2 旧固定账号迁移时，Git 更新不会覆盖服务器私有 `.env`。维护者必须把其中两个 `AGENTFORGE_DEMO_FIXED_*` 值同步为登录页公开值，再运行 `scripts/deploy/seed-demo.sh` 创建/复用新普通 USER workspace；确认新账号可登录后，可保留旧账号作为临时回退或按受控数据流程停用。若未执行这一步，新登录页展示的公开账号不会在既有数据库中自动出现。

构建按 core-api、agent-service、web、gateway 顺序执行，避免 2C4G 机器并行构建。Docker Compose v5 使用 `docker compose build <service>`；不要传入已不受支持的 `build --no-deps`，且只有显式指定 `--with-dependencies` 时才会连带构建依赖。Demo 初始化先停止公网 gateway，只在 Core API 容器内部临时开启注册；创建或复用固定 USER workspace，并创建随机备用 USER workspace，恢复注册关闭后才重新开放 gateway。脚本只输出固定邮箱与随机备用凭据，不回显固定密码。

Nginx 对 `/agent/chat/stream` 关闭响应缓冲和缓存，并保持长于模型请求预算的读取超时；其余 API 继续使用默认代理策略。部署后的流式验收必须证明多个 delta 能在 complete 前到达，而不只是最终正文正确。

## 日常命令

```bash
scripts/deploy/health-check.sh
scripts/deploy/logs.sh all
scripts/deploy/logs.sh core-api
scripts/deploy/backup.sh
scripts/deploy/update.sh
scripts/deploy/rollback.sh
```

`update.sh` 先备份，再 fast-forward 拉取部署分支、顺序构建、启动并验收。`rollback.sh` 使用更新前保存的 commit，数据库迁移必须保持向后兼容；脚本不会删除 volume。

## TLS

首次启动先使用与 `PUBLIC_HOST` 类型匹配的临时自签证书让 gateway 可加载配置，再以 ACME webroot 申请受信证书并 reload gateway。IPv4/IPv6 使用 Certbot `--ip-address` 与 short-lived profile；域名使用 `-d`。当 `PUBLIC_HOST` 是普通根域名时，证书和 Nginx 同时包含该域名与动态派生的 `www.` 子域；当它本身以 `www.` 开头时不再追加，IP 模式也不追加。`PUBLIC_WWW_HOST` 由部署脚本运行时派生，不需要写入服务器 `.env`。证书固定使用 `PUBLIC_HOST` 作为 Certbot cert-name，因此 live 目录始终是 `/opt/agentforge/tls/letsencrypt/live/${PUBLIC_HOST}`，与同步脚本兼容。

域名首次申请前，必须确认根域名以及自动包含的 `www` 域名都已通过 A/AAAA 记录指向当前服务器，且公网 80 可访问 ACME webroot。然后在 `/opt/agentforge/env/.env` 设置 `PUBLIC_HOST`，同步更新 `AGENTFORGE_JWT_ISSUER`，执行：

```bash
scripts/deploy/deploy.sh
scripts/deploy/tls-issue.sh
scripts/deploy/install-tls-timer.sh
scripts/deploy/health-check.sh
```

`tls-issue.sh` 成功后立即调用 `tls-sync.sh`，把 live 目录中的 `fullchain.pem`、`privkey.pem` 复制到 `/opt/agentforge/tls/current/` 并 reload gateway Nginx。

从旧 IP 证书切换前可先运行与 `tls-renew.sh` 相同挂载的 `certbot certificates` 查看 Certificate Name。旧脚本通常以 IP 作为 lineage 名；若实际名称不同，先运行新版 `tls-issue.sh` 以当前 `PUBLIC_HOST` 和显式 cert-name 重新签发，再依赖 timer 续期。

公网 IP 证书是短期证书，域名证书也由相同 timer 管理；到期日不是需要人工重装的日期。服务器上的 `agentforge-tls-renew.timer` 会定期调用 `certbot renew`；Certbot 从既有 renewal 配置恢复 IP 或完整域名集合，成功后 `tls-sync.sh` 按稳定 cert-name 将新证书复制到 gateway 使用目录并 reload Nginx。发布和日常巡检使用：

```bash
systemctl is-enabled agentforge-tls-renew.timer
systemctl is-active agentforge-tls-renew.timer
systemctl list-timers --all agentforge-tls-renew.timer
systemctl start agentforge-tls-renew.service
journalctl -u agentforge-tls-renew.service --since "7 days ago" --no-pager
scripts/deploy/health-check.sh
```

如果 timer 未启用，执行 `systemctl enable --now agentforge-tls-renew.timer`。如果续期服务失败，先检查 80 端口仍允许公网访问、DNS/公网 IP 未变化、gateway 和 ACME webroot 是否健康，再查看上述 journal；修复后重新启动续期 service 并运行健康检查。不得等到证书过期后才处理告警。

## 故障处理

- `docker compose ps`：先看容器健康状态。
- `scripts/deploy/logs.sh <service>`：只查看指定服务最近日志，禁止复制包含 token 的完整请求。
- Core API unhealthy：检查 PostgreSQL 健康、Flyway、JWT Base64 长度和内部 token。
- Agent Chat 503：检查 provider、模型名、余额和 Agent 日志；紧急时把 provider 设为 `disabled`。
- 磁盘不足：检查 `docker system df` 和备份目录；只清理未使用镜像，不删除 named volume。
- 更新失败：运行 `rollback.sh`，再执行健康检查。
