# Pi 代码审查报告：r01-action-checkpoint-identity / Attempt 1

- 日期：2026-10-03
- 审查阶段：r01-action-checkpoint-identity
- 审查对象：INDEX@ff1a0a5（基线：ff1a0a52317c69dba3d51e61e12e56dbf46f2bca）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

## 一、概述与总体结论

- **审查阶段**：r01-action-checkpoint-identity（Milestone Review，轮次 1/3）
- **审查范围**：Commit INDEX@ff1a0a5 的 28 个改动文件（Python Action Runtime / Core API 内部契约 / Flyway V14 / 文档与测试）
- **总体结论**：**通过（无需修复后交付的阻断性问题）**。本次变更在方向、边界与实现上符合 ADR-0037 与既有 ADR-0021/0022 的约束：workflow ID 作为不可复用的轮次标识，由 Python 生成、经内部 proposal 传递、由 Java 唯一持久化，并在 Resume 时做 Namespace + workflow + action + decision 的精确匹配；v1/legacy/MCP 兼容分支保留，公共 API 未引入该内部字段。
- 未发现明确证据支持的权限绕过、空指针、并发/幂等破坏、契约冲突或数据一致性问题。
- 存在若干**建议级**的测试覆盖与资源使用项，不阻塞合入，但建议在后续小改中闭环。

**风险等级判断**：变更触及审批状态机、跨服务内部契约与数据库约束，风险等级 L3 合理；现有回归（单元 + 真实 PostgreSQL 并发 + 跨进程 smoke）与本次审查结论一致。

## 二、详细发现清单

### 必须修改
无。

### 建议修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
|----|----------|------|------|----------|
| S1 | 中 | `services/core-api/.../infrastructure/HttpAgentServiceClient.java`、`AgentServiceHttpContractIntegrationTest.java` | 流式解析 / 契约测试 | Java 端 NDJSON `complete.toolProposal.actionWorkflowId` 的解析未纳入契约/集成断言，而流式是主要入口 |
| S2 | 中 | `services/agent-service/src/agentforge_agent/action_runtime.py` | `_postgres_workflow_lock` | PostgreSQL 模式下在整段图操作期间占用池连接持 advisory lock，连接池 max_size=10 时高并发可能 PoolTimeout |
| S3 | 低 | `services/core-api/.../application/AgentActionService.java` | `createPending` 中 `actionWorkflowId == null` 分支 | 缺失 workflow ID 时静默 `Optional.empty()`，会掩盖滚动升级/解析失败并可能造成“Python 已建 checkpoint、Java 无 Action”的会话卡死 |
| S4 | 低 | `services/core-api/.../domain/AgentTaskAction.java` | `pending(...)` 11 参重载 | 生产可见的静态工厂会生成随机 workflow UUID，存在被误用为真实轮次绑定的风险 |
| S5 | 低 | `services/core-api/.../application/AgentActionWorkflowServiceTest.java` | legacy 兼容测试 | `verify(never()).resume(8 参旧重载)` 对实际调用的 9 参重载无验证力，断言本身无效 |

### 无需修改

| ID | 说明 |
|----|------|
| N1 | `ACTION_STATE_SCHEMA_VERSION=2` 与 `LEGACY_ACTION_STATE_SCHEMA_VERSION=1` 的双版本校验逻辑正确；`_require_supported` / `_require_workflow` 对 schema 2 强制存在 workflow_id、对 legacy 强制无 workflow_id |
| N2 | `interrupt` 中“同 request 同 proposal 复用同一 workflow、不同 request 冲突”的判定顺序正确（先指纹、后 request_id），且与 ADR-0037 一致 |
| N3 | Flyway V14 的 check 约束与部分唯一索引正确覆盖 CHAT(v2 必须有 id)、CHAT(v1/null 必须无 id)、MCP(必须无 version/id/conversation) 三类历史与新数据 |
| N4 | Java `createPending` 采用“会话行悲观锁 + 事务级 advisory lock + 全局唯一索引”三层保护，同 workflow 并发只产生一个 PENDING Action |
| N5 | `AgentActionWorkflowService` 对 `actionWorkflowVersion != null` 才 Resume、并以 `actionWorkflowId` 参与结果校验，v1/null/MCP 兼容路径保留 |
| N6 | Python `actionWorkflowId` 仅参与内部 proposal 与 Resume，未写入公共 `pendingAction` 语义 |

## 三、逐个 Issue 展开

### S1｜Java 流式路径 `actionWorkflowId` 解析缺少契约覆盖

