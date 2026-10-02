# 功能文档索引

- `voice-input.md`：P3-05 实时语音转写、项目权限与服务端密钥边界。
每个用户可见能力或关键平台能力必须有独立文档。代码新增功能前，先从 `docs/templates/feature-template.md` 创建或更新对应文件。

## 当前功能

- `mcp-adapter.md`：V3-01 MCP Tools、Streamable HTTP、JWT 与 Approval 边界（已实现并通过 Milestone Review）。

- `user-and-project.md`：Day 1 用户与项目基础及 Day 2 安全迁移，已实现并通过真实 PostgreSQL 集成测试。
- `authentication-and-authorization.md`：Day 2 注册、登录、JWT 和基础 RBAC，已实现。
- `wiki.md`：Day 2 项目 Wiki Page CRUD，已实现。
- `task.md`：Day 2 项目 Task CRUD，已实现。
- `review-orchestration.md`：当前提交前一次性 Pi 只读审核，以及已停用的历史自动编排方案。
- `agent-chat.md`：Day 3 项目内 Chat、LangGraph 状态流与 Java-Python 边界，已实现。
- `observability.md`：V2-01 Langfuse 基础 Trace、字段白名单、异常闭合与 fail-open 边界，已实现。
- `context-management.md`：V2-02 ContextBundle、V2-03 Conversation Summary/Token Budget 与 V2-04 Memory Namespace 隔离。
- `security-and-risk.md`：V2-05 Java 集中式 RBAC、Tool Metadata 与 Risk Engine。
- `conversation-history.md`：V2-05 受 Project/User/Thread 隔离保护的持久化历史会话。
- `approval-idempotency-and-audit.md`：V2-06 五态 Approval、幂等执行与追加式审计，已实现。
- `agent-runtime.md`：V2-07 PostgreSQL checkpoint、LangGraph interrupt 与跨重启 resume，已实现。
- `evaluation.md`：V2-08 固定 dataset、RAG/Answer/Tool runner、指标边界与真实报告，已实现。
- `rag-retrieval.md`：Day 4 Wiki/Task Chunk、Embedding、BM25、RRF、Context 与来源引用，已实现并通过真实 pgvector 与跨进程验证。
- `tool-calling-and-confirmation.md`：Day 5 create/update task 意图、待确认 action 与 Java 确定性写回，已实现。
- `web-workspace.md`：Day 6 登录、项目选择、Wiki/Task、AI Chat、人工确认与 Markdown 预览，已实现并在 Day 7 完成真实浏览器验收。
- `public-demo-protection.md`：V1.1 公网 Demo 的注册、配额、限速和模型预算保护。

- [Model Gateway 与 Provider 抽象](model-routing.md)：V3-02 统一模型调用与故障回退。

## 后续计划

以下状态区分已交付能力与后续计划：

- V2-09 Release Gate 已完成；V2-01 至 V2-09 全部收口。Memory Namespace 仅表示 Agent 记忆归属，不代表长期记忆或完整 SaaS 多租户。
- V3-01 至 V3-09 已实现；V3-09 Integration / V3 Release Gate 已完成完整回归与 Milestone Review，不新增业务能力。

- [Graph Domain Model](graph-domain-model.md)：V3-04 Implemented；GraphRAG Retrieval Implemented。
- [Graph Extraction Pipeline](graph-extraction.md)：V3-05 显式语法抽取、来源生命周期与 evidence provenance；GraphRAG Retrieval Implemented。
- [Entity Resolution](entity-resolution.md)：V3-06 已实现；候选、人工确认与可撤销规范映射。
- [GraphRAG Hybrid Retrieval](graphrag.md)：V3-07 Implemented；有界图遍历、文本融合与文档引用。
- [Git Repository Context](repository-context.md)：V3-08 Implemented；按项目绑定的 HEAD 只读资料与仓库引用。
- V3-09 Release Gate：Implemented；统一复跑 V1/V2 主链路、V3 专项能力、条件式跨进程契约与公开文档真实性检查，4/4 stages PASS。
