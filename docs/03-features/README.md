# 功能文档索引

每个用户可见能力或关键平台能力必须有独立文档。代码新增功能前，先从 `docs/templates/feature-template.md` 创建或更新对应文件。

## 当前功能

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

## 后续计划

以下文档尚未创建，表示功能也不应开始实现：

- V2-09 Release Gate 已完成；V2-01 至 V2-09 全部收口。Memory Namespace 仅表示 Agent 记忆归属，不代表长期记忆或完整 SaaS 多租户。
- V3-01 MCP 尚未授权，禁止提前实现。
