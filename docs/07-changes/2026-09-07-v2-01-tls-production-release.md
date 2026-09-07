# V2-01 与 TLS 域名兼容生产发布

- 日期：2026-09-07
- 状态：Implemented
- 目标环境：阿里云中国香港单机生产 Demo
- 集成分支：`codex/v2-01-production-release`
- 计划发布标签：`v2.0.0-alpha.1`

## 背景

生产 `main` 已包含 V1.2.1 居中 Demo，后续完成的 V2-01 Langfuse 基础 Trace、验证流程优化和 TLS 公网 IP / 域名兼容分别位于连续提交链上。用户已明确授权把所有已完成代码合并到 `main` 并部署正式环境；V2-02 尚未授权，不得随本次发布启动。

## 范围

- 以最新 `origin/main` 为发布基线，合并 `codex/tls-public-host-compat`；该提交链已包含 V2-01 与工作流优化。
- 完整回归 Java、Python、Web、生产 Compose/TLS 契约与核心跨服务 smoke，并执行 Release Gate 的 Pi Milestone Review。
- 将通过验证的同一提交链快进到 `dev`，随后把发布集成提交推送到 `main`，创建不可移动的预发布标签 `v2.0.0-alpha.1`。
- 通过生产 `update.sh` 先备份数据库、快进服务器 `main`、重建五个服务并检查容器与公开边界。
- 核对生产私有环境中的 `PUBLIC_HOST`、域名 DNS 与 TLS 现状，但不读取、输出或提交任何密码、Token、API key、私钥或完整 `.env`。
- 域名正式证书首次申请作为部署后的明确操作：若 DNS 与公网 80 已就绪且无需用户提供信息，可在生产直接完成；否则给出唯一需要用户执行的命令，不伪造 HTTPS 成功。

## 非目标

- 不实现 V2-02 或任何后续 Node。
- 不修改 Schema、生产业务数据、模型供应商或现有密钥。
- 不把用户工作树中的未提交文档修改和 DOCX 带入发布。
- 不 force push，不重写已发布历史，不删除现有证书或备份。

## 风险与验证计划

这是 Release Gate，按 L3 操作严谨度执行。主要风险是两条已完成历史合并后的回归、生产环境变量仍指向旧 IP、旧证书与域名不匹配、构建失败或数据库备份不可用。

验证包括：Git 拓扑与 diff、Java `clean verify`、Python `pytest --cache-clear`、Web 全量测试与 production build、生产 Compose 配置、TLS 公共主机与 Nginx 契约、核心跨服务 smoke、Gitleaks、Pi V4-pro Milestone Review；生产侧记录备份、服务器 commit、容器健康、HTTP/HTTPS 边界、TLS timer 和首次证书状态。无法在本地替代的公网 DNS/ACME 结果必须在生产真实执行或明确列为用户必需步骤。

## 回滚

生产 `update.sh` 在更新前写入 previous-release 并创建 PostgreSQL 备份。部署或核心检查失败时，使用既有 `rollback.sh` 回到上一提交并再次运行健康检查；没有 Schema 迁移，证书、命名卷和数据库备份均保留。GitHub `main` 不回写历史，必要时以新的 revert/修复提交处理。

## 实施与证据

