# V2/V3 节点路线图

- 状态：Accepted（路线已确认；各功能在实现前均为 Planned）
- 当前状态：V1 completed；V2 尚未开始
- Next Node：V2-01 Langfuse 基础 Trace
- 开发协议：`../00-governance/v2-v3-node-development-protocol.md`

本文是 V2/V3 开发顺序与 Node Scope 的唯一来源，同时规定验收标准、DeepSeek Pi Review 重点和 GitHub 展示重点。每次只完成一个 Node，禁止跨节点开发；完成后必须停止并等待用户明确授权下一 Node。

## V2 — Reliable Agent Engineering

V2 的总目标不是增加花哨功能，而是把“能运行的 Agent”升级为可靠、可控、可恢复、可观察、可评估的 Agent。

固定顺序：`V2-01 → V2-02 → V2-03 → V2-04 → V2-05 → V2-06 → V2-07 → V2-08 → V2-09`

### V2-01 Langfuse 基础 Trace

- **目标与 Scope**：建立 `request → agent → llm / retrieval / tool` 基础全链路 Trace；记录 `request_id`、`thread_id`、`project_id`、LLM/检索/Tool latency、Token usage、Tool result 和 error。
- **暂不做**：复杂 Dashboard、Eval、大规模日志系统重构。
- **验收**：一次 Agent 请求可从用户请求追踪到 Agent Node、Retrieval、LLM、Tool、耗时、Token 与 Error，异常链路也能正确结束。
- **Pi Review**：漏节点、Trace ID 断链、异常 Trace 未关闭、JWT/password/secret 泄露、Tool 输入输出敏感字段、Trace 影响主业务。
- **GitHub**：按现有目录结构建立 Observability 功能/架构文档；重点说明如何定位慢点、错误、调用与 Token 消耗。有真实 Trace 后才建议 README 截图。

### V2-02 Context Manager 数据结构

- **目标与 Scope**：建立 `ContextBundle`，统一 Working Context、Conversation Summary、Project Context、Retrieved Context、Tool Context；明确生产者和消费者，将散落的 `messages`、`project_id`、`retrieved_docs`、`tool_result` 逐步纳入 Context Builder/Manager。
- **暂不做**：完整 Conversation Summary 策略。
- **验收**：Agent Node 不再各自手工拼接上下文；State 与 Context 职责清晰，项目边界显式。
- **Pi Review**：Context 重复、隐式全局状态、Project 串数据、Tool Result 无限进入 messages、Context Manager 与 LangGraph State 混淆。
- **GitHub**：建立/更新 Context Management 文档，解释其工程问题、State/Context 区别及来源/消费者；关键词是 Context Engineering，不是“优化 Prompt”。

### V2-03 Conversation Summary + Token Budget

- **目标与 Scope**：按 `Recent Messages + Conversation Summary + Retrieved Context + Project Context` 组合上下文；Token Budget 配置化，超预算时压缩旧消息、保留最近 N 轮并把更早历史纳入 Summary。
- **验收**：长对话 Prompt 不无限增长；关键历史约束可引用；最近消息不被 Summary 覆盖；Retrieval Context 不被历史挤掉；预算真实生效。
- **Pi Review**：Summary 反复总结自身导致漂移、关键约束丢失、Tool Result 被错误总结、预算未生效、仍可能超限。
- **GitHub**：更新 Context Management 文档并建议 Context Composition Mermaid；只有真实对比才公开 Token 变化，禁止编造节省比例。

### V2-04 Memory Namespace 隔离

- **目标与 Scope**：建立 `tenant → workspace → project → user → thread` Namespace，优先复用现有存储，不为 Namespace 引入不必要数据库。
- **验收**：必须包含负向隔离测试；Project A/User A/Thread A 的信息绝不泄露至其他 Project/User/Thread。
- **Pi Review**：所有可能跨 User、Project、Thread 污染的读写与 Context 路径。
- **GitHub**：更新 Context Management 文档，准确展示 Memory Isolation/Namespace；未实现复杂长期记忆时不得如此宣称。

### V2-05 完整 RBAC + Risk Engine

- **目标与 Scope**：建立 `READ / LOW / MEDIUM / HIGH` 风险等级和集中式 Tool Metadata（`required_role`、`risk_level`、`need_approval`），至少覆盖 `search_wiki`、`get_task`、`create_task`、`update_task` 与高风险修改/删除操作；Java 对 LLM Action Intent 做最终确定性授权。
- **边界**：禁止在各 Tool 散落权限 if/else；不得提前完整实现属于 V2-06 的 Approval/Audit/Idempotency。
- **验收**：Agent 与直接 Java API 两条路径都不能绕过 RBAC/Risk；Tool Metadata 不能由客户端篡改。
- **Pi Security Review**：越权、IDOR、伪造 `project_id`、修改他人 Task、绕 Agent 直调 API、Metadata 篡改、Prompt Injection 绕权限。
- **GitHub**：建立 Security and Risk 文档，建议 Mermaid `LLM Intent → Tool Policy → Risk Engine → RBAC → Execution`；可在真实实现后表述 `AI decides intent. Deterministic services enforce permission.`

