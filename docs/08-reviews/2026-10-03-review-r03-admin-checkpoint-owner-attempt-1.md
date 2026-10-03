# Pi 代码审查报告：r03-admin-checkpoint-owner / Attempt 1

- 日期：2026-10-03
- 审查阶段：r03-admin-checkpoint-owner
- 审查对象：INDEX@5542224（基线：5542224a3e37e9b9b9493ec41c7afc8398fde135）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# R03 管理员代审批 checkpoint owner 分离 — Milestone 深度只读审查报告

## 一、概述与总体结论

- **审查阶段**：r03-admin-checkpoint-owner（Milestone）
- **审查轮次**：1 / 3
- **审查目标**：Commit INDEX@5542224 的 15 个改动文件（ADR-0039、特性/API/变更文档、Core API Action workflow 及应用层与测试）
- **总体结论**：**通过（PASS）**
- **阻断性问题**：无
- **核心判断**：本次改动正确定位并修复了“管理员代审批时使用当前 actor 作为 Resume `userId`，导致 Python checkpoint Namespace 不匹配、Java 已持久化决定后恢复失败”的真实缺陷。修复方向与 ADR-0039 描述一致：checkpoint owner 取自已持久化事实 `AgentTaskAction.requestedByUserId`，授权、审计、批准后业务执行仍使用当前认证 actor。改动范围小、内聚，未发现真实 Bug、越权、契约破坏、并发/幂等回归或架构边界破坏。

**验证要点（基于 diff 证据）**
- 生产路径 `confirmInternal` / `reject` 的 Resume 参数由 `actor.userId()` 改为 `requireCheckpointOwner(approved/rejected)`，owner 来自持久化 view，客户端无法覆盖。
- 仅在 `actionWorkflowVersion() != null`（即存在 checkpoint）时要求 owner，旧无 workflow Action 与 MCP Action 不受影响。
- 内部 wire 字段 `userId` 未改名（`InternalResumeRequest` 未出现在 diff 中），真实 Java→Python 契约测试改用 `actorAdmin=true` 仍可 Resume，证明语义兼容。
- 新增管理员批准/拒绝、owner 缺失失败关闭三组工作流测试，及“公共响应不含 owner”断言。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（近似） | 核心问题 |
|----|----------|------|--------------|----------|
| S-1 | 中 | `AgentActionWorkflowService.java` | 新增 `requireCheckpointOwner` 处（约 70–75）；resume 调用约 51、88 | owner 缺失在决定已提交后才抛 `ServiceUnavailableException`(503)，语义错误且理论不可自愈 |
| S-2 | 低 | `AgentActionServiceTest.java` / `AgentActionWorkflowServiceTest.java` | 新增测试段 | 仅验证 `approve` 路径携带 owner；`reject`、`approveAutomatically` 的真实服务层 owner 传递无独立断言 |
| S-3 | 低 | `AgentActionApiTest.java` | confirm 断言处（约 63） | “公共响应不暴露 owner”仅在 confirm 端点断言，chat `pendingAction` / reject / auto-confirm 未覆盖 |
| S-4 | 低 | `ADR-0039-checkpoint-owner-and-decision-actor.md` | 决策节（约 12–13 行） | 文案称“workflow v2 的 Resume 必须有持久化 owner”，代码实际对任意非空 workflow version（含 v1）均要求 owner，文档与实现略有偏差 |
| N-1 | 无需修改 | `AgentServiceClient.java` / `HttpAgentServiceClient.java` | 方法签名 | 形参改名 `checkpointUserId`、JSON 字段保持 `userId` 属正确取舍，无 wire 变更 |
| N-2 | 无需修改 | `AgentActionView.java` | record 组件与新增构造器 | 末尾追加组件并保留短参构造器重载，向后兼容，未破坏既有调用 |

---

## 三、逐个 Issue 展开

