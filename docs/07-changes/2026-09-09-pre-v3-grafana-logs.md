# Grafana 日志界面（独立运维增强）

- 日期：2026-09-09
- 状态：In Progress（用户要求创建紧急可回滚检查点；Pi 审核和生产部署仍待后续完成）
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

### 尚未完成

- 未运行最终 gitleaks/敏感信息扫描。
- 未执行 L2 DeepSeek Pi Diff Review，也没有生成本次 `docs/08-reviews/` 报告。
- 未把本次文件单独暂存；未创建 Git commit、未推送远端、未核验远端引用。
- 未部署到生产服务器，也未执行公网 `/grafana/` 验收；现有服务器私有 `.env` 仍需添加 Grafana 管理员配置。

## 后续接手步骤

1. 先检查 `git status` 和本记录列出的范围。保留用户原有的 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md` 修改、`.worktrees/` 与 `AgentForge_产品规划与三阶段迭代路线.docx`，不得把它们混入本次提交或发送给外部 Reviewer。
2. 复核最终 diff，重点检查 Alloy Docker socket 权限、Grafana 子路径、生产 `.env` 升级兼容和 `grafana-logs-smoke.ps1` 的精确清理。
3. 对本次显式文件执行最终敏感扫描；不要扫描或输出本机真实 `.env` 内容。
4. 仅暂存本次 Grafana 范围，以 `HEAD → INDEX` 调用一次 L2 Pi Diff Review。按照项目规则，Pi 只读审核，不修改、不测试、不提交。
5. 逐条判断 Pi finding；只有可复现的阻断问题才修复并重跑受影响验证。回填本记录并将状态改为 `Implemented`。
6. 确认暂存区只包含本次范围后创建可读提交，非强制推送 `codex/pre-v3-grafana-logs` 并核验远端 commit。生产发布仍需用户对 main 与服务器变更另行明确授权。

本次特殊交接不修改 `AGENTS.md`，也不改变其中的文档先行、测试、Pi 只读审核、提交和推送门禁。

## 紧急检查点说明

因本周 Codex 额度即将耗尽，用户于 2026-09-09 明确要求先把当前已验证内容提交并推送到独立分支，避免后续接手者误改后无法回滚。本次提交是可恢复的开发检查点，不表示 L2 Pi Diff Review、生产部署或最终交付已经完成；后续接手者必须先完成敏感扫描复核和 Pi 只读审核，再判断是否需要修复并进入生产发布流程。

## 风险与回滚

- 风险等级预估：L2；涉及生产 Compose、反向代理、外部镜像、管理员凭据和 Docker socket。
- Alloy 访问 Docker socket 可读取容器元数据与日志；通过只读挂载、只读根文件系统、移除全部 capabilities 后仅恢复数据卷所需的 `DAC_OVERRIDE`、禁止提权及 project label 过滤缩小暴露面，但不能把只读 socket 视为强隔离。
- Loki filesystem 只适合当前单机低流量场景；7 天 retention 不能替代磁盘告警，磁盘仍需运维巡检。
- 回滚时恢复此前提交并重新部署；业务服务不依赖 Grafana/Loki/Alloy，观测栈失败或移除不应影响业务请求。删除日志/界面 named volume 属于破坏性操作，不包含在自动回滚中。
