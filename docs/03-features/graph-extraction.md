# Graph Extraction Pipeline

- 状态：Implemented（V3-05；显式语法抽取，GraphRAG Retrieval 仍 Planned）
- 前置：[Graph Domain Model](graph-domain-model.md)
- ADR：[ADR-0033](../02-architecture/decisions/ADR-0033-durable-graph-source-sync.md)

## 用户场景与范围

项目 owner 修改 Wiki/Task 后，可在项目图中看到从内容**明确陈述**的 Service、API、Issue 以及有原文证据的关系。当前只识别独立行（允许 Markdown 列表前缀）：

- Wiki：`Service: <name>`、`API: <METHOD> <path>`、`Issue: <id>`，形成 `Wiki DESCRIBES target`。
- Task 描述：`Modifies Service: <name>`、`Modifies API: <METHOD> <path>`，形成 `Task MODIFIES target`。

标签大小写不敏感；目标值经 trim 后必须非空且最长 200 字符；匹配行作为 excerpt 必须为 1..2000 个 UTF-16 单位，超过上限整源失败且不发布部分投影；一份来源最多接受 50 条有效匹配行；超过上限记录失败且不发布部分投影。没有匹配行时只保留 Wiki/Task 实体，不推断关系。任意自然语言、LLM 候选、同义词及跨来源合并均不在本节点；避免把可能的关系当事实。

## 生命周期与数据

Wiki 内容、Task 描述分别是唯一 evidence 原文；source_document 为当前项目的类型、UUID 和业务 version；source_chunk 是原文中的行序号（从 0 开始），evidence 是该行的 UTF-16 [start,end) 和逐字 excerpt；confidence 对显式语法固定为 1.0。一个来源中重复的相同目标/关系按稳定键去重，保留每个独立位置的 evidence。生成的 Service/API/Issue 使用来源作用域 externalId（source type/id + target type + 文本键稳定摘要，长度不超过 200），同名跨来源暂不合并，留给 V3-06。生成关系仍受 V3-04 类型白名单限制。

Wiki/Task CRUD 在 PostgreSQL 同一事务中 upsert `graph_source_sync` 待办；既有来源由迁移回填。后台仅在 graph enabled 时按项目/来源串行取待办，读取**当前**业务版本，原子替换此来源在 Neo4j 的自动投影；来源已删除时删除此来源生成的 evidence/实体与失去 evidence 的关系。来源更新会移除引用其旧业务版本的证据（含手工证据），保留独立来源及当前版本的手工证据；来源删除会移除引用其全部版本的证据。手工 V3-04 写入且不属于此来源的实体/证据保留。Neo4j 成功后才清除待办；失败保留待办并重试。图读取仍按 V3-04 重新验证来源版本，待办期间过期证据不可见。PostgreSQL/Neo4j 不声明跨库原子事务。

## 权限、接口和恢复

CRUD 继续沿用 Java Tool Risk/ProjectAccess；worker 不接收用户或 LLM 提供的权限字段，只处理已提交的业务事实。手工重建入口 `POST /api/v1/projects/{projectId}/graph/extraction/rebuild` 要求 Bearer owner/admin，重新登记项目现有 Wiki/Task 来源；返回 202，后台异步处理。不提供直接 Cypher、任意来源上传或 Python 图写入口。图关闭/不可用时 Wiki/Task CRUD 仍成功，待办留存；重建可在恢复后重试。若来源不断变化，待办最终以最新业务版本为准。

## 验证与限制

通过真实 Wiki/Task HTTP 写入、Graph HTTP 查询和 PostgreSQL/Neo4j 验证新增、更新、删除、重复行、证据位置、项目隔离、关闭/故障恢复及重建。自动投影具有最终一致性；客户端可暂时看到空页或旧来源被隐藏。抽取仅覆盖上述显式语法，不宣称自由文本语义理解或 GraphRAG Answer。

同步状态入口 `GET /api/v1/projects/{projectId}/graph/extraction/status` 返回当前项目 `pending` 和 `retrying` 数量（retrying 是 attempts>0 的待办）；同样要求 owner/admin。待办清零表示该来源的 Neo4j 替换已提交，不代表两库原子快照。
图同步调度仅在 `agentforge.graph.enabled=true` 时创建；`agentforge.graph.sync.enabled` 默认 true，可在维护窗口临时关闭处理而保留待办，恢复后继续。默认轮询间隔 5000 ms，可通过 `agentforge.graph.sync-interval-ms` 调整；每轮最多处理 20 条。

来源删除后，同项目中任何引用该 Wiki/Task 作为 evidence source 的证据都失效并从派生图清理；以已删除业务对象为端点的关系一并移除。关系若仍有其他有效 evidence 且端点仍存在，则保留。人工 V3-04 投影只在引用删除来源或删除端点时受清理，其余手工证据保留。

自动刷新会更新生成实体的来源版本与名称，但不递增图实体的手工 CAS version；手工修改仍需通过当前业务来源版本校验。若未来允许无来源的手工覆盖自动字段，应另行收敛版本语义。
超过 50 行、目标名 200 字符或 excerpt 2000 字符上限的来源会留在待办中继续重试；状态接口仅显示 retrying 数量，维护者需检查来源内容并修正后重建。