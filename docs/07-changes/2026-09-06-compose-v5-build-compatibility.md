# Docker Compose v5 首次部署兼容性修复

- 状态：Implemented
- 日期：2026-09-06
- 影响环境：阿里云中国香港生产 Demo，Docker Compose v5

## 背景

首次生产部署已完成服务器初始化、4 GiB Swap、生产配置校验和 PostgreSQL 启动，但在构建 Core API 前停止。真实命令 `scripts/deploy/deploy.sh` 调用 `docker compose build --no-deps core-api`，服务器 Docker Compose v5.5.1 返回 `unknown flag: --no-deps`；本地 Compose v5.1.4 的 `docker compose build --help` 同样不提供该参数。

失败发生在 CLI 参数解析阶段，Core API、Agent Service、Web 和 gateway 尚未构建或启动。PostgreSQL 容器与命名卷保留，可由幂等部署脚本继续使用。

## 目标与范围

- 使首次部署和后续更新/回滚调用的顺序构建兼容 Docker Compose v5。
- 保持逐服务构建顺序，避免 2C4G 服务器并行构建。
- 增加自动化回归检查，拒绝在生产部署脚本中重新引入 Compose v5 不支持的 `build --no-deps`。
- 在修复推送并由服务器快进更新后，从现有 PostgreSQL 状态继续首次部署。

## 非目标

- 不修改应用业务逻辑、数据库模型、API 或公网安全组。
- 不删除 PostgreSQL volume，不重新生成生产 `.env`，不读取或输出真实模型密钥。
- 不升级或降级服务器 Docker/Compose。

## 实现计划

- 更新生产运维文档，明确 Compose v5 使用 `compose build <service>`；只有显式 `--with-dependencies` 才构建依赖，因此无需 `--no-deps`。
- 先扩展生产配置验证脚本并运行，确认它能捕获现有三个不兼容调用。
- 最小修改 `deploy.sh` 的三个构建命令，继续逐项构建和启动。
- 运行生产配置验证、Shell 语法检查、ShellCheck、Compose 配置渲染和服务器真实续跑。

## 回滚思路

脚本修改只移除不受支持的 CLI 参数。若修复后构建仍失败，可把服务器代码退回 `716a4a6`；保留现有 PostgreSQL 命名卷和 `/opt/agentforge/env/.env`。由于旧脚本在 Compose v5 上不可部署，回滚仅用于诊断，不能作为可工作的生产发布版本。

## 验证证据

### 失败与最小复现

- 服务器首次运行 `/opt/agentforge/repo/scripts/deploy/deploy.sh`：配置校验通过，PostgreSQL 17 镜像、network、命名卷和容器创建成功；随后 Docker Compose v5.5.1 在首个 `compose build --no-deps core-api` 返回 `unknown flag: --no-deps`，脚本退出码 1。
- 本地 Docker Compose v5.1.4 的 `docker compose build --help` 退出码 0，只提供显式的 `--with-dependencies`，不提供 `--no-deps`。`rg` 确认不兼容参数仅存在于 `deploy.sh` 的三个逐服务构建命令。
- 排障期间 HTTPS IP 查询经过不同出口并返回了不适用于 SSH 的地址，曾导致错误判断；用户本机与 Codex 环境的原始 `Test-NetConnection 47.76.95.86 -Port 22` 最终均返回 `TcpTestSucceeded: True`，实际 SSH 出口和原安全组单 IP 规则保持有效。未把 22 端口扩大到 `0.0.0.0/0`。

### TDD 回归

- 先扩展 `scripts/validation/v1-1-production-config.ps1`，扫描生产部署脚本中的 Compose v5 不支持参数；实现修改前运行，退出码 1，并精确报告 `deploy.sh uses build --no-deps`。
- 移除三个不支持参数后再次运行同一验证，退出码 0；继续确认只有 gateway 发布 80/443、五个服务使用 10 MiB × 3 日志上限。

### 当前本地门禁

- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/validation/v1-1-production-config.ps1`：退出码 0；Docker Compose v5.1.4。Docker 客户端因沙箱不可读取用户级 `config.json` 打印警告，但配置渲染和全部断言正常完成。
- `C:\Program Files\Git\bin\bash.exe -n scripts/deploy/*.sh`：退出码 0。PATH 中的 `bash.exe` 是受限制的 WSL 入口并退出 1，因此改用已核验的 Git Bash 绝对路径，不复用失败结果。
- ShellCheck 0.11.0：固定镜像 `koalaman/shellcheck-alpine:v0.11.0`，以只读仓库挂载运行，排除动态 source 信息项 SC1090/SC1091 后退出码 0。前两次容器调用分别因入口和通配符未展开退出 1，没有产生代码检查结论；修正调用后才计为有效通过。

### 服务器真实验证

- 在服务器现有 `dev@716a4a6` 源码和真实生产环境文件上运行 `docker compose --env-file /opt/agentforge/env/.env -f infra/compose.prod.yaml build core-api`，Docker Compose v5.5.1 接受命令并退出 0。
- Core API 镜像构建执行 Maven `-DskipTests package`，93 个 main source 与 19 个 test source 编译成功，测试按镜像构建策略跳过；Maven `BUILD SUCCESS`，最终生成 `agentforge-core-api:latest`。完整 Java 79 项 clean verify 已由 V1.1 主变更提供，本次只修改部署脚本，没有改变 Java 源码。
- PostgreSQL 容器在诊断前已持续 healthy；本次验证没有启动公网 gateway、没有修改生产 `.env`、没有删除 network 或命名 volume。构建缓存和 Core API 镜像保留，用于修复推送后的幂等部署续跑。

### 安全与独立审核

- Gitleaks 8.30.1 对本次 staged diff 扫描约 6.43 KiB，退出码 0，`no leaks found`；生产 `.env`、模型密钥和服务器日志未进入扫描范围或提交范围。
- 按 `pi-review-connection.md` 执行启动器与模型目录预检，启动器可调用，但 `--list-models v4-pro` 退出码 1。预检发生在构造和发送审核 diff 之前，因此没有代码发送给 Pi、没有审核报告，也没有安装、路径搜索、降级模型或循环重试。
- 用户于 2026-09-06 针对本次 Compose v5 兼容修复明确豁免 Pi 审核，并授权直接提交、推送 `dev`。该豁免不扩展到后续变更。

### 已知限制与后续检查点

- 本次修复不改变应用代码，Java/Python/Web 全量测试沿用 V1.1 已通过的基线；本次新增的部署 CLI seam 已通过本地静态回归、Git Bash、ShellCheck 和服务器真实镜像构建验证。
- 修复提交推送并核验后，服务器需要 fast-forward 到新 `dev` 提交，再从现有 healthy PostgreSQL 与已构建镜像状态幂等续跑部署。该动作属于推送后的下一检查点，需遵守仓库的强制中断规则。
