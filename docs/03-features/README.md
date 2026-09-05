# 功能文档索引

每个用户可见能力或关键平台能力必须有独立文档。代码新增功能前，先从 `docs/templates/feature-template.md` 创建或更新对应文件。

## 当前功能

- `user-and-project.md`：Day 1 用户与项目基础及 Day 2 安全迁移，已实现并通过真实 PostgreSQL 集成测试。
- `authentication-and-authorization.md`：Day 2 注册、登录、JWT 和基础 RBAC，已实现。
- `wiki.md`：Day 2 项目 Wiki Page CRUD，已实现。
- `task.md`：Day 2 项目 Task CRUD，已实现。
- `review-orchestration.md`：历史 Codex/Pi 编排方案；Pi 已停用，只保留追溯资料。
- `agent-chat.md`：Day 3 项目内 Chat、LangGraph 状态流与 Java-Python 边界，已实现。
- `rag-retrieval.md`：Day 4 Wiki/Task Chunk、Embedding、BM25、RRF、Context 与来源引用，已实现并通过真实 pgvector 与跨进程验证。
- `tool-calling-and-confirmation.md`：Day 5 create/update task 意图、待确认 action 与 Java 确定性写回，正在实施。

## 后续计划

以下文档尚未创建，表示功能也不应开始实现：

- [React 项目工作区](web-workspace.md)：登录、项目选择、Wiki/Task、AI Chat、人工确认与 Markdown 预览（Day 6）
