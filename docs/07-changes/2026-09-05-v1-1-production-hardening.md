# V1.1 公网 Demo 加固与单机部署

- 状态：Implemented
- 日期：2026-09-05
- 目标环境：阿里云中国香港 ECS，Ubuntu 22.04，2 vCPU / 4 GiB / 40 GiB

## 背景

V1 已完成本地全栈与国内兼容模型调用，但本地 Compose 会发布数据库和应用端口，公共注册默认开放，也没有公网 Demo 的请求预算、TLS、备份和回滚路径。直接把该形态暴露到公网会增加凭据、数据和模型费用风险。

## 目标与范围

- 建立 `feature/* -> dev -> main` 的集成与发布分支流程。
- 新增单机生产 Compose：仅 Nginx gateway 发布 80/443；PostgreSQL、Core API、Agent Service和 Web 只在 Docker 内网通信。
- 生产环境关闭公共注册；Demo 用户通过受控脚本创建，只有普通 USER 权限和独立 workspace。
- Core API 对 AI Chat 使用数据库原子日计数；Nginx 对登录和 API 请求执行按 IP 限速；Agent Service 限制模型最大输出 Token。
- Docker 日志启用大小与文件数上限；服务器初始化 4 GiB Swap。
- 提供环境初始化、部署、健康检查、日志、备份、更新和回滚命令。
- 公网 IP 使用短期 IP TLS 证书并自动续期；真实密钥只保存在服务器 `.env`。

## 非目标

- 不实现 V2 的完整 RBAC、持久化 Memory、Langfuse、评测平台或多模型路由。
- 不开放自由注册、管理员后台或跨 workspace 的 Demo 能力。
- 不引入 Redis 作为配额事实源。

## 设计与测试边界

- 公共 HTTP seam：注册开关关闭时 `POST /api/v1/auth/register` 返回 403；AI 日配额耗尽时 Chat 返回 429，且超额请求不调用下游 Agent。
- LLM seam：三家兼容模型构造参数都包含可配置 `max_tokens`。
- 基础设施 seam：生产 Compose 渲染结果只有 gateway 发布主机端口，并包含日志轮转、只读挂载和健康检查。
- 真实部署 seam：公网 HTTPS 首页、Core API 健康检查、Demo 登录与一次 Agent Chat。

## 计划变更

- 文档：ADR-0013、认证与 Agent Chat 功能、Core API/Agent API、Git 工作流、生产运维手册和根 README。
- Core API：生产注册开关、AI 日配额配置与 PostgreSQL 迁移、429 Problem Detail。
- Agent Service：模型最大输出 Token 配置。
- Infra：生产 Compose、Nginx gateway、服务器与发布脚本。
- Demo：随机密码初始化脚本及独立演示数据。

## 安全影响

密钥不进入浏览器、镜像或 Git；生产配置必须显式提供数据库密码、JWT secret、内部 token 和模型 key。配额更新由 PostgreSQL 单语句原子完成，避免并发超发。公网只允许 80/443；22 由云安全组限制到维护者 IP。

## 回滚思路

每次部署先备份数据库并记录上一 Git commit；回滚脚本切换到上一 commit、顺序重建并启动服务。数据库迁移只新增配额表，可向后兼容旧版本；紧急时也可把 LLM provider 改为 `disabled` 停止外部费用。

## 验证证据

### 已实现

- Core API 新增 Flyway V5 `ai_usage_daily`、PostgreSQL 原子条件 upsert、429 Problem Detail 与生产注册开关。
- Agent Service 为 DeepSeek、智谱、千问统一传入 64–4096 范围内的 `max_tokens`，生产默认 800。
- `infra/compose.prod.yaml` 只发布 gateway 的 80/443；五个服务均限制为 10 MiB × 3 个 JSON 日志文件，PostgreSQL 使用显式生产卷名。
- Nginx 分别限制登录、Agent Chat 和一般 API；终止 TLS 并转发可信代理头。
- 新增 Ubuntu/Docker/4 GiB Swap 初始化、环境生成与校验、顺序构建、TLS、Demo 初始化、健康检查、日志、备份、更新和代码回滚脚本。Demo 初始化时先停止 gateway，避免临时注册窗口暴露公网。
- 新增生产配置边界和隔离全栈烟测；烟测使用唯一容器项目及唯一 PostgreSQL 卷并在结束时清理。

### Codex 机器验证

- Java：`services/core-api/mvnw.cmd clean verify`，Maven 3.9.11 / Java 21.0.12.1，退出码 0；79 tests，0 failures，0 errors，6 skipped。跳过项是既有的外部 uvicorn 契约条件测试；PostgreSQL 17.11 Testcontainers 成功执行 V1–V5 迁移与配额持久化测试。
- Python：`.venv/Scripts/python.exe -m pytest -q`，Python 3.14.3 / pytest 8.4.2，退出码 0；30 passed，0 failed，3 个非阻断弃用/缓存警告。
- Web：`npm test -- --run`，Node 24.14.0 / npm 11.9.0，退出码 0；3 files / 10 tests passed。`npm run build` 退出码 0，Vite 7.3.6 生成生产 bundle。
- 镜像：生产 Compose 按 core-api、agent-service、web 顺序构建，三者退出码均为 0。
- 配置：`scripts/validation/v1-1-production-config.ps1` 退出码 0，确认只有 gateway 发布 80/443、5 个服务均设置日志上限。
- 网关：一次性 Nginx 1.29-alpine 执行 `nginx -t` 成功；临时自签证书目录已清理。
- 全栈：`scripts/validation/v1-1-production-smoke.ps1` 退出码 0；5 个隔离容器全部 healthy，HTTPS 首页 200，关闭注册返回 403；容器、network、专用 volume 与临时目录全部删除。
- Shell：Git Bash `bash -n scripts/deploy/*.sh` 退出码 0；ShellCheck 0.11.0 排除动态 source 信息项 SC1090/SC1091 后退出码 0，无其他发现。
- 安全：Gitleaks 8.30.1 对 staged diff 扫描约 53 KiB，退出码 0，`no leaks found`。首次不合适的全目录扫描命中被 `.gitignore` 排除的本地 `.env`/虚拟环境后已中止，未用其替代提交门禁。
- 格式：`git diff --cached --check` 退出码 0。

### 诊断记录

- 首次配额集成测试的第三次调用已正确抛出限额异常，但断言错误使用数据库会话 `current_date`；数据库与 Java UTC 日界线不同。测试改为按用户读取并显式断言 UTC 日期后全量通过，生产逻辑未放宽。
- 首次烟测安全评估拒绝可能歧义的 volume 清理；生产卷改为显式可配置，烟测固定并验证唯一专用卷。随后发现 PowerShell 把 `-d` 当作 Debug 参数，改用简单函数原样转发 `$args` 后烟测通过并完成清理。

### 独立审核状态

已按用户授权调用 `run-review.ps1`，但在发送 diff 前因本机缺少 `pi.cmd` 立即失败；没有代码发送、没有报告、没有降级模型或重试。用户于 2026-09-06 明确豁免本次 Pi 审核并授权直接提交 `dev`；本次独立审核限制保留在记录中，不以 Pi 文字结论替代任何机器测试。
