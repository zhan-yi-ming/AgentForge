# P3 功能生产发布

- 状态：Complete
- 日期：2026-09-23
- 候选：`codex/pre-v3-chat-experience` 的 `30af771440e2db6381b3df97e879d8b199d1e32d`；当前 `origin/main` 为 `af7afa3a2e743a3a8e4dce63845a7b62d20e28da`，已验证为候选祖先。
- 用户授权：将最新版本直接部署到 `zhanyiming.cloud`，原有三个未提交项保持原样。

## 发布目标与边界

发布 P3-01 至 P3-07 及随后的聊天、图谱、登录布局和 ASR 收尾修复。保持既有 V2 数据和登录视觉；不包含 V3 新功能。按照 `docs/06-operations/production-single-host.md` 固定顺序：发布级全仓回归与只读审核通过，候选非强制快进共享 `origin/main` 并核验，然后在干净生产 `main` 上运行 `scripts/deploy/update.sh`（先备份、再拉取构建和健康检查）。上线后验证 HTTPS、认证边界、聊天关键链路、数据库迁移和回滚指针。不得把本地 `.env`、用户未提交文件或真实密钥上传。

## 前置状态和风险

L3 Release Gate，影响 Web、Core API、Agent Service、Schema、Compose、TLS/部署配置及跨服务行为，执行全仓回归、Compose/跨进程/Evaluation、敏感扫描和 Pi Milestone Review。当前维护端对服务器 SSH/22 连接超时；在连通和生产工作树干净得到确认前，不更新共享 `main`。向第三方公网 IP 查询服务发送请求被自动审批拒绝，未采用替代服务绕过；已请用户在阿里云安全组中放行当前维护来源。

## 验证与部署回填

- `scripts/validation/plan-change-gates.ps1 -BaseRef origin/main -TargetRef HEAD -ReleaseGate -Json`：L3 / Milestone，Web、Core API、Agent Service、Schema、Deployment/TLS 与 Release 全域；含完整回归与跨进程门禁。
- `scripts/validation/v2-release-regression.ps1`：11/11 阶段通过，退出码 0。Java `clean verify` 132 tests / 0 failures / 0 errors / 8 条既有条件跳过；Python pytest 120 passed / 4 warnings（Starlette/AnyIO 弃用与现有 pytest cache 写入）；Web Vitest 72 passed / 0 failed；Vite/TypeScript 生产构建通过。RAG 项目隔离、审批/拒绝/重放、重启恢复、Evaluation 基线、隔离 Compose 全栈公共 API 验收均 PASS；专用容器、网络、卷清理成功。
- PowerShell parser：受影响 6 个 `.ps1` 文件通过；Git Bash `-n`：受影响 7 个 `.sh` 文件通过。`tls-public-host-nginx.ps1`、`grafana-logs-config.ps1`、`v1-1-production-config.ps1` 退出码 0。TLS Shell contract 首次因默认 `/repo` 路径错误，第二次在非 root Git Bash 被正确拒绝；按历史文档改在 `agentforge-agent-service:latest` root 容器中以只读挂载执行，退出码 0，涵盖 IPv4/IPv6/域名、续期、环境校验和观测健康 fail-open。
- 公开 HTTPS 预检：`https://zhanyiming.cloud/` 返回 200，当前仍是旧 bundle `assets/index-CEg6ypVD.js`。
- 候选 diff 敏感扫描：761,483 字符，private key/GitHub token/AWS key/OpenAI key/JWT/Bearer token 正则命中均为 0；Pi 启动器再次自行扫描后使用 `deepseek/deepseek-flash` 做 Milestone Review，报告 `docs/08-reviews/2026-09-23-review-pre-v3-production-release-attempt-1.md` 为 PASS、无阻塞项。因累计 diff 超过 180,000 字符，Pi 仅接收首/中/尾采样；本轮各 Node 已有独立审查，当前完整发布机器门禁如上。S1/S2 为测试补强建议，实际 16,000 限制及 EXECUTED 同键返回已核对；S3 所疑 PUBLIC_URL_HOST 由 common.sh 运行时生成，未构成升级阻断；其余建议记录在报告评估表。
- 当前阻塞：维护端 `ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=yes root@47.76.95.86` 连接 22 超时；用户已被请求更新阿里云安全组维护来源。未取得生产工作树/备份/ASR 环境配置状态，故尚未更新 `origin/main` 或服务器。向第三方公网 IP 查询服务发请求被自动审批明确拒绝，未绕过；审批服务短暂连接失败后恢复，发布测试正常继续。

