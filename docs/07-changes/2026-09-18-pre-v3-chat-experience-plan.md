# V3 前聊天体验与 Wiki 可视化修复

- 状态：In Progress（P3-01 已实现、验证和审核；P3-02 至 P3-07 待执行）
- 日期：2026-09-18
- 基线：`7778a78be7b509e6ddfbfcb9d149d0e8f34a09e3`；目标远端 `origin/codex/pre-v3-grafana-logs`

## 背景与目标

当前 Web 把聊天、Wiki 和其他工作台留在一个页面状态中；历史虽按 project/user/conversation 持久化，却缺少显式新建和删除。新消息把旧回答折叠，来源展示检索候选而非已核实引用，Tool 意图依赖固定正则。确认卡片和输入框布局不符合需求。用户要求在 V3-01 前按阶段修复，并为聊天与 Wiki 提供独立路由。

## 范围与顺序

1. 路由与会话隔离：`/chat`、`/chat/:conversationId`、`/wiki`，新建、进入历史、刷新恢复与跨项目隔离。新增页面各自使用路由和页面组件，按路由加载代码，不再把页面实现堆在 `App.tsx`。
2. 历史生命周期与消息展示：单会话删除、旧回答保持可见、流失败与切换竞态处理。
3. 聊天 UI：输入框默认紧凑，确认卡片悬浮，引用文章列表可收起。
4. Tool 意图与引用：自然语言生成结构化提案，Java 继续决定权限/审批/写入；引用与回答证据关联。
5. 语音输入：实时 ASR 转写进入可编辑输入框，用户决定何时发送；凭据仅在服务端。
6. 审批倒计时：默认 60 秒；高危操作必须手动确认，任何自动处理保持 Java 幂等、审计和权限复核。
7. Wiki 关系可视化：`/wiki/graph` 展示有明确来源的 Wiki 链接；V3-04/05/07 的图模型、抽取与 GraphRAG 顺序不变。

每阶段独立记录实际修改和验证，完成前一阶段并核验远端后继续已获授权的下一阶段；不得把多个阶段混进同一提交。

## 本次首先执行的阶段

阶段 1：明确浏览器路由、页面拆分与会话作用域。前端只通过 Core API 读取历史，URL 中不放凭据或用户 ID；聊天、Wiki 和后续新页面使用独立页面组件与按需加载。公共 seam 为 Web DOM/typed API 与 Core 历史 HTTP。先验证新会话和历史切换的失败信号，再最小实现。

## 非目标与风险

不提前建设 Neo4j、图抽取、GraphRAG 或 V3 Model Gateway。高风险写操作不自动批准。会话删除须处理 pending action、checkpoint 与审计引用，不能只删除前端列表。主要风险是跨项目/用户读写、旧 SSE 回调污染新会话、刷新后错误复用 ID。

## 目标文档

`docs/01-product/v2-v3-node-roadmap.md`、`docs/03-features/conversation-history.md`、`docs/03-features/web-workspace.md`、`docs/02-architecture/frontend-architecture.md`；后续阶段按实际接口、数据和边界补充 API、数据架构和 ADR。

## 验证回填

P3-01 涉及 `apps/web/src/route.ts`、`apps/web/src/pages/ChatPage.tsx`、`apps/web/src/pages/WikiPage.tsx`、`apps/web/src/App.tsx` 与 Web DOM 测试。新会话从空白 `/chat` 开始，首条响应取得 ID 后切换到 `/chat/:conversationId`；历史详情按 ID 加载，旧请求被取消或忽略。Chat 与 Wiki 页面分别形成独立生产 chunk。

