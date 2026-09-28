# ADR-0033：持久来源待办驱动 Neo4j 图抽取

- 状态：Accepted
- 日期：2026-09-28
- 前置：[ADR-0032](ADR-0032-neo4j-derived-graph.md)

## Context

V3-04 的 Neo4j 投影由 Java 手工写入，Wiki/Task PostgreSQL 事务与图事务不能原子提交。仅在业务保存后直接调用 Neo4j 会在进程崩溃或图故障时丢失同步意图；仅靠读时过滤能防止旧证据出现在回答中，不能清理旧投影。

## Decision

在 PostgreSQL 建 `graph_source_sync`，以 project/type/id 为唯一键。Wiki/Task 创建、更新、删除在其业务事务内登记待办；迁移回填既有来源。Java worker 事务锁定一条待办，读取当前业务来源而非历史事件 payload，在单次 Neo4j 项目锁事务中替换该来源的自动投影，图提交后删除待办。Neo4j 失败使 PostgreSQL 事务回滚，下一轮重试；重复处理幂等。图禁用时不处理待办。手工重建重新登记全部当前来源。

抽取采用显式内容语法和原文位置，不使用 LLM 判断关系。生成实体 ID 来源作用域化，手工证据以独立 origin 保留。Java 保持权限、校验与图写入所有权，Python/LLM 不拥有数据库凭据或最终写入权。

## Alternatives / Trade-offs / Consequences

同步双写没有故障后恢复能力；直接引入 Kafka 对现有单体及此节点规模过重；周期性全项目扫描缺少明确待办状态和有界增量。持久待办增加一张表、后台轮询和运维可见性，但保留失败并允许重建。持锁跨库处理有短时资源占用，单条 Neo4j 事务设有 5 秒超时，批次有上限；吞吐升级留待真实负载证据。PostgreSQL/Neo4j 仍为最终一致，不声称原子一致。V3-06 才处理名称消歧，V3-07 才用于 GraphRAG 检索。

worker 在 graph application 中使用仅内部的、project/type/id 约束的 PostgreSQL 只读查询访问 Wiki/Task 当前事实；这是该同步流程唯一允许的跨模块持久化读取，不暴露 HTTP，不让 Python 或浏览器绕过授权。抽取只从已在同一业务事务中登记的来源执行。来源 Wiki/Task 图实体可与 V3-04 手工创建共享稳定 ID；自动替换不得把手工实体标记为自动，也不得删除其他来源或手工 evidence。Project/业务事实仍由 PostgreSQL 拥有。

删除来源时需清理所有引用该来源的 evidence（包括先前手工 evidence），以及以该业务对象为端点的关系；这些引用已无有效 PostgreSQL 事实。来源更新替换该来源的自动 evidence，并清理引用其旧版本的手工 evidence；独立来源以及当前版本的手工 evidence 保留。匹配行的 excerpt 沿用 V3-04 的 1..2000 契约，越界时整源处理失败且不发布部分投影。
