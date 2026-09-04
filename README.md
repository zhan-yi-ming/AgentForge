# AgentForge

面向研发团队的“项目知识 + 任务协作 + AI Agent”平台。当前正在实施 V1，目标是跑通 Web → Java → Python Agent → RAG / Tool → Java 写回的可演示闭环。

## 当前进度

- 已建立文档先行制度和可追溯文档目录。
- 已设计 React Web、Java Core API、Python Agent Service 的单仓库边界。
- 已推送：V1 Day 1，Java 21 + Spring Boot + PostgreSQL 的 User / Project 基础。
- 已完成：V1 Day 2，登录鉴权、Wiki / Task 和公开仓库安全门禁。
- 已完成：V1 Day 3，FastAPI + LangGraph Chat 与 Java-Python 契约，并通过 Pi 集中验证。
- 尚未实现：RAG、Tool Calling 与前端页面。

## 先读文档

- [文档中心](docs/README.md)
- [产品概览](docs/01-product/product-overview.md)
- [系统架构](docs/02-architecture/system-overview.md)
- [本地开发](docs/05-development/local-development.md)
- [当前变更](docs/07-changes/2026-09-04-day-3-agent-service-chat.md)
- [公开仓库安全](docs/00-governance/public-repository-security.md)

任何修改都必须先遵守 [AGENTS.md](AGENTS.md) 的文档先行规则。

## 仓库入口

```text
apps/web/                   React Web（Day 6 实现）
services/core-api/          Java 确定性业务服务（当前）
services/agent-service/     Python Agent（Day 3 实现）
infra/                      本地 PostgreSQL / Redis
docs/                       项目长期记忆与变更证据
```

## 本地快速开始

需要 Java 21 与 Docker Desktop。复制 `.env.example` 为 `.env`，生成独立的本地 JWT secret，再按 [本地开发文档](docs/05-development/local-development.md) 启动依赖和 Core API。任何真实密钥都不能进入本公开仓库。

> 当前机器的默认 Java 是 8，不能用于本项目；必须让 `java -version` 指向 Java 21。
