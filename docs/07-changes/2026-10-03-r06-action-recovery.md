# R06 审批恢复与幂等重试

- 状态：Verified（待提交）
- 日期：2026-10-03
- 风险：L3（审批状态机、幂等与公共 API）
- 影响域：Core API、Web、Docs

## 问题确认

深层审查 R06 成立。Web 仅在 React 内存保存 `pendingAction` 与 Action 对应的决定幂等键；历史会话读取只返回消息，Core API 也没有查询可恢复 Action 的接口。因此刷新、跨设备或从 MCP 创建 Action 后无法继续审批；若 confirm 已把 Action 提交为 `APPROVED`、但响应或后续执行失败，刷新后生成新键会被服务端按正确的防重放规则拒绝。

## 目标与边界

- 新增项目范围、当前用户范围的可恢复 Action 读取接口，仅返回 `PENDING` 与 `APPROVED`。
- 可按 conversationId 过滤 Chat Action；不带过滤时同时覆盖 Chat 与 MCP Action。
- `APPROVED` 恢复响应携带服务端已持久化的原决定键，Web 只能用该键继续 confirm；`PENDING` 不返回决定键，用户作出决定时才生成新键。
- 历史会话打开时并行恢复该会话的 Action；项目初始化时恢复最早的项目级 Action，从而让 MCP Action 也有入口。
- `APPROVED` UI 不得重新 reject 或自动确认；终态 Action 不得重新出现。
- 保持 Java 权限、策略校验和业务写入职责不变，不修改数据库 schema、Python Agent 或既有终态语义。

## 验证计划

- 先以 Core API service/API 测试证明当前缺少授权恢复 seam，再实现最小查询路径。
- 以前端 App/API 测试覆盖直接刷新历史会话、恢复 APPROVED 原键、MCP 项目级恢复及终态不恢复。
- 运行规划器、相关 Java/Web 测试、Core API clean verify、Web build、diff check、敏感扫描和 Pi Milestone Review。

## 验证回填

### TDD 与实现

- Java 红灯：`AgentActionServiceTest,AgentActionApiTest` 在恢复 View、repository seam 与 service 方法不存在时 testCompile 失败；这证明当前没有受保护的恢复契约。
- Web 红灯：`tests/api.test.ts` 明确失败为 `listRecoverableActions is not a function`。
- 新增 `GET /agent/actions/recoverable`，服务先校验项目访问，再固定用当前 actor 的 userId 查询 `PENDING / APPROVED`；即使 ADMIN 也不会通过恢复列表读取他人的决定键。
- repository 按 createdAt 升序读取项目级 Chat/MCP 队列，conversationId 过滤路径仅返回对应 Chat Action。
- 恢复响应保留 `CHAT / MCP` 来源。Web 对 MCP CREATE 继续显示手动确认，不复用 Chat 的定时自动确认。
- 历史刷新恢复该 conversation 的 Action；项目初始化恢复 Chat/MCP 项目队列的首项。`APPROVED` 只显示“继续执行”，隐藏 reject 并复用原决定键。

### 当前机器证据

- `mvnw -Dtest=AgentActionServiceTest,AgentActionApiTest test`：28 tests，0 failure/error/skip，退出码 0。
- `mvnw -Dtest=PersistenceIntegrationTest#recoverableActionsIncludeChatAndMcpButConversationScopeReturnsOnlyItsChatAction test`：真实 PostgreSQL，1 test，0 failure/error/skip，退出码 0。
- `npm test -- --run tests/api.test.ts tests/app.test.tsx`：63 tests，全部通过，退出码 0。
- MCP CREATE 恢复定向回归：1 passed、52 skipped（过滤未执行），退出码 0。
- `services/core-api/mvnw clean verify`：226 tests，0 failure/error，12 skipped，BUILD SUCCESS，退出码 0。日志仍包含已有图同步测试切换 Testcontainers 后调度线程访问已关闭 PostgreSQL 的 warning/error 日志，但未造成测试失败，且不在本次审批读取路径。
- `apps/web npm test -- --run`：6 files、76 tests 全部通过，退出码 0。
- `apps/web npm run build`：TypeScript 与 Vite production build 成功，退出码 0。
- `scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json`：L3，CoreApi + Web + Docs，Milestone Review；回填前 fingerprint `a663609ffbbc3c80a4ca308951d6ec240fd140c103963123a5a4829115364161`。
- `git diff --check`：通过。

### 效率记录

- 规划读取集中在治理入口、审批功能/API、Action 状态/仓储、App/API 与直接测试；未扩大到 Python 或无关业务实现。
- 截至 Pi 前执行 8 组测试/构建命令；相关输入变化后定向重跑 2 组，没有复用跨任务证据。
- Pi 次数：1；结果 PASS，无阻塞项。返工：一次自查（恢复的 MCP CREATE 不能继承 Chat 自动确认）及一次 PASS 建议加固（恢复失败隔离、旧提案禁重新自动确认、队列推进、安全证据）。

### Pi 审核与建议处置

- Pi Milestone Attempt 1：PASS，无必须修改项。
- S1 采纳：恢复 GET 与核心工作区/历史读取解耦；恢复失败仅报告，不再丢弃已成功读取的页面、任务或消息。
- S2 采纳：恢复的 PENDING Chat Action 不重新武装 60 秒自动确认；实时新提案的既有自动确认测试继续通过。
- S3 采纳：决策完成及返回空 Chat 路由后刷新并推进下一条可恢复 Action。
- S4 采纳：真实 PostgreSQL 补充跨用户 ADMIN 查询为空及 EXECUTED/REJECTED 终态排除。
- S5 采纳：TS 允许 `decisionKey` 为省略或 null，与 API 文档统一。
- PASS 后建议调整不触发第二轮 Pi；Codex 已重跑受影响证据：PostgreSQL 定向 1/1、Web API/审批测试保持通过、最终 App 完整 56/56、production build 成功。
- 加固过程中没有隐藏失败：首次组合 Web 回归为 68/69，原因是测试夹具错误预期了直接历史路由会先发项目级恢复请求；修正夹具后通过。随后一次过宽的空 Chat route effect 造成 11 个既有 App 用例失败；触发条件被收敛到“新建会话/从历史返回”后，定向 3/3 与最终 App 56/56 均通过。
