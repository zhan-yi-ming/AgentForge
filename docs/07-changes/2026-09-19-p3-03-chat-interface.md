# P3-03 聊天界面整理

- 状态：Implemented
- 日期：2026-09-19
- 基线：`365356edef56609115745bad47238b1b358b60fb`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：`docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md`、`.worktrees/`、产品规划 docx 保持原状

## 目标与边界

聊天路由的输入框首次显示为紧凑单行，可主动展开和缩回；引用文章默认折叠并可逐条回答展开；待确认 Action 以覆盖聊天内容的独立弹层呈现，带清晰字段、手动确认/拒绝和键盘焦点。关闭弹层只暂时隐藏，待决操作可重新打开，不等于批准或拒绝。历史删除确认弹窗也补上初始焦点和 Esc 关闭；Esc 绝不执行删除。业务写入仍仅由 Java 接口决定。

本阶段不实现自动通过或 60 秒倒计时（P3-06），不改变引用的证据生成（P3-04），不新增页面或改变路由。确认弹层展示当前提案字段；修改提案字段需要 Java 公共契约和验证规则，留给后续明确设计，界面不提供无效的假编辑。

## 公共 seam 与验证

以 Web DOM 为验收 seam：路由进入后的输入框状态与切换、引用折叠开合、确认弹层可访问性与 Esc/重新打开、手动执行/拒绝。各行为先观察失败再最小修复；运行风险规划器、Web 测试与构建、敏感扫描和与风险相称的 Review。

## 实施与验证证据

- Git preflight：`git fetch origin codex/pre-v3-chat-experience` 退出码 0；本地和远端均为 `365356edef56609115745bad47238b1b358b60fb`，用户原有三个无关路径未纳入本阶段。
- `plan-change-gates.ps1 -Paths ... -Json`：L1，影响域 Docs/Web，门禁为 diff/docs/sensitive scan/Web test；不触发 Pi。页面路由、API、审批状态机均未改变。
- TDD 红灯：聊天页缺“放大聊天输入框”按钮；引用文章默认出现在 DOM；待决 Action 无独立 dialog；历史删除弹窗未聚焦取消按钮。各用例分别实际失败，再经最小实现转绿。
- 最终 `npm test -- --run --reporter=dot`：Vitest 3.2.7，3 files/53 passed/0 failed/0 skipped，退出码 0。覆盖会话路由、新会话、引用按回答独立折叠、审批手动确认/拒绝、Esc 隐藏与重新打开、历史删除 Esc 不调用 API。
- 最终 `npm run build`：TypeScript 5.9.2、Vite 7.3.6，退出码 0；聊天页仍为独立按需加载 chunk（4.80 kB/gzip 1.91 kB），入口 JS 223.48 kB/gzip 70.91 kB。npm 有现存 `Unknown user config "home"` warning，未隐藏。
- 代码审查：确认弹层的关闭动作只改本地可见状态，执行/拒绝仍调用既有 Java typed API；引用只读展示，输入框状态只影响聊天页布局；未发现当前范围的阻塞项。Pi 依规划器对 L1 不调用。