### V2-06 Approval + Idempotency + Audit

- **目标与 Scope**：建立 `PENDING / APPROVED / REJECTED / EXECUTED / FAILED` Approval 状态；流程为 Agent → Tool Intent → Risk Engine → Approval Request → User Approval → Java Permission Recheck → Execute。防止重试重复创建；Audit 至少记录 who、project、action、target、request_id、approval_id、result、timestamp。
- **验收**：重复确认、网络/LangGraph retry、响应超时、replay 均不能造成重复或越权执行；拒绝后不可执行。
- **Pi Review**：双击确认、重复提交、approval replay、rejected 再执行、idempotency collision、执行成功但响应超时。
- **GitHub**：更新 Security and Risk，并视实现建立 Agent Runtime 文档；真实展示 HITL、Idempotent Execution、Auditability 作为工程能力。

### V2-07 LangGraph Checkpoint / Interrupt / Resume

- **目标与 Scope**：实现 Thread、Checkpoint、Interrupt、Resume、Retry。
- **验收**：`User Request → Agent Interrupt → Python Service Restart → User Approval → Resume → Tool Correctly Executes` 必须验证真实持久化，而非内存 Demo。
- **Pi Review**：Checkpoint 持久化、重启恢复、重复 Resume、Retry 与 Idempotency 冲突、旧 Thread 污染、State Schema 升级兼容。
- **GitHub**：重点更新 Agent Runtime，展示 Running → Interrupt → Checkpoint → Waiting → Resume → Continue；稳定后才能在 README 写 Stateful/Recoverable Agent。

### V2-08 Evaluation

- **目标与 Scope**：建立可实际运行的 Evaluation Pipeline，而非只有 metric 函数。RAG 指标包含 Recall@K、MRR、Hit Rate；Answer 包含 Faithfulness；Agent 包含 Tool Selection Accuracy、Task Success Rate。建立固定小型 dataset、rag/agent/tool eval runner 与真实 report。
- **验收**：至少覆盖期望 Wiki、Task、Tool、Tool 参数与 no-tool 样本；runner 可重复执行并输出真实报告。
- **Pi Review**：指标计算、数据泄漏、gold 合理性、Retrieval/Answer Eval 混淆、Tool Accuracy 是否验证参数而非仅工具名。
- **GitHub**：建立 Evaluation 文档，展示真实 dataset 结构、runner 和 report；没有真实运行结果不得在 README 填数字。

### V2-09 Regression Test + V2 Release Gate

- **目标与 Scope**：禁止新增功能；完善 JUnit、pytest、Tool Mock、Permission/HITL Tests 和 Regression Dataset，证明 V2 稳定。
- **完整验收**：普通问答、RAG、Create/Update Task、High-risk Approval、Reject、Duplicate Request、Agent Retry、Service Restart/Resume、Cross Project、Unauthorized、Long Conversation、Trace、Evaluation 全部纳入 Release Regression；整体通过后才可 `tag v2-stable`。
- **Pi V2 Release Review**：跨节点遗漏、权限回归、幂等、Context 污染、Resume、Trace、Evaluation、文档与真实实现一致性。
- **GitHub**：整理 README 与真实存在的 Architecture、Context、Security/Risk、Agent Runtime、Observability、Evaluation 文档；主题是 Reliable Agent Engineering，不是“新增很多功能”。

## V3 — Advanced Agent Context & Extensibility

固定顺序：`V3-01 → V3-02 → V3-03 → V3-04 → V3-05 → V3-06 → V3-07 → V3-08（Optional）→ V3-09`。GraphRAG 位于后半段，不得提前堆 Neo4j。

### V3-01 MCP Adapter

- **目标与 Scope**：把现有 Java Tool（如 `search_wiki`、`get_task`、`create_task`、`update_task`）标准化暴露为 MCP；调用必须为 `MCP → Java Application Service → RBAC → Risk Engine → Approval → DB`。
- **边界与验收**：禁止 MCP 直连 DB 或绕过 V2 安全体系；Schema、Validation、错误契约、Retry/Idempotency 一致。
- **Pi Review**：权限绕过、Schema Drift、参数校验、错误契约、重试与幂等。
- **GitHub**：建立 MCP 文档，说明 Agent 与业务 Tool 的协议解耦；“用了 MCP”本身不是亮点。

### V3-02 LiteLLM Model Gateway

- **目标与 Scope**：统一 GPT、DeepSeek、GLM 等 provider 的调用、timeout、fallback、cost、usage；Agent 只使用统一 Gateway。
- **验收**：切换 Provider 不需修改 Agent、RAG、Tool。
- **Pi Review**：Provider exception、timeout/retry/fallback、Structured Output/Tool Calling 差异、Token usage 统一。
- **GitHub**：建立 Model Routing 文档；本 Node 只宣称 Model Gateway/Provider Abstraction，不得提前声称智能多模型路由。

### V3-03 Multi-model Routing

