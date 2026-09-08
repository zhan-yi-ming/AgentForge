# Pi 代码审查报告：v2-07-langgraph-checkpoint-resume / Attempt 1

- 日期：2026-09-09
- 审查阶段：v2-07-langgraph-checkpoint-resume
- 审查对象：INDEX@3467662（基线：3467662a1e4dc900232c0c882e75578833940c14）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge V2-07 LangGraph Checkpoint/Resume — Milestone Review 报告

## 概述与总体结论

本次审查针对 Commit `3467662` 的 V2-07 节点：Python 引入 PostgreSQL-backed LangGraph action checkpoint、动态 `interrupt()` 与内部 Resume；Java 将 confirm/reject 拆分为「持久化决定 → Resume → 重新锁定并确定性执行」三阶段编排，并以可空 `action_workflow_version` 区分 V2-06 旧 Action。

总体设计方向正确：checkpoint 内容最小化（只存 namespace、proposal 指纹、action/decision/idempotency/request 元数据）、Java 独占业务事实、旧 Action 兼容标记、动态 interrupt 只在存在 Tool proposal 时触发，均与 ADR-0022 和节点 Scope 一致，未发现越权或令牌/敏感信息写入 checkpoint 的问题。

但存在 **1 项必须修改的并发正确性问题**：`open_postgres_action_runtime` 在进程级只建立一个 psycopg 连接并挂在 `app.state` 上，而 FastAPI 同步端点全部运行在线程池中，多请求会并发使用同一连接与同一 `PostgresSaver`（其写入依赖 pipeline 模式），存在 checkpoint 写入交错/损坏的明确风险。此外有若干建议性改进（测试面、错误语义、遗留入口的契约兜底）。结论为 **需修复后交付**。

---

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| M1 | High | services/agent-service/src/agentforge_agent/action_runtime.py；main.py；api.py | action_runtime.py:168-181；main.py:10-23；api.py:61-62 | 进程级单一 PostgreSQL 连接被线程池并发复用，checkpoint 写入非线程安全 |

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Medium | services/core-api/.../application/AgentActionView.java | 29-43 | 旧 15 参构造函数默认 `actionWorkflowVersion=1`，与「null==旧记录」语义相反，易误用 |
| S2 | Medium | services/core-api/.../application/AgentActionService.java | 128-131（3 参 confirm） | 遗留 `confirm` 入口可绕过 Resume，破坏「version=1 必须恢复」的不变量 |
| S3 | Low | services/core-api/.../infrastructure/HttpAgentServiceClient.java | 101-122 | 把 Python 404/409 冲突统一折叠为 503，丢失契约语义且难以排障 |
| S4 | Low | services/agent-service/src/agentforge_agent/api.py | 流式 `except ValueError` 分支 | `ActionWorkflowConflict` 被折进 ValueError，输出误导性错误文案 |
| S5 | Low | services/agent-service/tests/test_action_runtime.py | 全文 | 缺少共享 runtime 下的并发 interrupt/resume 测试，无法证明线程安全 |

### 无需修改

