# 居中聊天、新手引导与公开 Demo 账号

- 状态：Implemented
- 日期：2026-09-06
- 目标分支：`feature/centered-chat-onboarding`
- 与 V2 的关系：V2-01 启动前的独立体验切片，不属于任何 V2 Node，不改变 V2/V3 路线顺序

## 背景

V1.2 工作区在宽屏上把 Wiki/Task/文本整理放在左侧、聊天固定在右侧。首次访问者不容易判断主要入口，聊天也没有成为页面视觉中心。登录页同样采用左右分栏，且固定面试账号仍依赖服务器私有配置，不方便 HR 直接读取和进入体验。

用户要求在不启动或跨入 V2-01 的前提下完成一个独立小分支：改为类似主流对话产品的居中单栏聊天体验、加入首次新手引导，并把面试 Demo 账号固定为公开可读的普通 USER 凭据。

## 目标与范围

- 登录页改为居中的单卡片布局，直接展示固定 Demo 邮箱与密码，并提供一键填入。
- 登录后的主内容改为单列居中；Agent Chat 位于首要位置，Wiki、Task、AI 文本整理纵向排列在其后，不再使用左右并排主布局。
- 首次进入工作区显示可关闭的新手引导，说明选择项目、在中央对话、确认写入及使用下方工具；用户可从顶栏重新打开。
- 用浏览器 `localStorage` 记住引导已完成，仅保存布尔标记，不保存账号、Token 或业务数据。
- 生产 Demo 固定账号设为用户指定的公开体验凭据；生成配置、示例配置、校验和 seed 使用同一值。
- 既有服务器升级时需要同步私有 `.env` 后重新运行 seed；代码更新不会静默创建或修改生产用户。
- 保留 Demo USER 权限、项目隔离、每日 AI 配额、Nginx IP 限速与人工确认，不改变 Java/Python API、数据模型或 Agent 行为。
- 先通过 React DOM 和部署脚本公共 seam 写失败测试，再做最小实现。

## 公共测试 seam

1. React 登录/工作区 DOM：固定凭据可见且可一键填入；登录后中央对话位于主要内容首位；首次引导可关闭、持久化且可重新打开。
2. 生产配置与部署脚本：示例和生成脚本输出指定固定凭据；校验脚本接受该公开 Demo 密码但继续拒绝非指定弱密码；seed 仍创建普通 USER workspace。
3. 现有 ApiClient 与 Java/Python 接口保持不变；不测试 React 私有 state。

用户本次请求已明确授权上述用户可观察行为与 seam，无需另行扩大测试范围。

## 安全与架构影响

指定 Demo 凭据将公开显示并进入仓库，因此它不再被视为秘密或生产认证凭据，只能用于公开、可重置、无敏感数据的受限演示账号。所有真正的数据库密码、JWT、内部 Token 和模型 Key 仍必须随机生成并保存在服务器私有 `.env`。公开 Demo 账号必须保持 USER、项目隔离、配额和限速；不得复用于维护、管理员或任何包含敏感数据的环境。

该决策替代 ADR-0014 中“固定 Demo 密码不能进入公开仓库”的部分，新增 ADR 记录安全取舍。SSE 与 Java/Python 信任边界不变。

## 非目标

- 不开始 Langfuse Trace 或任何 V2-01 能力。
- 不修改 API Contract、数据库 Schema、Java/Python 业务逻辑或权限模型。
- 不新增完整路由、聊天历史持久化、多会话侧栏或复杂产品教程系统。
- 不删除 Wiki、Task、AI 文本整理或人工确认能力。
- 不修改或提交工作树中用户预先存在的 DOCX 与无关变更。

## 预计修改

- 文档：Web Workspace、Frontend Architecture、Public Demo Protection、Production Operations、ADR 与变更索引。
- Web：`apps/web/src/App.tsx`、`apps/web/src/styles-v12.css`、`apps/web/tests/app.test.tsx`。
- 部署：`.env.production.example`、`scripts/deploy/generate-production-env.sh`、`scripts/deploy/validate-env.sh` 及相关验证脚本/测试。

## 风险评级与验证计划

预计 L2：跨 React UI、浏览器持久化和生产部署脚本；固定公开凭据改变 Demo 安全假设，但不改变权限/API/Schema。

- Web：Vitest 全量与 Vite production build。
- 部署：针对固定凭据的失败/通过测试、全部 shell `bash -n`、生产配置验证与 Compose config。
- 回归：Java/Python 接口无改动，先不运行不相关全量测试；若 diff 或测试显示边界扩散则升级。
- UI：真实浏览器检查桌面和窄屏布局、新手引导与登录凭据填入。
- 安全：Gitleaks/敏感模式扫描必须区分明确允许的公开 Demo 凭据与真正秘密。
- Review：完成 Codex 测试后执行 Pi V4-pro Diff Review。

## 回滚思路

恢复上一版 React 结构/CSS、生产固定凭据生成与校验策略，并用新 ADR 取代本决策；服务器可删除或停用公开 Demo USER。该回滚没有数据库迁移，Java/Python 接口和已有用户数据不受影响。

## 实施与验证回填

