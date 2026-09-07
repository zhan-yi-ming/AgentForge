# AI 文本整理流式可靠性修复

- 日期：2026-09-07
- 状态：Implemented
- 阶段：V1.2 线上缺陷修复
- 交付目标：当前分支 / 生产更新

## 背景

线上“AI 整理并预览”仍调用同步 `POST /agent/chat`。一次包含 4757 个字符的 Markdown 整理请求通过认证后返回 503；浏览器控制台同时出现其他 401 和本机 `ERR_INTERNET_DISCONNECTED`。同步模型调用要等待完整回答后才返回首字节，而 Agent Service 的模型请求默认超时为 10 秒、Core API 同步读取超时为 30 秒，长文本整理容易被统一包装为 503，且前端只能在请求结束后一次性显示结果。

用户粘贴的复现 curl 含 Bearer JWT；本记录不保存该凭据或请求正文，只记录脱敏后的路径、状态和输入规模。该令牌应撤销或随会话失效，不进入测试、日志、提交或审核输入。

## 目标

- “AI 整理并预览”复用已存在的公共 SSE Chat 接口，收到模型 delta 后立即增量显示 Markdown，完成后保留最终结果。
- 整理请求使用独立、无 conversationId 的调用，不改变项目 Chat 的 conversation、answer、sources 或 pending action。
- 项目 Chat 与 AI 整理互斥；切换项目或卸载组件时取消仍在进行的整理，旧项目迟到事件不得污染新项目。
- 整理流若在 complete 前失败，清除半成品并禁止应用到 Wiki；取消请求不显示为普通错误，旧请求结束不得解除新请求的 busy 状态。
- 整理提示明确要求不写入；即使下游意外返回 tool proposal，整理入口也不展示或覆盖项目 Chat 的待确认操作。
- 生产 Agent 模型/回调单次等待预算显式设为 60 秒，Core 下游读取预算设为 75 秒，并保持小于浏览器、SSE emitter 与 Nginx 的 120 秒流式总预算。
- 保持 401、403、404、429、503 Problem Details 和流内通用错误的既有安全边界。

## 非目标

- 不改变 Agent Service 的公共 API、数据库、配额计数或 HITL 写入契约。
- 不用前端重试掩盖无效 key、余额不足、RAG 故障或真实上游不可用。
- 不把模型/RAG 原始错误、令牌或上游响应正文暴露给浏览器。
- 不进入 V2 Trace、持久化、重试或状态机节点。

## 受影响文档

- `docs/02-architecture/frontend-architecture.md`：补充整理流的独立状态、取消和互斥边界。
- `docs/03-features/web-workspace.md`：更新用户流程、错误状态和测试 seam。
- `docs/04-api/agent-service.md`：注明长文本整理客户端优先使用既有 SSE 入口，不改变契约。
- `docs/06-operations/production-single-host.md`：细化 401、503 与浏览器离线错误的排障顺序。

## 设计决定

采用现有 `POST /api/v1/projects/{projectId}/agent/chat/stream`，不新增专用 formatter API。同步 Chat 保持兼容；浏览器整理入口改为 SSE 后，模型可在完整答案生成前发送首段，Nginx 已对该路径关闭缓冲且 Core 流式生命周期为 120 秒。格式化流使用独立 AbortController 和局部展示状态，不复用项目 Chat conversation，也不接受 pending action。

生产 Compose 显式传递 `AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS`，默认 60 秒；Core 的 `AGENTFORGE_AGENT_READ_TIMEOUT` 默认 75 秒。顺序保持 Agent 单次等待 < Core 下游读取 < SSE/Nginx 总预算，防止内层仍工作而外层先超时。两项均可通过部署环境覆盖，不增加自动重试或请求总次数。

该修复不改变系统边界、数据模型或公共接口，无需新增 ADR。

## 实现

