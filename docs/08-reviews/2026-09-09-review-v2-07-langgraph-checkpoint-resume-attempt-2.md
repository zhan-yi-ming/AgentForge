# Pi 代码审查报告：v2-07-langgraph-checkpoint-resume / Attempt 2

- 日期：2026-09-09
- 审查阶段：v2-07-langgraph-checkpoint-resume
- 审查对象：INDEX@3467662（基线：3467662a1e4dc900232c0c882e75578833940c14）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge V2-07 LangGraph Checkpoint / Resume — Milestone Review 报告（Attempt 2）

## 概述与总体结论

本轮为 `v2-07-langgraph-checkpoint-resume` 里程碑第 2 轮审查，审查对象为 `INDEX@3467662`（基线 `3467662a1e4dc900232c0c882e75578833940c14`）。上一轮 Attempt 1 结论为 `NEEDS_FIX`，核心为 M1 单连接并发瓶颈，另有 S1–S5 建议项。

本次提供的 diff 显示上述问题均已收敛：

- M1：`open_postgres_action_runtime` 已从裸 `psycopg.connect` 改为 `psycopg_pool.ConnectionPool`（1–10 连接），并设置 `options="-c search_path=agent_checkpoint"`，消除串行瓶颈；新增真实 PostgreSQL 下 8 并发 interrupt / 8 并发 resume 回归测试。
- S1：`AgentActionView` 兼容构造器默认 `actionWorkflowVersion` 改为 `null`，与「`null` == V2-06 旧记录」语义一致。
- S2：删除 `AgentActionService` 中可直接绕过 Resume 的组合 `confirm` 入口，公共决策统一走 `AgentActionWorkflowService`。
- S3：`HttpAgentServiceClient.resume` 对 Python 404/409 映射为公共 `ConflictException`（409 且不透出下游正文），网络/5xx 仍为 503。
- S4：流式路径新增 `ActionWorkflowConflict` 独立处理分支，输出明确冲突文案。
- S5：新增共享运行时并发 interrupt/resume 真实 PostgreSQL 测试。

基于提供的 diff 首/中/尾采样与显式上下文，**未发现新的必须修改问题**。方向、边界与 V2-07 节点定义一致：checkpoint 内容最小化、完整 Memory Namespace 派生物理 thread key、Java 独占业务事实、三阶段确认编排、旧 Action 以可空 workflow version 兼容，均符合 ADR-0022 和路线图。本轮审查通过。

---

## 详细发现清单

| ID | 分组 | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- | --- |
| S1 | 建议修改 | Low | `services/agent-service/tests/test_action_runtime.py` | 全文 | 缺少「已恢复 workflow 后同一 conversation 再次提出新 proposal，重新 interrupt 建立新一轮等待状态」的自动化用例 |
| N1 | 无需修改 | — | `action_runtime.py` | `open_postgres_action_runtime` | 上轮 M1 已修复：连接池替代单连接，且 options 固定 search_path，无并发交错风险 |
| N2 | 无需修改 | — | `AgentActionView.java` | 构造器 | 上轮 S1 已修复：便捷构造器默认 workflow version 为 null |
| N3 | 无需修改 | — | `AgentActionService.java` | 公开 API | 上轮 S2 已修复：移除可直接绕过 Resume 的组合 confirm 入口 |
| N4 | 无需修改 | — | `HttpAgentServiceClient.java` | resume | 上轮 S3 已修复：404/409 映射为公共 409，失败关闭语义正确 |
| N5 | 无需修改 | — | `api.py` | 流式异常分支 | 上轮 S4 已修复：ActionWorkflowConflict 在 ValueError 前独立捕获 |
| N6 | 无需修改 | — | `test_action_runtime.py` | 并发测试 | 上轮 S5 已补齐并发测试 |

---

## 逐个 Issue 展开

### S1. 缺少「已恢复后同一 conversation 新 proposal 重新 interrupt」的自动化用例

