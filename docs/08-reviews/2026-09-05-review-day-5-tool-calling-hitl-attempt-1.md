# Pi 代码审查报告：day-5-tool-calling-hitl / Attempt 1

- 日期：2026-09-05
- 审查阶段：day-5-tool-calling-hitl
- 审查对象：WORKTREE@d4973a5（基线：d4973a5da90f84cea849081242f56019c4b6d95b）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge Day 5 Tool Calling & 人工确认 — 独立只读审查报告

- 审查阶段：day-5-tool-calling-hitl
- 审查轮次：1 / 3
- 审查目标：WORKTREE@d4973a5
- 审查依据：本次提供的完整 Git Diff（未截断）、文件清单、Codex 测试记录（作为证据，非本报告测试结论）
- 审查范围：Python Tool planner / graph / schemas / api，Java action domain / application / api / repository / Flyway V4，E2E 脚本，相关文档与测试

## 概述与总体结论

**结论：发现 1 项可复现的契约/健壮性缺陷和若干测试覆盖与文档一致性缺口，建议修复后交付（NEEDS_FIX）。**

本阶段总体架构判断正确且与 ADR-0011 一致：Python 只产出白名单 `ToolProposal`，Java 在 JWT + Project 授权后归一化、持久化 `agent_task_action`，confirm/reject 复用 `TaskService` 并以内事务锁（PESSIMISTIC_WRITE + `@Version`）保证重复确认幂等。权限面（`requireAccess` 先于 action 查询、普通用户仅可决策自己发起的 action、ADMIN 仍需项目访问）与数据隔离（`projectId + actionId` 查询、`TaskService` 二次校验 task 归属与 version）未发现越权路径。并发重复确认在 Postgres 行锁下可正确收敛为单次写入。

需要处理的核心问题是：**Java 对无效/超长 proposal 统一抛 `ServiceUnavailableException(503)`，与功能文档声明的“不可信字段返回普通 Chat 且不保存 action”相矛盾**，且该路径可由用户输入（标题超过 200 字符）触发——Python 侧 `ToolProposal` 未做标题/描述长度约束。其余为测试缺口（version 冲突路径未被任何测试覆盖，而变更记录宣称已覆盖）与多份文档中的 Pi 职责互相矛盾。

详细发现如下（按严重度排序）。

## 详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| AF-D5-01 | Medium | `services/core-api/.../agent/application/AgentActionService.java`（`normalize()` / `invalidProposal()`）；`docs/03-features/tool-calling-and-confirmation.md` | `AgentActionService.java` L154–185，`tool-calling-and-confirmation.md` L36 | 无效/超长 proposal 抛 `ServiceUnavailableException`→公开 Chat 返回 503，违反文档“降级为普通 Chat”的契约；由 title>200 字符的用户输入可复现 |
| AF-D5-02 | Medium | `services/agent-service/src/agentforge_agent/tool_planner.py` | L8–11、L26 | `_UPDATE_PATTERN` 的 `[0-9a-f-]{36}` 可匹配“36 位但结构非法”的 UUID，`UUID()` 抛 ValueError → 内部 Chat 422（经 Java 透传为公开 5xx），而非“不生成 proposal” |
| AF-D5-03 | Medium | `services/core-api/src/test/java/.../AgentActionServiceTest.java`；`docs/07-changes/2026-09-05-day-5-tool-calling-hitl.md` | 测试类全文；变更记录“验证计划”3 与“当前真实验证证据” | version 冲突双路径（createPending 时 stale expectedVersion→409；confirm 时 Task 已变更→409 且 action 保持 PENDING）无任何自动化测试，但文档宣称 Java verify 与 E2E 已覆盖“版本冲突” |
| AF-D5-04 | Low | `docs/05-development/testing-strategy.md`；`docs/03-features/review-orchestration.md`；`docs/06-operations/review-orchestration.md`；`docs/07-changes/README.md` | 见下 | 同一文件内 Pi 职责互相矛盾（“停用 Pi”与“重新授权 Pi 只读审核”并存；`Codex 不执行测试` 残余文本与当前分工冲突）；新变更记录 status 字符串不在既定状态集合 |
| AF-D5-05 | Low | `AgentActionServiceTest.java` / `AgentActionApiTest.java` | — | 缺 ADMIN 决策他人 action（ADR-0011 决策 3）、projectId+actionId 路径不匹配→404（文档错误契约）、无效 proposal 降级为普通 Chat 等分支测试 |

