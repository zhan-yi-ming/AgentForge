# R17 Web 异步响应作用域隔离

- 日期：2026-10-04
- 状态：Implemented
- 风险：L1（仅前端状态所有权与跨项目隔离，不改公共 API 或后端写入）
- 范围：React 工作区的 Wiki 保存、Task 刷新、Action 决策与 AI 文本整理

## 问题核实

报告问题成立。当前 Chat stream 和历史详情已有 controller、projectId 或 load sequence 的局部保护，但 `saveWiki`、`loadTasks` 与 `decideAction` 在异步完成后直接写入当前视图；`formatText` 的 delta 和完成只比较 projectId。项目 A 的请求在切换到 B、注销重登录或 A→B→A 后迟到时，可能覆盖新工作区的 Wiki/Task/错误/忙碌状态；旧 Action 决策还可能清除后来出现的 pending action。

## 目标契约与公共 seam

公共 seam 是用户可观察的 React 工作区状态与 `ApiClient` 调用。每次登录会话或项目选择建立新的 workspace generation；异步工作捕获 generation、token 与 projectId，只有三者仍匹配才可写入 Wiki、Task、pending action、格式化结果、错误或 busy 状态。相同 projectId 离开后再返回也属于新 generation。

格式化继续使用 AbortController，并额外要求当前 controller 与 workspace scope 都匹配。Action 请求一旦已发送不重复发送；迟到结果只停止影响旧视图，且只能清除它对应的 action，不能覆盖或清除较新的 pending action。后端已成功的 Wiki 保存或 Action 决策保持成功，不由前端自动重放。

## TDD 与验证计划

1. 先在 DOM/ApiClient 公共 seam 增加延迟 Promise 测试，覆盖 Wiki 保存 A→B、格式化 A→B→A、注销重登录、旧 Action 完成时已有新 pending，以及受作用域保护的 Task 刷新。
2. 运行新增用例取得真实红灯，并记录旧实现的错误可见状态。
3. 最小实现统一 workspace scope 与 operation ownership，保留现有 Chat/历史、审批幂等 key 和 401 退出语义。
4. 复跑新增用例、Web 全量测试与生产构建；按门禁规划执行 diff check、完整 staged Gitleaks 与 Pi Diff Review。

## 验证回填

### TDD 与问题证据

- 通过延迟 `ApiClient` Promise 在公共 UI seam 建立 4 个回归用例。旧实现实际得到 `4 failed`：项目 A 的保存结果覆盖项目 B Wiki；用户 A 的迟到保存覆盖重新登录后的用户 B Wiki；A→B→A 后旧格式化完成覆盖同项目的新整理；项目 A 的旧审批完成清除项目 B 新 pending action。
- 最小实现新增单调递增的 workspace generation，并把 token、projectId、generation 组成请求 scope。项目选择在点击时同步推进 generation，注销/401 reset 同样推进；保存与审批另有 operation sequence，格式化保留 controller identity，pending action 通过同步 ref 只允许原 action 收口自身。
- 已发往后端的 Wiki 保存和 Action 决策不重发；迟到结果仅丢弃前端写入。既有 Chat、历史详情/删除、恢复查询与会话列表刷新也接入同一 scope，避免局部 projectId 检查在 A→B→A 后失效。

### 当前机器绿灯

- 门禁规划：`plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json` 退出 0；`L1`、`Docs/Web`，要求 Web test、docs、diff 与 Gitleaks；fingerprint `a40bf5a250743fcf7abf05c74aace50410eb21caec4a91844024f4c0e8190b39`。按用户明确要求，本问题仍执行一次 Pi Diff Review。
- R17 定向用例：实现后 `4 passed, 56 skipped`，退出 0。
- Web 全量：Pi 修复后的最终相关源码变化后重新执行 `npm test -- --run`，`6` 个 test files、`86 passed`、无 failed/skipped，退出 0。
- Web 生产构建：最终相关源码变化后重新执行 `npm run build`，TypeScript project build 与 Vite build 均退出 0，`295 modules transformed`。首次构建发现新增测试把可选 `onDelta` 当作必选调用，修正测试类型后重跑通过；不涉及产品实现缺陷。
- 清理：删除本次构建生成并经绝对路径校验的 `apps/web/dist` 与 `tsconfig.app.tsbuildinfo`；保留 2026-10-03 已存在的 `tsconfig.node.tsbuildinfo`。R17 未创建 Junction、容器、网络或卷；按 `agentforge-r17` 名称检查均为空。
- 未运行 Core API、Agent Service、数据库、跨进程或 Compose 测试：R17 不改 Java/Python、数据库、HTTP schema、部署配置或服务间契约，Web 全量 DOM/API client 测试与生产构建覆盖当前影响域。

### Pi 与提交前收口

Pi Attempt 1 Diff Review 返回 `NEEDS_FIX`，报告为 `docs/08-reviews/2026-10-06-review-main-review-r17-attempt-1.md`。R17-01 指出项目切换已推进 generation，旧 Chat `finally` 因 scope 失效不能复位 `streaming`；R17-02 指出同一路径会遗留 `deleteBusy`；R17-03 要求补 Chat 取消回归。这三项均可在公共 DOM seam 复现，并与原报告的 stale busy/finally 约束直接相关，因此全部采纳。

新增两例在修复前实际 `2 failed`：切换项目后发送按钮仍显示“生成中…”并禁用，新项目历史删除按钮也保持禁用。最小修复在新 project generation 启动时中止并释放旧 Chat/format controller ownership，并显式复位 `streaming` 与 `deleteBusy`；定向复跑 `2 passed, 60 skipped`，最终 Web 全量和生产构建也重新通过。

Pi Attempt 2 返回 `PASS`，报告为 `docs/08-reviews/2026-10-06-review-main-review-r17-attempt-2.md`，无必须修改项。R17-04 是同 generation 内恢复 GET 理论上覆盖更新 pending 的极窄竞态，现有 session/project/generation/route 防线有效且无复现证据；R17-05 是对已由 controller identity、abort signal 与 scope 共同保护的 Chat 迟到成功分支增加专项测试。二者均为低风险补强建议，按规则记录但不扩大本批、不触发复审。

Attempt 2 报告、处置与最终状态纳入后，完整 staged diff 再执行 `git diff --cached --check` 与 `zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1`；均要求退出 0、`no leaks found`，之后不再修改 R17 内容。

## 风险与回滚

保护过严可能丢弃仍属于当前工作区的有效结果，保护过松则继续污染新工作区；测试必须同时覆盖普通成功和跨 generation 迟到。回滚本提交恢复旧前端行为，不涉及数据库、公共 HTTP schema 或业务数据迁移。
