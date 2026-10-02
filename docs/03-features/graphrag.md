# GraphRAG Hybrid Retrieval

- 状态：Implemented（V3-07；Milestone Review PASS）
- 相关：[RAG 检索](rag-retrieval.md)、[Graph Domain Model](graph-domain-model.md)、[Entity Resolution](entity-resolution.md)、[ADR-0035](../02-architecture/decisions/ADR-0035-evidence-bounded-graphrag.md)

## 用户场景与流程

项目成员在 Chat 询问服务、API、任务或问题间的影响关系时，Agent 需要同时利用已授权的 Wiki/Task 文本和有原文证据的项目图。Java 仍是图、项目授权和业务来源的边界；Python 负责理解查询、检索融合与生成回答。

Chat 先按现有流程从 Java 取得当前项目 Wiki/Task 并更新 RAG 索引。Python 用查询文本调用 Java 内部 GraphRAG 只读入口；Java 在读取图之前重新验证内部 token、用户存在及项目 owner/admin 权限。候选来自当前项目的有界实体扫描，匹配名称、有效人工确认的 canonical name/alias；只消费当前来源有效的映射。由候选出发最多两跳、有总量上限地遍历。每条关系的两端和 evidence 都由 Java 对当前 PostgreSQL 来源重验；无有效 evidence 的关系不可返回。Python 合并图证据与 Vector/BM25 片段，使用稳定排序、去重和统一字符预算形成 Retrieved Context，再经既有 LLM 与完成后引用筛选。

## 数据与接口

图检索返回 entity、relation、source、evidence、confidence 及文档引用候选；原始 Neo4j 节点和 PostgreSQL 人工规范映射不被改写。Graph evidence 的 Wiki/Task ID 是唯一可进入最终 `sources` 的引用身份；实体 ID 本身不是文档 citation。内部查询契约见 [Agent Service API](../04-api/agent-service.md) 与 [Core API](../04-api/core-api.md)。公开 Chat JSON/SSE 字段不变。

## 权限、失败与上限

内部入口不接受浏览器 Bearer 代替服务 token；先授权，再扫描图。图关闭、超时或暂不可用时，Python 使用现有 Hybrid RAG 结果；授权、请求或响应关联错误不得当成空图成功。项目间实体、失效来源、撤销映射和无 evidence 的关系不得进入 Context。候选扫描、根数、跳数、邻居数、关系数、输出条数和 Context 长度均须有固定硬上限；达到上限只截断，不扩散查询。引用只允许最终回答显式标注的已授权 Wiki/Task 来源。

## 测试与限制

真实 Core HTTP + Neo4j/PostgreSQL 集成验证关系问题、权限隔离、过期来源、映射失效和两跳上限；Python retrieval 公共接口验证图文融合、引用及预算；独立 Python 进程验证真实 Core HTTP 图契约。当前没有端到端跨进程 Chat/LLM 回答断言，不能把契约 smoke 记作完整 Chat 验证。短查询和弱名称匹配可能漏召回；Neo4j 与业务库没有跨库原子快照。没有来源证据时不声称关系事实成立；本节点不接入 Git 仓库上下文。