### S-1（中）决定持久化后 owner 缺失返回 503，语义与可恢复性存疑

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionWorkflowService.java`，`requireCheckpointOwner`（约 70–75 行），调用点约 51、88 行。

**Evidence**
```java
private UUID requireCheckpointOwner(AgentActionView action) {
    if (action.requestedByUserId() == null) {
        throw new ServiceUnavailableException("Agent action is missing its checkpoint owner.");
    }
    return action.requestedByUserId();
}
```
调用顺序（confirm 为例）：
```java
AgentActionView approved = ... actions.approve(...);   // 已提交 APPROVED + 审计
if (approved.actionWorkflowVersion() != null) {
    AgentResumeResult resumed = agentService.resume(projectId, requireCheckpointOwner(approved), ...);
}
```

**Description**
- `requestedByUserId` 为 V1 即存在、由 `pending(...)` 必填的持久化事实（DB 中应为 NOT NULL），因此该分支在正常数据下不可达；这一点使问题严重度有限。
- 但若该不变量被破坏（历史脏数据、迁移异常、未来映射遗漏），决定已先提交为 `APPROVED`/`REJECTED`，随后每次同 key 重试都会再次走到该分支并返回 503，Action 既无法执行、也无法重新决定，形成永久卡死；同时用 `ServiceUnavailableException`(503) 表达“数据不变量缺失”也误导调用方按“服务暂不可用可重试”处理。

**Suggested Fix**
- 优先在持久化前校验 owner 存在，或在决定提交前读取并校验，避免“已提交决定 + 恢复未触发”的中间态；或
- 若坚持防御式失败关闭，改用不可重试内部错误（如 500/409 语义），并保留审计/日志；同时补充一条断言 `requestedByUserId` 非空的仓库层/约束测试以固化不变量。

---

### S-2（低）owner 传递在 reject / auto-confirm 路径缺少独立验证

**File & Line**：`AgentActionServiceTest.java`（新增 `adminDecisionKeepsTheRequesterAsCheckpointOwnerAndAuditsTheAdmin`）、`AgentActionWorkflowServiceTest.java`（新增管理员用例）。

**Evidence**
```java
// 仅验证 approve：
AgentActionView approved = service.approve(projectId, action.getId(), admin, "admin-decision-key", "admin-decision-request");
assertThat(approved.requestedByUserId()).isEqualTo(requesterId);
```
工作流测试则以 mock 直接返回带 owner 的 view：
```java
when(actions.approve(projectId, actionId, admin, "admin-key", "admin-request")).thenReturn(approved); // owner 由 stub 提供
```

**Description**
- `AgentActionWorkflowServiceTest` 的 owner 由 stub 注入，无法独立证明真实 `AgentActionService.reject` / `approveAutomatically` 也经 `AgentActionView.from` 携带 `requestedByUserId`。若其中任一方法使用了新 16 参构造器（owner 默认 null），真实管理员拒绝/自动确认将命中 S-1 的失败分支而测试仍绿。

**Suggested Fix**
- 在 `AgentActionServiceTest` 为 `approveAutomatically` 与 `reject` 各补一条 `assertThat(view.requestedByUserId()).isEqualTo(requesterId)`；或改为集成式使用真实 service 而非 stub view，以覆盖端到端 owner 传播。

---

### S-3（低）公共响应不暴露 owner 的断言覆盖不全

**File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentActionApiTest.java`（约 63 行）。

**Evidence**
```java
.andExpect(jsonPath("$.status").value("EXECUTED"))
.andExpect(jsonPath("$.requestedByUserId").doesNotExist())
```
仅有 confirm 端点断言。

**Description**
- `AgentActionView` 新增 record 组件后，若任意响应映射（chat `pendingAction`、reject、auto-confirm）采用反射式字段拷贝而非显式映射，`requestedByUserId` 可能泄漏到响应。虽然其为普通用户 UUID、非高危敏感信息，但与 ADR/变更日志“公共 API 不增加 owner 字段”的显式承诺应被一致验证。

**Suggested Fix**
- 在 reject、auto-confirm 及 chat `pendingAction` 响应断言中同样加入 `jsonPath("$.requestedByUserId").doesNotExist()`（或对 pending action 的字段白名单做断言）。

---

### S-4（低）ADR 与实现的 owner 强制范围措辞不一致

**File & Line**：`docs/02-architecture/decisions/ADR-0039-checkpoint-owner-and-decision-actor.md`，决策节。

