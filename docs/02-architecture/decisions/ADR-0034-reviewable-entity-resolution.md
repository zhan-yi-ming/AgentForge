# ADR-0034：可撤销的实体消歧覆盖层

- 状态：Accepted
- 日期：2026-09-29
- 前置：[ADR-0032](ADR-0032-neo4j-derived-graph.md)、[ADR-0033](ADR-0033-durable-graph-source-sync.md)

## Context

V3-05 的来源作用域图实体与原文 evidence 可追溯，但同一真实 Service/API/Issue 可能分裂；直接按名称合并会误合并，直接重接 Neo4j 关系会损坏 provenance 与回滚能力。LLM 建议具有不确定性，不能成为业务写入授权。

## Decision

保留 Neo4j 原始 GraphEntity/GraphRelation/GraphEvidence 及稳定 ID。PostgreSQL 作为规范映射、别名、人工确认与撤销审计的事实所有者；规范 ID 指向本项目当前有效、同类型的原始实体。Java 验证 ProjectAccess、来源当前版本、类型与 CAS 后写入，读取时再次验证并隐藏失效映射。锚点来源同 ID 变更后，人工确认可 CAS 刷新规范实体的锚点来源版本；每个成员保存其确认时的锚点版本，避免刷新锚点时旧合并自动恢复。规范锚点不作为其他规范实体的成员，避免循环/链式映射。所有合并均由认证用户确认；撤销成员映射即可恢复原视图，不物理合并、重接或删除原图边。跨库不声明原子事务。

Java 从 Neo4j 有界读取当前项目候选并做确定性规则排序；只把允许的候选 ID 与名称交给 Python Agent。Python 在现有内部鉴权边界下计算 embedding 相似度、可调用配置模型给出候选选择/abstain 与置信度；Java 把返回值当不可信建议，核验 ID 必须来自候选集合，模型不可用时 fail closed 为人工审阅。最终写入、权限、并发和撤销始终在 Java。

## Alternatives / Trade-offs / Consequences

按名称自动 merge 无法处理重名；直接修改原始 Neo4j ID/边会使 evidence 回滚困难；仅保存别名文本而没有人工决策历史无法审计 false merge。覆盖层增加 PostgreSQL 表与查询时来源重验，也使 V3-07 必须显式消费规范映射，但能保留原文证据和可撤销性。候选上限与人工确认优先保障安全，可能漏掉规模较大项目的弱相似候选；应以测试和真实负载改进候选检索，不把低置信度推断自动落库。
