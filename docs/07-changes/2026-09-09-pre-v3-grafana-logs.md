# Grafana 日志界面（独立运维增强）

- 日期：2026-09-09
- 状态：Implemented（代码、验证与 Pi Diff Review 已完成；生产部署需另行授权）
- 阶段：V2 stable 后的独立运维增强（不属于 V3 Node）
- 交付目标：远程分支 `codex/pre-v3-grafana-logs`

## 背景

生产环境已有受限大小的 Docker JSON 日志和命令行查看脚本，但维护者需要登录服务器、逐服务执行命令，不能在浏览器中按服务、关键词或 `request_id` 快速关联 Core API 与 Agent Service 日志。本次作为独立运维增强补齐最小日志界面，不属于任何 V3 Node，也不处理或推进 V3。

## 目标

- 在现有 HTTPS gateway 下提供 `/grafana/` 登录入口，不新增公网端口。
- 使用 Grafana + Loki + Alloy 收集当前 `agentforge` Compose project 的容器日志。
- 预置 Loki 数据源和 AgentForge Logs 页面，支持按服务、关键词和 `request_id` 查询。
- 日志默认保留 7 天，采集和存储故障不阻塞业务容器。
- 生产环境强制设置独立 Grafana 管理员密码并关闭匿名访问和公开注册。

## 非目标

- 不采集主机指标、应用 metrics、distributed tracing 或告警。
- 不替代 Langfuse，不把日志与 Trace 自动合并。
- 不修改业务 API、数据库 schema、Java/Python 业务执行边界或 V3 路线。
- 不建设高可用/分布式 Loki，不承诺文件系统存储适用于大规模生产日志。

## 受影响文档

- `docs/03-features/observability.md`：补充日志界面的用户流程、安全边界和限制。
- `docs/06-operations/production-single-host.md`：补充配置、访问、查询、排障与回滚方式。
- `docs/02-architecture/decisions/ADR-0023-single-host-grafana-loki-logs.md`：记录引入 Grafana、Loki、Alloy及 Docker socket 取舍。

## 设计决定

- 公共 seam：`https://<PUBLIC_HOST>/grafana/`；仅 gateway 暴露 80/443，Grafana 自身登录保护界面。
- 采集 seam：Alloy 通过 Docker discovery 读取日志，只保留 Compose project label 为 `agentforge` 的容器，并写入 Loki。
- 查询 seam：预置 Loki 数据源和 `AgentForge Logs` dashboard，以 `service` 为低基数标签，关键词和 `request_id` 使用行过滤。
- 存储：单机 Loki TSDB + filesystem，7 天 retention；Grafana 与 Loki 使用独立 named volume。
- 镜像固定为 Grafana `12.3.11`、Loki `3.7.0`、Alloy `v1.19.0`，后续升级必须单独验证配置兼容性。

## 实现

- `infra/compose.prod.yaml` 增加固定版本的 Grafana `12.3.11`、Loki `3.7.0` 和 Alloy `v1.19.0`。三个服务均只接入内部 Compose 网络，不发布主机端口；业务服务不依赖观测栈。
- `infra/observability/` 增加 Loki 7 天 retention、Alloy Docker discovery、Grafana Loki datasource 和 `AgentForge Logs` dashboard provisioning。
- `infra/nginx/production.conf.template` 增加 `/grafana/` HTTPS 反向代理和 WebSocket headers；Grafana 关闭匿名访问与用户自助注册。
- `.env.production.example`、`generate-production-env.sh` 与 `validate-env.sh` 增加 Grafana 管理员用户名/强密码和三个 named volume 配置；IPv6 使用带方括号的 `PUBLIC_URL_HOST`。
- `deploy.sh`、`health-check.sh` 与 `logs.sh` 纳入观测服务的拉取、启动、认证健康检查和日志查看。
- 新增 `grafana-logs-config.ps1` 和 `grafana-logs-smoke.ps1`，分别验证静态生产边界与隔离三容器真实运行链路。
- 首次真实 smoke 发现 Alloy 在 `cap_drop: ALL` 后无法写入 UID 473 数据卷。根因是 root 丢失 `DAC_OVERRIDE`；最终只为 Alloy 恢复该 capability，保留只读根文件系统、只读 Docker socket 和 `no-new-privileges`。

