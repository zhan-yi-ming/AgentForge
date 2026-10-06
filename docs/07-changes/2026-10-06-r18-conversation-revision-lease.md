# R18 同一会话并发提交隔离

- 日期：2026-10-06
- 状态：Implemented
- 风险：L3（会话状态机、并发一致性与 Action checkpoint 持久副作用顺序）
- 范围：Python Agent Service 的 ConversationMemory、同步/流式 Chat 收口与内部文档；不改变 HTTP schema、Java 权限或业务写入

## 问题核实

报告问题成立。当前 `ConversationLease` 只绑定 Namespace 与 session generation，`commit_exchange` 在锁内只校验 session 仍存在且 generation 相同。两个请求从同一 session 快照 load 后，即使分别基于相同旧历史生成，仍能依次成功提交；当前机器公共 seam 复现得到 `['u1', 'a1', 'u2', 'a2']`。最终顺序由完成时序决定，第二个回答没有观察到第一个 exchange。

单纯给最终 commit 增加 revision CAS 还不完整：同步和流式 Action 路径目前先持久化 WAITING checkpoint、后提交 Memory。若最终 CAS 失败，冲突请求会留下没有成功响应可关联的 checkpoint。因此并发判定必须位于 checkpoint 副作用之前，并在取消/异常时精确释放。

## 目标契约与公共 seam

采用 ADR-0042：lease 增加读取时 revision 与唯一 lease ID；模型生成不持锁，完成后在短临界区 claim exchange。只有 claim 成功的请求可创建 Action checkpoint并提交；commit 推进 revision，异常和取消只释放自己的 claim。被 claim session 不可被 LRU 淘汰，不同 Namespace 不互斥，进程重启后的旧 lease 必须失败关闭。

公共 seam 为 `ConversationMemory` 的 load/claim/commit/release 行为与 FastAPI 同步/流式 Chat。HTTP 字段不变；同步冲突沿用脱敏 422，已开始流沿用安全 `error` 且无 `complete`。冲突请求可能已生成或传输 delta，但不得提交 Memory 或创建 WAITING checkpoint。

## TDD 与验证计划

1. 在 ConversationMemory 公共 seam 增加同 Namespace 并发提交、不同 Namespace 并行 claim、取消释放、实例重启与 claim/LRU 测试，先取得真实红灯。
2. 在 FastAPI 公共入口增加 Action proposal 的 claim-before-checkpoint 回归，证明冲突请求不会调用 checkpoint 持久化；覆盖同步与流式失败收口。
3. 最小实现 generation + revision + lease ID 的短期 claim，并在同步/流式路径用 `finally` 精确释放；不扩大到持久化 Memory 或分布式锁。
4. 运行门禁规划、Agent Service 定向与全量测试；因 L3 状态机变化追加跨进程契约/相称回归，清理临时资源，执行 diff check、完整 staged Gitleaks 与 Pi Milestone Review。
5. R18 独立提交并推送核验后，另建 R01–R18 累计审计记录，对基线 `ff1a0a52317c69dba3d51e61e12e56dbf46f2bca` 至最终 HEAD 的完整差异执行严重问题复核、全量回归、Gitleaks 与一次性 Pi Milestone Review；只修复可复现严重问题。

## 验证回填

### TDD 与实现证据

- 实现前公共 seam 定向运行得到 `6 failed, 1 passed`：同一 revision 的两个线程都返回 `committed`；Memory 尚无 claim/release；同步与流式冲突测试均记录到 `interrupt_calls == 1`，证明 WAITING checkpoint 副作用发生在 Memory 冲突之前。另一个实例重启用例已由既有 generation 防线通过。
- 最小实现为 session 增加单调 revision 与 claim owner，为 lease 增加读取 revision 与唯一 lease ID。`claim_exchange` 在锁外完成确定性输入校验、锁内只校验并占有当前 revision；`commit_exchange` 原子写入、推进 revision 并清 claim；`release_exchange` 只释放完全匹配的 owner。LRU 仅淘汰未 claim session，全部繁忙时失败关闭。
- 同步与流式 Chat 都在 Action runtime `interrupt` 之前 claim，并在 `finally` 中精确 release；模型、Retriever、流式迭代与 checkpoint 数据库调用不位于 Memory 锁内。定向复跑为 `7 passed, 52 deselected, 2 warnings`。

