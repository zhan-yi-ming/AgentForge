# Day 7 V1 验收与本地交付收尾

- 状态：Implemented
- 日期：2026-09-05
- 阶段：V1 / Day 7

## 背景

Day 1–Day 6 已完成 Web → Java → Python Agent → RAG / Tool → Java 写回闭环。Day 7 不新增 V2/V3 能力，而是把现有三应用变成可重复启动、可生成安全本地配置、可创建演示数据并可一键验收的 V1 本地交付物。

## 范围

- 为 Web、Core API、Agent Service 增加本地容器构建，并扩展 `infra/compose.yaml` 为完整 V1 栈。
- 增加 PowerShell 本地配置生成脚本：只写被 Git 忽略的 `.env`，分别生成 JWT、Java→Python 与 Python→Java 三个独立随机 secret。
- 增加幂等友好的演示数据脚本，通过公开 API 注册/登录并创建 Project、Wiki 和 Task，不直接写数据库。
- 增加 V1 验收脚本，验证服务健康、Web 入口、鉴权、Project/Wiki/Task、RAG Chat、Tool proposal、confirm/reject 与清理。
- 更新 README、本地启动、运维和测试文档，明确必需 secret、可选 OpenAI-compatible Embedding key 及无 key 的默认 hash 模式。

不在本次范围：生产 Kubernetes/云部署、TLS、域名、外部密钥管理、真实生成式 LLM、SSO、Neo4j、Langfuse、LiteLLM、MCP 或 V2 可靠性能力。

## 目标运行方式

```powershell
.\scripts\setup-local-env.ps1
docker compose --env-file .env -f infra/compose.yaml up --build -d
.\scripts\demo\seed-v1.ps1
```

浏览器访问 `http://127.0.0.1:5173`。默认 `hash` Embedding 不需要外部 API key；若切换为 `openai`，只在本地 `.env` 添加 `AGENTFORGE_AGENT_OPENAI_API_KEY`，不得提交。

## 验证计划

1. 文档完成后再修改 Dockerfile、Compose 与脚本。
2. 对 PowerShell 脚本做语法解析和安全路径检查。
3. 执行 Web `npm ci`、10 项 Vitest 与 production build。
4. 执行 Agent Service pytest，包括真实 pgvector 集成测试。
5. 在真实 Docker Desktop 上从空构建运行完整 Compose，执行 V1 验收脚本并记录服务健康、业务断言与清理。
6. 执行 Java clean verify、敏感信息扫描、`git diff --check`，清理生成物。
7. 由 Codex 审核 Day 7 完整差异，并使用真实浏览器覆盖登录、数据加载、Chat、confirm/reject、AI 整理预览与非自动写回；Day 7 不再调用 Pi。

## 回滚思路

Dockerfile、脚本与 Compose 服务均为本地开发入口，不改变已有业务表和 HTTP 契约。回滚可恢复旧 Compose 并删除新增脚本；常规 `docker compose down` 保留数据卷，只有用户明确要求清空本地数据时才使用 `down -v`。

## 实施与验证回填

目标文档先行建立后才开始实现。当前已增加三应用 Dockerfile、Nginx API 反向代理、完整 Compose、安全配置生成脚本、演示数据脚本和一键 V1 验收脚本。验收脚本的所有公开业务请求均通过 Web/Nginx 入口，避免只验证后端直连。

首次完整镜像构建在 Core API 的 `dependency:go-offline` 阶段因 Maven Central 大包传输提前结束而失败。该步骤还会下载运行构建不需要的额外插件；按排障技能建立失败信号后，计划移除独立 go-offline 层，改用 BuildKit `/root/.m2` cache mount 直接执行 package，使网络中断后的有限重试能复用依赖缓存。

有限重试成功复用缓存并完成镜像构建。第一次启动时 Web 唯一不健康，容器日志与 healthcheck 证明确认为 Alpine 中 `localhost` 连接 IPv6、Nginx 监听 IPv4；将探针目标限定为 `127.0.0.1` 后，PostgreSQL、Core API、Agent Service 与 Web 全部 healthy。

第一次业务验收执行到最后一个断言时，PowerShell 7 将 `Invoke-RestMethod` 返回的 JSON 数组作为单一对象包裹，导致脚本层任务数误判。对隔离 PostgreSQL 做只读查询确认项目实际有 2 个 Task，action 分别为 `EXECUTED` 与 `REJECTED`，业务行为正确；去掉多余数组包装后重新运行通过。

## 当前验证证据

- 完整 Compose 构建与启动：退出码 0；四个服务均 healthy。
- `scripts/validation/v1-acceptance.ps1`：退出码 0；Web 200、Core/Agent `UP`、RAG 来源 1、确认成功、拒绝状态 `REJECTED`、最终 Task 2。
- `scripts/demo/seed-v1.ps1`：退出码 0；通过公开 API 创建 Project、Wiki 与 Task，输出登录信息且不输出 access token。
- Java Temurin 21.0.12.1：`mvnw.cmd clean verify` 75 项，0 失败、0 错误、6 跳过；跳过项是需显式外部 Agent URL 的契约类，已由本次真实 Compose 验收覆盖跨进程链路。
- Python 3.14.3 / pytest 8.4.2：17 项全部通过，包含真实 pgvector Testcontainers；2 条第三方弃用 warning，无失败或跳过。
- Node 24.14.0 / npm 11.9.0：Vitest 10 项全部通过；production build 通过。
- Docker 29.5.3 / Compose 5.1.4：用于空项目名和独立端口的真实本地验收。
- 浏览器验收：演示账号登录成功；Project/Wiki/Task 载入成功；确认提案后任务数从 1 更新为 2，拒绝提案后保持 2；AI 整理先产生预览，显式应用只修改浏览器草稿，新页面重新登录后 Wiki 仍为原始持久化内容。

用户明确决定 Day 7 不需要 Pi 审核，改由 Codex 完成代码与浏览器交互审核。Codex 最终审核未发现阻断问题，报告见 `../08-reviews/2026-09-05-review-day-7-v1-acceptance-codex.md`。

配置生成负向检查通过：已有 `.env.local` 时脚本拒绝覆盖且 SHA-256 不变，JWT 解码长度为 32 字节，两个内部 token 相互独立，文件被 Git 忽略。本机未安装 Gitleaks，因此使用仓库 review gate 相同的 private key、GitHub token、AWS key、OpenAI key、JWT、Bearer token 六类模式扫描当前 diff，0 命中；远端 GitHub Actions 继续执行最终 Gitleaks 门禁。

验收后已停止并删除精确项目名 `agentforge-day7-acceptance` 的容器、网络和临时 PostgreSQL 卷；已删除本次生成的 `.env.local`、Java `target`、Python `.venv`/pytest cache、Web `node_modules`/`dist`/TypeScript 产物。用户原有 `.env`、未跟踪产品规划 DOCX 和无关工作树修改均未改动或纳入清理。