## 逐项展开

### AF-D5-01：无效/超长 proposal 导致 Chat 返回 503，与文档契约冲突（Medium）

- **Severity**：Medium
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` 的 `normalize()`（约 L154–183）与 `invalidProposal()`（约 L185–187）；`docs/03-features/tool-calling-and-confirmation.md` “安全与并发”第 2 条（约 L36）
- **Evidence**：

```java
private NormalizedProposal normalize(ToolProposal proposal) {
    if (proposal == null || !StringUtils.hasText(proposal.actionType())) {
        throw invalidProposal();
    }
    try {
        AgentActionType type = AgentActionType.valueOf(proposal.actionType());
        String title = normalizeText(proposal.title(), 200);
        String description = normalizeText(proposal.description(), 10000);
        ...
    } catch (IllegalArgumentException exception) {
        throw invalidProposal();
    }
}

private ServiceUnavailableException invalidProposal() {
    return new ServiceUnavailableException("Agent Service returned an invalid tool proposal.");
}
```

`AgentChatService.chat()` 调用 `createPending(...)` 时没有 try/catch，异常直接冒泡给 Controller。

- **Description**：文档明文约定“Python 提供的枚举、长度、组合和 UUID 由 Java 再校验，不可信字段返回普通 Chat 且不保存 action，或在公共 action API 返回 400/409。”但实现将所有非法 proposal 归一为 `ServiceUnavailableException`（HTTP 503），既未降级为普通 Chat，也未返回 400。更关键的是该路径可被用户输入触发：`ToolProposal`（Python `schemas.py`）对 `title`/`description` 没有长度约束（仅 Java 侧校验），而 `ChatRequest.message` 上限 8000，用户发送“create task: <200+ 字符标题>…”时 Python 会成功产出 proposal，Java `normalizeText(title, 200)` 抛 503，使一个语法合法但字段超长的用户请求错误地表现为“服务不可用”，混淆运维语义。
- **Suggested Fix**：二选一（推荐组合）：
  1. 在 Python `ToolProposal` 上补长度约束：`title: str | None = Field(default=None, max_length=200)`、`description: str | None = Field(default=None, max_length=10000)`，让超长输入在 Python 端退化或失败为 4xx；同时保留 Java 侧校验作为纵深防御。
  2. 修改 Java：将非法 proposal 的语义从 `ServiceUnavailableException(503)` 改为“不保存 action、返回普通 Chat”（例如 `createPending` 返回 `Optional.empty()`，`AgentChatService.chat` 在空结果时原样返回 chat），或至少用 `BadRequestException`/`InvalidProposalException`（400）而非 503 来表达“输入非法”。

### AF-D5-02：更新意图的 UUID 正则可匹配非法 UUID，抛 422 而非“无提案”（Medium）

- **Severity**：Medium
- **File & Line**：`services/agent-service/src/agentforge_agent/tool_planner.py` L8–11（`_UPDATE_PATTERN`）、L26（`UUID(...)`）
- **Evidence**：

```python
_UPDATE_PATTERN = re.compile(
    r"^update\s+task\s+(?P<task_id>[0-9a-f-]{36})\s+version\s+(?P<version>\d+)\s*[:：]\s*(?P<patch>.+)$",
    re.IGNORECASE,
)
...
return ToolProposal(
    action_type="UPDATE_TASK",
    task_id=UUID(update_match.group("task_id")),
    ...
)
```

- **Description**：正则只检查“36 个十六进制/连字符字符”，不校验 UUID 的分组结构。例如 `123456789012345678901234567890123456`（36 位无连字符）或连字符错位的 36 位串都能匹配，随后 `uuid.UUID(...)` 抛 `ValueError`。该异常被 `api.py` 的 `except ValueError` 捕获为内部 Chat 的 422；Java 侧对非 2xx 的内部响应通常映射为公开 5xx，导致“明确格式意图、但 ID 结构错误”的消息偶发 5xx/422，与文档“无法唯一确定目标时返回普通回答而不生成 proposal”相矛盾。
- **Suggested Fix**：用 `UUID(update_match.group("task_id"))` 前先以 `fullmatch` 严格 UUID 正则（含分组位置）或用 `try/except ValueError: return None` 包裹，使非法 ID 退化为“不生成 proposal”。更稳妥：直接把 task_id 提取出来交给 `uuid.UUID(...)` 前用 `re.fullmatch(r"[0-9a-f]{8}-...")` 校验标准 8-4-4-4-12 结构。

### AF-D5-03：version 冲突路径未被测试覆盖，但文档宣称已覆盖（Medium）

- **Severity**：Medium
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/application/AgentActionServiceTest.java`（全文）；`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentActionApiTest.java`；`docs/07-changes/2026-09-05-day-5-tool-calling-hitl.md`“验证计划”第 3 条与“当前真实验证证据”段落
- **Evidence**：
  - 变更记录“验证计划 3”：`Java clean verify：…版本冲突…`；“当前真实验证证据”：`双服务：…update version 1、reject 后总数 1…`，并称 E2E 验证了“版本冲突”。
  - 实际测试：`AgentActionServiceTest.updateProposalRequiresCurrentVersionAndMergesOnlyProposedFields` 仅 mock 了成功 update：
    ```java
    when(taskService.update(projectId, taskId, actor, "Existing title", "JWT", TaskStatus.DONE, TaskPriority.HIGH, 2))
            .thenReturn(updated);
    ```
    没有测试 `createPending` 在 `current.version() != expectedTaskVersion` 时抛 `ConflictException`，也没有测试 confirm 时 `taskService.update` 因乐观锁失败（action 保持 `PENDING`）的传播路径；`scripts/validation/day5-e2e.ps1` 全程只用创建后 Task 的当前 version（0）发起 update，并未构造 version 冲突用例。
