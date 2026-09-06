# V1.2 面试 Demo 体验与流式 Agent

- 状态：Implemented
- 日期：2026-09-06
- 目标分支：`dev` 验证后合并 `main`

## 背景

V1.1 已在公网 IP 上完成可信 HTTPS 部署和真实 DeepSeek 问答，但面试账号只有脚本生成的随机密码，不便于重复分享；登录页与工作区缺少作者身份和面试场景引导，现有宽屏卡片布局的信息层级与配色也不够成熟；Agent Chat 只能等待完整 JSON 响应后一次性显示，无法呈现模型生成过程。

## 目标与范围

- 保留一次性随机 Demo 账号，同时允许用服务器专有配置创建和复用固定面试账号；固定密码不进入 Git、镜像、容器环境或日志。
- 登录页加入面向面试官的欢迎语和 `zhan-yi-ming` 署名；工作区重做视觉层级、栅格、配色、状态与响应式布局。
- 新增真实模型 Token 流：Python 读取 OpenAI-compatible 流，Java 保持鉴权、配额与写入边界并转发 SSE，React 增量解析和渲染 Markdown。
- 保留现有 JSON Chat 接口供兼容与 AI 文本整理使用；Tool proposal 仍需 Java 保存和用户确认。
- 完成 Java/Python/Web 测试、生产配置/镜像/全栈烟测、敏感扫描和 Pi V4-pro 一次性只读审核。
- 审核无阻塞项后提交 `dev`；经远端核验后合并到 `main`，再更新公网服务器并执行登录、流式问答、权限和网络边界验收。

## 非目标

- 不新增公开注册、密码找回、OAuth、管理员后台或聊天历史持久化。
- 不把固定密码写入前端、README、Compose、Docker image 或 GitHub。
- 不让 Python 执行业务写入，不绕过 Java 配额和 HITL。
- 不引入 WebSocket、Redis 消息总线或 V2 观测平台。

## 公共测试 seam

1. `seed-demo.sh`：服务器固定凭据存在时可创建或复用固定 USER workspace，同时生成独立随机 USER；缺失或弱固定密码时快速失败，输出不进入 Docker 日志。
2. Python `/internal/v1/chat/stream`：认证后返回 NDJSON，至少包含 metadata、一个或多个 delta、complete；模型异常以安全 error 事件结束。
3. Java `POST /api/v1/projects/{projectId}/agent/chat/stream`：Bearer、项目授权、日配额和 request ID 与 JSON Chat 一致，媒体类型为 `text/event-stream`，Tool proposal 只在 Java 转为 pending action。
4. React typed client：正确处理跨网络块拆分的 SSE 与 UTF-8 文本；UI 在 complete 前可观察到部分回答，并在完成后展示来源/action。
5. 视觉与可访问性：登录欢迎语、作者署名、表单标签、主要区域和移动端布局可由组件测试与浏览器截图验收。

## 安全影响

固定账号与随机账号共享既有每用户日配额和 Nginx IP 限速。固定密码只读取 `/opt/agentforge/env/.env`，不传给 Compose 服务；脚本通过临时开启注册创建 USER 后立即恢复关闭。SSE 只携带回答、来源、request ID 和经过 Java 校验的 pending action，不携带内部 token、模型响应原文错误或凭据。

## 风险评级

本次修改跨 React、Java、Python、Nginx、凭据初始化与公开 API Contract，属于 L3，并且是 V1.2 节点收口。已执行完整测试套件、真实 DeepSeek 流式 smoke 和敏感扫描；必须通过 Pi Milestone Review 后才能提交。低风险累计计数在审核通过后归零。

生产事实回填属于 L0 纯文档闭环，不改变运行行为；只执行文档一致性、差异格式和敏感信息检查，不重复全量测试或 Pi。该闭环计为 Milestone Review 后第 1 次 L0 修改。

## 回滚思路

发布前备份 PostgreSQL并记录上一 release。应用回滚到 V1.1 时，新增固定 Demo 用户只是普通 USER 数据，可保留或人工删除；旧 JSON Chat 接口始终保留。SSE/Nginx 改动随 Git 回滚；数据库无新 schema 迁移。真实 `.env` 新字段对旧版本无影响。

## 验证证据

