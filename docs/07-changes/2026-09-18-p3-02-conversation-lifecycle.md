# P3-02 历史会话生命周期与回复展示

- 状态：Implemented
- 日期：2026-09-18
- 基线：`c04f3fc553030ac9b5218f0a7aa50d95220ef4bb`；目标远端 `origin/codex/pre-v3-chat-experience`
- 用户已有改动：`docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md`、`.worktrees/`、产品规划 docx 保持原状

## 目标与公共 seam

历史抽屉能删除当前用户在当前项目的一条已完成会话；删除后列表和详情不可再读，也不能以旧 conversationId 继续保存。已有 AI 回复在后续发送消息时保持可见。公共验收 seam 为 Core HTTP、Web DOM/typed API；状态一致性通过真实数据库和应用服务验证。

## 删除边界

Java 仍是会话与审批的权威。删除前重新校验 JWT、项目权限和会话 project/user 作用域。存在 PENDING 或 APPROVED Action 时返回 409，要求用户先手动完成或拒绝审批。终态 Action 与审计事实保留，Python checkpoint 不由此 HTTP 操作清理。会话行作为不可复用 tombstone 保存，已完成消息正文和来源物理删除，预览清空；列表、详情及后续 append 排除 tombstone。前端提供明确的单次删除确认，删除当前会话后进入空白 `/chat`，旧请求与流失效。

并发边界：创建待审批 Action 时也锁定已存在的会话主行并拒绝 tombstone，与删除事务串行化；新会话在首条消息完成前尚无主行，因此不存在可删除的历史记录。

已知 conversationId 的 Chat 请求在转发 Python 前由 Core 检查 tombstone，删除后立即重用旧 ID 会得到 409；在途流与删除交错仍由持久化及 Action 创建时的锁和 tombstone 守卫约束。

## 范围与验证计划

更新历史 HTTP/typed API、数据库迁移与 Repository、Java 删除规则及 Web 历史入口。先让 HTTP/DOM 回归实际失败，再最小实现；运行门禁规划器，完成 Core clean verify、Web 相关测试和构建、敏感扫描及 L3 Pi Milestone Review。P3-03 的输入框和确认卡片样式留在下一阶段。

## 当前验证证据

- Git preflight：fetch `origin` 退出码 0，本阶段基线和远端均为 `c04f3fc553030ac9b5218f0a7aa50d95220ef4bb`；用户已有三个无关路径不纳入提交。
- `plan-change-gates.ps1 -Paths ... -Json` 返回 L3、CoreApi/Docs/Web、`database-integration`、`java-clean-verify`、`web-core-contract`、`web-test` 与 Milestone Review。
- TDD 红灯：新增 DELETE HTTP 用例先因服务无 `delete` 方法编译失败；旧 AI 回复用例先以找不到 `First answer` 失败；手动删除用例先以缺删除按钮失败；旧 ID 待审批与 Chat 前置检查先因新构造/方法不存在编译失败。各切片最小实现后聚焦测试通过。
- Web 全量 `npm test -- --run --reporter=dot`：Vitest 3.2.7，最终 3 files/48 passed/0 failed/0 skipped；中间曾因旧用例按钮选择器同时命中“删除”而 5 failed/42 passed，改为明确选择历史入口后通过。`npm run build`：Vite 7.3.6 / TypeScript 5.9.2，退出码 0，Chat/Wiki 独立 chunk 保持，入口 JS 222.65 kB/gzip 70.69 kB。
- Core `mvnw.cmd -q clean verify`：Java 21.0.12.1，退出码 0，当前源码干净构建的 Surefire 汇总为 24 suites/117 tests/0 failures/0 errors/8 skipped。`PersistenceIntegrationTest` 8/8 执行无跳过，使用 Testcontainers PostgreSQL 17.11 验证 Flyway V9、JPA validate、会话删除后消息清除与 ID 不可复用。Mockito 动态 agent 有现存 JDK warning；未隐藏跳过与 warning。
- 8 个 skip 均属 `AgentServiceHttpContractIntegrationTest` 的外部 Agent Service 条件测试；本阶段未启动跨进程服务，Core 与 Web 新 DELETE 契约分别在 HTTP、typed client 和真实数据库 seam 验证。
- Pi 第一次 Milestone Review 返回 PASS、无阻塞；建议 R-04 指出删除在途时切到另一会话可能被旧回调重置路由，先补 Web 回归再修复。R-02 的跨作用域 Chat 状态码统一为 404；R-06 移除可静默跳过 tombstone 检查的测试构造器旁路。其余建议按影响评估，不为纯建议重复审核。
- R-04 回归实际先红：删除 A 未决时切到 B，删除完成把 `/chat/B` 改为 `/chat`；修复后保持 B。另补首页删除 A 保持 `/` 的用例。R-02 的 404 用例先红（原为 403）再绿；补流式 `prepareStream` 旧 ID 前置拒绝测试。R-06 删除空 Repository 构造路径，R-08 改批量消息删除；R-03 的跨设备在途流与删除交错可能已经消耗配额，保留为已知限制，不改变现有 SSE 完成/持久化顺序。R-07 当前为同一 Java 模块内的应用服务协作，未发现运行期循环依赖。
- 修复后最终 Web 全量为 3 files/50 passed/0 failed/0 skipped，生产构建退出码 0（入口 JS 222.85 kB/gzip 70.72 kB）；Core `mvnw.cmd -q clean verify` 退出码 0，24 suites/119 tests/0 failures/0 errors/8 skipped，真实 PostgreSQL `PersistenceIntegrationTest` 8/8 执行。测试输入改变后均在本机重跑。
- 第二次 Pi Milestone Review 返回 PASS、无阻塞；建议 R2-01 指出 AgentChatService 旧构造器可静默跳过 tombstone 守卫，收紧为强制依赖并重跑 Core。R2-02 的公共 HTTP 状态码可由已覆盖的服务、控制器与流式前置调用链组合验证；R2-04 锁范围优化与 R2-06 弹窗焦点体验分属后续性能/界面阶段。
- R2-01 先增加空历史服务依赖构造测试并实际观察红灯，随后移除四参构造器，五参构造器明确拒绝 null。聚焦 `AgentChatServiceTest` 退出码 0；最新 `mvnw.cmd -q clean verify` 在 Java 21.0.12.1 退出码 0，24 suites/120 tests/0 failures/0 errors/8 skipped；真实 PostgreSQL 17.11 迁移 V9 和 8 个持久化集成用例通过。Mockito 动态 agent warning 仍在。Web 源码自上次 50/50 测试及构建后未变化，可复用本阶段证据。
- 第三次 Pi Milestone Review 返回 PASS，无必须修改项；逐条评估建议并回填报告。阶段最终 diff check 与敏感扫描通过；本阶段仅提交 P3-02 文件。后续 P3-03 将处理弹层焦点与键盘体验。