### 当前机器验证

- 门禁规划：`plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Json` 退出 0；实现后为 `L3`、`AgentService/Docs`、Milestone Review，要求 Python、文档、diff 与 Gitleaks；fingerprint `dfa55e1ac43ec419d59694aca3b063234c6fd133cb437fc971964142dbac7e9d`。状态机与 checkpoint 顺序按项目规则保持 L3，并追加真实跨进程契约。
- Agent Service 全量：使用工作树源码与独立安全 basetemp，`233 passed, 5 warnings`，退出 0。warning 为 Starlette/AnyIO 既有 deprecated alias、HTTP 422 常量弃用和 LiteLLM/Pydantic `ReadOnly` 提示；无 failed/skipped。
- Java↔Python 真实门禁：仅在确认目标不存在后创建指向主工作树 venv 的临时 Junction，以工作树 `PYTHONPATH` 运行 `v3-release-regression.ps1 -Only java-python-contracts -Json`；`13/13`、`PASS_PARTIAL`、退出 0。finally 核验 LinkType/Target 后只删除 Junction，源 venv 保持存在。
- 清理：工作树内 pytest basetemp 均经绝对路径校验后删除；临时 `.venv` Junction 已删除；`agentforge-v3-java-python-contracts` 容器、网络与卷检查为空。
- 未运行 Core API 全量、Web、数据库角色门禁或生产 Compose：R18 不改 Java/Web、数据库 schema/role、部署配置或 HTTP 字段；Agent 全量与 13 项真实 Java↔Python Chat/Resume/Graph 契约覆盖当前影响域。R01–R18 累计审计将在 R18 独立提交并远端核验后运行完整 Release Regression，不能用本节结果替代。

### Pi 与提交前收口

- 完整 R18 staged diff 的 `git diff --cached --check` 退出 0；`zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1` 扫描约 36.20 KB，退出 0，`no leaks found`。
- Pi Attempt 1 使用精确模型 `deepseek/deepseek-flash`、`INDEX@3968a79` 与 Milestone 模式，返回 `PASS`；报告为 `docs/08-reviews/2026-10-06-review-main-review-r18-attempt-1.md`，无必须修改项。
- S1 指出直接 `commit_exchange` 在未显式 claim 时会在同一锁内隐式认领。该兼容 seam 不创建 checkpoint，仍执行 generation/revision/owner 原子校验；同步与流式 API 已强制先显式 claim 再 `interrupt`，因此不构成数据一致性或契约阻断，本批不收紧既有测试/调用方。
- S2 建议再分别覆盖真实第二 claim 拒绝与非 owner release；当前并发双 commit、取消 owner、不同 Namespace、claim/LRU 及同步/流式 checkpoint-before-claim 负向用例已验证目标风险，建议记为后续测试补强，不为 PASS 后的非阻断覆盖建议扩大本提交。
- S3 是同一次 exchange 两次确定性规范化的微小冗余；不持锁执行、结果一致且消息预算有界，不影响正确性或并发，记录但不修改。N1/N2 是 Pi 明确认定无需修改的 LRU 公平性/422 复用与流式 delta 后冲突限制，现有文档已披露。
- Pi 仅给出建议项，按治理规则不触发 Attempt 2。上述报告与处置回填后重新执行完整 staged diff check 与 Gitleaks；随后不再修改 R18 内容。

## 风险与回滚

主要风险是 claim 泄漏导致该会话后续提交持续失败、错误释放其他请求、或 LRU 淘汰已 claim session 使 checkpoint 与 Memory 不一致。所有 claim 必须有异常安全 release，并以 lease ID 限定所有权。回滚本提交恢复旧的完成顺序行为，不涉及 schema 或业务数据迁移；若已创建 WAITING checkpoint，仍按既有精确 Abort/恢复契约处理，禁止静默删除。