- TDD 与实现：Python 新增 OpenAI-compatible 原生流式 responder 和内部 NDJSON；Java 新增同步授权/配额准备、HTTP 流消费和公开 SSE；React 新增跨 chunk UTF-8/SSE 解析与渐进渲染；生产 seed 同时维护固定与随机 USER；Nginx 对流式路径关闭缓冲。
- Java：OpenJDK 21.0.12.1，`.\mvnw.cmd clean verify` 退出码 0；83 tests，0 failures/errors，7 项仅在显式双服务契约门禁开启时运行。此前本次实现已启动真实 uvicorn 并单独执行 `AgentServiceHttpContractIntegrationTest`，7 tests，0 failures/errors/skipped，退出码 0；监听端口与临时日志已清理。
- Python：Python 3.14.3 / pytest 8.4.2；从仓库根目录发起的一次命令因未加载子项目 `pyproject.toml` 测试环境而无效（15 个 API 用例缺少测试变量，17 项已通过），随后从 `services/agent-service` 正确执行 `.venv\Scripts\python.exe -m pytest -q --cache-clear`，退出码 0，32 passed、0 failed/skipped，3 条第三方弃用/Windows cache 警告。
- Web：Node v24.14.0 / npm 11.9.0；`npm test -- --run` 退出码 0，3 files、12 tests 全部通过；`npm run build` 退出码 0，Vite 7.3.6 生产构建成功。
- 真实本地 Compose：Docker Client/Server 29.5.3、Compose v5.1.4，PostgreSQL/Core API/Agent Service/Web 均为 healthy；通过公开 Java SSE 向已配置的 DeepSeek 发起一次验收，HTTP 200，1 metadata、35 delta、1 complete、0 error，66 个回答字符，首 delta 1829 ms、总计 2079 ms。验收不记录模型 key 或回答正文。
- UI：在真实浏览器打开本地构建，确认 `Built by zhan-yi-ming` 是作者署名，“你好，面试官”仅为访客问候；窄视口自动使用单栏且登录卡片无横向溢出。
- 运维静态门禁：生产 Compose `config --quiet`、全部部署脚本 `bash -n`、ShellCheck（仅排除动态 source 的 SC1090/SC1091）和 `git diff --check` 均退出码 0。
- 安全与审核：Gitleaks v8.30.1 扫描最终暂存差异约 155.43 KB，退出码 0，未发现泄漏；Pi V4-pro 以 Milestone 模式审核 INDEX 和显式产品上下文，Attempt 1 为 `PASS`、0 个必须修改。6 个建议项已在报告逐项回填，登记为后续流式韧性/运维体验切片，不阻塞本次发布。
- 已知非阻塞限制：流式期间其他可能产生 pending action 的入口尚未统一禁用；未知中途异常和客户端断开仍可加强三端 error 事件/资源回收测试；固定账号主动换密需要受控流程。Java 权限与 action 校验、通用错误转换及 120 秒超时仍保证当前安全边界。
- Git：V1.2 在 `dev` 的实现提交为 `9e780d4266c0582b9cb9a65d161868895e36368e`，远端核验一致；以非快进方式合并并推送 `main`，发布提交为 `0ea380a8917785aaa472e233224c17cebb77ab41`，远端核验一致。
- 生产部署：服务器 `/opt/agentforge/repo` 为干净 `main@0ea380a`；顺序构建 Core API、Agent Service、Web 和 gateway 后，PostgreSQL、Core API、Agent Service、Web、gateway 全部 healthy。发布前备份为 `/opt/agentforge/backups/agentforge-20260906T012341Z.dump.gz`。
- Demo：固定邮箱为 `interviewer@agentforge.local`，固定与随机备用凭据写入 `/opt/agentforge/state/demo-credentials.txt`，文件模式 600；真实密码、access token 和模型 key 均未进入仓库或最终验收输出。
- 公网流式验收：经受信 HTTPS、Nginx、Java、Python 和 DeepSeek 完成 1 metadata、43 delta、1 complete、0 error；说明生产路径为真实增量流而非最终一次性响应。
- 生产边界：公网 5432、6379、8000、8080、5173 均为 closed；只保留既定 22/80/443。TLS timer 为 enabled/active，手工触发续期 service 的 Result 为 success；当前受信 IP 证书有效期为 2026-09-05 22:19:11 UTC 至 2026-09-12 14:19:10 UTC，未进入续期窗口时保持原证书属于正常行为。

## Pi 非阻塞建议登记（V1.2.1 候选）

以下 6 项来自 `docs/08-reviews/2026-09-06-review-v1-2-milestone-and-risk-governance-attempt-1.md`，均不阻塞 V1.2，尚未实现：

1. S1 / Medium：流式回答期间禁用其他可能生成 pending action 的入口，避免界面共享状态出现 last-writer-wins。
2. S2 / Low：Python 流生成器为未知异常补充安全 `error` 终止事件，避免 NDJSON 静默截断。
3. S3 / Low：Java 在客户端主动断开后二次发送失败时保证 emitter 及时回收，不等待 120 秒超时。
4. S4 / Low：补齐 Python、Java、Web 三端流式 error 分支自动化测试。
5. S5 / Low：固定账号密码变化时改善 seed 的错误提示，并减少密码短暂出现在 `jq --arg` 进程参数中的机会。
6. S6 / Info：把 SSE 文档改为明确的成功序列 `metadata → delta* → complete` 与失败序列 `metadata → delta* → error`。

上述事项后续应按调用关系重新评级：S1 可作为局部 UI 切片；S2–S4 建议合并为流式韧性 L2 切片；S5 为部署安全/运维 L2；S6 可随同主题文档修正。不得将本登记描述为已经实现。
