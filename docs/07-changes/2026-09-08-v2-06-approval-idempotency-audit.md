# V2-06 Approval、Idempotency 与 Audit

- 日期：2026-09-08
- 状态：Completed
- 完成日期：2026-09-09
- 阶段：V2-06
- 目标分支：`codex/v2-06-approval-idempotency-audit`
- 基线：`97779c0970b4c36e867941b377e00263ad029750`
- Start Gate：用户已于 2026-09-08 明确授权在 V2-05 推送后直接开始 V2-06。

## 背景与目标

V2-05 已集中 Tool Risk/RBAC，但 Agent Task Action 仍沿用 Day 5 的三态确认票据，不能完整表达批准后执行失败，也没有显式幂等键和可查询的追加式审计事实。V2-06 将在 Java 确定性边界内建立 `PENDING / APPROVED / REJECTED / EXECUTED / FAILED` 生命周期，保证确认重试、响应丢失和 replay 不造成重复或越权执行，并记录 who、project、action、target、requestId、approvalId、result 与 timestamp。

## Scope

- 将现有 Agent Task Action 演进为首个通用 Approval 用例，状态与 Tool Policy 对齐。
- confirm 接受显式 Idempotency Key；相同 actor/project/approval/key 重放返回相同结果，不重复写 Task。
- confirm/reject 每次都重新执行 ProjectAccess、请求者归属和服务端 Tool Policy 校验。
- 审批创建、批准、拒绝、执行成功/失败形成 Java 写入的追加式 Audit Event。
- Web 在确认调用中生成并复用本次决策的幂等键；网络失败时保留待确认卡片以便安全重试，服务端 `FAILED` 终态则稳定展示并清理卡片。

## 非目标

- 不实现 V2-07 LangGraph checkpoint、interrupt/resume。
- 不增加 Agent 删除 Tool、审批人分派、多级审批、审批过期或外部事件总线。
- 不把 Python、LLM 或 Web 提供的 risk/role/approval 当作可信授权事实。

## 风险与门禁

本节点修改 Schema、状态机、权限、幂等和公共 HTTP 契约，手工提升为 L3。要求 Core `clean verify`、真实 PostgreSQL/Flyway/JPA、Web 全量与 build、相关跨服务契约、敏感扫描及 Pi Milestone Review。

## 预确认公共测试 Seam

1. Core HTTP：confirm/reject 的 Idempotency-Key、状态响应、401/403/404/409 与 replay 契约。
2. Core Application：Approval 状态迁移、执行前权限复核、同 key 重放、不同 key 冲突、拒绝后不可执行、失败终态。
3. PostgreSQL：唯一约束与行锁下的并发确认、Task 只写一次、Audit Event 与业务状态原子提交。
4. Web DOM/API Client：一次确认生成稳定 key；网络失败后重试复用 key，成功后刷新 Task，拒绝不写。

第一条红灯测试须等待用户确认以上 seam。

## 实现进展

- 用户已确认 Core HTTP、Core Application、PostgreSQL 与 Web DOM/API Client 四个公共 seam。
- `agent_task_action` 已演进为五态 Approval，并绑定首次决策 Idempotency Key；confirm/reject 同 key 返回既有事实，不同 key 冲突。
- Java 在 confirm 时重新检查 ProjectAccess、action owner 与 Tool Risk Policy；批准后执行成功/业务冲突分别落为 `EXECUTED`/`FAILED`。
- V7 新增幂等唯一索引和追加式 `agent_action_audit_event`，记录 actor、project、approval、action、target、requestId、key、result 与时间。
- Web 确认/拒绝请求生成 action 级稳定 key；网络失败保留 pending action，并在重试时复用 key；服务端返回 `FAILED` 时清除待决卡片、显示稳定错误且不刷新 Task。

## 当前验证证据