- `apps/web/src/App.tsx`：AI 整理由同步 `api.chat` 改为独立 `api.chatStream`；delta 增量写入整理预览，最终 answer 收口；独立 AbortController 在项目切换/卸载时取消；意外 pending action 不进入项目 Chat 状态；Chat 流式期间禁用整理入口。
- `apps/web/tests/app.test.tsx`：通过 DOM 与 `ApiClient` 公共 seam 覆盖无 conversationId 的流式整理、增量预览、显式应用、Chat 状态隔离、proposal 忽略、生成互斥和项目切换取消。
- `infra/compose.yaml`、`infra/compose.prod.yaml`、`.env.example`、`.env.production.example`：显式传递模型/回调 60 秒等待预算，把 Core 下游读取预算调整为 75 秒，并补齐本地 Compose 的输出 Token 配置透传。
- 本机忽略的 `.env` 仅把两个非敏感超时值同步为 60/75 秒；未读取、输出或修改其中凭据。
- 更新前端架构、Web 功能、Agent API、生产排障和变更索引文档；公共 API、Schema 与数据库无变化。

### 最新 main 集成与发布

- 旧 `feature/centered-chat-onboarding` 的功能基线 `fee96a9` 已通过 `main` 的 `18064bf` 合并；截至 `main@ca4ecfc`，旧分支唯一未进入 main 的实质补丁为本修复 `228ad28`，不会重复或回滚居中工作区功能。
- 集成以最新 main 的聊天折叠历史、Markdown 外层围栏规范化、Wiki 应用滚动/反馈、登录保护与 V2-01 Langfuse 配置为基线，只叠加流式整理、取消/互斥/失败清理和 60/75 秒超时预算。
- 合并、发布前验证和生产证据完成后回填 main commit、远端核验、备份、容器健康、浏览器 smoke 与旧 feature 删除结果。

## 验证结果

- TDD 红：`npm test -- --run tests/app.test.tsx -t "keeps the wiki draft until AI text is explicitly applied"`，退出码 1；1 failed、11 skipped，准确证明旧实现仍调用同步接口，流式 mock 未被消费。
- TDD 绿：在 `apps/web` 执行 `npm test -- --run tests/app.test.tsx -t "keeps the wiki draft until AI text is explicitly applied"`，退出码 0；1 passed、11 skipped。第二切片在同一目录执行 `npm test -- --run tests/app.test.tsx -t "prevents AI formatting while project chat is streaming"`，先退出码 1（按钮未禁用），最小修复后使用完整相同命令重跑，退出码 0（1 passed、12 skipped）。
- Pi 建议对应的第三个 TDD 切片：`-t "discards a partial formatting result when the stream fails"` 先退出码 1（半成品仍可见），清理 partial、收紧 busy 守卫与 AbortError 识别后退出码 0（1 passed、13 skipped）。
- Web 最终完整回归：`npm test -- --run`，Vitest 3.2.7，退出码 0；3 files、18 tests 全部通过，0 failed、0 skipped。
- Web 最终生产构建：`npm run build`，TypeScript + Vite 7.3.6，退出码 0；283 modules transformed，产物生成成功。
- Agent 定向测试：首次误用系统 Python 3.14.3，因未安装 pytest 退出码 1；改用仓库 `.venv` 后 `python -m pytest tests/test_api.py tests/test_llm.py -q` 退出码 0，27 passed、0 failed/skipped、2 warnings。
- Agent 全量测试：仓库 `.venv` 执行 `python -m pytest -q`，退出码 0；32 passed、0 failed/skipped、3 warnings。warning 为既有 Starlette/AnyIO deprecation 与 pytest cache 路径告警，不影响行为结论。
- Core 定向契约：第一次 Maven Wrapper 因沙箱阻止网络/用户缓存访问而退出码 1，未运行测试；获准读取用户级 Maven 缓存后 `mvnw.cmd -Dtest=AgentChatApiTest test` 退出码 0，6 passed、0 failed/errors/skipped。
- Core 全新门禁：Maven 3.9.11、Java 21.0.12.1 执行 `mvnw.cmd clean verify`，退出码 0；重新编译 95 个主源码和 19 个测试源码，83 tests、0 failures/errors、7 skipped。7 项为既有需独立 Python 进程的跨进程 HTTP 契约条件测试；本轮相关 API controller 6 项已单独 0 skipped 通过，Python API/LLM 也已单独通过。
- Compose 配置：第一次生产渲染因缺少 `PUBLIC_HOST` 占位值退出码 1；设置 `example.invalid` 后，本地 `.env` 与 `.env.production.example` 两种渲染均退出码 0，且只输出脱敏值 `Agent=60`、`Core=PT75S`。
- 静态门禁：`git diff --check` 退出码 0；JWT、私钥、Bearer credential 扫描 0 命中，按文件复核后的 API key/token/password 字面量扫描 0 命中；临时诊断标记 0 命中。
- 清理：`docker ps --filter label=org.testcontainers=true` 返回 0 个容器；没有创建临时请求文件。用户原有未提交文档改动和未跟踪 DOCX 未触碰。
- Pi Diff Review：`deepseek/deepseek-v4-pro`，Attempt 1，结果 PASS，报告为 `docs/08-reviews/2026-09-07-review-ai-formatting-stream-reliability-attempt-1.md`。无必须修改项；采纳 S1/S2/S4/S5 的 busy 竞态、半成品清理、失败测试和 AbortError 鲁棒性建议。S3 的孤立 pending action 可观测性需要 Core mode/Trace 契约，记录到 V2 后续，不在本次前端修复中扩大公共接口。
- 生产限制：出于凭据与项目正文外发风险，自动审批拒绝了对用户附件中 Bearer 请求的公网重放；因此尚未用该真实令牌验证线上响应。修复结论由旧公共调用路径的确定性失败测试、SSE 既有契约与超时配置渲染支持；部署后仍须用新登录会话完成不泄露 token 的真实浏览器 smoke。

