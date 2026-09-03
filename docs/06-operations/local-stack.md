# 本地基础设施

- 状态：Accepted
- 编排文件：`infra/compose.yaml`

## PostgreSQL

Day 1 的必要依赖，保存 User 和 Project。Compose 使用命名卷持久化数据，并通过 `pg_isready` 健康检查。默认端口为 5432，可通过环境变量调整。

## Redis

为 V1 后续的短期状态与缓存预留。Day 1 Java 不连接 Redis；启动它只是验证整体本地拓扑，不代表相关功能已实现。

## 启停

```text
docker compose --env-file .env -f infra/compose.yaml up -d postgres redis
docker compose --env-file .env -f infra/compose.yaml ps
docker compose --env-file .env -f infra/compose.yaml logs postgres
docker compose --env-file .env -f infra/compose.yaml down
```

`down` 保留命名卷。只有明确希望删除本地数据时才使用带卷删除的命令；该操作具有破坏性，不作为常规排错步骤。

## 安全说明

示例密码仅用于本地开发。共享、演示或生产环境必须使用密钥管理与独立凭据，且不能把真实值写进 `.env.example` 或 Git。