- Application 首次红灯为缺少审计类型与新 confirm 签名；最小实现后 `AgentActionServiceTest` 8/8 通过。
- HTTP 红灯证明缺少 Idempotency-Key 时没有统一 Problem Details；修复后 `AgentActionApiTest` 3/3 通过。
- Web 红灯分别证明请求头缺失和重试 key 未复用；修复后定向 2 files / 34 tests 通过。
- Web `FAILED` 展示切片先因无 alert 红灯，最小实现后定向 1/1 通过，且 Task 列表没有被误刷新。
- PostgreSQL 首次因 V7/`approved_at` 缺失红灯；Docker 恢复后 PostgreSQL 17.11、Flyway V1–V7、JPA validate、同 key 单次写入与审计链通过。
- 批准后 Task 版本冲突真实落为 `FAILED` 且不覆盖目标；双线程同 key confirm 仅创建一个 Task 和一条 EXECUTED 审计。
- Core 全新门禁：最终源码以 Java 21.0.12.1 执行 `mvnw.cmd clean verify`，退出 0；102 tests、0 failures/errors、7 个显式外部 Agent 条件测试 skipped。真实 PostgreSQL 17.11、Flyway V1–V7、JPA validate 与 7 个 Persistence tests 全部通过。
- Web 全量：Vitest 3 files / 37 tests 全部通过；TypeScript 与 Vite production build 退出 0。仅保留 npm `home` 配置弃用提示，不影响结果。
- Agent 全量：Python 3.14 / pytest 执行 `81 passed`、0 failed/skipped；4 个既有 warning 为 Starlette/HTTP 422 弃用与 `.pytest_cache` ACL。
- Java→Python 契约：第一次临时 uvicorn 缺少必填测试 `RAG_DB_DSN`，7 项中 2 error，明确不计为通过；补齐非敏感占位 DSN、保持 RAG/LLM disabled 后重跑 7/7、0 skipped、BUILD SUCCESS，随后正常终止服务。
- 清理：删除本轮 Web `dist`、Core `target` 与 Agent `src/tests` 下生成的 `__pycache__`；既有、被 Git 忽略且有 ACL 限制的 `.pytest_cache` 保持不动。

## Pi Milestone Review

- Attempt 1 使用 `deepseek/deepseek-v4-pro` 对已扫描 INDEX 做一次性只读 Milestone Review，结论 `NEEDS_FIX`：1 个必须修改项、4 个低风险建议。
- 必须项 M1 已复现：confirm 终态 replay 只复核 1/3 次 Tool Policy，reject/其后 confirm 为 0/4 次；测试先红后将策略复核统一提前，8/8 转绿。
- S1 采纳为事务提交统一 flush；S2 明确当前删除约束并留待真实用户删除需求决策；S3 补充 409/FAILED HTTP 契约；S4 明确 APPROVED 过程态。具体处置已回填 Attempt 1 报告。
- Attempt 2 结论 `PASS`，确认 M1 与四项处置闭环，无新增必须修改项。非阻断 F1 将事件链测试改为集合验证，避免把相同 timestamp 下随机 UUID 当作顺序；F2 保持 flush 期冲突整事务回滚并明确文档；F3 以红绿用例保证结果 Task 后续删除时仍稳定 replay `EXECUTED`。

## Node Close Gate

- Node：V2-06 Approval + Idempotency + Audit。
- Scope 完成：五态 Approval、显式 Idempotency-Key、悲观行锁与数据库唯一约束、每次决策的 RBAC/Risk 复核、追加式 Audit Event、Web 稳定 key/retry/FAILED 展示均已完成。
- 明确未实现：V2-07 checkpoint/interrupt/resume、多级审批、审批委派/过期、通用审计查询 UI、用户硬删除策略。
- Tests：Core clean verify 102 tests（7 个外部 Agent 条件项另以真实 uvicorn 7/7 通过）；Agent pytest 81 passed；Web 37 tests 与 production build；PostgreSQL 17.11/Flyway V1–V7/JPA 7 项真实集成通过。
- DeepSeek Review：Attempt 2 `PASS`；Attempt 1 唯一必须项已按 TDD 修复，非阻断项已逐条处置。
- GitHub Maintenance：README not required；Architecture/API/Feature/ADR updated；Demo/Screenshot not required；Evaluation/Evidence recorded。
- Close Gate：YES。V2-07 未启动，等待用户新的明确授权。
- 结果回填前的 staged 门禁规划：L3、CoreApi/Docs/Governance/Web、Milestone，fingerprint `b92575f2e130a7b1abecadea0f2a7bbe80947ce286c2cb4d695c49fa07e76211`；`git diff --cached --check`、15 项 gate-planner contract 与全部 PowerShell parser 均通过。纯证据回填会自然改变 fingerprint，不跨快照复用该值。
- 最终敏感扫描：Gitleaks v8.30.1 扫描约 129.35 KB staged diff，退出 0、`no leaks found`。端口 18008 listener 为 0，Testcontainers label 容器为 0；用户原有未暂存文档、`.worktrees/` 与 DOCX 均未进入暂存、扫描或 Pi 输入。
