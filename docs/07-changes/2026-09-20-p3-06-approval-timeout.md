# P3-06 审批时限与安全自动确认

- 状态：Implemented
- 日期：2026-09-20
- 基线：`c08bebaf82b3943c1cda06d154bc781cca78e86c`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：旧审计记录、`.worktrees/`、产品规划 docx 保留；不处理 P3-04 遗留受 ACL 保护的 pytest 目录。
- 用户授权：完成 P3-05 收尾后继续 P3-06、P3-07；本阶段仅实现 P3-06，提交并远端核验后再进入 P3-07。

## 需求和边界

悬浮审批表单显示弹窗打开后开始的 60 秒倒计时。只有当前 Java Policy 明确为低风险、需要审批的 `CREATE_TASK` 可以在倒计时结束、弹窗仍打开且网络可用时由浏览器请求 `auto-confirm`；`UPDATE_TASK`（中风险）及未来高风险操作必须用户点击确认。关闭弹窗或页面不会暗中触发自动执行。所有决定仍由 Java 核验 actor、project、持久化状态、风险策略、时限、幂等键和审计；Python 只接收经 Java 决定后的 resume，不能自行批准。`auto-confirm` 的审批审计事件明确标识自动来源；失败不降级为手动确认。

倒计时只用于 UI，服务端另以持久化 `created_at + 60s` 为自动确认下限；浏览器时间提前或伪造请求不能提前执行。当前审批状态机、Task 写入和 replay 规则保持 V2 语义。新增公共自动确认入口、审计事件类型和对应迁移；无需改变登录页样式或其它路由。

## 验证计划

先运行门禁规划器；公共 HTTP/Java service/Web 对话框纵向测试先红灯，覆盖未到时拒绝、低风险到期一次执行、中高风险自动拒绝、跨用户/项目、手动与自动竞态幂等、审计来源和倒计时取消。按 L3 公共契约与 schema 影响执行 Java clean verify、Web test/build、迁移与跨进程 smoke；清理、扫描后做 Pi Milestone Review。

## 本机验证与审核

- 门禁规划：L3 / Milestone；Core API、Web、schema，要求数据库集成、Java clean verify、Web 测试、差异与敏感扫描。
- TDD：公共自动确认 HTTP 从 404 红灯到 200 绿灯；Web 对话框倒计时和中风险手动测试先红后绿。
- Java：`mvnw.cmd -q clean verify`，26 suites / 130 tests / 0 failures / 0 errors / 8 skips；Flyway 验证并应用 10 个迁移，含 V10。存在既有 Mockito 动态 agent warning。
- Web：`npm test -- --run --reporter=dot`，5 files / 63 passed；`npm run build` 成功。npm 有既有 user config home warning。
- 跨进程：首次 Day5 smoke 被 Compose 缺少必填测试 token 拦截，未进入业务链路；设置隔离的测试占位值后重新执行 PASS，确认重复 confirm 只写一次、拒绝和跨用户 403；隔离 PostgreSQL 容器、网络及卷已清理。
- P3-05 真实阿里云语音验收仍需用户在本地安全填写 Key/Workspace；本阶段无 Python 实现变化。
- 待 Pi 只读 Milestone Review、最终敏感扫描和 Node Close Gate。

## Pi 审核与 Node Close Gate

Pi Attempt 1：PASS，无阻塞项。建议包括：UI 自动资格提示与 Java Policy 潜在漂移、自动请求失败提示/测试、HTTP 负向状态测试、内联回调造成 interval 重建。服务端 Policy 是唯一安全权威；当前策略变化会被 403 拦截。建议记录供后续质量整理，不扩展本节点范围。

- Node：P3-06，审批时限。
- Scope：悬浮表单 60 秒计时；仅低风险 `CREATE_TASK` 的自动请求；中高风险手动确认；Java 时限、RBAC、幂等、审计和 Agent resume 均已实现。
- 本次修改：独立 `auto-confirm` API、Java 审批复核、`AUTO_APPROVED` 审计与 V10 迁移、Web 对话框倒计时和 API 客户端、文档与测试。未触及 P3-07 Wiki 图谱，V2 登录样式未改变。
- Tests：Java clean verify 130 tests / 0 failed / 8 skipped，Flyway 10 migrations；Web 63 passed/build 成功；Day5 跨进程 smoke PASS。既有 Mockito/npm warning 如上记录。
- DeepSeek Review：Milestone PASS，无必须修改项。
- Gate：YES。提交、非 force 推送并核验远端后，按本轮用户明确授权再开始 P3-07。