- **Severity**：Low（建议修改）
- **File & Line**：`services/agent-service/tests/test_action_runtime.py`（全文，现有用例为 interrupt→resume→replay、namespace 隔离、跨 runtime 恢复、并发 interrupt/resume）

- **Evidence**

当前 `ActionWorkflowRuntime` 的状态机覆盖了 `WAITING → RESUMED` 与同 key replay，但没有用例验证：

1. 同一 namespace 先 `interrupt` 一次；
2. `resume` 至 `RESUMED`（`snapshot.next` 为空）；
3. 随后同一 namespace 再次 `interrupt` 一个新 proposal，应建立**新一轮** `WAITING` 状态，并保留 LangGraph checkpoint 历史。

这一行为对应文档 `agent-runtime.md` 明确承诺的「恢复完成后的新 proposal 会建立新一轮状态，同时保留 LangGraph checkpoint 历史」，但当前公共 seam 测试没有覆盖该边界。

- **Description**

`interrupt()` 对「已有 final state 且无 pending interrupt」的路径会跳过 `snapshot.next` 保护并重新 `invoke`。常规语义下可以工作，但由于 LangGraph 在同一 `thread_id` 上二次 invoke 的 behavior 依赖版本实现，缺少测试会让该契约在未来升级时静默回归。

- **Suggested Fix**

在 `test_action_runtime.py` 增加一个用例：

```python
def test_second_proposal_after_resume_starts_a_new_waiting_state():
    scope = namespace()
    runtime = ActionWorkflowRuntime(InMemorySaver())
    runtime.interrupt(scope, proposal(), "request-1")
    runtime.resume(scope, action_id=uuid4(), decision="APPROVE",
                   idempotency_key="key-1", request_id="resume-1")
    second = runtime.interrupt(scope, proposal(), "request-2")
    assert second.status == "WAITING"
    assert second.request_id == "request-2"
```

若在真实 PostgreSQL 版 `open_postgres_action_runtime` 上也跑一遍，可一并验证 checkpoint 历史保留。

---

## 上轮修复确认（无需修改项简述）

- **M1→N1**：`open_postgres_action_runtime` 已采用 `psycopg_pool.ConnectionPool`，每个连接 `autocommit=True`、`prepare_threshold=0`、`options="-c search_path=agent_checkpoint"`，`PostgresSaver` 接收 pool，并在生产路径使用。
- **S1→N2**：`AgentActionView` 便利构造器末尾传 `null`，强制所有生产构造走 `from` 工厂，杜绝「无 checkpoint 旧 Action 被误标 version=1」的隐患。
- **S2→N3**：`AgentActionService` 不再暴露 `confirm(UUID, UUID, actor)` 或旧 5 参确认组合；`Controller` 仅注入 `AgentActionWorkflowService`，测试改经 `approve`/`executeApproved` seam，公共决策必须经过 Resume。
- **S3→N4**：`HttpAgentServiceClient.resume` 分 `RestClientResponseException` 与通用 `RestClientException` 两类处理，404/409 精准转为 `ConflictException`，5xx/网络失败仍为 503，不暴露下游正文。
- **S4→N5**：流式 generator 的 `except ActionWorkflowConflict` 位于 `except ValueError` 之前，输出专门的冲突文案，语义与同步路径一致。
- **S5→N6**：新增跨 runtime 共享 PostgreSQL 的并发测试，8 线程 interrupt + 8 线程 resume，断言全部状态稳定，回归了 M1 修复。

---

## 主开发 (Codex) 评估回填区（预留）

| Review ID | 初判 | 复判（接受/驳回） | 说明与修改计划 |
| --- | --- | --- | --- |
| S1 | 建议修改 | 接受 | 已补充公开 seam 单元测试，验证同一 Namespace 恢复完成后新 proposal 建立第二轮 WAITING 并清空上轮 action。该建议不改变实现且不触发复审。 |

> 交接说明：本轮为只读审查，未执行命令、未修改文件或 Git 状态；依据提供的 diff 采样与显式上下文完成。S1 仅为测试面增强建议，不带阻断属性。
