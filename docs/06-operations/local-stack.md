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

## V3-04 可选 Neo4j（Implemented）

图默认关闭。显式启用 compose profile graph、AGENTFORGE_GRAPH_ENABLED=true 和独立 GRAPH_PASSWORD 后运行；Neo4j 仅在容器内部暴露 Bolt，不公开端口，持久卷独立。Core graph uri 默认 bolt://neo4j:7687，username neo4j。首次图请求创建 schema v1 约束，需要约束管理和图读写权限。请勿把图密码提供给 Python。图关闭/宕机不影响现有服务启动和 Chat/Wiki/Task 健康。

图是派生数据，重新导入前 owner/admin 调用 DELETE /api/v1/projects/{projectId}/graph?confirm=true，再通过实体/关系 API 按稳定 ID 重建；自动抽取与生命周期编排留给 V3-05。
启用步骤：先在本地非提交 env 中设置 AGENTFORGE_GRAPH_PASSWORD（独立强密码）及 AGENTFORGE_GRAPH_ENABLED=true，然后使用现有本地启动命令增加 `--profile graph`。Neo4j heap 128..256 MiB、page cache 128 MiB，启动健康检查只确认内部 Bolt 端口，真正鉴权及 schema 由首次 Java graph 请求验证。Neo4j 不列入 Core depends_on，启动竞态期间 graph 返回 503，可待健康后重试。生产 compose 同样提供内部 app 网络 graph profile，无公开 Neo4j 端口。

图清理状态保存在 PostgreSQL `graph_project_state`。运维可只读查询 `project_id,generation,resetting,updated_at` 并关注 `resetting=true`；有效清理五分钟内拒绝重叠请求，超过五分钟后重新调用同一 `DELETE graph?confirm=true` 会以新 generation 接管。当前租约没有心跳；若一次清理可能超过五分钟，应暂停该项目写入并在接管清理完成后调用 extraction rebuild，成功清理或失败后重试也以显式 rebuild 恢复完整派生投影。不要手工删除 canonical/event 审计行。

若 Docker Hub pull 卡住，可在正确配置 Desktop/Containers proxy 后用官方完整地址 `docker pull registry-1.docker.io/library/neo4j:5.26-community`，成功后 `docker tag registry-1.docker.io/library/neo4j:5.26-community neo4j:5.26-community`。两者为同一版本/镜像，不使用第三方替代镜像。
