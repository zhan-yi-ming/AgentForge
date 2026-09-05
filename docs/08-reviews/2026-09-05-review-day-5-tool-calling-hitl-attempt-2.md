# Pi 代码审查报告：day-5-tool-calling-hitl / Attempt 2

- 日期：2026-09-05
- 审查阶段：day-5-tool-calling-hitl
- 审查对象：WORKTREE@d4973a5（基线：d4973a5da90f84cea849081242f56019c4b6d95b）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Codex disposition: ACCEPTED_WITH_DEFERRED_LOW（无重大问题；不发起 Attempt 3）
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge Day 5 Tool Calling & 人工确认 — 独立只读审查报告（Attempt 2）

- 审查阶段：day-5-tool-calling-hitl
- 审查轮次：2 / 3
- 审查目标：WORKTREE@d4973a5
- 审查依据：本次提供的完整 Git Diff（未截断）、文件清单、Codex 测试记录与 Attempt 1 报告
- 审查范围：Python planner/graph/schemas/api，Java action domain/application/api/repository/Flyway V4，E2E 脚本，相关文档与测试

## 概述与总体结论

**结论：Attempt 1 的 5 项发现基本修复到位，但仍残留 1 处文档与真实测试证据不一致的陈述，需要 Codex 做文档先行的小幅修正（NEEDS_FIX）。**

修复验证结果：

- AF-D5-01 已修复：`AgentActionService.normalize()` 对非法 proposal 抛 `IllegalArgumentException`，`createPending()` 捕获后返回 `Optional.empty()`；`AgentChatService.chat()` 以 `Optional.map(...).orElseGet(result::withoutToolProposal)` 保留普通 Chat 回答且不保存 action，不再把公开 Chat 误报为 503。Python `ToolProposal` 虽未加长度约束，但 Java 纵深校验已保证降级，不构成公开 5xx。
- AF-D5-02 已修复：`tool_planner.py` 用 `try/except ValueError` 包住 `UUID(...)`，非法 36 位 ID 退化为“不生成 proposal”；`test_chat_does_not_fail_for_malformed_task_uuid` 覆盖该路径。
- AF-D5-03 基本修复：`AgentActionServiceTest` 新增 `staleUpdateProposalIsRejectedBeforeSavingAction()` 与 `concurrentVersionConflictLeavesActionPending()`，分别覆盖 createPending 时 stale version→409 与 confirm 时乐观锁冲突→action 保持 PENDING；变更记录也已修正为“不再表述 E2E 已覆盖冲突”。但功能文档仍有一处未同步（见 AF-D5-06）。
- AF-D5-04 已修复：`testing-strategy.md`、`review-orchestration.md`、功能/运维文档与变更记录中的 Pi 职责表述已统一为“只读审核、Codex 执行测试”，Day 5 变更记录状态已回到既定 `In Progress`。
- AF-D5-05 部分采纳并按计划处理：无效 proposal 降级测试已随 AF-D5-01 补齐；ADMIN 跨用户决策与路径错配 404 测试未新增，属于已被明确记录的技术豁免，本轮不重复视为阻断。

## 详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| AF-D5-06 | Low | `docs/03-features/tool-calling-and-confirmation.md` | 测试边界一节，约 L41 | 文档仍称“双服务 E2E 证明……版本冲突”，但 `day5-e2e.ps1` 从未构造过期 version 或断言 409；与代码、真实测试证据及该报告中已修正的“不再称 E2E 覆盖冲突”声明相互矛盾 |

## 逐项展开

### AF-D5-06：功能文档仍声称 E2E 覆盖版本冲突，与实际测试证据不一致（Low）

- **Severity**：Low
- **File & Line**：`docs/03-features/tool-calling-and-confirmation.md`，“测试边界”一节末尾（约 L41）
- **Evidence**：
  ```text
  - 双服务 E2E 证明未确认不写、确认写入、拒绝不写、项目隔离和版本冲突。
  ```
  同一交付中 `docs/07-changes/2026-09-05-day-5-tool-calling-hitl.md` 已明确修正为：
  ```text
  - AF-D5-03 采纳：...校验冲突返回且 action 保持 PENDING；E2E 证据不再表述为已覆盖冲突，除非实际增加该场景。
  ```
- **Description**：`scripts/validation/day5-e2e.ps1` 全程只使用刚创建 Task 的当前 version（0）发起 update，随后仅对另一个创建 action 做重复 confirm；没有任何步骤发送过期 version，也没有针对版本冲突断言公开 API 409。版本冲突的双路径实际由 `AgentActionServiceTest` 两条 Java 单元测试覆盖，不是 E2E。该功能文档仍把“版本冲突”列入 E2E 证明范围，属于文档先行制度下的证据误述，会让后续协作者误以为 E2E 已提供冲突回归，或误判该场景在双服务闭环中的行为。
- **Suggested Fix**：二选一：
  1. 推荐：将末句改为“双服务 E2E 证明未确认不写、确认写入、拒绝不写、项目隔离和重复确认；版本冲突由 Core API 服务测试（`AgentActionServiceTest`）覆盖。”与本轮已验证事实一致。
  2. 如确需保留“E2E 覆盖版本冲突”表述，则应在 `day5-e2e.ps1` 增加一次过期 version 的 confirm/提案并断言公开 API 409，再保留原文。按当前测试边界，推荐选择 1。

## 主开发 (Codex) 评估回填区（待回填）

| Issue ID | 采纳（是/否/豁免） | 处理说明 | 处理提交 / 重跑测试 |
| --- | --- | --- | --- |
| AF-D5-06 | 延后 | 仅为 Low 级测试归属文字不一致，不影响实现、可运行性、架构或数据一致性。按用户要求不为小问题反复修改/审核，登记到 Day 6 审核统一处理。 | Day 6 review backlog |

---

**审查员说明**：本报告完全基于启动器提供的 diff、文件清单与 Codex 测试记录完成，未运行任何命令或修改任何文件。上述 Codex 测试记录（Python 17 passed、Java clean verify 75 tests、E2E PASS 等）仅作为证据采信，不作为本报告的测试执行结果。除 AF-D5-06 外，Attempt 1 的可复现代码缺陷与文档矛盾已实质修复，无需阻断架构方向。