- **Severity**：中（测试覆盖，不阻塞）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/HttpAgentServiceClient.java`（流式解析段，本次 diff 未展示）、`services/core-api/src/test/java/com/agentforge/core/agent/infrastructure/AgentServiceHttpContractIntegrationTest.java`（`javaClientStreams...` 仅断言事件类型）
- **Evidence**：
  - Python 侧同时覆盖同步与流式：
    ```python
    # api.py chat_stream
    waiting = action_runtime.interrupt(namespace, proposal, bundle.project.request_id)
    proposal = proposal.model_copy(update={"action_workflow_id": waiting.workflow_id})
    ...
    "toolProposal": proposal.model_dump(mode="json", by_alias=True) if proposal is not None else None,
    ```
  - 但 Java 契约测试中新增/修改的断言只覆盖同步 `chat()`：
    ```java
    // javaClientResumesARealInterruptedPythonWorkflow / javaClientParsesStructuredToolProposal
    assertThat(result.toolProposal().actionWorkflowId()).isEqualTo(workflowId);
    ```
  - 流式测试仅断言：
    ```java
    assertThat(events.getLast().type()).isEqualTo("complete");
    ```
- **Description**：如果 Java 的 NDJSON 反序列化未把 `actionWorkflowId` 映射进 `ToolProposal`（例如按字段手工构造或使用了不含该组件的中间类型），`createPending` 会命中 S3 的静默丢弃分支：Python 已创建 checkpoint，但 Java 不产生 PENDING Action，用户在流式入口永远拿不到审批卡片，并且该会话后续任何 Chat 都会因“当前轮次已等待”而 409，形成卡死。虽然按现有 record + Jackson 结构大概率可自动映射，但该关键路径当前零自动化断言。
- **Suggested Fix**：在 `AgentServiceHttpContractIntegrationTest` 增加一条流式契约测试，使用真实 Python（或录制响应）返回带 `actionWorkflowId` 的 `complete`，断言 `AgentStreamEvent.toolProposal().actionWorkflowId()` 非空且与 Python 返回值一致，并在 `AgentChatService` 流式路径上断言 `createPending` 收到该 ID。

### S2｜PostgreSQL workflow 锁在锁内占用连接池连接

- **Severity**：中（并发资源，demo 规模下可接受）
- **File & Line**：`services/agent-service/src/agentforge_agent/action_runtime.py` → `_postgres_workflow_lock`
- **Evidence**：
  ```python
  @contextmanager
  def _postgres_workflow_lock(pool: ConnectionPool, thread_key: str):
      with pool.connection() as connection:
          with connection.transaction():
              connection.execute(
                  "SELECT pg_advisory_xact_lock(hashtextextended(%s, 0))",
                  (thread_key,),
              )
              yield
  ```
  以及 `open_postgres_action_runtime` 中 `ConnectionPool(..., max_size=10)`。
- **Description**：锁连接在 `yield` 期间（即整个 `graph.get_state/invoke` 及 PostgresSaver 写入期间）一直被占用；PostgresSaver 使用同池的另一条连接。单请求占 2 条连接，池上限 10，因此约 5 个并发审批写入后进入排队；极端延迟下会触发 `PoolTimeout` 而非明确业务错误。不会死锁（占用图连接的线程不依赖排队线程），但可用性会下降。
- **Suggested Fix**：为 advisory lock 使用独立的、`max_size` 较小的连接池；或把锁的持有范围收敛到“读状态并判定 + 短写入”，而非覆盖全部图操作；或将主池 `max_size` 提升并设置明确的 `timeout` 与超时后的可重试错误映射。

### S3｜缺失 `actionWorkflowId` 时静默丢弃 proposal

- **Severity**：低（行为/可观测性）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java` → `createPending`
- **Evidence**：
  ```java
  UUID actionWorkflowId = proposal.actionWorkflowId();
  if (actionWorkflowId == null) {
      return Optional.empty();
  }
  ```
- **Description**：Python 在新建等待轮次时始终返回 `actionWorkflowId`；该分支实际上只在“Python 已建 checkpoint 但 Java 未解析到 ID”或版本不一致时触发。`Optional.empty()` 会被上层当作“普通回答、无待确认动作”，从而无声地吞掉工具提案，并因 checkpoint 已存在导致会话在等待期内无法再发起 Chat。
- **Suggested Fix**：在该分支记录 `warn` 级日志（包含 project/conversation/requestId），或返回可区分的状态让上层产生明确错误/告警，避免静默降级。

### S4｜`AgentTaskAction.pending` 无 workflow ID 重载生成随机 UUID