### 最新 main 集成验证

- 在隔离 worktree 以 `main@ca4ecfc` 为第一父提交执行普通 merge；冲突只出现在本修复与 main 后续同时修改的 Web、文档和 Compose 配置。逐项保留 main 新能力后再叠加流式整理，没有采用旧分支整文件覆盖。
- 在隔离 worktree 的 `apps/web` 执行 `npm test -- --run`：Vitest 3.2.7，修复审核发现后最终退出码 0；3 个测试文件、26 个测试全部通过，0 失败、0 跳过。首次使用同一命令在受限沙箱内复用依赖时，esbuild 因工作树路径访问受限在测试启动前退出 1；获准本地执行后用完整相同命令重跑成功。
- 在隔离 worktree 的 `apps/web` 执行 `npm run build`：Node.js 24.14.0、npm 11.9.0、TypeScript 与 Vite 7.3.6，退出码 0；283 个模块完成转换。
- 在仓库根目录执行 `docker compose --env-file .env.production.example -f infra/compose.prod.yaml config`：退出码 0；最终值包含 Agent 请求 `60` 秒、Core 读取 `PT75S`，并保留 main 的 Langfuse 与 Token 上限配置。
- Pi 集成 Diff Review Attempt 1 返回 `NEEDS_FIX`：确认流式首个 delta 后“应用到 Wiki 草稿”按钮仍可点击，存在把未完成内容复制到草稿的缺口。采纳 M1，并在同一状态边界内一并采纳 S1–S4：完成前禁用应用、旧项目迟到错误不写当前视图、按对象 `name` 识别跨 realm AbortError、旧 Chat finally 只清理自己的流状态，同时补齐双向互斥 DOM 回归测试。
- 审核修复 TDD：在隔离 worktree 的 `apps/web` 执行 `npm test -- --run tests/app.test.tsx -t "prevents applying partial formatting or starting chat while formatting streams"`，首次退出码 1（1 failed、20 skipped），精确命中应用按钮未禁用；实现最小状态门控后使用完整相同命令重跑，退出码 0（1 passed、20 skipped），随后按上列完整命令再次执行全量前端回归与生产构建并通过。
- Pi 集成 Diff Review Attempt 2 返回 `PASS`，无必须修改项。S1 不采纳：整理按钮在 `busy` 时禁用，当前 UI 不存在同项目并发启动第二次整理的入口，项目切换与 finally 已有 controller 守卫；S2 不采纳：delta 增量可见是本修复的首字节目标，完成前应用已禁用，延迟到 complete 会恢复原等待体验；S3 记录为后续测试增强，当前 ApiClient 的可选 metadata 契约及最终 answer 收口均已有实现边界，不阻塞本次线上修复。

### 远端与生产进度