- **目标与 Scope**：根据 `FORMAT / REWRITE / PLAN / REVIEW` 等任务类型确定性选模型；综合成本、延迟、能力与兼容性。
- **边界**：暂不让另一个 LLM 自动选择 LLM，以免增加成本、延迟和不确定性。
- **Pi Review**：fallback 无限循环、route 错误、Tool Calling/Structured Output 兼容、timeout、cost accounting。
- **GitHub**：更新 Model Routing，展示 Task-aware Routing 与 Cost/Latency/Capability Trade-off，不只罗列模型名。

### V3-04 Neo4j Graph Domain Model

- **目标与 Scope**：在 GraphRAG 前设计 Project、Service、API、Wiki、Task、Issue 图模型和 CONTAINS、EXPOSES、DESCRIBES、MODIFIES、AFFECTS 等关系；所有关系从第一天携带 source、evidence、confidence。
- **边界与验收**：只实现实体/关系/evidence 写入与查询，不做 GraphRAG Answer；Neo4j 不等于 GraphRAG。
- **Pi Review**：模型合理性、关系方向、ID 稳定性、重复实体、provenance 完整性。
- **GitHub**：建立 GraphRAG 文档时必须标明 Graph Domain Model implemented、GraphRAG Retrieval planned/in progress。

### V3-05 Graph Extraction Pipeline

- **目标与 Scope**：把 Wiki/Task 内容转换为 Entity/Relation；关系携带 `source_document`、`source_chunk`、`evidence`、`confidence`。
- **验收**：内容新增、更新、删除均有一致的数据生命周期和可追溯 evidence。
- **Pi Review**：幻觉关系、evidence 不匹配、重复关系、源文档删除/更新后的旧关系、Graph/PostgreSQL 不一致。
- **GitHub**：更新 GraphRAG，重点解释 Evidence 驱动的图构建与 Relationship Provenance，而不是“LLM 抽实体”。

### V3-06 Entity Resolution

- **目标与 Scope**：管理 `canonical_name`、alias、metadata、confidence、source；执行 Entity → Candidate Retrieval → Rule/Embedding → LLM Disambiguation → Confidence → Low Confidence → Human Confirmation。
- **边界与验收**：不得因 LLM 判断相同就自动 Merge；处理 false merge/split、alias pollution、重名、merge rollback，并持久化人工确认。
- **Pi Entity Resolution Review**：候选、置信度、错误合并/拆分、回滚和人工结果复用。
- **GitHub**：建立 Entity Resolution 文档，说明 Entity Resolution ≠ NER；核心问题是不同表述是否属于同一真实实体。

### V3-07 GraphRAG Hybrid Retrieval

- **目标与 Scope**：到此 Node 才正式实现 GraphRAG。检索链为 Query Understanding → Entity Candidate → Graph Traversal → Vector/BM25 Retrieval → Merge/Rerank → Context → LLM；结合 Graph Evidence、Text Evidence 和现有 Hybrid RAG。
- **验收**：结果尽量包含 entity、relation、source、evidence、confidence、document citation，可回答跨模块影响类关系问题。
- **Pi Review**：选错实体、遍历无限扩散、邻居过多导致 Context 爆炸、Graph/Vector 冲突、无 evidence 关系进入回答、Resolution 错误传播、citation 丢失。
- **GitHub**：更新 GraphRAG，解释传统 Vector RAG 对关系型问题的不足；禁止只宣传“接入 Neo4j”。

### V3-08 Git Repository Context（Optional）

- **目标与 Scope**：可选接入 README、目录结构、API Docs、关键配置、Commit Summary，使 Agent 结合 Wiki、Task、Graph 与提交摘要回答研发上下文问题。
- **边界**：不尝试完整理解整个仓库；时间不足可直接跳过。
- **Pi Review**：Context 过大、secret、无关文件、过期上下文、commit noise。
- **GitHub**：若真实实现，更新 Architecture/Context Management，突出 Engineering Context，不夸大为“AI 会读代码”。

### V3-09 Integration / V3 Release Gate

- **目标与 Scope**：禁止新增功能；完整验收 RAG、GraphRAG、Entity Resolution、HITL、Risk、Approval、Audit、Retry/Resume、Langfuse、Evaluation、MCP、LiteLLM、Fallback、Multi-model Routing，可选 Git Context；全部通过后才可 `tag v3-stable`。
- **Pi V3 Release Review**：完整架构、安全、数据、恢复、评测、集成与文档真实性审核。
- **GitHub**：整理 README 及真实存在的 Architecture、Context、Security/Risk、Agent Runtime、Observability、Evaluation、GraphRAG、Entity Resolution、MCP、Model Routing 文档。
- **最终公开验收**：首次访问的 AI 应用研发面试官应在 3 分钟内理解它不是 Wrapper/普通知识库；具备真实 Context Engineering、业务对象操作、LLM/确定性系统边界、RBAC/Risk/HITL、Idempotency/Audit、Persistence/Recovery、Trace、Evaluation；GraphRAG 用于关系问题，MCP 用于 Tool 解耦，多模型用于 Routing/Cost/Reliability。
