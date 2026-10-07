# Pi 代码审查报告：action-resume-history-replay / Attempt 1

- 日期：2026-10-07
- 审查阶段：action-resume-history-replay
- 审查对象：INDEX@ab94cb8（基线：ab94cb8c7d2f2f43b5a652e21d1b1f8fb764452b）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# AgentForge Milestone 只读审查报告

- 审查阶段：action-resume-history-replay
- 审查模式：Milestone（轮次 1 / 3）
- 审查目标：Commit INDEX@ab94cb8（基线 ab94cb8c7d2f2f43b5a652e21d1b1f8fb764452b）
- 审查范围：本次 10 个文件改动 + 显式提供的 `AgentActionWorkflowService.java`、`action_runtime.py`、`docs/03-features/agent-runtime.md` 上下文
- 审查员：Pi（DeepSeek V4.1 Flash，只读）

---

## 一、概述与总体结论

本次变更修复了一个经真实 PostgreSQL 与内部 HTTP 复现的 P1 缺陷：Java 已提交 APPROVED、Python 已提交 RESUMED，但响应丢失或 Java 业务事务失败后，同会话下一轮 Chat 覆盖最新 checkpoint，导致旧审批以原 key 重试时被 `workflow_id` 不匹配永久阻断。

实现方式克制且符合既有架构边界：
- 仅在 `resume()` 中当前最新轮 `workflow_id` 与请求不一致（且请求 ID 非空）时，转入 `_replay_historical_resume()`；
- 在同一完整 Namespace 的 LangGraph checkpoint 历史中倒序分页（`limit=64`、`before` 游标）查找该 workflow 已终结的 `RESUMED` 事实；
- 复用既有 `_require_supported` / `_require_workflow` / `_require_replay` 三重校验（schema、Namespace、workflow、action、decision、key），失败关闭；
- **绝不调用** 历史 `invoke`/`update_state`，不消费、不改写当前等待轮；业务授权与写入仍全部留在 Java。
- 修复未新增端点、字段、迁移或权限；v1 无 workflow ID 仍只允许当前轮恢复，符合 ADR-0037/0043 与 V1 边界（未引入任何 V2/V3 组件）。

核心正确性判断：
- 分支放置正确：先 `_require_supported`，再历史重放，再 `_require_workflow`；v1 当前快照携带 workflow ID 时仍因历史无匹配而冲突，保持失败关闭。
- 并发安全：整段逻辑仍在 `_workflow_lock(thread_key)`（PostgreSQL advisory xact lock）内，历史重放与当前轮不会被并发交错。
- 权限/越权：历史查询的物理 thread 由完整 Namespace 派生，跨 Project/User 无法枚举；NSD 校验在命中候选快照时立即执行。
- 契约一致性：历史重放返回原 `requestId`，与 `docs/04-api/agent-service.md`、ADR-0043 描述一致；Java `requireMatchingResume` 仅校验 conversation/workflow/action/decision/status，不受影响。
- 测试有效性：新增参数化身份/决定/key 篡改、ABORTED 轮、25 轮长历史分页、真实 PostgreSQL 跨重启重放，以及 Java HTTP 跨进程契约（含错误 key 409、新轮独立拒绝）；覆盖了主路径与失败路径。

结论：**通过。未发现必须修改项。** 仅有文档一致性层面的建议修改，不阻塞交付。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号(约) | 核心问题 |
|----|----------|------|----------|----------|
| S1 | 建议修改 | docs/02-architecture/decisions/README.md | 3 | ADR-0043 被放在文档顶部正文前，未进入下方“索引”列表，与既有 ADR 索引约定不一致 |
| S2 | 建议修改 | docs/07-changes/2026-10-06-action-resume-history-replay.md | 5, 25+ | 文件日期为 2026-10-06，回填证据日期为 2026-10-07，日期表述不一致 |
| N1 | 无需修改 | services/agent-service/src/agentforge_agent/action_runtime.py | 145-170 | 历史扫描无总量上限，成本随会话历史增长（ADR-0043 已显式列为已知限制，非本次回归） |
| N2 | 无需修改 | services/agent-service/src/agentforge_agent/action_runtime.py | 160 | 重放返回原 `requestId` 而非本次请求 ID（契约有意为之，文档已声明） |
| N3 | 无需修改 | services/agent-service/tests/test_action_runtime.py | ~152 | 旧测试改用不同 `action_id` 以维持冲突语义（与新重放语义一致，非掩盖回归） |

“必须修改”组：无。

---

## 三、逐个 Issue 展开

### S1（建议修改）ADR 索引位置不一致
- Severity：Low（文档一致性）
- File & Line：`docs/02-architecture/decisions/README.md` 约第 3 行
- Evidence：
  ```diff
   # 架构决策记录

  +- [ADR-0043](ADR-0043-historical-action-resume-replay.md)：已恢复审批轮次的历史事实只读重放。
  +
   ADR 保存影响长期结构的决定。...
   ...
   ## 索引
   - `ADR-0001-documentation-first.md`：...
  ```