## 验证结果

- 风险规划：`scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths <本次显式路径> -Json`，退出码 0；结果为 L2、Deployment/Docs/TlsDeployment/Unknown、Pi Diff Review，最终 change fingerprint 为 `680eba3ceef224ca0b19a05171cf7504ed23dee02bdd05d2c237155074ec9532`。
- TDD 红灯：`& .\scripts\validation\grafana-logs-config.ps1`，退出码 1；只发现原 5 个服务，符合实现前预期。
- 静态配置：`& .\scripts\validation\grafana-logs-config.ps1`，退出码 0；认证子路径、仅内部服务、project-scoped 采集、7 天 retention 与 dashboard provisioning 通过。
- 生产 Compose：`& .\scripts\validation\v1-1-production-config.ps1`，退出码 0；8 个服务均使用 `10m × 3` 日志上限，只有 gateway 发布 80/443。
- Shell parser：`& 'C:\Program Files\Git\bin\bash.exe' -n scripts/deploy/*.sh scripts/validation/tls-public-host-contract.sh`，退出码 0。
- ShellCheck：`docker run --rm -v "${PWD}:/repo:ro" koalaman/shellcheck-alpine:v0.11.0 shellcheck -e SC1090,SC1091,SC2034 /repo/scripts/deploy/common.sh /repo/scripts/deploy/deploy.sh /repo/scripts/deploy/generate-production-env.sh /repo/scripts/deploy/health-check.sh /repo/scripts/deploy/logs.sh /repo/scripts/deploy/validate-env.sh /repo/scripts/validation/tls-public-host-contract.sh`，退出码 0；排除项为仓库既有动态 source/公共变量告警。
- Nginx/TLS：`& .\scripts\validation\tls-public-host-nginx.ps1`，退出码 0；IPv4、IPv6、根域名和已有 `www` 配置均通过真实 Nginx 解析。
- Linux Shell contract：`docker run --rm -v "${PWD}:/repo:ro" agentforge-agent-service:latest bash /repo/scripts/validation/tls-public-host-contract.sh`，退出码 0；生产环境校验、生成和 TLS contract 通过。
- Loki 版本校验：`docker run --rm --mount "type=bind,source=${PWD}\infra\observability\loki\config.yaml,target=/etc/loki/config.yaml,readonly" grafana/loki:3.7.0 "-config.file=/etc/loki/config.yaml" "-verify-config=true"`，退出码 0，输出 `config is valid`。
- Alloy 版本校验：`docker run --rm --mount "type=bind,source=${PWD}\infra\observability\alloy\config.alloy,target=/etc/alloy/config.alloy,readonly" grafana/alloy:v1.19.0 validate /etc/alloy/config.alloy`，退出码 0。
- 首次 Loki/Alloy smoke 退出码 1，真实复现 Alloy 数据卷权限失败；专用容器、网络和 volumes 已清理。最小 capability 修复后，端到端采集 `observability-smoke` service label 通过。
- 一次手工三容器 smoke 退出码 0：Grafana/Loki/Alloy 均运行，未认证 Grafana search 返回 401，管理员可读取预置 dashboard，Loki datasource 为 OK，Alloy 成功写入 Loki；专用资源已清理。
- 固化后的最终 smoke：`& .\scripts\validation\grafana-logs-smoke.ps1`，退出码 0；认证、dashboard、Loki datasource、project-scoped 日志采集均通过，脚本确认专用容器和 volumes 无残留。
- `git diff --check` 已运行并退出码 0；仅报告两个既有 PowerShell 文件未来可能发生 LF→CRLF 转换的 warning，无空白错误。

### 2026-09-14 检查点续作

