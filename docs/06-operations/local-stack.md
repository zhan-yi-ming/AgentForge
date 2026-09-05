# 本地基础设施

- 状态：Implemented
- 编排文件：`infra/compose.yaml`

## 完整 V1 栈

`infra/compose.yaml` 统一启动 `postgres`、`core-api`、`agent-service` 和 `web`。`redis` 仅作为可选 profile 保留，不参与 V1 业务链路。Web 容器提供静态 React 页面，并把 `/api` 反向代理到 Core API。

## PostgreSQL

Day 1 的必要依赖，保存 User 和 Project。Compose 使用命名卷持久化数据，并通过 `pg_isready` 健康检查。默认端口为 5432，可通过环境变量调整。

## Redis

为后续短期状态与缓存预留。V1 不连接 Redis，默认完整栈也不启动它；只有显式启用 `optional` profile 才启动。

## 启停

```text
docker compose --env-file .env -f infra/compose.yaml up --build -d
docker compose --env-file .env -f infra/compose.yaml ps
docker compose --env-file .env -f infra/compose.yaml logs -f web core-api agent-service postgres
docker compose --env-file .env -f infra/compose.yaml down
```

`down` 保留命名卷。只有明确希望删除本地数据时才使用带卷删除的命令；该操作具有破坏性，不作为常规排错步骤。

## 安全说明

示例密码仅用于本地开发。共享、演示或生产环境必须使用密钥管理与独立凭据，且不能把真实值写进 `.env.example` 或 Git。
