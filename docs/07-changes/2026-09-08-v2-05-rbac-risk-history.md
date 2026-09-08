# V2-05 完整 RBAC、Risk Engine 与历史会话

- 日期：2026-09-08
- 状态：Completed
- 阶段：V2-05
- 交付目标：`origin/codex/v2-05-rbac-risk-engine`
- 基线：`f8c0f10243cc8d02506228167ad9ef8bab0ae21a`

## 背景

现有 Core API 已有 owner-or-admin 项目访问和 Day 5 Task Action 确认，但 Tool 的角色、风险和确认要求仍隐含在各用例中；Python 的 Action Intent 也没有统一、不可伪造的服务端策略描述。聊天历史同时只存在于浏览器和 Agent Service 进程内，用户不能可靠列出并重新打开历史会话。

## 目标

- 建立 Java 集中式 `ToolPolicy` 与 `RiskEngine`，统一 `required_role`、`risk_level` 和 `need_approval`。
- Agent Intent 与直接 Java API 都经过 ProjectAccess 和服务端风险策略，客户端字段不能降低风险。
- 用 PostgreSQL 持久化已完成的 user/assistant exchange，提供授权范围内的会话列表与详情。
- Web 左侧提供历史入口，选择记录后恢复对应聊天内容页和 conversationId。

## 非目标

- 不实现 V2-06 的通用 Approval、Audit Log、Idempotency Key 或新审批状态。
- 不实现 Agent 删除 Tool、项目成员邀请、细粒度 workspace membership。
- 不实现 V2-07 checkpoint、interrupt/resume 或重启后的 Agent Context 恢复。

## 受影响文档

- 新增 `security-and-risk.md`、`conversation-history.md`。
- 更新认证、Agent Chat、Web、Core/Agent API、Backend/Frontend/Data Architecture 与测试策略。
- 新增 ADR-0019/0020，分别记录集中式 Java 风险策略和 Core 持久化展示历史。

## 设计决定

- Java 以服务端 Tool 名称查询固定策略；模型、Python、Web 均不能提交可信 Metadata。
- `READ/LOW/MEDIUM/HIGH` 是动作风险，不等同于用户角色。首版角色沿用 `USER/ADMIN` 与 Project owner-or-admin。
- `search_wiki/get_task` 为 READ；`create_task` 为 LOW；`update_task` 与普通内容修改为 MEDIUM；删除为 HIGH 且要求 ADMIN。Agent create/update 沿用现有确认票据；不提前建立通用审批系统。
- Core API 在同步成功或 SSE complete 时保存完整 exchange；半截流和错误不保存 assistant 消息。
- 历史表服务于展示与继续使用 conversationId，不替代 Python Context Memory。

## 实现

- 新增 Java `ToolRiskEngine`，以不可变服务端映射定义 Wiki/Task Tool 的角色、风险和审批要求；直接 Wiki/Task 用例与 Agent Task Intent 均经过该策略。
- 新增 Flyway V6、Conversation/Message 领域模型与持久化适配器；仅在同步回答成功或 SSE complete 时保存成对消息。
- 新增当前 Project/User 作用域内的历史列表与详情 API，跨 Project/User/Thread 查询不返回正文。
- Web 新增历史会话抽屉，可恢复已完成问答并继续使用原 `conversationId`；项目切换会清理旧历史，新回答完成后异步刷新列表。

## 验证结果