- Description：其它所有 ADR 均位于“## 索引”列表内；ADR-0043 被加到标题与说明段落之间，导致同一编号只出现在正文顶部、不出现在索引列表中，按索引检索会遗漏。
- Suggested Fix：删除正文段落上方的单独条目，改在“## 索引”列表末尾（或按编号顺序）追加：
  ```markdown
  - `ADR-0043-historical-action-resume-replay.md`：已恢复审批轮次的历史事实只读重放。
  ```

### S2（建议修改）变更记录日期不一致
- Severity：Low（文档一致性）
- File & Line：`docs/07-changes/2026-10-06-action-resume-history-replay.md` 第 4-5 行与“验证回填”小节
- Evidence：
  ```markdown
  - 日期：2026-10-06
  ...
  - 2026-10-06 初次执行被自动审批服务额度中断；... 2026-10-07 用户明确继续 ...
  ```
- Description：文件名/头部日期为 2026-10-06，而实际验证回填跨到 2026-10-07，读者难以判断证据归属轮次。
- Suggested Fix：在头部补充“更新日期：2026-10-07”，或将回填段落明确标注为“2026-10-07 续审回填”，保持时间线可读。

### N1（无需修改）历史扫描成本
- File & Line：`action_runtime.py` `_replay_historical_resume`（约 145-170 行）
- Evidence：
  ```python
  before = None
  while True:
      page = list(self._graph.get_state_history(config, before=before, limit=64))
      ...
      before = page[-1].config
  ```
- Description：分页限定单页内存（≤64），但总查找量随历史线性增长。该权衡已在 ADR-0043 与 `agent-runtime.md`“已知限制”显式记录（“倒序分页查找成本随历史增长……未来清理必须保证未终结 Java Approval 的恢复事实可达”）。
- 结论：属于已声明的设计取舍，无本次回归证据，不阻塞。

### N2（无需修改）重放返回原 requestId
- File & Line：`action_runtime.py` `_view(values, namespace.thread_id)`（约 160 行）
- Evidence：`return _view(values, namespace.thread_id)`，`_view` 取 `values["request_id"]`。
- Description：返回历史事实的原始 requestId，而非本次重试的 requestId。`docs/04-api/agent-service.md` 与 ADR-0043 均明确“返回相同事实及原 requestId”；Java `requireMatchingResume` 不依赖该字段，契约自洽。
- 结论：有意行为，非缺陷。

### N3（无需修改）旧测试语义调整
- File & Line：`services/agent-service/tests/test_action_runtime.py` 约 152 行
- Evidence：
  ```diff
       with pytest.raises(ActionWorkflowConflict):
           runtime.resume(
               scope,
               workflow_id=first_waiting.workflow_id,
  -            action_id=first_action,
  +            action_id=uuid4(),
               decision="REJECT",
  ```
- Description：原测试用旧 workflow + 旧 action/key 断言“不能恢复新等待轮”。新语义下这种“完整身份一致的旧轮重放”已改为只读返回历史事实，因此改用不同 action_id 才能继续验证“不匹配即冲突”。同时新增测试断言当前轮 `interrupt` 结果不变，弥补了原测试对“不消费当前轮”的验证。
- 结论：合理，不掩盖回归。

---

## 四、主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 处理方式 / 说明 | 关联提交 |
|---------|----------|------------------|----------|
| S1 | 接受为非阻塞建议 | 仅记录；ADR 链接有效，索引位置不影响行为，按本次只修阻塞项范围保留 | 本次修复 |
| S2 | 不采纳为缺陷 | 文件日期为立项日；记录已明确 10-07 续作和验证，不改写原时间线 | 本次修复 |
| N1 | 确认 | 每页 64 条限制内存；总查询成本随历史增长，ADR 已声明 | 本次修复 |
| N2 | 确认 | 返回已提交事实的原 requestId；Java 不要求等于重试 requestId，跨进程通过 | 本次修复 |
| N3 | 确认 | 正确身份旧轮可重放；错误 action ID 仍拒绝，新轮隔离有额外正向/负向覆盖 | 本次修复 |

---

## 五、审查边界与证据说明

1. 本轮为**只读审查**，未执行任何命令、未修改文件、未改变 Git 状态；所有运行结论均引用变更记录（`docs/07-changes/2026-10-06-action-resume-history-replay.md`）中 Codex 提供的测试回填，不将其转述为 Pi 自身执行结果。
2. 显式上下文未包含 `HttpAgentServiceClient` 源码，故未对 `AgentResumeResult` 的 `requestId` 字段装配方式做独立验证；仅依据契约测试通过记录与 `requireMatchingResume` 的字段使用范围判断契约自洽，无阻塞证据。
3. 未发现引入 V2/V3 组件（Neo4j/GraphRAG、Langfuse 完整 Trace、LiteLLM、MCP）；修复严格限定在既有 LangGraph checkpoint 与 Java 业务权威边界内。
4. 门槛结论：无“必须修改”项，PASS；建议在提交前完成 S1/S2 两处文档一致性修订。