- **Severity**：低（可维护性/误用防护）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/domain/AgentTaskAction.java`
- **Evidence**：
  ```java
  public static AgentTaskAction pending(... UUID conversationId, AgentActionType actionType, ...) {
      return pending(
              projectId, requestedByUserId, conversationId, UUID.randomUUID(), actionType, ...);
  }
  ```
- **Description**：该重载会为 CHAT Action 生成一个和任何 checkpoint 都没有对应关系的 workflow UUID，使 Action 无法被真实 Resume。当前生产仅 `createPending` 使用带 workflow ID 的重载，但保留该重载会在后续开发中形成隐式误用入口。
- **Suggested Fix**：移除该重载，或将其标注为仅供测试（例如迁移到测试工具类 / 加 `@VisibleForTesting` 语义并在生产代码禁用）。

### S5｜legacy 兼容测试的 `never()` 断言使用了错误重载

- **Severity**：低（测试表达力）
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/application/AgentActionWorkflowServiceTest.java` → `confirmExecutesLegacyPendingActionWithoutRequiringAMissingCheckpoint`
- **Evidence**：
  ```java
  verify(agentService, never()).resume(
      projectId, userId, false, conversationId, actionId,
      "APPROVE", "legacy-key", "request-legacy");   // 8 参 default 重载
  ```
  而生产代码调用的是 9 参重载（含 `actionWorkflowId`）：
  ```java
  agentService.resume(..., approved.conversationId(), approved.actionWorkflowId(), actionId, ...);
  ```
- **Description**：Mockito 对 interface default 方法默认打桩，`verify(never())` 只统计 8 参重载的调用次数，而生产代码调用 9 参，因此该断言永远成立、不具备防护力。好在若发生回归改调 9 参重载，mock 返回 `null` 会使 `requireMatchingResume` 抛 `ServiceUnavailableException`，测试仍会失败，因此并非真实覆盖缺口；但断言本身是误导性的。
- **Suggested Fix**：改为对 9 参重载做 `verify(agentService, never()).resume(projectId, userId, false, conversationId, null, actionId, ...)`，或直接 `verifyNoInteractions(agentService)`。

## 四、主开发 (Codex) 评估回填区

| Issue | Codex 判定（接受/不接受/已修复） | 处理说明 / 证据（命令、退出码、测试名） | 残留风险 |
|-------|-------------------------------|------------------------------------------|----------|
| S1 | 已修复 | 真实 Python NDJSON 契约改为产生 Action proposal，并断言 `complete.toolProposal.actionWorkflowId` 非空；PostgreSQL-backed Java→Python 契约套件 8/8 通过，连同受影响单元测试共 28 tests、0 failures/errors/skipped。 | 无。 |
| S2 | 不接受为当前缺陷 | 锁仅覆盖同 namespace 的短 checkpoint 读写，模型与检索明确在锁外；当前并发 PostgreSQL 回归和跨进程 smoke 均通过。`max_size=10` 下的容量属于未给出实际 PoolTimeout 复现的扩展性建议，不在 R01 改连接池拓扑。 | 高并发容量未做压力基准；后续出现可重复 PoolTimeout 时单独治理。 |
| S3 | 接受，归入 R02 | 该分支确会把“checkpoint 已创建但 Java 未落 Action”表现为静默无 Action；它与外部审查 R02 的补偿/一致性问题相同，R01 不跨边界混改，下一独立变更将以失败注入建立红灯后处理。 | R02 完成前仍存在异常路径卡住会话的已知风险。 |
| S4 | 已修复 | 删除生成随机 workflow UUID 的无 ID `pending(...)` 重载；测试显式提供 workflow ID。最终 `mvnw.cmd clean verify` 重新编译并通过。 | 无。 |
| S5 | 已修复 | legacy 测试改为 `verifyNoInteractions(agentService)`，直接保证任何 Resume 重载都未调用；目标套件与 clean verify 均通过。 | 无。 |

## 五、Milestone 方向与下一节点提示（基于显式上下文）

- R01 目标（Action ↔ workflow 轮次一对一、旧决策不能消费新等待点、同 Chat 重试不重复建 Approval）在实现与测试中均已闭环：`test_old_decision_cannot_resume_a_new_waiting_round`、`test_waiting_round_replays_only_the_same_request_and_proposal`、`test_postgres_concurrent_replay_of_one_round_returns_one_workflow_id`、`concurrentReplayOfOneChatWorkflowCreatesOnePendingApproval` 覆盖核心不变量。
- 变更记录明确将“checkpoint 已创建但 Java 未写入”的补偿归入 R02，将管理员身份恢复归入 R03，与本次 diff 范围一致，未出现越界实现。
- 下一节点需注意：**S1/S3 组合**（流式入口 + 缺失 workflow ID 静默丢弃）可能表现为非确定性卡死；建议在进入 R02 补偿之前先补齐流式契约断言，以便 R02 的补偿设计有可靠的触发信号。
