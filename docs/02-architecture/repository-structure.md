# 仓库结构

- 状态：Accepted
- 决策：单仓库，多应用，职责明确

## 目标结构

```text
AgentForge/
├─ AGENTS.md                 # AI 项目级硬规则
├─ README.md                 # 人类入口与快速开始
├─ apps/
│  └─ web/                   # React + TypeScript + Vite 项目工作区
├─ services/
│  ├─ core-api/              # Java 21 + Spring Boot，确定性业务系统
│  └─ agent-service/         # Python + FastAPI + LangGraph，V1 Day 3 开始实现
├─ infra/
│  └─ compose.yaml           # 本地 PostgreSQL / Redis
├─ scripts/                  # 跨平台或开发辅助脚本
├─ docs/                     # 产品、架构、功能、API、开发、运维与变更历史
├─ .github/workflows/        # 自动化质量门禁
└─ .env.example              # 可提交的环境变量说明，不包含真实密钥
```

## 为什么使用单仓库

- V1 的 Web、Java、Python 和接口会一起演进，同一变更可在一个 diff 中同时展示契约与实现。
- 文档、Compose、测试和接口契约有一个稳定入口。
- 用户可以清楚查看一次功能改动跨越了哪些组件。
- 应用仍保持独立构建和部署目录，未来需要拆仓库时边界清楚。

## 命名规则

- 可部署单元放在 `apps/` 或 `services/`，目录使用 kebab-case。
- Java 包从 `com.agentforge.core` 开始，并按业务能力分包。
- 文档文件使用英文 kebab-case，正文使用中文，方便命令行和跨平台链接。
- 数据库迁移使用 `V<序号>__<description>.sql`。
- API 从 `/api/v1` 开始，破坏性变化通过新版本或明确迁移处理。

## 依赖方向

- `apps/web` 依赖公开 HTTP 契约，不依赖服务内部代码。
- `services/agent-service` 通过公开/内部 HTTP 契约调用 `services/core-api`，不直接连接业务数据库。
- `services/core-api` 不依赖 Web，也不依赖 Python 代码。
- `infra` 负责运行依赖，不存放业务逻辑。
- `docs` 描述所有目录，但实现目录不能成为唯一设计来源。

## Git 边界

仓库采用真实、可读的阶段性提交。AI 在完成文档、实现和验证后创建提交，并在提交成功后立即停止；用户查看阶段汇报和仓库状态，明确确认后才进入下一阶段。提交前不重写用户已有历史，不把密钥、构建产物或本地数据库数据加入 Git。
