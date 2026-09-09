# ADR-0023：单机 Grafana、Loki 与 Alloy 日志界面

- 状态：Accepted
- 日期：2026-09-09
- 决策者：项目维护者

## 背景

AgentForge V2 stable 的生产容器使用带上限的 Docker JSON 日志，维护者只能通过 SSH 和 `docker compose logs` 查看。V3 前需要一个最快可交付、可从浏览器按服务与 `request_id` 查询的日志界面。现有服务器为单机小规格环境，不能为了日志查询改变业务服务边界或引入复杂集群。

## 备选方案

1. 只保留 `docker compose logs`：没有新增资源和攻击面，但跨服务检索与历史浏览效率低。
2. Grafana + Loki + Alloy：开源组件成熟，能直接发现 Docker 容器、保留服务标签并提供浏览器查询；代价是三个常驻容器、Docker socket 读取权限和本地磁盘占用。
3. Elasticsearch/OpenSearch + Dashboards：全文检索能力强，但对当前 2C4G 单机和日志量过重，部署与维护成本最高。
4. 托管日志平台：减少本机组件，但引入外部账号、网络、费用和日志出站合规问题，不能作为默认自托管路径。

## 决策

采用单副本 Grafana + Loki + Alloy：

- Grafana 只经现有 Nginx gateway 的 `/grafana/` 子路径访问，关闭匿名访问和用户自助注册，管理员凭据只从受保护的生产环境文件注入。
- Loki 使用 TSDB 索引和本地 filesystem named volume，启用 7 天 retention；它是可丢弃的运维日志副本，不是业务事实库。
- Alloy 使用官方推荐的 Docker discovery / Docker log source，只保留 Compose project `agentforge`，把 Compose service 映射为低基数 `service` 标签。
- 业务容器不依赖观测容器；观测栈停止时业务继续运行。
- 不安装 Loki Docker logging driver，避免日志后端故障改变业务容器日志写入路径。

## 结果

维护者获得一个无需 SSH 的日志界面和预置查询入口，可按服务、关键词和 `request_id` 关联日志。新增约三个常驻容器和两个持久卷；文件系统 Loki 不提供高可用，磁盘容量仍需外部巡检。

Alloy 对 Docker socket 的只读挂载不构成安全隔离：socket 一旦被滥用可能获得高权限。当前以官方采集方式、固定镜像、只读根文件系统、先移除全部 capabilities 后仅恢复写入镜像 UID 473 数据卷所需的 `DAC_OVERRIDE`、禁止提权、内部网络和最小 project 过滤接受该风险。若未来进入多租户或更高安全等级，应改为受限 socket proxy、主机级受控 collector 或远程日志出口，并用新 ADR 取代本决策。

## 取代关系

无。
