# 单机生产部署与运维

- 状态：Accepted
- 适用版本：V1.1
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

运行 `scripts/deploy/generate-production-env.sh <公网IP> <provider>` 生成随机数据库/JWT/内部密钥；只在服务器替换模型 key，文件保持 0600。也可参考 `.env.production.example` 手工创建，但部署前置检查会拒绝占位符、弱内部 token、非 Base64 JWT 或 URL 不安全的数据库密码。随后执行：

```bash
scripts/deploy/deploy.sh
scripts/deploy/health-check.sh
scripts/deploy/seed-demo.sh
```

构建按 core-api、agent-service、web、gateway 顺序执行，避免 2C4G 机器并行构建。Docker Compose v5 使用 `docker compose build <service>`；不要传入已不受支持的 `build --no-deps`，且只有显式指定 `--with-dependencies` 时才会连带构建依赖。Demo 初始化先停止公网 gateway，只在 Core API 容器内部临时开启注册；创建成功、恢复注册关闭后才重新开放 gateway。

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

首次启动先使用临时自签证书让 gateway 可加载配置，再以 ACME webroot 申请公网 IP 短期证书并 reload gateway。证书续期由 systemd timer 定期执行；健康检查必须确认 HTTPS 证书受信且未过期。获得域名后应改用域名证书并更新 issuer/入口地址。

## 故障处理

- `docker compose ps`：先看容器健康状态。
- `scripts/deploy/logs.sh <service>`：只查看指定服务最近日志，禁止复制包含 token 的完整请求。
- Core API unhealthy：检查 PostgreSQL 健康、Flyway、JWT Base64 长度和内部 token。
- Agent Chat 503：检查 provider、模型名、余额和 Agent 日志；紧急时把 provider 设为 `disabled`。
- 磁盘不足：检查 `docker system df` 和备份目录；只清理未使用镜像，不删除 named volume。
- 更新失败：运行 `rollback.sh`，再执行健康检查。
