# ADR-0010：Day 4 RAG 边界与混合排序

- 状态：Accepted
- 日期：2026-09-04
- 决策者：项目维护者

## 背景

Wiki 与 Task 是 Java Core API 掌握的业务事实，Python Agent Service 负责概率性检索与 Context。Day 4 同时需要 pgvector 持久化、来源及时失效和跨项目隔离，不能让 Python 直接查询业务表，也不能把项目权限复制成另一套实现。

## 备选方案

1. Python 直接读取业务表：实现短，但绕过 Java 权限边界并耦合业务 schema，拒绝。
2. Java 完成 Chunk、Embedding、BM25、RRF：数据边界简单，但把概率性 RAG 逻辑放入确定性业务服务，拒绝。
3. Java 提供已授权来源 DTO，Python 管理派生索引与混合检索：多一次内部回调，但职责清楚，可独立替换检索策略，采用。

## 决策

Java→Python Chat 与 Python→Java 来源读取使用两个不同的内部 token。Python 回调 Core API 时携带原始 `projectId`、`userId`、`actorAdmin` 和 `requestId`；Core API 验证服务 token、用户存在，并复用 `ProjectAccess` 再次授权后才读取 Wiki/Task。

Python 只访问 `rag_chunk` 与 `rag_project_snapshot` 两张派生索引表，不查询或修改业务表。Core 在可重复读事务中读取由 Wiki/Task 提交触发器推进的项目来源代际，并将其作为已授权全量来源的单调 `snapshotVersion`；`rag_source_generation` 只向 Core 开放读取。Agent 可声明已应用的 `knownSnapshotVersion`：Core 仍先授权并读取代际，匹配时不读取/传输业务正文，不匹配时返回完整来源集合以保留删除语义。Python 只对变化快照取得项目事务锁，并且只应用不小于已落库代际的快照，旧快照既不能回退内容也不能执行缺失删除。

每次 Chat 只在搜索事务看到的索引代际等于本次授权代际时执行检索，否则文本候选 fail closed 为空。pgvector cosine 与由 title/content 生成、GIN 索引支持的 PostgreSQL 词法预选各自限制 `candidate_k`；Python 只对有界词法候选计算 BM25，再执行 RRF，不再把项目全部 Chunk 搬入进程。Graph evidence 按 Core 已有的有界 match，在相同代际的 Chunk 上用来源身份、版本和 excerpt 再匹配。固定 384 维是 V1 数据契约；默认 hash provider 保证无密钥和确定性，可选 OpenAI-compatible provider 提供更强语义向量。

## 结果

业务事实和授权继续集中在 Java；RAG 策略集中在 Python；索引可随时重建。代价是 Chat 形成 Java→Python→Java 的同步调用，必须避免 Core 内部来源接口再次调用 Agent Service，并设置明确超时。稳定项目的 Chat 只做代际握手和有界候选查询；来源变化请求仍同步应用完整快照。独立后台增量队列不在本次决策范围。

## 取代关系

补充 ADR-0009，不取代其 Java 主入口和业务写入边界。