| ID | 严重级别 | 文件 | 核心问题 |
| --- | --- | --- | --- |
| N1 | — | docs/*、ADR-0022、V8 迁移 | 文档/ADR/迁移/compose 一致性良好 |
| N2 | — | action_runtime.py 的 checkpoint state | 未保存 JWT/内部 token/密码/完整 Prompt/检索正文，敏感面控制正确 |
| N3 | — | V8__create_agent_checkpoint_schema.sql | 可空 `action_workflow_version` + CHECK 约束，升级兼容且幂等 |
| N4 | — | api.py resume 端点 | 内部 token 校验与命名空间隔离符合契约 |

---

## 逐个 Issue 展开

### M1. 进程级单一 PostgreSQL 连接被并发复用（High，必须修改）

**File & Line**
- `services/agent-service/src/agentforge_agent/action_runtime.py:168-181`
- `services/agent-service/src/agentforge_agent/main.py:10-23`
- `services/agent-service/src/agentforge_agent/api.py:61-62`

**Evidence**
```python
# action_runtime.py
@contextmanager
def open_postgres_action_runtime(dsn: str):
    connection = psycopg.connect(dsn, autocommit=True, prepare_threshold=0)
    try:
        connection.execute("SET search_path TO agent_checkpoint")
        checkpointer = PostgresSaver(connection)
        checkpointer.setup()
        yield ActionWorkflowRuntime(checkpointer)
    finally:
        connection.close()
```
```python
# main.py
with application.state.action_runtime_context_factory(
    settings.effective_checkpoint_db_dsn()
) as action_runtime:
    application.state.action_runtime = action_runtime
```
```python
# api.py
def get_action_runtime(request: Request) -> ActionWorkflowRuntime:
    return request.app.state.action_runtime
```

**Description**
整个 Agent Service 进程只建立**一条** psycopg 连接和一个 `ActionWorkflowRuntime`，挂在 `app.state` 上供所有请求共享。但 `/internal/v1/chat`、`/internal/v1/chat/stream`、`/internal/v1/agent/resume` 都是同步 `def` 端点，FastAPI 通过 `run_in_threadpool` 在线程池（uvicorn 默认 40 线程）中执行。多个并发请求——例如用户同时开两个会话产出 proposal、或 confirm 重试与另一次 chat 同时发生——会在不同线程上同时调用同一 `PostgresSaver`。`langgraph-checkpoint-postgres` 的写入依赖 psycopg3 的 **pipeline 模式**（connection 级状态机，非 per-thread），单连接的并发 pipeline 会导致命令交错、checkpoint/writes/blobs 部分写入，破坏本节点「Checkpoint 持久化正确、可恢复」的核心验收目标。当前测试与 smoke 全是串行执行，无法暴露此问题。

**Suggested Fix**
使用连接池替代裸连接，并保持 `search_path=agent_checkpoint`：
```python
from psycopg_pool import ConnectionPool

@contextmanager
def open_postgres_action_runtime(dsn: str):
    pool = ConnectionPool(
        dsn,
        min_size=1,
        max_size=10,
        open=False,
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "options": "-c search_path=agent_checkpoint",
        },
    )
    pool.open()
    try:
        checkpointer = PostgresSaver(pool)
        checkpointer.setup()
        yield ActionWorkflowRuntime(checkpointer)
    finally:
        pool.close()
```
`PostgresSaver` 原生接受 `psycopg_pool.ConnectionPool`；或直接改用官方推荐的 `PostgresSaver.from_conn_string(dsn)`（内部自行建池）。修复后补一条并发 smoke/单元测试（见 S5）。

---

### S1. AgentActionView 旧构造函数默认 workflow version=1（Medium，建议修改）

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionView.java:29-43`

**Evidence**
```java
public AgentActionView(
        UUID id,
        ...
        Instant decidedAt) {
    this(
            id, projectId, conversationId, actionType, status, taskId,
            expectedVersion, title, description, taskStatus, priority,
            resultTask, createdAt, decidedAt, 1);
}
```

**Description**
15 参便利构造函数把 `actionWorkflowVersion` 硬编码为 `1`，与 V8 迁移「null == V2-06 旧 Action、1 == V2-07 新 Action」的语义相反。当前生产路径只用 `AgentActionView.from(AgentTaskAction, TaskView)` 读取真实列值，故暂不可触发；但这是一个明显易误用的默认值——将来任何一处用旧构造器构造「无 checkpoint 的旧 Action」视图，都会被 `AgentActionWorkflowService` 当作 version=1 强制调 Resume，导致旧 Action 永远 503。

**Suggested Fix**
把默认值改为 `null`（与迁移的「空值=旧记录」一致），或删除该便利重载，强制所有生产构造走 `from` 工厂。

---

### S2. 遗留 Confirm 入口可绕过 Resume（Medium，建议修改）

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentActionService.java:128-131`

**Evidence**
```java
@Transactional
public AgentActionView confirm(UUID projectId, UUID actionId, AuthenticatedActor actor) {
    return confirm(projectId, actionId, actor, "legacy-" + actionId, "internal");
}
```

**Description**
Controller 已切换到 `AgentActionWorkflowService`，但 `AgentActionService.confirm` 的 3 参/5 参重载仍是公开 service API，其内部直接 `approve → executeApproved`，完全不经过 Resume。任何未来模块若直接注入 `AgentActionService` 调 `confirm`，就会对 version=1 的 Action 执行「批准即写 Task 而永不恢复 Python」的路径，违反「V2-07 新 Action 强制 Resume」的设计不变量。当前生产无调用方（无阻塞），但属于契约级隐患。

**Suggested Fix**
删除这两个组合重载（仅测试在用），或在其内部增加 guard：`if (action.getActionWorkflowVersion() != null) throw new IllegalStateException(...)`，强制所有 version=1 决策必须走 `AgentActionWorkflowService`。

---

### S3. Python 404/409 被折叠为通用 503（Low，建议修改）

**File & Line**
`services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/HttpAgentServiceClient.java:101-122`

**Evidence**
```java
try {
    AgentResumeResult response = restClient.post()
            .uri("/internal/v1/agent/resume")
            ...
            .retrieve()
            .body(AgentResumeResult.class);
    ...
    return response;
}
catch (RestClientException exception) {
    throw new ServiceUnavailableException("Agent Service resume is unavailable.", exception);
}
```

**Description**
Python 的 `ActionWorkflowNotFound`（404）与 `ActionWorkflowConflict`（409，如 action/decision/key/namespace 不匹配、旧 schema）都会被 RestClient 抛成 `RestClientResponseException`，最终统一变成公共 503。对于依赖不可用（网络失败、5xx）这是正确的 fail-safe；但对 Java 校验已放行、仅因 Python 侧状态不匹配触发的确定性冲突，丢失 409 语义，排障时会误导到「依赖挂了」而非「状态冲突」。

**Suggested Fix**
在 catch 链中先捕获 `HttpClientErrorException` 并读取状态码，对 409/404 保留结构化信息（例如转 `ConflictException` 或至少记录底层状态码与 body），5xx/网络失败才归入 `ServiceUnavailableException`。若坚持当前 fail-safe 语义，也需要在日志中保留下游状态码，避免丢失可观测性。

---

### S4. 流式路径把 ActionWorkflowConflict 折进 ValueError（Low，建议修改）

**File & Line**
`services/agent-service/src/agentforge_agent/api.py`（`chat_stream` 生成器内 `except ValueError` 分支，约 236-243 行）

**Evidence**
```python
except ValueError as exception:
    generation_observation.fail(exception)
    ...
    yield encode({
        "type": "error",
        "message": "Conversation context changed before completion.",
    })
```

**Description**
`ActionWorkflowConflict` 继承 `ActionWorkflowError(ValueError)`。在流式路径中，当 `action_runtime.interrupt` 因「同一 conversation 已有另一 Tool proposal 等待」抛出冲突时，会进入 `except ValueError`，对外输出与真实原因无关的「Conversation context changed before completion.」，误导前端与排障。同步 `chat` 已有专门的 `except ActionWorkflowConflict → 409` 分支，流式缺少对等处理。

**Suggested Fix**
在 `except ValueError` 之前增加 `except ActionWorkflowConflict`, 输出明确冲突文案（如 `"Another tool action is already waiting for this conversation."`），保持与同步路径一致的语义。

---

### S5. 缺少共享 runtime 的并发测试（Low，建议修改）

**File & Line**
`services/agent-service/tests/test_action_runtime.py:1-133`

**Evidence**
现有测试只覆盖：单线程 interrupt→resume→replay、namespace 隔离、重建 runtime 后的恢复（`test_postgres_action_workflow_resumes_after_runtime_is_recreated`）。整个过程串行，没有任何两个线程同时操作同一 `ActionWorkflowRuntime`/`PostgresSaver` 的用例。

**Description**
V2-07 的核心承诺是可靠性，而 M1 正是并发场景下的正确性风险。当前测试面无法证明「并发 interrupt/resume 不会损坏 checkpoint」。补上并发测试既能直接验证线程安全，也能为 M1 的修复提供回归门禁。

**Suggested Fix**
新增用例：用 `ThreadPoolExecutor` 同时提交多个 `interrupt`（不同 namespace）与多个 `resume`（同一 namespace 相同 key replay），断言全部成功且 replay 结果稳定、checkpoint 计数正确。先以 InMemorySaver 跑，再在 `open_postgres_action_runtime`（池化版）上跑一遍。

---

### 无需修改项说明

- **N1 文档/ADR/迁移一致性**：ADR-0022、V8 迁移、`data-architecture.md`、`agent-runtime.md`、`core-api.md` 对 checkpoint schema、workflow version、失败关闭语义的描述与实现一致。
- **N2 敏感面控制**：checkpoint state 仅含 schema version、namespace、proposal 指纹（SHA-256）、action/decision/key/request 元数据，未保存 JWT、内部 token、密码、完整 Prompt 或检索正文，符合节点要求。
- **N3 V8 兼容性**：`action_workflow_version INTEGER NULL CHECK (NULL OR 1)` 可空且幂等，旧行保持 NULL，新行由 `AgentTaskAction.pending` 置 1，正确区分升级前后记录。
- **N4 Resume 端点保护**：`/internal/v1/agent/resume` 复用 `require_internal_token` 的 hmac 常量时间比较，namespace 在服务端由配置 tenant/workspace + 请求 project/user/conversation 重建，不信任客户端 tenant/workspace。

---

## 主开发 (Codex) 评估回填区（预留）

| Review ID | 初判 | 复判（接受/驳回） | 说明与修改计划 |
| --- | --- | --- | --- |
| M1 | 必须修改 | 接受（修正事实后处理） | 已安装的 saver 自带全局锁，原实现不会按报告所述交错损坏，但会把并发请求串行化。改为官方支持的 1–10 连接池，并以真实 PostgreSQL 并发 8 个 interrupt 与 8 个 resume 验证。 |
| S1 | 建议修改 | 接受 | 便利构造器默认 workflow version 改为 null；需要 checkpoint 的测试视图显式传 1。 |
| S2 | 建议修改 | 接受 | 删除 `AgentActionService` 两个组合 confirm 入口；公共决定只能经 `AgentActionWorkflowService`，底层测试显式调用 approve/execute seam。 |
| S3 | 建议修改 | 接受 | Python 404/409 映射为不含下游正文的公共 409；网络、5xx 仍为 503。 |
| S4 | 建议修改 | 接受 | 流式 Action 冲突使用独立通用文案，并新增 HTTP 测试。 |
| S5 | 建议修改 | 接受 | 新增连接池共享 runtime 的真实 PostgreSQL 并发测试。 |

> 交接说明：本轮仅依据提供的 Git Diff、文件清单与显式上下文完成只读审查，未执行任何命令、未修改文件或 Git 状态。修复 M1 并完成最终 clean verify / Compose config / diff·docs·敏感扫描后，建议进入下一轮以验证修复并识别修复引入的新问题。
