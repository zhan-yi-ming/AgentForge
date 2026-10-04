# ADR-0041：手工图事实纳入一致灾备集

- 状态：Accepted
- 日期：2026-10-04
- 前置：[ADR-0032](ADR-0032-neo4j-derived-graph.md)、[ADR-0034](ADR-0034-reviewable-entity-resolution.md)

## Context

自动来源抽取可以从 PostgreSQL Wiki/Task 重建，但 V3-04 Graph API 还保存用户选择的 externalId、显示名、关系类型和多条 evidence。PostgreSQL 没有这些手工图事实的完整副本，却保存以 Neo4j 稳定实体 ID 为目标的规范映射。把整个 Neo4j 卷视作可丢弃缓存，会同时丢失不可重建输入并形成悬空跨库引用。

Neo4j Community 不支持在线数据库备份；复制运行中的 `/data` 也不能提供一致归档。为手工图再建立 PostgreSQL 双写事实表会引入跨库提交和冲突恢复协议，超出本修复范围。

## Decision

Neo4j 的自动来源投影仍是可重建派生数据；经公共 Graph API 写入且不能由当前 Wiki/Task 抽取规则完整重现的属性、关系与 evidence 是需要灾备的持久图事实。生产备份集同时包含 PostgreSQL custom dump 与 Neo4j 默认数据库的离线 dump。

备份先停止 gateway、Core API 和 Agent Service，使业务、索引、checkpoint、规范映射与图写入静默；随后取得 PostgreSQL dump，停止受管 Neo4j 容器并用同版本 `neo4j-admin database dump` 归档。备份目录只有在两个必需步骤和 SHA-256 清单成功后才发布。恢复先校验清单，在写入面停机期间用 `pg_restore` 和同版本 `neo4j-admin database load --overwrite-destination=true` 恢复；任一步失败都不自动开放应用写入。

恢复验收必须至少覆盖一个手工 Service、一条手工关系、多个 evidence，以及 PostgreSQL 中指向该稳定实体 ID 的已确认规范映射。自动重建不能替代该演练。

## Alternatives / Trade-offs / Consequences

选择维护窗口会造成短暂停机，但在 Community 版和单机部署上提供可解释的跨存储静默点。只备份 named volume 的在线文件被拒绝；只备份 PostgreSQL 被拒绝；立即实现 PostgreSQL 手工图事实镜像也被拒绝，因为它扩大写路径和一致性协议。未来若改用支持在线一致备份的版本或将所有手工事实迁入单一事实库，应以新 ADR 替代本决策。
