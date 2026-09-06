# AgentForge

面向研发团队的“项目知识 + 任务协作 + AI Agent”平台，由 `zhan-yi-ming` 构建。V1 Day 1–Day 7 已完成，提供 Web → Java → Python Agent → DeepSeek / 智谱 / 通义千问 + RAG / Tool → Java 写回的可演示闭环。V1.1 增加公网 Demo 的注册开关、双层限流、模型预算和单机生产部署；V1.2 增加真实流式回答、面试演示界面与服务器私有固定账号。

## 当前进度

- 已建立文档先行制度和可追溯文档目录。
- 已设计 React Web、Java Core API、Python Agent Service 的单仓库边界。
- 已推送：V1 Day 1，Java 21 + Spring Boot + PostgreSQL 的 User / Project 基础。
- 已完成：V1 Day 2，登录鉴权、Wiki / Task 和公开仓库安全门禁。
- 已完成：V1 Day 3，FastAPI + LangGraph Chat 与 Java-Python 契约。
- 已完成：V1 Day 4，Wiki/Task Chunk、Embedding、BM25、RRF、Retrieved Context 与来源引用；Day 1–4 已由 Codex 使用干净构建和真实跨进程测试复核。
- 已完成并推送：V1 Day 5，Tool Calling、持久化待确认动作、人工确认/拒绝与 Java 确定性写回。
- 已完成：V1 Day 6，React 工作区、Markdown 安全预览、Chat 来源与待确认动作交互，以及 AI 文本显式应用到 Wiki 草稿；Pi 未发现阻断性问题，审查项已集中修正并由 Codex 回归验证。
- 已完成：V1 Day 7 完整 Compose、演示数据、安全配置生成、接口验收和真实浏览器验收。
- 进行中：V1.2 固定面试账号、随机备用账号、SSE 流式回答和视觉体验升级。

## 先读文档

- [文档中心](docs/README.md)
- [产品概览](docs/01-product/product-overview.md)
- [系统架构](docs/02-architecture/system-overview.md)
- [本地开发](docs/05-development/local-development.md)
- [当前变更](docs/07-changes/2026-09-05-day-7-v1-acceptance.md)
- [公开仓库安全](docs/00-governance/public-repository-security.md)
- [单机生产部署](docs/06-operations/production-single-host.md)

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

最快方式只需要 Git、PowerShell 和 Docker Desktop：

```powershell
.\scripts\setup-local-env.ps1
# 打开 .env，把 LLM_PROVIDER 改成 deepseek/zhipu/qwen，并填写 LLM_API_KEY
notepad .env
docker compose --env-file .env -f infra/compose.yaml up --build -d
.\scripts\demo\seed-v1.ps1
```

在 `.env` 中选择 `deepseek`、`zhipu` 或 `qwen` 并填写 `AGENTFORGE_AGENT_LLM_API_KEY`，再打开 `http://127.0.0.1:5173`，使用演示脚本输出的账号密码登录。默认 `disabled` 模式无需外部 key；`.env` 中的模型 key、JWT 与两个内部 token 均不会提交。完整配置和手工运行方式见 [本地开发文档](docs/05-development/local-development.md)。

> Compose 方式不要求本机安装 Java、Python 或 Node。只有手工开发模式才需要 Java 21、Python 3.12–3.14 与 Node.js。

## 生产部署

生产环境使用 `infra/compose.prod.yaml`，只有 Nginx gateway 发布 80/443。先复制 `.env.production.example` 到服务器安全目录并替换全部占位符，再按[单机生产部署手册](docs/06-operations/production-single-host.md)执行；禁止把服务器 `.env`、SSH 私钥或模型 key 放入仓库。