- **Description**：ADR-0011 决策 5 点名“冲突返回 409，action 保持 PENDING”是本阶段的数据一致性关键，但该双路径无任何可观察失败/成功断言。变更记录把“版本冲突”列为已验证项，与测试实际覆盖不一致，属于证据表述与代码不符；在文档先行、证据链为交付门禁的项目中应视为待修复的测试缺口。
- **Suggested Fix**：在 `AgentActionServiceTest` 补两个用例：
  1. `createPending` 传入 `expectedVersion` 与当前 Task version 不一致 → 断言 `ConflictException`，且 `actions.save` 未被调用。
  2. 构造 confirm 场景：`taskService.update(...)` mock 抛乐观锁冲突/`ConflictException` → 断言 confirm 失败，并验证 action 状态仍为 `PENDING`（可通过再次 `findByProjectIdAndIdForUpdate` 返回的实体断言）。如保留 E2E 中“版本冲突”表述，则在 `day5-e2e.ps1` 增加一次用过期 version 的 confirm 并断言公开 API 409；否则从文档中删除该过度声明。

### AF-D5-04：Pi 职责文档同一文件内互相矛盾（Low）

- **Severity**：Low
- **File & Line**：
  - `docs/05-development/testing-strategy.md`：新增“## Day 5 质量门槛与审核分工”末尾（“重新授权 Pi 仅做只读代码审核”）与紧邻其上的“## 2026-09-05 生效：停用 Pi”（“用户已撤销 Pi 审查与测试授权”）并存且互相矛盾；
  - `docs/03-features/review-orchestration.md`：底部“Pi 的代码审查模式保持完全只读。独立验证模式启用…由 Pi 执行测试…Codex 不执行测试，只依据 Pi 报告修复…”与顶部新增“本页其余自动编排内容为历史设计，不再启用”及当前 `pi/AGENTS.md`（VALIDATION 停用、测试由 Codex 执行）冲突；
  - `docs/06-operations/review-orchestration.md`：仍保留“Pi 受限验证模式…Codex 不执行测试”段落；
  - `docs/07-changes/2026-09-05-day-5-tool-calling-hitl.md` 头部 status = `Implementation and Codex Validation Complete; Pi Review Pending`，不在 `docs/07-changes/README.md` 定义的 `Proposed/In Progress/Implemented` 状态集合内。
