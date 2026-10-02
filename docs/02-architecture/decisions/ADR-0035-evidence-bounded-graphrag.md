# ADR-0035：证据约束的有界 GraphRAG 检索

- 状态：Accepted
- 日期：2026-09-30
- 前置：[ADR-0010](ADR-0010-day-4-rag-boundary-and-ranking.md)、[ADR-0032](ADR-0032-neo4j-derived-graph.md)、[ADR-0034](ADR-0034-reviewable-entity-resolution.md)

## Context

关系型问题仅凭 Vector/BM25 容易漏掉多跳拓扑；图存储又是可重建投影，来源和人工消歧随时可能失效。允许 Python 直连 Neo4j 或由 LLM 生成 Cypher 会复制授权/证据规则并扩大读取范围。

## Decision

Java 提供受独立内部 token、真实用户及 ProjectAccess 保护的只读 GraphRAG 查询。Java 用固定候选与两跳遍历上限，重验每个实体、规范映射和证据来源；只返回同项目当前有效的 Wiki/Task 文档引用。Python 将图返回视为可用的检索候选，与既有 Vector/BM25 排名融合，并在统一预算内构造 Context；生成式模型仍只能读 Context，不能决定授权或写入。图依赖不可用时退回现有文本检索，权限或契约错误不降级为成功。不会增加图或业务数据写入。

## Alternatives and trade-offs

Java 完成全部排序会把概率性检索策略移入业务服务；Python 直接读取 Neo4j 则破坏既有信任边界。采用跨进程只读 DTO 会增加一次内部调用；固定上限可能漏掉大图中的远邻，且图与 PostgreSQL 无原子快照。读取时来源重验和最终引用筛选降低过期事实进入回答的风险。后续需用真实评测数据决定是否调整上限或排名，不预先宣称准确率改善。