- Web：`App.tsx` 把登录介绍与表单合并为居中单卡片，展示公开 Demo 凭据并提供一键填入；登录后保留项目导航，主内容改为最大 980px 的居中单列，Agent Chat 位于首位，Wiki、Task、文本整理依次向下排列。
- 引导：首次认证后显示带 `role="dialog"` 的四步引导；完成标记使用 `agentforge.onboardingComplete`，只保存 `true`；顶栏“新手引导”可随时重新打开。
- Demo：`.env.production.example` 与 `generate-production-env.sh` 使用指定公开凭据；`validate-env.sh` 只为这组 email/password 放行 8 字符，其他自定义固定密码仍要求 12–72 字符。seed 继续从受控环境读取并创建普通 USER，不改变注册、权限或配额逻辑。
- 运维：既有生产服务器必须显式同步私有 `.env` 并重新运行 seed；Git 更新不会自动修改生产用户。

TDD Red 证据：

- `npm test -- --run tests/app.test.tsx`：退出码 1；11 tests 中新增 3 failed、原有 8 passed，分别证明指定账号、一键填入、新手引导和 Chat 首位尚未存在。首次沙箱内启动因 esbuild 无权读取上级目录而失败，随后按规则在沙箱外重跑获得真实行为失败信号。
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation/v2-prep-demo-experience.ps1`：退出码 1，明确因 `.env.production.example` 仍为旧固定邮箱失败。

Green 与回归证据：

- Node `v24.14.0`、npm `11.9.0`、Vitest `3.2.7`：目标 `app.test.tsx` 11/11 passed，退出码 0；Web 全量 3 files、15/15 tests passed，0 failed/skipped，退出码 0。
- `npm run build`：TypeScript build 与 Vite `7.3.6` production build 通过，283 modules transformed，退出码 0。
- `v2-prep-demo-experience.ps1`：公开账号静态契约通过，退出码 0。
- Docker `29.5.3` / Compose `v5.1.4`：在本机已有 `agentforge-agent-service:latest` Bash 5.2.37 中执行 `validate-env.sh`；指定公开凭据通过，自定义 9 字符弱密码被拒绝，临时 fixture 已删除。
- 对 `scripts/deploy/*.sh` 全部执行 `bash -n`，退出码 0；ShellCheck `v0.11.0`（仅排除动态 source 的 SC1090/SC1091）退出码 0。
- `v1-1-production-config.ps1`：退出码 0；仅 gateway 发布 80/443，5 个服务保持 10m × 3 日志上限，部署脚本无 Compose v5 不支持的 build flag。
- UI 视觉验收：本地 fixture 下检查 1280×800 与 390×844；登录卡片居中、固定凭据可读、首次引导无横向溢出、关闭后 Chat 位于主内容首位，Wiki/Task 在下方。用户随后明确要求无需继续逐像素调整，已停止额外视觉微调。
- 清理：临时 Vite/API fixture 均已退出；5173 与 8080 监听数均为 0；临时环境 fixture 不存在。

最终回归与交付门禁：

- Pi 修复后再次执行 Web 全量测试：3 files、16/16 tests passed，0 failed/skipped，退出码 0；其中 `app.test.tsx` 为 12/12。
- Pi 修复后再次执行 production build：Vite `7.3.6`、283 modules transformed，退出码 0。
- Pi 修复后再次执行 `v2-prep-demo-experience.ps1`：前端与部署侧公开账号契约一致，退出码 0。
- `git diff --cached --check`：退出码 0；Gitleaks `v8.30.1` 对暂存差异扫描约 73.31 KB，未发现泄漏，退出码 0。指定 Demo 凭据按 ADR-0015 属公开体验值，不是服务秘密。
- Java/Python 测试未执行：本切片没有修改 Java/Python 源码、API Contract、数据库 Schema 或 Agent 行为；Web 全量、部署脚本检查与核心 UI smoke 已覆盖实际影响面。
- 未执行生产部署：本次只提交并推送隔离分支。既有服务器要让 HR 使用新账号，仍需运维同步私有 `.env` 并重跑 seed。
- 风险等级：L2。原因是变更横跨 React UI、浏览器持久化及生产部署脚本；Pi Diff Review 已 PASS，累计低风险计数归零。
- 本切片完成后停止，不开始 V2-01；只有收到用户明确的 V2-01 启动指令后才进入该节点。

## Pi Review Attempt 1 处置计划

Pi V4-pro Diff Review 返回 `PASS`，没有阻塞项。审核输入包含 23 个暂存文件；公开 Demo 密码字面值在发送前统一替换为 `[REDACTED_PUBLIC_DEMO_PASSWORD]`，临时脚本与 Prompt 已清理。

- S1 / Medium：认可并已修复。TDD Red 为 12 tests 中 1 failed、11 passed，`SecurityError` 在渲染期抛出；加入安全读写后同一目标套件 12/12 passed。读取失败回退为显示引导，写入失败时仍关闭当前内存态引导。
- S2 / Medium：认可并已修复。现有部署契约检查已显式核对 `App.tsx` 的公开 email/password 常量，防止前端与 seed 配置漂移；契约检查退出码 0。
- S3 / Low：记录不改。当前对话框已有语义、明确按钮和可重开入口；完整 focus trap/ESC/焦点还原属于可访问性增强，用户已要求停止额外细调，不扩大本切片。
- S4 / Low：记录不改。该邮箱由用户明确指定为公开 HR Demo 账号，ADR-0015 已记录垃圾邮件/钓鱼取舍；不得把此值复用于真实敏感环境。
- N1：认可，无需修改；公开凭据是显式产品决策，真正服务秘密仍保持私有。
- N2：认可，无需修改；`centered-workspace` 是测试与 DOM 的语义标记，实际居中规则由 `.workspace` 提供。

Pi Attempt 1 已为 PASS；两项 Medium 建议均以最小范围落实，没有新增 API、状态机或权限变化，因此不重复发起第二轮审核。
