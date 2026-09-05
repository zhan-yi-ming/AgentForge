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
- `tool-calling-and-confirmation.md`：Day 5 create/update task 意图、待确认 action 与 Java 确定性写回，已实现。
- `web-workspace.md`：Day 6 登录、项目选择、Wiki/Task、AI Chat、人工确认与 Markdown 预览，已实现并在 Day 7 完成真实浏览器验收。
- `public-demo-protection.md`：V1.1 公网 Demo 的注册、配额、限速和模型预算保护。

## 后续计划

以下文档尚未创建，表示功能也不应开始实现：

- V2 的 Context Manager、Memory Namespace、完整 RBAC、审计、幂等和可恢复执行尚未建立功能文档，也不得提前实现。