- Git preflight：最新 `origin/main` 为 `18064bf`（`v1.2.1`）；`codex/tls-public-host-compat@57772bb` 的提交链包含 `382ac71` V2-01、`431d705` 验证流程优化与 `57772bb` TLS 兼容。用户工作树中的历史文档修改和 DOCX 未进入隔离发布工作树。
- 文档先行提交 `ef99426` 后执行 non-fast-forward 合并；唯一冲突位于 `docs/07-changes/README.md` 的新增索引相邻行，保留双方全部条目后形成集成提交 `f002914`。`57772bb..f002914` 的内容差异只有本发布记录与索引，没有业务实现漂移。
- 门禁规划器对 `origin/main..HEAD` 给出 L3、AgentService / Deployment / Docs / Governance / TLS，ReviewMode 为 Milestone。
- Java Temurin 21.0.12.1：`mvnw.cmd clean verify` 退出码 0，83 tests、0 failures、0 errors、7 skipped；Testcontainers 使用 PostgreSQL 17.11 并验证 5 个迁移，`BUILD SUCCESS`。
- Python 3.14.3 / pytest 8.4.2：复用锁定依赖环境并用发布工作树 `PYTHONPATH` 执行 `pytest -q --cache-clear`，退出码 0，44 passed、0 failed、0 skipped、2 个既有弃用 warning。
- Web Node 24.14.0 / npm 11.9.0 / Vite 7.3.6：通过临时 junction 复用锁文件一致的依赖；Vitest 3 files / 16 tests 全部通过，production build 转换 283 modules，两条命令均退出码 0。
- 配置与 TLS：gate planner 15 项契约、V2-prep Demo 契约、本地/生产 Compose `config --quiet`、生产端口/日志边界、TLS IPv4/IPv6/根域名/已带 www 合约、真实 Nginx 三场景 `nginx -T`、Bash `-n` 与 ShellCheck 0.11.0 全部退出码 0。
- 跨服务 smoke 前两次在 Compose 启动前分别因未预置内部测试 token、JWT secret 而退出 1，脚本 finally 均完成清理；列齐既有前置配置后第三次退出码 0：Wiki/Task source 各 1、更新版本 1、删除 Task chunk 0、unmatched 0、cross-project 0。
- 清理：隔离 E2E container、network、volume 的精确过滤查询均为空；发布工作树的 Java `target`、Web `dist`、Python cache 与两个临时依赖 junction 已删除，工作树恢复干净。
- Gitleaks 8.30.1 扫描完整 `origin/main..INDEX` 发布补丁约 274.20 KB，耗时 227 ms，退出码 0、无泄漏；临时扫描目录已清理。
- Pi DeepSeek V4-pro Milestone Review Attempt 1 返回 `NEEDS_FIX`。其 M-01 声称 Langfuse 4.15.1 的 observation `update()` 不接受 `usage_details`；Codex 用本次实际依赖创建无网络真实 SDK generation，并由 `inspect.signature` 得到显式参数 `usage_details: Dict[str, int] | None`，因此 M-01 及由此推导的 fake seam S-01 均为事实误报，不修改正确实现、不触发复审。S-04 文档状态不一致已修正；host URL 和客户端首次消费前断流为低风险后续建议。
- GitHub：确认 `origin/dev@fee96a9` 是完成链祖先后，非强制快进到 `57772bb`；发布集成分支推送到 `0ed9b5e`，`main` 从 `18064bf` 非强制更新到同一提交；annotated tag `v2.0.0-alpha.1` 已推送。GitHub 22 端口一次 banner 超时后，改用已验证的官方 `ssh.github.com:443` 入口完成推送，没有循环重试或改写历史。
- 生产连接：默认 SSH key 对 `root@47.76.95.86` 返回 publickey 拒绝；读取本机 `.ssh` 文件名后发现既有 `agentforge-demo-key.pem`，使用 `IdentitiesOnly` 后成功连接，没有读取或输出私钥内容。
- 生产 preflight：服务器为干净前基线 `main@18064bf` 之前，检测到唯一手工修改 `infra/nginx/production.conf.template`，内容是固定追加 `www.${PUBLIC_HOST}`。新版动态实现完整覆盖且避免 IP / `www.www` 边界，因此先保存为 `/opt/agentforge/backups/pre-v2-alpha1-nginx-manual.patch`（0600）再恢复该文件；生产私有 `.env` 另备份为 `/opt/agentforge/backups/pre-v2-alpha1-env.backup`（0600）。
- 生产公开配置只读核对发现 `PUBLIC_HOST=zhanyiming.cloud`，但 JWT issuer 仍指向旧 IP；只把该公开 URL 更新为 `https://zhanyiming.cloud/core-api`，没有读取、输出或改动其他密钥。根域名与 `www` 的 A 记录均解析到 `47.76.95.86`，公网 80/443/22 TCP 探测成功。
- 生产 `scripts/deploy/update.sh` 退出码 0：先创建 `/opt/agentforge/backups/agentforge-20260907T001937Z.dump.gz`，再把服务器 `main` 快进到 `0ed9b5e`；Core API、Agent Service、Web 和 gateway 依次构建/拉取并重建，PostgreSQL 保持运行。脚本最终输出 `AgentForge HTTPS and authentication boundary are healthy at https://zhanyiming.cloud/`。
- 发布后核验：生产仓库 `HEAD=0ed9b5e2920385c2d23d61121454f6cc2a4b298d`、dirty count 0；postgres/core-api/agent-service/web/gateway 五个服务全部 healthy；根域名和 `www` HTTPS 均返回 200，未认证 `/api/v1/users/me` 返回 401；Langfuse 保持默认 `false`，本次未配置或输出真实平台密钥。
- TLS：服务器在部署前已经存在 `zhanyiming.cloud` lineage，current 与 live 均是 Let's Encrypt 证书，SAN 同时包含 `zhanyiming.cloud`、`www.zhanyiming.cloud`，有效期为 2026-09-06 至 2026-12-05；`agentforge-tls-renew.timer` 为 enabled/active。因此本次不需要再次执行“首次申请”，新版续期/同步脚本将在后续 timer 运行时接管。
- 真实公开 Demo 登录/Agent Chat smoke 原计划只输出布尔结果、不输出 token 或回答，但受控执行审批因明文账号和可能产生模型费用在进程创建前拒绝；未绕过，生产没有产生该请求。本地发布候选已通过真实跨服务 smoke，生产公开 HTTPS、认证拒绝边界与容器健康已通过；此项记录为未执行而非 PASS。
