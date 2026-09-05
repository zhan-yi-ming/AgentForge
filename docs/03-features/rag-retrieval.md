# RAG 检索

- 状态：Implemented
- 所属阶段：V1 / Day 4
- 相关 ADR：ADR-0009、ADR-0010

## 用户价值与使用场景

用户在项目 Chat 中询问架构、约束或任务状态时，Agent 会从当前项目的 Wiki 和 Task 中检索相关片段，并在回答中返回可追溯来源。检索不跨项目，也不把模型猜测伪装成项目事实。

## 范围与非目标

Day 4 包含 Wiki/Task Chunk、384 维 Embedding、BM25、RRF、Retrieved Context 和结构化来源。V1 当前只保留无密钥 `hash` Embedding，用于本地可重复运行；真实生成式回答由独立的国内 LLM provider adapter 提供。

不包含生成式 LLM、对话记忆、Tool Calling、写回、HITL、GraphRAG、完整 Trace 或离线评测平台。

## 关键流程

1. 公共 Chat 请求先由 Java 校验 JWT 和项目 owner/admin 权限，再把 `userId` 与 `actorAdmin` 发送给 Python。
2. LangGraph `retrieve` 节点用独立 Core 内部 token 回调来源接口；Java 验证 token、用户存在和项目权限后读取 Wiki/Task。
3. Python 按来源版本同步派生 Chunk。未变化来源复用现有向量，变化来源替换旧 Chunk，已删除来源清除索引。
4. 查询分别进入向量召回和 BM25 召回。RRF 按两个排名融合，不直接比较异构分数。
5. 最多 6 个 Chunk 进入有字符预算的 Retrieved Context。确定性 responder 根据 Context 给出摘要；Chat 响应同时返回去重后的来源列表。

## 接口

- 公共入口保持 `POST /api/v1/projects/{projectId}/agent/chat`，成功响应新增 `sources` 数组。
- Python 内部 Chat 请求新增 `actorAdmin`，仅用于回调时重建已由 Java认证的 actor。
- Core API 新增 `POST /internal/v1/rag/sources`，由 `X-AgentForge-Core-Internal-Token` 保护，详见 API 文档。

## 数据

`rag_chunk` 是可重建的派生索引，不是业务事实。隔离键是 `project_id`；来源身份由 `source_type + source_id` 确定，`source_version` 用于失效。向量固定为 384 维，内容保留原文片段以支持 BM25 和引用摘录。

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

- Chunk 边界稳定，来源版本变化和删除能正确替换/清除索引。
- BM25、向量排名和 RRF 在固定语料上结果可重复；跨项目 Chunk 绝不进入候选。
- 公共 Chat 返回相关 Wiki/Task 来源；无结果不伪造来源。
- 内部接口覆盖缺失/错误 token、用户不存在、跨用户拒绝、ADMIN 和 owner 成功。
- Codex 使用 pgvector PostgreSQL 容器执行迁移与真实跨进程闭环，全部测试必须记录真实命令、退出码、测试数量、失败数、错误数、跳过数和清理状态。

## 已知限制与后续计划

V1 每次 Chat 都会读取项目来源元数据，并在 Python 进程内计算 BM25；适合演示规模，不适合大项目。hash 向量主要表达词项相似性；真实语义 Embedding provider、后台索引任务、缓存、评测和 Token Manager 留到后续阶段。
