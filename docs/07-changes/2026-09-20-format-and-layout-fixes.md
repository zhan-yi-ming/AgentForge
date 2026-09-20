# 本地验收反馈：长文整理与页面布局修复

- 状态：Complete
- 日期：2026-09-20
- 基线：`0317d18d61f658d7f5b73ffa1167a141e783ccb0`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：旧 Pi 审计记录、`.worktrees/`、产品规划 docx 保留；受 ACL 保护的 pytest 目录不处理。
- 用户授权：修复 AI 整理 400、聊天按钮、图谱全屏视觉和登录首屏高度；语音真实验收由用户配置密钥后另行进行。

## 故障信号和判断

用户附的 curl 是认证请求，原文及 Bearer Token 不纳入仓库或审核材料。脱敏后路径为当前项目 `/agent/chat/stream`，请求只含 `message`；请求文本即使扣除转义，保守下界仍超过 8,000 字符。Core 与 Python 对 message 均限制 8,000，Web 整理把原文加提示词后直接提交，未提供长度反馈。先以公共 HTTP 测试复现 400，再把同步/SSE 和 Java/Python 限制对齐到 16,000 字符；Web 对超限整理输入保留原文并给出明确提示，不发送会失败的请求。保持项目权限、配额、审批和业务写入规则不变。

## 页面范围

- 聊天新建会话和语音控制改成与输入框放大键同列的紧凑图标；录音中的状态文字仍可见、可取消。
- `/wiki/graph` 脱离工作台容器，占满视口；参考用户截图的星空、轨道、彩色星球、中央项目节点和右侧页面详情视觉。节点与连线仍只基于当前项目真实 Wiki 页面及显式链接，不制造数据或 V3 图领域模型。
- 登录页桌面及窄屏尽量在一个视口内完整呈现，缩小标题和垂直间距；极低视口高度保留可滚动兜底以确保表单可用。

## 验证计划

先运行门禁规划器；公共 HTTP/Agent schema/DOM seam 按红绿测试验证。随后执行 Java clean verify、Python pytest、Web test/build、Compose 本地 smoke 与多尺寸视觉检查。变更含公共 Chat 请求上限，按 L3 + Core API/Agent/Web 做 Milestone Pi 只读审核。敏感扫描仅覆盖本次 diff，不发送附件或凭据。

## 验证回填

- 故障红绿：Core 公共 SSE 请求 10,000 字符先返回 400，调整后 200；Agent 内部流式请求同长度先 422，调整后 200。Web 整理页超出含提示词 16,000 字符时保留原文并阻止提交，DOM 回归由红转绿。
- Web：`npm test -- --run`，6 文件 / 71 passed / 0 failed；`npm run build` 退出 0。图谱独立路由与聊天图标 DOM 回归通过。
- Core：`mvnw.cmd -q clean verify` 退出 0；26 suites / 132 tests / 0 failures / 0 errors / 8 个外部 Agent 条件 skip。Java 21.0.12.1，PostgreSQL 17.11 与 Flyway 10 migrations 实测；既有 Mockito 动态 agent warning。
- Agent：首次全套 pytest 114 passed、5 个仅因系统默认 `pytest-of-86134` 临时目录 ACL 失败；指定本次仓库内 `--basetemp` 重跑 120 passed / 0 failed / 3 条 Starlette/AnyIO 弃用 warning，测试临时目录清理完成。
- 跨进程：隔离 Day4 E2E `status=PASS`，Wiki/Task 引用各 1，跨项目数据 0，容器、网络、卷清理成功。
- 本地镜像：Core、Agent、Web `docker compose --env-file .env -f infra/compose.yaml build` 成功。
- 可视检查限制：本机浏览器自动化内核受 ACL 拦截，Edge headless 未输出截图；多尺寸像素级目测未能提供机器证据。桌面/手机 CSS 断点、图谱容器和登录高度在代码与 DOM/构建门禁内检查；需用户在本地页面最终目测。
- 真实阿里云语音调用本次未测试，按用户当前配置密钥的安排保留给用户验收。

- Pi Milestone 只读审核：`docs/08-reviews/2026-09-20-review-pre-v3-format-layout-feedback-attempt-1.md`，`deepseek/deepseek-flash`，PASS。F1 的图谱加载失败被误判为空数据，新增 Web 红绿回归并展示错误；F2 新增 Core/Python 16,001 字拒绝边界测试；F4 清理文档占位。F3 的流完成覆盖为建议，本次 10,000 字 HTTP 验证和 Day4 跨进程 smoke 已覆盖请求入口与实际链路。F5 在 ≤590px 高度可滚动兜底，常见桌面/手机高度仍按单视口布局；浏览器自动化受 ACL 阻挡，需用户在目标设备目测。不因建议项重跑 Pi。
- 最终本地 Compose：三项镜像构建，`up -d --no-build core-api agent-service web` 后四容器 healthy；`/` 与 `/wiki/graph` HTTP 200、SPA HTML 一致，HTML 引用本次 Web 构建 hash；Core/Agent health 为 UP。
- 验证清理：专用 pytest 临时目录、预览临时目录和隔离 Day4 容器/卷清理成功；未接触用户原有 `.pytest-p304`、`.worktrees/`、docx 与旧 Pi 审计记录。
