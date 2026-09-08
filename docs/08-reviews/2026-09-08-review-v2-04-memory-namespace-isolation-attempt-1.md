# Pi 代码审查报告：v2-04-memory-namespace-isolation / Attempt 1

- 日期：2026-09-08
- 审查阶段：v2-04-memory-namespace-isolation
- 审查对象：INDEX@00c6438（基线：00c6438eb95c2874686023dc1231aeefacfd7ef9）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge V2-04 Memory Namespace Isolation — Milestone Review（轮次 1/3）

## 1. 概述与总体结论

本次为只读 Milestone Review，未运行任何命令，未改变 Git 状态；Codex 提供的测试记录仅作为审查证据使用。

**结论：通过（PASS），无必须修改项。**

V2-04 的核心实现方向正确、边界清晰：

- `MemoryNamespace` 是不可变五层键（`tenant → workspace → project → user → thread`）；tenant/workspace 仅来自 Agent Service 服务端配置，不进入 HTTP body，客户端无法伪造覆盖。
- `ConversationMemory` 以完整 Namespace 作为 LRU key；`load` 返回绑定 Namespace 与 session generation 的 `ConversationLease`，`commit_exchange` 强制核对该 lease；LRU 淘汰 / session 重建后的陈旧提交被拒绝（同步 422、流式安全 `error` 事件）。
- `ContextBundle` 的 Conversation 与 Project Context 共享同一个 Namespace，旧 `project_id/user_id/conversation_id` 平行字段全部改为从 Namespace 派生，消除了字段漂移面。
- Retriever 只从 Namespace 读取 project/user，RAG SQL/schema 未变，未扩大数据库隔离范围之外的能力。
- HTTP schema、Java、Web、数据库迁移、依赖均未变动；未越级实现 V2-05 RBAC/Risk、Approval/Audit、持久化恢复。
- 各层负向隔离与失败路径测试覆盖充分（五层 Namespace 逐一变化、HTTP project/user/thread 矩阵、LRU stale lease、同步失败不提交、流式失败/重绑不提交、客户端伪造字段无效）。

存在 3 项建议修改（见下），均为文档一致性或防御性健壮性，不阻塞交付。

---

## 2. 发现清单

| ID | 严重级别 | 分类 | 文件 | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | 建议 | 治理/文档一致性 | docs/01-product/v2-v3-node-roadmap.md | 路线图“当前状态”未推进，仍写“下一候选为 V2-03 尚未授权”，与 V2-04 交付事实矛盾 |
| S2 | 建议 | 代码健壮性 | services/agent-service/src/agentforge_agent/context.py | `conversation=None` 时的 fallback lease 无语义，误用时抛出误导性错误 |
| S3 | 建议 | 防御性 | services/agent-service/src/agentforge_agent/context.py | `MemoryNamespace.__post_init__` 对 None 输入抛 `AttributeError` 而非清晰 `ValueError` |

（“无需修改”项见第 4 节。）

---

## 3. 详细发现

### S1 — 路线图“当前状态/Current Node”未随之更新（建议修改）

- **Severity**：建议（Medium，治理一致性）
- **File & Line**：`docs/01-product/v2-v3-node-roadmap.md:8-9`（“当前状态/Current Node”段；该文件不在本次 diff 中）
- **Evidence**（来自显式审查上下文）：
  ```
  - 当前状态：V1 completed；V2-01 Langfuse 基础 Trace completed；V2-02 Context Manager 数据结构 completed
  - Current Node：无开发中 Node；下一候选为 V2-03 Conversation Summary + Token Budget，尚未授权
  ```
- **Description**：本次交付是 V2-04，基线为已远端核验并提交的 V2-03（`00c6438eb95c2874686023dc1231aeefacfd7ef9`）。`docs/03-features/context-management.md` 已把所属阶段推进到 V2-02/V2-03/V2-04，ADR 索引也已加入 ADR-0017/0018，但路线图的“当前状态”仍停留在“V2-02 completed、V2-03 尚未授权”。按 `v2-v3-node-development-protocol.md`，路线图是路线唯一来源，且 Node Close Gate 步骤 9/11 明确要求“核对当前 Roadmap Node”；现状与事实矛盾，可能误导下一节点（V2-05）的授权判断。
- **Suggested Fix**：在 Node Close Gate 前同步更新路线图“当前状态”为：
  ```
  - 当前状态：V1 completed；V2-01 completed；V2-02 completed；V2-03 completed；V2-04 completed
  - Current Node：无开发中 Node；下一候选为 V2-05 完整 RBAC + Risk Engine，尚未授权
  ```
  该文件不在本次 diff 中，建议随本轮或下轮一并提交更新。

---

### S2 — `ContextManager.build` 在无会话时的 fallback lease 无语义（建议修改）