- 远端 preflight：本地 `HEAD` 与 `origin/codex/pre-v3-grafana-logs` 均为 `a143e7803da95e0e6b14c09d63b7e95b917da307`；用户原有 Markdown、`.worktrees/` 与 DOCX 仍保持未暂存/未跟踪且未进入本次范围。
- ref-to-ref 规划：以 `af7afa3a2e743a3a8e4dce63845a7b62d20e28da` 为 base、`HEAD` 为 target，对 24 个显式路径运行规划器，退出码 0；结果仍为 L2、Deployment/Docs/TlsDeployment/Unknown、Diff Review，fingerprint 为 `b5292e7928176be730c171385f5e7f2de90571f0d6f91f51271951aa2e234cae`。
- 安全复核：45,009 字节统一 diff 的私钥、GitHub/AWS/OpenAI key、JWT 与 Bearer 正则均为 0 命中；随后 `zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1` 扫描完整 ref-to-ref diff 约 51.40 KB，退出码 0，`no leaks found`。
- 不依赖真实业务环境的门禁重新通过：`grafana-logs-config.ps1`、`v1-1-production-config.ps1`、Git Bash parser 与 `tls-public-host-nginx.ps1` 均退出码 0；Compose 仍为 8 个服务、仅 gateway 发布 80/443，Nginx IPv4/根域名/已有 `www` 配置通过。
- 真实 smoke 红灯：在本机已有超过 7 天的 `agentforge` 容器 bounded logs 时，`grafana-logs-smoke.ps1` 退出码 1。Alloy 已正常运行并连接 Loki，但首次回放旧日志被 Loki retention 以 HTTP 400 `timestamp too old` 拒绝；脚本把该预期丢弃与真正采集故障一并判为致命错误。本次随机项目的容器、网络和三个 volumes 已全部清理。
- 修复边界：第一次最小过滤后，第二次红灯捕获 Loki 对旧回放的等价 HTTP 400 `entry too far behind`；最终 smoke 只忽略同时包含 `status=400` 和 `timestamp too old` / `entry too far behind` 的旧条目拒绝行，继续阻断其他 `Error:`、`failed to` 和 `level=error`。随后必须用同一真实链路验证当前 `observability-smoke` 日志仍成功进入 Loki。
- 真实 smoke 绿灯：修复后再次运行 `& .\scripts\validation\grafana-logs-smoke.ps1`，退出码 0；在旧日志仍存在的同一 Docker 环境中，Grafana 认证、dashboard、Loki datasource 和当前 `observability-smoke` project-scoped 日志采集均通过，随机项目的容器、网络和三个 volumes 均已清理。
- 预审核 INDEX 门禁（证据回填前）：只暂存本记录与 smoke 判别修复，用户原有内容仍未暂存；以旧 base 到 INDEX 的 24 文件完整范围运行规划器，退出码 0，L2 / Diff Review，fingerprint 为 `0486ae8c343b7d8e0c655f059a0e529fe1fab6ea1b9ee22c0b3943095bb696ea`。`git diff --cached --check` 退出码 0；Gitleaks v8.30.1 扫描约 53.70 KB，退出码 0，`no leaks found`。

### 生产部署后续

- 未部署到生产服务器，也未执行公网 `/grafana/` 验收；现有服务器私有 `.env` 仍需添加 Grafana 管理员配置。

### Pi Attempt 1 研判

