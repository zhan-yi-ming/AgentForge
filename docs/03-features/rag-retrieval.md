# RAG 检索

- 状态：Implemented
- 所属阶段：V1 / Day 4
- 相关 ADR：ADR-0009、ADR-0010

## 用户价值与使用场景

用户在项目 Chat 中询问架构、约束或任务状态时，Agent 会从当前项目的 Wiki 和 Task 中检索相关片段，并在回答中返回可追溯来源。检索不跨项目，也不把模型猜测伪装成项目事实。

## 范围与非目标

Day 4 包含 Wiki/Task Chunk、384 维 Embedding、BM25、RRF、Retrieved Context 和结构化来源。V1 当前只保留无密钥 `hash` Embedding，用于本地可重复运行；真实生成式回答由独立的国内 LLM provider adapter 提供。

本节描述 Day 4 基线；后续生成、记忆、Tool、Trace 与评测见各自功能文档。V3-07 的图与文本融合及来源约束见 [GraphRAG Hybrid Retrieval](graphrag.md)。

## 关键流程

1. 公共 Chat 请求先由 Java 校验 JWT 和项目 owner/admin 权限，再把 `userId` 与 `actorAdmin` 发送给 Python。
2. LangGraph `retrieve` 节点用独立 Core 内部 token 回调来源接口；Java 验证 token、用户存在和项目权限，并在 PostgreSQL 可重复读事务中读取项目来源代际 `snapshotVersion`。Agent 携带已应用的可选 `knownSnapshotVersion`：代际未变时 Core 不读取或返回 Wiki/Task 正文；代际变化或本地尚无索引时才返回完整来源集合。该代际由 Wiki/Task 提交触发器推进，不因只读请求增加。
3. Python 只对 `sourcesChanged=true` 的完整快照在项目事务锁内同步派生 Chunk。旧于已应用代际的来源集合不得覆盖新索引或执行缺失删除；变化来源替换旧 Chunk，完整快照中已删除来源清除索引。未变化响应不进入同步锁。
4. 查询分别进入 PostgreSQL 内的向量召回和带 GIN 索引的词法预选，两路各自最多返回 `candidate_k` 个 ID；Python 只对有界词法候选计算 BM25，再用 RRF 融合，不直接比较异构分数，也不搬运项目全部 Chunk。
5. 最多 6 个 Chunk 进入有字符预算的 Retrieved Context。确定性 responder 根据 Context 给出摘要；Chat 响应同时返回去重后的来源列表。

P3-04 起，结构化来源列表只包含完成回答实际标注的已授权检索资料。检索候选以稳定编号进入模型上下文；回答使用 `【来源编号】` 标注资料，Python 将有效编号映射回 sourceType/sourceId/title/excerpt。没有有效标注时 `sources=[]`，不把候选自动列为引用。流式响应直到 complete 才确定准确来源。

## 接口

- 公共入口保持 `POST /api/v1/projects/{projectId}/agent/chat`，成功响应新增 `sources` 数组。
- Python 内部 Chat 请求新增 `actorAdmin`，仅用于回调时重建已由 Java认证的 actor。
- Core API 新增 `POST /internal/v1/rag/sources`，由 `X-AgentForge-Core-Internal-Token` 保护，详见 API 文档。

## 数据

`rag_chunk` 是可重建的派生索引，不是业务事实。隔离键是 `project_id`；来源身份由 `source_type + source_id` 确定，`source_version` 用于内容失效。`rag_project_snapshot` 记录每个项目最后原子应用的全量来源代际，只用于来源握手、拒绝乱序同步并约束搜索，不复制业务权限。向量固定为 384 维；词法列由 title/content 生成并使用 GIN 索引，内容保留原文片段以支持引用摘录。

## 权限与安全

- Web 不能访问 `/internal/v1/**`；该路径不接受 Bearer JWT 代替内部 token。
- 内部来源读取仍执行用户存在与项目 owner/admin 校验，且必须先授权再访问 Wiki/Task Repository。
- Python 不读取 `wiki_page`、`task_item` 或用户表，只接收 Java 返回的已授权 DTO。
- 两个服务方向使用不同 token；任何 token、Embedding API key 和数据库密码不得进入响应、日志或 Git。

## 失败与排查

- Core 内部 token 错误返回 401；用户或项目权限错误保持 401/403/404。
- Agent Service 无法连接 Core API、索引数据库或 Embedding provider 时，内部 Chat 失败，公共入口归一为 503。
- 用同一 `X-Request-Id` 串联 Java→Python 和 Python→Java；先检查两个健康端点，再检查 Core URL、token、数据库扩展和 provider 配置。
- 无候选不是系统错误；返回空 `sources` 和明确的“未找到相关项目上下文”。

## 测试与验收

- Chunk 边界稳定，来源版本变化和删除能正确替换/清除索引；v2 后到达的 v1、删除后的旧全量快照均不能回退或复活索引。
- 搜索只消费与当前授权 `snapshotVersion` 相同的索引；索引已被更新请求推进时本次文本候选为空，不泄漏另一快照内容。
- 未变化代际不读取或传输 Wiki/Task 正文、不进入项目同步锁；变化代际仍以完整快照传播删除。
- 向量和词法候选各自不超过 `candidate_k`，Python 收到的 Chunk 只来自两路候选并集；Graph evidence 只按有界 match 在相同代际索引中验证。
- BM25、向量排名和 RRF 在固定语料上结果可重复；跨项目 Chunk 绝不进入候选。
- 公共 Chat 返回相关 Wiki/Task 来源；无结果不伪造来源。
- 内部接口覆盖缺失/错误 token、用户不存在、跨用户拒绝、ADMIN 和 owner 成功。
- Codex 使用 pgvector PostgreSQL 容器执行迁移与真实跨进程闭环，全部测试必须记录真实命令、退出码、测试数量、失败数、错误数、跳过数和清理状态。

## 已知限制与后续计划

来源发生变化时，当前请求仍同步取得完整授权快照并更新索引；本次修复消除稳定项目的重复正文传输与全项目 Chunk 搬运，但不引入独立后台索引 worker。当前 PostgreSQL 词法预选使用 `simple` 配置；无空格的 CJK 查询可能得不到词法候选，此时只使用向量路，后续需以独立检索质量变更评估 `pg_trgm` 或中文分词，不能在容量修复中静默改变排序语义。hash 向量主要表达词项相似性；真实语义 Embedding provider 与后台增量队列留到后续独立阶段。
