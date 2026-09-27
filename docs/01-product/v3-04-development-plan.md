# V3-04 开发计划：Neo4j Graph Domain Model

- 状态：Planned；Start Gate 待用户确认
- 路线依据：v2-v3-node-roadmap.md 的 V3-04
- 前置：V3-02/V3-03 审核修复完成、验证及远端提交核验

## 目标与现状

建立可追溯的项目实体和关系写入/查询基础。PostgreSQL 保持业务事实唯一来源；现有 Wiki 链接图仅展示页面引用，不等同于本节点的领域图。Python 只产生候选意图，Java 保持权限、校验及写入控制。当前不存在 Neo4j 领域实现；本文件不改变运行能力。

## 图模型草案

| 类型 | 稳定 ID 与来源 |
| --- | --- |
| Project | projectId，对应已存在 Java Project |
| Wiki / Task | projectId + 类型 + 现有业务 UUID；携带 sourceVersion |
| Service / API / Issue | projectId + 类型 + 显式稳定 externalId；名称只是展示属性 |

节点统一带 projectId、entityId、type、displayName、source。Service/API/Issue 是图投影实体，不新增这些业务系统或 CRUD 产品能力。相同名称不自动合并，alias 和实体消歧留给 V3-06。

| 关系 | 方向与允许端点 |
| --- | --- |
| CONTAINS | Project → Service/API/Wiki/Task/Issue |
| EXPOSES | Service → API |
| DESCRIBES | Wiki → Service/API/Task/Issue |
| MODIFIES | Task → Service/API/Wiki |
| AFFECTS | Issue → Service/API/Task |

每条关系必须带 source、evidence、confidence（有限数值 0–1），并带 sourceType/sourceId/sourceVersion、可选 sourceChunk、证据位置或原文片段。证据来源必须归属当前项目且版本可验证；不能仅提交一个自由字符串冒充来源。多来源同一关系的 evidence 独立保存，避免最后写入覆盖其他来源。

关系逻辑键为 projectId + type + fromId + toId；证据键加入 sourceId/version/chunk/位置。确定性 ID、Neo4j 唯一约束和幂等 upsert 防重复。更新证据使用预期版本，冲突拒绝；跨项目端点和不合法方向拒绝。定义可重建/清理接口和版本语义，本节点不实现自动抽取或业务事件驱动流水线。

## 实现顺序与公共 seams

1. 变更记录、Graph Domain 功能文档、数据/后端架构、API 契约和 Neo4j 派生投影 ADR，先明确数据所有权及失败语义。
2. Java project-scoped application service：鉴权、端点类型、来源归属/版本、证据和置信度校验；从公开 API 测试拒绝跨项目和未知实体。
3. Neo4j adapter：真实 Testcontainers 验证约束、稳定 ID、幂等、并发和有界查询。初始化约束版本化，Neo4j 配置为可选部署 profile。
4. 受限图写入入口只允许项目 owner/admin 并重新验证来源；只读入口要求项目访问权。以类型化 DTO 传递实体/关系，无任意 Cypher。批量大小、邻接结果数量和查询超时必须有上限。
5. PostgreSQL/Neo4j 不存在跨库原子事务：不让图写入改变业务事实；Neo4j 故障返回通用依赖错误，Chat/Wiki/Task 原链路仍可运行。证据读取再次验证来源当前版本；过期/删除来源不能返回可用证据。自动同步/重建编排留给 V3-05。
6. 相称测试、跨进程 smoke、敏感扫描、Pi Milestone Review、Close Gate、提交推送并核验。

每个切片先公共 seam 红灯再最小实现。确定数据库所有权和 API 后才写测试；最终方案如改变上述边界，先更新文档和 Gate。

## 风险与验证

L3：schema、跨项目隔离、图数据写入和来源一致性。覆盖匿名/无权访问、跨项目节点/来源、稳定 ID、重复写入、多来源 evidence、非法方向、缺失证据、NaN/无穷/越界 confidence、过期 sourceVersion、分页/数量上限、并发幂等、Neo4j 不可用。真实 Java clean verify、Neo4j 集成；若影响 Python/现有 Chat 公共契约则补对应回归，根 Compose 变更按治理运行全仓回归。Pi 重点审核方向、重复实体、provenance、权限和失败隔离。

## 明确边界

不做 V3-05 自动 Entity/Relation 抽取、不做 V3-06 Entity Resolution/merge、不做 V3-07 GraphRAG 检索/回答，不新增 Issue 工单系统，不重构现有 Wiki 链接图。Graph Domain Model implemented 的公开声明只有验收后才能更新；GraphRAG Retrieval 保持 Planned。

## 预计修改模块与公开文档

Core API graph domain/application/infrastructure/API/tests；Neo4j 可选部署与示例配置；必要 schema/迁移及约束初始化。新增 docs/03-features/graph-domain-model.md，更新架构/API/operations、ADR 目录及路线状态。README 仅按已验证能力作简短更新。

## Node Start Gate

Node：V3-04。目标：实体/关系/evidence 写入和查询。当前状态：V3-03 已实现，审核修复单独收口。Scope、数据结构、风险和测试见上文。GitHub 展示价值：具有来源证据与确定性权限边界的领域图基础。下一节点边界：V3-05 才负责自动抽取和来源生命周期流水线。**等待用户确认后，另建变更记录开始实现。**