- 合并提交 `b2e2b9661a6e7bcdd43fdd3247ad0565d9522447` 已先推送 `codex/ai-format-main-release`，再快进推送 `main`；两条远端引用均用 `git ls-remote` 核验为相同哈希。
- 生产 SSH preflight 执行 `ssh -o BatchMode=yes -o ConnectTimeout=10 root@47.76.95.86 "...只读仓库与超时变量检查..."`，退出码 1，返回 `Permission denied (publickey)`，未进入服务器、未读取生产配置。随后执行 `ssh -vvv -o BatchMode=yes -o ConnectTimeout=10 root@47.76.95.86 "exit"`，证明确已建立 TCP 连接并依次提供本机 RSA/ED25519 公钥，但服务器均未接受；因此安全组已放行，阻塞点为 root 的 `authorized_keys` 或登录用户，不是端口规则。

### 生产发布与真实验证

- 用户通过阿里云云助手把本机 ED25519 公钥加入 root `authorized_keys` 后，在仓库根目录执行 `ssh -o BatchMode=yes -o ConnectTimeout=10 -i C:\Users\86134\.ssh\id_ed25519 root@47.76.95.86 "set -eu; cd /opt/agentforge/repo; printf 'HEAD='; git rev-parse HEAD; printf 'BRANCH='; git branch --show-current; printf 'DIRTY_COUNT='; git status --porcelain | wc -l; if grep -q '^AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS=' /opt/agentforge/env/.env; then echo 'AGENT_TIMEOUT_OVERRIDE=present'; else echo 'AGENT_TIMEOUT_OVERRIDE=absent'; fi; if grep -q '^AGENTFORGE_AGENT_READ_TIMEOUT=' /opt/agentforge/env/.env; then echo 'CORE_TIMEOUT_OVERRIDE=present'; else echo 'CORE_TIMEOUT_OVERRIDE=absent'; fi"`，退出码 0；生产仓库为干净 `main@ca4ecfc`，两个超时变量均无私有覆盖。
- 在仓库根目录执行 `ssh -o BatchMode=yes -i C:\Users\86134\.ssh\id_ed25519 root@47.76.95.86 "cd /opt/agentforge/repo && scripts/deploy/update.sh"`，退出码 0；先创建备份 `/opt/agentforge/backups/agentforge-20260907T112012Z.dump.gz`，再把生产仓库快进到 `b7fc259`，顺序构建并启动 Core API、Agent Service、Web 与 gateway。Web 镜像内部真实执行 `npm run build`，Vite 7.3.6、283 modules transformed；最终 gateway、core-api、agent-service、web、postgres 共 5 个容器全部 healthy，脚本输出 HTTPS 与认证边界健康。
- 首次附加配置核验把远端 `$(compose ...)` 写进 PowerShell 双引号，导致本机提前展开并退出 1；没有得到配置结论或修改服务器。修正后在仓库根目录执行 `ssh -o BatchMode=yes -i C:\Users\86134\.ssh\id_ed25519 root@47.76.95.86 'set -eu; cd /opt/agentforge/repo; source scripts/deploy/common.sh; request_timeout=$(compose exec -T agent-service printenv AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS); read_timeout=$(compose exec -T core-api printenv AGENTFORGE_AGENT_READ_TIMEOUT); printf "AGENT_REQUEST_TIMEOUT_SECONDS=%s CORE_READ_TIMEOUT=%s\n" "$request_timeout" "$read_timeout"; test "$request_timeout" = 60; test "$read_timeout" = PT75S; scripts/deploy/health-check.sh'`，退出码 0；实际容器值为 Agent `60`、Core `PT75S`，健康检查再次通过。
- 临时无凭据 smoke 脚本先在仓库根目录执行 `& 'C:\Program Files\Git\bin\bash.exe' -n .production-ai-stream-smoke.sh`，Git Bash 语法检查退出码 0。脚本只在服务器内从私有环境读取 Demo 凭据，把 curl Authorization 配置和响应保存到 `mktemp` 的 `0700` 目录并在退出时删除；没有输出密码、token、项目正文或模型回答。
- 第一轮执行 `$smokeScript = Get-Content -Raw -Encoding UTF8 '.production-ai-stream-smoke.sh'; $smokeScript | ssh -o BatchMode=yes -i C:\Users\86134\.ssh\id_ed25519 root@47.76.95.86 'bash -s'`，退出码 1 且无输出；增加非敏感阶段标记后确认登录、项目与流 HTTP 均为 200，但初版 grep 只接受 `event: metadata`，没有兼容生产的 `event:metadata`，误计事件为 0 并退出 1。修正事件正则后产品断言全部成功，但 Windows 管道追加的 CRLF 使远端脚本最后执行 `$'\r'` 并退出 1；这些失败不记作通过。
- 最终先再次执行 `& 'C:\Program Files\Git\bin\bash.exe' -n .production-ai-stream-smoke.sh`，退出码 0；再执行 `$smokeScript = Get-Content -Raw -Encoding UTF8 '.production-ai-stream-smoke.sh'; $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($smokeScript)); $encoded | ssh -o BatchMode=yes -i C:\Users\86134\.ssh\id_ed25519 root@47.76.95.86 'base64 -d | tr -d "\r" | bash -s'`，退出码 0。Base64 解码报告尾部 `invalid input` warning，但完整 Bash smoke 成功执行并输出 `LOGIN_HTTP=200 PROJECTS_HTTP=200 STREAM_HTTP=200 METADATA=1 DELTAS=54 COMPLETE=1 ERRORS=0`；临时服务器目录由 trap 清理。
- 删除远端分支前第一次用错误的 feature 完整哈希执行祖先检查，Git 退出 128，删除门禁安全停止且没有修改远端。随后从 merge commit `b2e2b96` 的第二父提交解析真实 feature 哈希 `3d903c8972a6a46c7103b00da5c4e396ce9a4566`，执行 `git merge-base --is-ancestor 3d903c8972a6a46c7103b00da5c4e396ce9a4566 b7fc259d0aeef8f2e0996254691a9d61cfb2172d`，退出码 0；执行 `git push ssh://git@ssh.github.com:443/zhan-yi-ming/AgentForge.git --delete feature/centered-chat-onboarding codex/ai-format-main-release`，退出码 0；再执行 `git ls-remote` 查询两个引用，返回 0 行。
- 本地清理前执行 `git merge-base --is-ancestor main b7fc259d0aeef8f2e0996254691a9d61cfb2172d`，退出码 0，并执行 `git log --oneline main --not b7fc259d0aeef8f2e0996254691a9d61cfb2172d`，返回 0 条独有提交；同时确认用户已修改文档在旧/新基线间无提交差异，未跟踪 DOCX 在 main 中仍未跟踪。随后安全快进本地 main 并执行 `git switch main`，退出码 0；首次 `git branch -d feature/centered-chat-onboarding` 因本地残留的已删除远端跟踪引用而退出 1，没有删除。执行 `git branch --unset-upstream feature/centered-chat-onboarding` 后再次执行 `git branch -d feature/centered-chat-onboarding`，退出码 0；`git branch --list feature/centered-chat-onboarding` 返回 0 条。用户原有修改 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md` 与未跟踪 DOCX 均保留，未被暂存或改写。
- 生产证据回填后在隔离 worktree 根目录执行 `git diff --check; $diffExit=$LASTEXITCODE; $changed=@(git diff --name-only); $diff=git diff; $privateKeys=[regex]::Matches($diff,'(?im)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----').Count; $bearers=[regex]::Matches($diff,'(?im)Bearer[ \t]+[A-Za-z0-9._~-]{20,}').Count; Write-Output "DIFF_EXIT=$diffExit CHANGED_FILES=$($changed.Count) PRIVATE_KEY_MATCHES=$privateKeys BEARER_CREDENTIAL_MATCHES=$bearers"`，退出码 0；仅本变更记录 1 个文件变化，diff check、私钥头与 Bearer credential 命中均为 0。

## 风险与回滚

最大影响范围为 Web 的 Chat/整理并发与既有 SSE 客户端，初评 L2（流式状态、异常/超时、跨模块契约）。回滚时恢复 Web 整理入口到同步 Chat；API 与数据库无需回滚。若流式入口仍返回 503，应依据 requestId 检查 Agent 日志中的 RAG、模型认证/余额、模型请求超时和 Core 下游状态，不能把浏览器离线提示当作服务端根因。