**Evidence**
```text
- workflow v2 的 Resume 必须有持久化 owner；缺失时失败关闭。没有 checkpoint 的旧 Action 和 MCP Action 不调用 Resume。
```
实现：
```java
if (approved.actionWorkflowVersion() != null) { requireCheckpointOwner(approved); ... }
```

**Description**
- 代码对任意非空 `actionWorkflowVersion`（v1 亦有 checkpoint）都要求 owner，而 ADR 仅点名 v2。文档精度问题，不影响行为（v1 亦持久化 owner），但会误导后续维护者认为 v1 可缺 owner。

**Suggested Fix**
- 将 ADR 该条改为“任何带 checkpoint（workflow version 非空，含 v1/v2）的 Resume 必须使用持久化 owner；无 checkpoint 的旧 Action 与 MCP Action 不调用 Resume”。

---

### N-1（无需修改）内部参数改名但 wire 契约保持不变

`AgentServiceClient.resume` 与 `HttpAgentServiceClient.resume` 仅将形参 `userId` 改名为 `checkpointUserId`，`InternalResumeRequest` 记录未变更（未出现在 diff），JSON 字段仍为 `userId`；`AgentServiceHttpContractIntegrationTest` 以 `actorAdmin=true` 对真实 Python 成功 Resume，构成 wire 兼容的直接证据。合理。

### N-2（无需修改）record 组件追加保持兼容

`AgentActionView` 在末尾追加 `requestedByUserId`，并显式保留 14/15/16 参构造器重载（缺失 owner 时置 null），既有位置调用不受破坏；`from(...)` 显式携带 `action.getRequestedByUserId()`。方向正确。

---

## 四、边界与一致性核对（无问题项）

- **权限/越权**：owner 仅用于 Python Namespace 定位，审批授权、审计与执行仍走当前 actor；普通用户只能决定自身 Action（`findForDecision`），ADMIN 代审批不替换 owner，未引入横向越权。
- **契约与状态码**：公共 API 请求/响应未新增字段；内部 Resume/Abort 请求体字段名不变；`actorAdmin` 语义明确为当前决策 actor 角色。
- **并发/幂等**：仍为“先提交决定 → 事务外 Resume → 锁行复核执行”，同 key 重试返回既有事实的语义未改变；owner 取自持久化行，重试稳定。
- **测试有效性**：管理员批准、管理员拒绝、owner 缺失失败关闭、原请求人自审批回归均有自动化覆盖；未发现关键分支无测试的硬缺口（S-2/S-3 为增强项）。
- **V1 边界**：未引入任何 V2/V3 组件（无 Neo4j/GraphRAG、Langfuse、LiteLLM、MCP 变更），未偏离节点目标。

---

## 五、主开发（Codex）评估回填区

| 发现 ID | Codex 结论（接受/部分接受/驳回） | 处理说明 | 关联提交 |
|---------|----------------------------------|----------|----------|
| S-1 | 驳回为当前缺陷 | V4 数据库 `NOT NULL`、JPA/领域非空约束及所有生产 `AgentActionView.from` 映射共同保证 owner；guard 只防御测试桩或未来错误映射，不为不可复现路径重排事务 | 本次提交 |
| S-2 | 建议合理但非阻断 | approve/auto-confirm/reject 共用 `AgentActionView.from`；workflow 已覆盖自动批准与管理员拒绝，不因 PASS 后纯测试建议扩张 | 本次提交 |
| S-3 | 已确认无泄露 | 公共 Action、Chat/SSE 使用显式 `AgentActionResponse.from` 白名单，MCP 手工构造结果；现有公共测试锁定该字段不存在 | 本次提交 |
| S-4 | 接受 | ADR 改为任何 workflow version 非空（含 v1/v2）的 Resume 都要求持久化 owner | 本次提交 |
| N-1 | 同意 | 无需处理 | 本次提交 |
| N-2 | 同意 | 无需处理 | 本次提交 |

---

## 六、结论

- 本轮**无“必须修改”项**，`REVIEW_RESULT: PASS`。
- 建议在后续（可合并本节点提交）按 S-1～S-4 增强防御语义、补齐 reject/auto-confirm 的 owner 传播与公共响应不暴露断言，并校正 ADR 措辞。
- 下一轮审查将以验证修复与识别修复引入的新问题为主，不重复本轮已列发现。