- 2026-09-23 补充：用户确认已把 `103.151.172.28/32`、`223.104.68.129/32`、`223.104.68.145/32` 加入 SSH/22。再次执行带 12 秒超时的生产 SSH 命令仍在 TCP 阶段超时；`Test-NetConnection` 到 `47.76.95.86:443` 为 True，DNS 仍为该 IP，公网 HTTPS 200 且旧 bundle 未变。读取本机具体防火墙规则被 Windows 拒绝；向 GitHub 22 做对照探测被自动审批拒绝，未绕过。仍待阿里云侧确认规则实际关联、网络 ACL/云防火墙与实例内 sshd 监听。
- 用户再将服务器自身 `47.76.95.86` 加入 SSH/22 来源后重试，TCP 仍在 12 秒超时；安全组来源应为维护端地址。已请用户核实实例实际绑定的安全组、优先级/拒绝规则和 ECS 22 端口诊断。
- 用户新增诊断显示的 `223.104.68.141/32` 后，本机生产 SSH 仍两次在 TCP 阶段超时；不再猜测来源 IP，改走阿里云云助手只读检查生产 Git/SSH 状态，待结果确认后选择可审计的标准发布路径。
- 阿里云云助手只读预检：生产仓库位于 `main` 的 `af7afa3a2e743a3a8e4dce63845a7b62d20e28da`，工作树干净；sshd 监听 IPv4/IPv6 的 22 端口；根卷 40 GiB、可用 25 GiB。旧版 `validate-env.sh` 通过，但生产 ASR API key 与 workspace ID 缺失，语音功能上线后仍需单独配置。维护端手机热点 IP 会变化，后续采用云助手执行同一标准发布脚本，不以临时放宽 SSH 安全组作为发布前提。
- 既有生产 `.env` 缺少新版必需的 `GRAFANA_ADMIN_USER` 与 `GRAFANA_ADMIN_PASSWORD`。已请用户通过云助手在服务器本机生成随机管理员密码并仅回报变量是否已设置；不得在聊天或日志中回传实际值。在环境校验通过前不更新共享 `main`。
- 再次核验正确 GitHub 远端 `https://github.com/zhan-yi-ming/AgentForge.git`：`main` 仍为 `af7afa3a2e743a3a8e4dce63845a7b62d20e28da`，候选分支仍为 `30af771440e2db6381b3df97e879d8b199d1e32d`，本地祖先检查可非强制快进。一次将仓库 owner 误写成 `zhanyiming` 的只读查询未认证成功，未改动任何远端。
- 用户通过云助手在生产 `.env` 本机生成 Grafana 管理员配置，仅回报 `GRAFANA_ADMIN_USER=set`、`GRAFANA_ADMIN_PASSWORD=set`，未在聊天中传输实际值。生产 ASR 两项仍缺失，计划发布主体功能并将语音作为已知待配置项。
- 共享分支发布：HTTPS 写操作因本机凭据不可用而安全失败，远端未变；改用仓库原有 SSH 认证执行 `git push origin HEAD:refs/heads/main`，非强制快进 `af7afa3..30af771` 成功。随后 SSH 只读核验遇到一次公钥拒绝，改用独立 HTTPS 只读 `ls-remote` 验证 `origin/main=30af771440e2db6381b3df97e879d8b199d1e32d`。已请用户通过阿里云云助手在干净生产 `main` 上运行标准 `scripts/deploy/update.sh`；待回填备份、构建、健康与公网验收结果。
- 首次云助手执行 `update.sh`：备份 `agentforge-20260923T083555Z.dump.gz` 已创建，生产 `main` 快进至 `30af771`，新脚本开始 `compose build core-api`；粘贴日志在 Maven 依赖下载阶段突然结束，没有 `ERROR` / `failed` / `exit code` 行。公网首页仍返回旧 bundle `assets/index-CEg6ypVD.js`，不得宣称部署完成。已请用户只读确认云助手任务状态、更新进程、备份文件和健康检查；在排除仍运行中的构建前禁止重试。若证实只是云助手超时且原进程终止，应从已拉取代码的 `deploy.sh` 和 `health-check.sh` 续跑，以保留 `previous-release=af7afa3`，避免第二次 `update.sh` 覆盖回滚指针。
- 云助手续跑 `deploy.sh` 再次报告超时；从维护端进行独立公网只读验收，首页已切换为候选本地构建对应的 `assets/index-B8L7zJ6c.js`，`/chat` 和 `/wiki` 200、未认证 `/api/v1/users/me` 401、`/grafana/api/health` 200。此时不能仅凭公网路径断言脚本整体退出成功；已请用户只读回报生产 HEAD、工作树、回滚指针、部署进程与标准 `health-check.sh` 结果，禁止再次运行部署命令。


## 发布结论

- 服务器最终回报 `head=30af771`、`update_processes=0`、`health=PASS`、`backup=PRESENT`。备份文件为 `agentforge-20260923T083555Z.dump.gz`。首次 `update.sh` 和续跑 `deploy.sh` 的云助手任务均发生等待超时，但最终独立健康检查通过；不将云助手超时误写为脚本正常退出。
- 公网 `https://zhanyiming.cloud/` 返回 200，前端资源 `assets/index-B8L7zJ6c.js` 与本次本地构建完全一致；`/chat` 和 `/wiki` 各返回 200，未认证 `/api/v1/users/me` 返回预期 401，`/grafana/api/health` 返回 200。可确认 P3 候选已部署且基础服务健康。
- 未执行真实账号的聊天、语音和 Grafana 登录烟测。生产 `AGENTFORGE_AGENT_ASR_API_KEY` 与 `AGENTFORGE_AGENT_ASR_WORKSPACE_ID` 仍缺失，线上语音输入暂不可用；用户需通过安全渠道在生产 `.env` 配置后另行验证。此前备份与 `update.sh` 的执行顺序表明 `previous-release` 由旧版 `af7afa3` 写入，但用户最终未单独回报该指针，因此不把其内容列为已直接核验。
- 发布收口的文档提交只包含本记录、索引和本次 Pi 审查报告；用户原有的审计记录修改、`.worktrees/` 与产品规划文档不纳入提交。