- Git preflight：`git fetch origin main` 首次因 `.git/FETCH_HEAD` 权限退出 1；受控权限重跑退出 0，远端 `origin/main` 为 `17cb7b6`。本地 `main` 与确认基线均为 `f8c0f10`，本地 main 领先远端 10 个提交。
- 分支：首次 `git switch -c codex/v2-05-rbac-risk-engine f8c0f10...` 因 `.git/index.lock` 权限退出 128；受控权限重跑退出 0。
- 用户既有未暂存 Markdown、`.worktrees/` 和规划 DOCX 保持范围外。
- TDD 红灯：`ToolRiskEngineTest`、`ConversationHistoryServiceTest`、Web `ApiClient` 历史方法在实现前分别以缺失类/方法失败；实现后定向用例通过。期间一次 Maven 用例名输入错误导致“无匹配测试”，随即用正确类名重跑。
- Core API：`.\mvnw.cmd clean verify` 退出 0，95 tests，0 failures/errors，7 个需外部 Agent 进程的条件用例跳过；PostgreSQL 17.11、Flyway V1–V6 和 JPA 真实集成通过。
- Java→Python 契约：启动本地 Agent Service 后运行 `AgentServiceHttpContractIntegrationTest`，7/7 通过，随后终止临时 uvicorn 进程。
- Agent Service：`.venv\Scripts\python.exe -m pytest -q --cache-clear` 退出 0，81 passed，4 warnings（Starlette 别名、HTTP 422 弃用提示与 pytest cache ACL）。
- Web：历史详情、跨项目迟到响应、跨账号登出重置与 API Client 契约纳入全量回归；`npm test -- --run --reporter=dot` 退出 0，3 files / 34 tests passed。`npm run build` 退出 0，283 modules transformed。
- Pi Attempt 1 结果 `NEEDS_FIX`：历史详情的项目切换竞态和 Web 全量门禁为阻断项。竞态 DOM 用例先红后绿，过时 UI 交互用例同步后全量转绿；同时收敛 UPDATE_WIKI approval、穷尽 Tool 映射、Unicode 安全截断、抽屉状态与定向测试覆盖。
- Pi Attempt 2 结果 `NEEDS_FIX`：发现跨账号登出状态残留；以共享 workspace reset 修复显式登出和 401 路径，并以 DOM 用例完成红绿验证。同期隔离 SSE 历史持久化故障、保护已完成响应，并收紧历史请求的跨项目 `busy` 状态。
- Pi Attempt 3 结果 `PASS`：此前阻断项均核实关闭，无新增必须修改项；四项非阻断建议完成逐项判断，文档真实性/层级问题已修正，HTTP 详情 seam 的额外契约用例不在 PASS 后扩大本节点范围。

## Node Close Gate

- Node：V2-05 完整 RBAC + Risk Engine 与持久化历史会话。
- Scope 完成：集中式 Tool Policy/Risk Engine、Agent 与直接 API 确定性授权、Core/PostgreSQL 历史持久化及受作用域保护的列表/详情、Web 历史恢复均已完成。
- 实际修改：Core 安全策略与历史领域/API/Flyway、Web 历史入口和竞态/登出保护、对应架构/功能/API/测试文档与 ADR。
- 明确未实现：V2-06 通用 Approval 状态机、Idempotency Key、Audit Log；V2-07 checkpoint/interrupt/resume。
- Tests：Core clean verify 95 tests；Agent pytest 81 passed；Java→Python 7/7；Web 34 tests 与 production build；PostgreSQL/Flyway/JPA 真实集成通过。
- DeepSeek Review：Attempt 3 `PASS`；Attempt 1/2 阻断项已修复，非阻断项已逐条回填。
- GitHub Maintenance：README not required；Architecture updated；Docs updated；Demo/Screenshot not required；Evaluation/Evidence recorded。
- 公开描述真实性检查：pass。
- 推荐 Commit：`feat(security): complete v2-05 risk engine and conversation history`。
- 是否满足进入下一 Node：YES（仅表示 V2-05 可关闭；V2-06 仍须用户新的明确授权）。

## 风险与回滚

本节点是 Schema、权限、安全策略与公共 API 变化，风险 L3，影响 Core API、Agent Service、Web、PostgreSQL 和跨服务契约。主要风险为 IDOR、直接 API 绕过、Metadata 篡改、SSE 半截历史、并发顺序和角色语义回归。回滚时撤销新增 API/实现并新增补偿迁移停用历史表；已发布 Flyway 文件不得修改或删除。