- `C:\Users\86134\Documents\ChatGPT\AgentForge\apps\web`：`npm test -- --run tests/app.test.tsx -t "opens a selected conversation at its own route"`，Vitest 3.2.7，初次退出码 1，1 failed/31 skipped，实际红灯为 URL 仍是 `/`；实现后退出码 0，1 passed/31 skipped。
- 同目录：`npm test -- --run tests/app.test.tsx --reporter=dot`，曾因路由清空正在生成的回答失败，修复后退出码 0，33 passed/0 failed。
- 同目录：`npm test -- --run --reporter=dot`，中间版本退出码 1，6 failed/35 passed；修复路由竞态及异步页面测试后，最终退出码 0，3 files / 41 passed / 0 failed / 0 skipped。
- 同目录：`npm run build`，Vite 7.3.6 / TypeScript 5.9.2，最终一次退出码 0；生成 ChatPage 2.21 kB、WikiPage 2.61 kB、TaskView 1.03 kB、FormatView 1.25 kB 和 MarkdownPreview 157.70 kB 独立 chunk。入口 JS 220.73 kB，gzip 70.23 kB；本次开始时未拆包入口约 381.89 kB / gzip 118.62 kB。这里只陈述本地构建资产大小，不宣称运行时延迟改善。
- 当前阶段规划器：`scripts/validation/plan-change-gates.ps1 -Paths ... -Json` 返回 Docs/Web、L1；人工上调为 L3 Milestone Review，因为 URL 会话恢复与跨项目隔离属于安全边界。用户已有 `.worktrees/`、产品规划 docx 和一份历史记录改动不纳入本阶段。
- 本阶段暂存 diff：`git diff --cached --check` 退出码 0；高置信密钥模式扫描命中 0；测试临时日志已清理。`gitleaks` 未在本机 PATH 中发现，未宣称该工具通过。
- Pi 预检：按 `docs/06-operations/pi-review-connection.md` 以配置的启动器执行 `--list-models deepseek-v4-flash`，退出码 1。依快速失败规则没有自动换模型、安装或重试；Milestone Review、提交与推送待用户修复本机 Pi 或明确豁免本次审核。
- 后续排障确认该退出码 1 的直接原因是沙盒拒绝 Pi 在用户目录创建 `trust.json.lock`，并非已证明旧模型不存在。用户随后明确要求 Codex 修复 Pi，官方文档确认当前 API ID 为 `deepseek-flash`；修复过程和真实证据见 `2026-09-18-pi-v41-flash-review-repair.md`。P3-01 后续审核必须使用新 ID 与沙盒外授权进程。
- Pi 首次 Milestone Review 发现历史详情加载中离开聊天后，迟到响应会重新导航到旧会话（F1，高风险）。先通过 Web DOM 回归测试复现，再在离开路由时使加载失效；同轮校正文档状态及 Wiki 草稿路由，完成验证后复审。
- F1 回归实际先红：`npm test -- --run tests/app.test.tsx -t "does not reopen a conversation" --reporter=dot` 退出码 1，离开 Wiki 后 URL 被旧响应改回 `/chat/conversation-late`；修复后同命令退出码 0，1 passed/33 skipped。最终 Web 全量 `npm test -- --run --reporter=dot` 退出码 0，3 files/42 passed/0 failed/0 skipped；`npm run build` 退出码 0，Chat/Wiki 保持独立 chunk，入口 JS 220.87 kB/gzip 70.24 kB。
- 第二次 Pi 审核仍为 NEEDS_FIX：从已加载会话 A 选 B 时旧路由可能重载 A；离开聊天时 SSE metadata 也可能迟到改写路由；无 ID `/chat` 的在途详情失效条件不足。先在公共 Web DOM seam 为这些路径建立红灯，再修正路由与流的生命周期。
- 第二轮 F1/F2/F6 的三个聚焦测试实际先红（3 failed），修复后 3 passed；Web 全量一度出现 6 failed/39 passed，定位为 `/chat` 无 ID 分支误伤从首页新发送，修正失效条件后最终 3 files/45 passed/0 failed/0 skipped。`npm run build` 退出码 0，入口 JS 221.10 kB/gzip 70.27 kB，Chat/Wiki 仍独立 chunk。`apps/web/tests/setup.ts` 已统一清理两种 storage，Pi F4 无需额外改造。
- 第三次 Pi Milestone Review 返回 PASS，无阻塞项。非阻塞建议中，V1.2 架构描述已对齐 Wiki 独立路由，停用的 validation 入口已标为历史；补充 metadata 触发 URL 更新的验收断言后收口。
- 最终 Web 全量 `npm test -- --run --reporter=dot` 为 3 files/46 passed/0 failed/0 skipped，`npm run build` 退出码 0；Pi attempt-3 `PASS`，本阶段可提交。P3-02 至 P3-07 继续按路线各自独立记录、验证、提交。