- Pi Diff Review 报告：`docs/08-reviews/2026-09-15-review-pre-v3-grafana-logs-attempt-1.md`，结果 `NEEDS_FIX`。
- Issue 1 采纳：Compose 依赖已经保证业务运行不依赖观测栈，但 `update.sh` / `rollback.sh` 共用的 `health-check.sh` 会因任一观测容器停止而失败，和 fail-open 运维契约不一致。业务五服务及 HTTPS/API 认证保持硬门禁；Grafana/Loki/Alloy 与 Grafana 认证检查改为非零 warning。
- Issue 2 采纳：先以 Grafana regex formatter 修复反引号插值，Attempt 2 的 S1 建议进一步收敛为 `${search:json}` 与 LogQL `|=` 纯文本包含，完整保护字符串定界符并保持关键词语义。
- Issue 3 采纳：生产 Grafana 管理员密码改为至少 24 字符、同时包含 ASCII 字母与数字且不能全部为同一字符；生成器使用 `Af9` 类别前缀和 48 字符随机 hex。文档同步为可验证要求，仍建议只使用随机生成值。
- Issue 4 采纳：只对精确 `/grafana/login` 入口复用现有 `login_per_ip` 限流，避免给 dashboard 静态资源和日志查询整体限流。
- Issue 5 采纳：smoke 改为运行时用密码学安全随机源生成一次性管理员密码，不把固定测试口令保存在仓库。
- 修复验证：更新后的 `grafana-logs-config.ps1` 首次因缺少精确登录限流退出码 1；Linux `tls-public-host-contract.sh` 首次因重复字符 Grafana 密码仍被接受退出码 1。实现后两者均退出码 0，后者额外模拟五个业务服务运行、三个观测服务停止，确认业务健康通过且输出 observability warning。
- 回归门禁：`v1-1-production-config.ps1`、Git Bash parser、ShellCheck v0.11.0、真实 Nginx IPv4/根域名/已有 `www` 解析均退出码 0；配置仍为 8 服务、仅 gateway 发布 80/443。
- 最终真实 smoke：运行时密码学随机管理员密码通过 curl stdin config 认证，不进入 curl 命令行参数；Grafana 登录保护、dashboard、Loki datasource、当前 project-scoped 日志采集均通过，退出码 0，随机容器、网络和三个 volumes 全部清理。
- Pi Attempt 2 结果 `PASS`，五个阻断项全部关闭。唯一低风险建议 S1 指出 regex formatter 不转义 LogQL 双引号/换行；采纳为 `${search:json}` + `|=` 纯文本包含，避免查询结构变化或把输入解释为正则。该项属于 PASS 后纯建议，按审核规则不触发第三轮 Pi，只重跑相关配置与真实 smoke。
- S1 红灯与回归：更新配置契约后，旧 dashboard 表达式使 `grafana-logs-config.ps1` 退出码 1；最小实现后配置契约和 `grafana-logs-smoke.ps1` 均退出码 0，Grafana provisioning、认证、Loki datasource 与当前日志采集通过，专用资源全部清理。

## 后续接手步骤

1. 生产发布仍需用户对共享 `main` 与服务器变更另行明确授权。
2. 发布前在服务器私有 `/opt/agentforge/env/.env` 增加满足新规则的 Grafana 管理员配置，运行 `validate-env.sh`，再按运维文档执行更新和公网验收。
3. 公网验收必须检查 `/grafana/` 登录、错误登录限流、预置 dashboard、按 service/request_id 查询、观测故障 warning 与业务健康 fail-open；真实凭据和日志不得进入报告。

本次特殊交接不修改 `AGENTS.md`，也不改变其中的文档先行、测试、Pi 只读审核、提交和推送门禁。

## 紧急检查点说明

因本周 Codex 额度即将耗尽，用户于 2026-09-09 明确要求先把当前已验证内容提交并推送到独立分支，避免后续接手者误改后无法回滚。本次提交是可恢复的开发检查点，不表示 L2 Pi Diff Review、生产部署或最终交付已经完成；后续接手者必须先完成敏感扫描复核和 Pi 只读审核，再判断是否需要修复并进入生产发布流程。

## 风险与回滚

- 风险等级预估：L2；涉及生产 Compose、反向代理、外部镜像、管理员凭据和 Docker socket。
- Alloy 访问 Docker socket 可读取容器元数据与日志；通过只读挂载、只读根文件系统、移除全部 capabilities 后仅恢复数据卷所需的 `DAC_OVERRIDE`、禁止提权及 project label 过滤缩小暴露面，但不能把只读 socket 视为强隔离。
- Loki filesystem 只适合当前单机低流量场景；7 天 retention 不能替代磁盘告警，磁盘仍需运维巡检。
- 回滚时恢复此前提交并重新部署；业务服务不依赖 Grafana/Loki/Alloy，观测栈失败或移除不应影响业务请求。删除日志/界面 named volume 属于破坏性操作，不包含在自动回滚中。
