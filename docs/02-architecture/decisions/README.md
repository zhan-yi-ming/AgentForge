# 架构决策记录

ADR 保存影响长期结构的决定。编号只增不减，Accepted 记录不通过覆写“改历史”；若改变决定，新建 ADR 并声明取代关系。

## 索引

- `ADR-0001-documentation-first.md`：所有实现变化必须先有文档变化。
- `ADR-0002-monorepo-multiple-applications.md`：采用单仓库、多应用目录。
- `ADR-0003-java-modular-monolith.md`：Java 采用按业务能力分包的模块化单体。
- `ADR-0004-versioned-database-migrations.md`：PostgreSQL schema 由 Flyway 版本化迁移管理。
- `ADR-0005-security-jwt-and-passwords.md`：V1 使用 Spring Security、短期 HS256 JWT、BCrypt 与服务端所有权校验。
- `ADR-0006-review-orchestration-loop.md`：Pi 审查循环使用本机状态、Git 报告和三次人工接管。
- `ADR-0007-autonomous-codex-pi-loop.md`：以 heartbeat 恢复 Codex 与 Pi 的跨回合协作。
- `ADR-0008-review-worker-observability-and-fix-correlation.md`：单工作树 worker、异常安全锁、可观测状态与修复提交 trailer。
- `ADR-0009-java-to-python-agent-boundary.md`：Java 鉴权后同步调用内部 Python Agent Service。
- `ADR-0010-day-4-rag-boundary-and-ranking.md`：Java 提供已授权来源，Python 管理派生索引并用 Embedding、BM25 与 RRF 检索。
- `ADR-0011-day-5-tool-confirmation-boundary.md`：Python 只提议 Tool，Java 持久化确认票据并确定性执行 Task 写入。
- `ADR-0012-compatible-llm-provider-adapter.md`：LangGraph 通过受限的 OpenAI-compatible 适配器调用 DeepSeek、智谱或通义千问，不连接 OpenAI 服务。
- `ADR-0013-single-host-public-demo-security.md`：单机公网 Demo 的网关、配额、TLS 和发布边界。
- `ADR-0014-streaming-agent-and-demo-credentials.md`：用内部 NDJSON、公共 SSE 实现真实流式 Agent，并把固定 Demo 凭据限制在服务器。
- `ADR-0015-public-demo-credential-and-centered-chat.md`：把固定面试账号定义为受限公开 Demo 凭据，并以居中对话和首次引导降低体验门槛。
- `ADR-0016-langfuse-fail-open-observability.md`：由 Python 集中建立脱敏、fail-open 的 Langfuse Agent Trace，不改变 Java 确定性业务边界。
- `ADR-0017-process-local-bounded-conversation-context.md`：用进程内有界 store 提供 Conversation Summary 与 Token Budget，并保留显式恢复限制。
- `ADR-0018-process-local-memory-namespace.md`：用完整 Memory Namespace 与强制 lease 隔离 tenant/workspace/project/user/thread，不提前建立业务 Workspace 数据模型。
- `ADR-0019-centralized-java-tool-policy.md`：Java 以服务端 Metadata 集中执行 Tool Role、Risk 与 Approval 策略。
- `ADR-0020-core-owned-conversation-display-history.md`：Core API 持久化授权范围内的会话展示历史，不提前实现 Agent checkpoint。
- `ADR-0021-durable-approval-idempotency-audit.md`：Java 持久化五态 Approval、显式幂等执行与追加式审计事实。
- `ADR-0022-postgres-langgraph-action-checkpoints.md`：用独立 PostgreSQL schema 持久化待决 Action workflow，Java 保留业务执行权。
- [ADR-0029-mcp-adapter-through-java-policy.md](ADR-0029-mcp-adapter-through-java-policy.md)：MCP Adapter 复用 Java Tool Policy 与 Approval，不直连业务数据库。
- [ADR-0030-litellm-model-gateway.md](ADR-0030-litellm-model-gateway.md)：Agent Service 通过 LiteLLM Gateway 统一模型调用、有限 fallback 与 usage/cost 观测。
- [ADR-0031-deterministic-task-model-routing.md](ADR-0031-deterministic-task-model-routing.md)：部署配置按确定性任务类型选择模型，模型输出不能自行路由。
- [ADR-0032-neo4j-derived-graph.md](ADR-0032-neo4j-derived-graph.md)：Java 授权和验证 Neo4j 派生图，PostgreSQL 保持业务事实。
- [ADR-0033-durable-graph-source-sync.md](ADR-0033-durable-graph-source-sync.md)：PostgreSQL 持久待办驱动可恢复的 Neo4j 来源抽取与清理。
- [ADR-0034-reviewable-entity-resolution.md](ADR-0034-reviewable-entity-resolution.md)：人工确认与可撤销规范映射，保留来源图和证据。
- [ADR-0035-evidence-bounded-graphrag.md](ADR-0035-evidence-bounded-graphrag.md)：GraphRAG 只消费当前有效来源证据，并与文本检索在统一预算内融合。
- [ADR-0036-project-scoped-repository-context.md](ADR-0036-project-scoped-repository-context.md)：Repository Context 由部署配置绑定项目，只读取 Git HEAD 的有界白名单资料。
- [ADR-0037-action-workflow-round-identity.md](ADR-0037-action-workflow-round-identity.md)：每轮 Chat Approval 使用不可复用 workflow ID 绑定 Java Action 与 LangGraph checkpoint。
- [ADR-0038-action-checkpoint-compensation.md](ADR-0038-action-checkpoint-compensation.md)：未形成 Java Approval 的 WAITING 由精确 Abort 终结，并以稳定请求 Namespace 支持重试。
- [ADR-0039-checkpoint-owner-and-decision-actor.md](ADR-0039-checkpoint-owner-and-decision-actor.md)：管理员代审批时分离 checkpoint owner 与实际决策 actor，恢复原请求人的 Namespace。
- [ADR-0040-database-service-role-separation.md](ADR-0040-database-service-role-separation.md)：分离 PostgreSQL 管理、Core 运行与 Agent 派生数据角色，以数据库 grants 强制 Java/Python 职责边界。
- [ADR-0041-manual-graph-disaster-recovery.md](ADR-0041-manual-graph-disaster-recovery.md)：将不可由 PostgreSQL 重建的手工图事实与规范映射纳入同一离线备份/恢复集。
- [ADR-0042-conversation-revision-lease.md](ADR-0042-conversation-revision-lease.md)：用 generation、revision 与短期 exchange claim 串行化同一进程内会话的完成提交，同时保持不同会话和模型调用并行。