- **Description**：文档作为项目“长期记忆”，同一文件内出现相互冲突的操作指令（停用 Pi vs 授权 Pi 审核；Codex 不执行测试 vs Codex 是唯一测试执行者），后续 AI/协作者按任一分支执行都会违反另一分支；且变更记录自定义了未登记的状态字符串，破坏状态机可读性。无运行时风险，但违反文档制度且易误导。
- **Suggested Fix**：把“停用 Pi”段落改为历史标注（如 `> 历史：2026-09-05 曾停用 Pi，随后用户重新授权一次性只读审核`），删除/改写 `Codex 不执行测试` 与 VALIDATION 模式残留描述为历史追溯，并把 Day 5 变更记录 status 收敛为既定枚举（如 `In Progress` 或与 README 一致的自有价值并在 README 中登记）。

### AF-D5-05：缺少管理员跨用户决策与路径错配 404、无效 proposal 降级的分支测试（Low）

- **Severity**：Low
- **File & Line**：`AgentActionServiceTest.java`、`AgentActionApiTest.java`（新增测试文件）
- **Evidence**：现有测试仅覆盖“普通其它用户 reject→403”（`anotherUserCannotDecidePendingAction`）与正向 confirm/reject；无 `actor.admin()==true` 决策他人 action 的用例；无 `findByProjectIdAndId(projectId, actionId)` 返回 empty→404 的用例（文档 `core-api.md` 明确“路径不匹配返回 404”）；无“Python 返回非法 proposal 时 Chat 降级/不保存”的用例（与 AF-D5-01 联动）。
- **Description**：ADR-0011 决策 3 与公共 API 文档明确了“普通用户仅可决策自己发起”“ADMIN 仍需项目访问”“路径不匹配 404”等边界。当前测试只覆盖了跨用户 403 的普通用户分支，ADMIN 授权分支与 404 错误契约缺少回归，易在后续 RBAC 演进中回归。
- **Suggested Fix**：补：
  1. `confirm/reject` 以 `actor.admin()==true` 决策另一用户 action → 成功（EXECUTED/REJECTED）；
  2. `projectId` 正确但 `actionId` 不存在 → `ResourceNotFoundException`（Controller 层断言 404）；
  3. 与 AF-D5-01 修复捆绑，验证非法 proposal 不落库且 Chat 返回正常或 400。

## 主开发 (Codex) 评估回填区（待回填）

| Issue ID | 采纳（是/否/豁免） | 处理说明 | 处理提交 / 重跑测试 |
| --- | --- | --- | --- |
| AF-D5-01 | 是 | 用户输入可触发错误 503，违反普通 Chat 降级契约；Java 现返回 empty，Chat 保留普通回答且不保存 action。 | Java 定向 11/11；clean verify 75 tests；E2E PASS |
| AF-D5-02 | 是 | 非法 UUID 可触发异常响应；解析器现捕获 ValueError 并退化为无 proposal。 | Python 定向 1/1；全量 17/17 |
| AF-D5-03 | 是 | version 冲突属于数据一致性关键路径；已补 createPending stale 与 confirm 并发冲突保持 PENDING 两条测试。 | Java 定向 11/11；clean verify 75 tests |
| AF-D5-04 | 是 | 清理现行职责与历史自动化说明冲突，并恢复规定状态枚举。 | 本轮文档先行已处理 |
| AF-D5-05 | 部分采纳 | 已增加无效 proposal 降级测试；ADMIN/404 为低风险覆盖扩展，现有实现与权限证据充分，本阶段不阻断。 | 记录豁免依据；不扩张本阶段范围 |

---

**审查员说明**：本报告完全基于启动器提供的 diff、文件清单与 Codex 测试记录完成，未运行任何命令或修改任何文件。上述 Codex 测试记录（Python 16 passed、Java 71 tests/0 failures、E2E PASS 等）仅作为证据采信，不作为本报告的测试执行结果。AF-D5-01、AF-D5-03 建议按 TDD 先补失败测试再修复并重跑 Java/Python 相关测试后再提交复审；AF-D5-02/04/05 可在同一轮处理，无需阻断架构方向。