- **Severity**：建议（Low，健壮性/潜在误用）
- **File & Line**：`services/agent-service/src/agentforge_agent/context.py:≈258-265`（`ContextManager.build`，行号按 diff 估算）
- **Evidence**：
  ```python
  conversation=conversation
  or ConversationContext(
      namespace=namespace,
      lease=ConversationLease(namespace, uuid4()),
  ),
  ```
- **Description**：当 `conversation=None`（仅出现在 `build_chat_graph` 未注入 `ConversationMemory` 的测试路径）时，生成的 `ConversationLease(namespace, uuid4())` 并不对应 store 中的任何会话，generation 是随机值。当前若对该 bundle 调用 `commit_exchange`，会命中 `_session_for_commit` 的 `session is None or generation != ...` 分支，抛出 `ValueError("conversation changed before completion")`——该报错对“从未加载过的会话”语义具有误导性。当前 API 两个端点在签名上恒定注入 `ConversationMemory` 并在 prepare 节点真实 `load`，因此生产路径不会触发；属潜在陷阱而非当前缺陷。
- **Suggested Fix**：任选其一（最小改动即可）：
  1. 在该 fallback 处加注释，明确“此 lease 仅用于无内存图构建场景，不可用于 commit，commit 会安全失败”；
  2. 或让 `ConversationContext` 的 lease 可空，并在 `ConversationMemory.commit_exchange` 首行显式拒绝 `lease=None`，给出 `"conversation was not loaded; cannot commit"` 之类的清晰错误。

---

### S3 — `MemoryNamespace.__post_init__` 对 None 输入的异常类型不友好（建议修改）

- **Severity**：建议（Low，防御性）
- **File & Line**：`services/agent-service/src/agentforge_agent/context.py:30-38`（`MemoryNamespace.__post_init__`）
- **Evidence**：
  ```python
  def __post_init__(self) -> None:
      tenant_id = self.tenant_id.strip()
      workspace_id = self.workspace_id.strip()
      ...
  ```
- **Description**：`tenant_id`/`workspace_id` 若为 `None`，`None.strip()` 会抛 `AttributeError`，而非带有明确信息的 `ValueError`。当前生产路径的值来自 `Settings.namespace_tenant/workspace`，两者都有默认值与 `pattern` 校验（`^[a-z0-9][a-z0-9_-]{0,63}$`），Pydantic 层面即拒绝空值/非法值，因此运行时不会为 None；纯属防御性改进，不阻塞。
- **Suggested Fix**：
  ```python
  tenant_id = (self.tenant_id or "").strip()
  workspace_id = (self.workspace_id or "").strip()
  ```
  即可把 None 归入既有的空白值错误路径。

---

## 4. 无需修改（已核验）

以下交叉点均检查通过，未发现可复现问题：

- **同 conversationId 跨 project/user 行为变化**：由 V2-03 的 422 改为“独立空 session”。ADR-0018、`docs/03-features/context-management.md`、`docs/04-api/agent-service.md` 三处文档均同步更新一致；该变化反而减少了“探测他人 conversationId 绑定”的信息泄露面，方向正确。
- **客户端伪造 Namespace 字段**：`tenant/workspace` 不进入 body；测试 `test_chat_namespace_uses_server_configuration_not_request_fields` 证明服务端配置优先，客户端值不生效。配置校验含完整锚定（`^…$`），空值/带空格/含路径符号均被启动期拒绝。
- **并发与幂等（lease）**：RLock 保护 load/commit；lease 同时绑定 Namespace 与 generation；`move_to_end` 维护 LRU；stale lease（LRU 淘汰、session 重建、load 快照期间被重绑）在同步 422、流式 `error` 且不发 `complete`，均与文档一致。
- **负向隔离测试覆盖**：`test_memory_namespace.py` 对 tenant/workspace/project/user/thread 五层逐一变化并验证“另一 Namespace 为空且原历史未被覆盖”；`test_api.py` 对 HTTP 层 project/user/thread 做矩阵；同步失败与不完整流均验证不提交。
- **边界不越界**：未引入 Neo4j/GraphRAG、MCP、LiteLLM、Web/Java 源码变更、DB migration 或依赖变更；RAG 仍沿用 `rag_chunk.project_id` 数据库隔离。
- **测试与扫描证据**：Codex 记录显示 Agent 全量 80 passed、Java 83 tests、Java→Python 契约 7/7、真实 pgvector Testcontainers 通过、gitleaks 0 命中；属充分且与本次风险等级匹配的 L3 门禁证据。

---

## 5. 主开发 (Codex) 评估回填区

| ID | Codex 判定（属实/不属实） | 是否需修改 | 最小修复与补测范围 | 备注 |
| --- | --- | --- | --- | --- |
| S1 |  |  |  |  |
| S2 |  |  |  |  |
| S3 |  |  |  |  |

（本轮无“必须修改”项，以上建议修改无需阻塞 Node Close；建议在 Close Gate 前集中处理或记录为后续维护项。）
