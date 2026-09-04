# 功能文档索引

每个用户可见能力或关键平台能力必须有独立文档。代码新增功能前，先从 `docs/templates/feature-template.md` 创建或更新对应文件。

## 当前功能

- `user-and-project.md`：Day 1 用户与项目基础及 Day 2 安全迁移，已实现并通过真实 PostgreSQL 集成测试。
- `authentication-and-authorization.md`：Day 2 注册、登录、JWT 和基础 RBAC，已实现。
- `wiki.md`：Day 2 项目 Wiki Page CRUD，已实现。
- `task.md`：Day 2 项目 Task CRUD，已实现。
- `review-orchestration.md`：Codex 与 Pi 的阶段审查、修复复审和三次人工接管循环，已确认实施方案。
- `agent-chat.md`：Day 3 项目内 Chat、LangGraph 状态流与 Java-Python 边界，已实现。

## 后续计划

以下文档尚未创建，表示功能也不应开始实现：

- RAG 检索（Day 4）
- Tool Calling 与人工确认（Day 5）
- AI 文本整理与 Markdown 预览（Day 6）
