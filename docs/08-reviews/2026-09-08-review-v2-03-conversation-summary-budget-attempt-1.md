# Pi 代码审查报告：v2-03-conversation-summary-budget / Attempt 1

- 日期：2026-09-08
- 审查阶段：v2-03-conversation-summary-budget
- 审查对象：INDEX@69fb54d（基线：69fb54df3e8a4fe4577009d32f599d4978c30502）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- 状态：RESOLVED
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

## 概述与总体结论

本次为 V2-03 Conversation Summary + Token Budget 的 Milestone Review（第 1/3 轮）。整体实现与 V2-03 节点目标、ADR-0017 及既有 HTTP 契约基本一致：进程内有界 `ConversationMemory`、LRU 淘汰、Recent/Summary 分离、Tool/Retrieved 排除、作用域绑定、Token Budget 后置校验、同步/流式成功提交与失败不提交均有代码与测试覆盖。

但发现 1 个必须修改的并发/API 契约问题：同步 `/internal/v1/chat` 中 `commit_exchange` 被放在异常捕获区与 `finally` 之外，`ValueError` 未转为 422；结合 LRU 淘汰与并发重绑，存在可触发 500 的竞态路径。建议修复后再交付。其余为边界加固与文档清晰度建议，不阻塞。

## 详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| MUST-1 | High | `services/agent-service/src/agentforge_agent/api.py`、`context.py` | `api.py` 约 139-146，`context.py` `_session` 约 109-123 | 同步 chat 的 `commit_exchange` 在 `finally` 与 `except ValueError` 之外，LRU 淘汰并发重绑时 `ValueError` 直接 500，破坏 422 契约并可能丢弃已生成回答 |
| SUG-1 | Low | `services/agent-service/src/agentforge_agent/context.py` | `ConversationMemory` 构造与 `commit_exchange` | 单条 user/assistant 消息长度未设上限；session/摘要虽有限，但单条巨型消息仍可能放大小内存占用 |
| SUG-2 | Low | `services/agent-service/src/agentforge_agent/llm.py` | `PromptComposer.compose` 循环内 recent 分支 | 预算极端时对最后一轮 exchange 只删/截一条消息，可能产生不对称 Recent 结构；建议整轮删除 |
| SUG-3 | Low | `services/agent-service/src/agentforge_agent/config.py` | 新增四项配置字段 | 未校验 `context_summary_token_budget <= context_token_budget`，配置不一致时只能在 Prompt 组装期被兜底裁剪 |
| SUG-4 | Low | `services/agent-service/tests/test_api.py`、`test_context.py` | 新增测试区 | 缺少“提交时 scope 不匹配 + LRU 淘汰并发重绑”回归测试，现有作用域测试只覆盖 load 阶段 |

## 逐个 Issue 展开

### MUST-1 — 同步 chat 提交交换未捕获 ValueError，存在 LRU 淘汰并发重绑 500

- **Severity**: High
- **File & Line**: `services/agent-service/src/agentforge_agent/api.py` 约 139-146；`services/agent-service/src/agentforge_agent/context.py` `_session` 约 109-123
- **Evidence**:
  ```python
  # api.py
  finally:
      agent_observation.end()
      request_observation.end()
  bundle = state["context_bundle"]
  conversation_memory.commit_exchange(
      bundle.conversation.conversation_id,
      bundle.project.project_id,
      bundle.project.user_id,
      bundle.working.message,
      state["answer"],
  )
  ```
  ```python
  # context.py
  elif session.project_id != project_id or session.user_id != user_id:
      raise ValueError("conversation scope does not match project and user")
  ```
- **Description**: `load` 在 graph `prepare` 阶段完成并在锁内读取快照；`commit_exchange` 在请求末尾重新按 `conversation_id` 从 `OrderedDict` 解析 session。若原 session 在 load 后因 `max_sessions` LRU 被淘汰，且同一 `conversation_id` 在 commit 前被另一 project/user 重绑，`commit_exchange` 会抛出 `ValueError`。同步路径中该调用位于 `except ValueError` 和 `finally` 之后，异常未被翻译为 HTTP 422，导致未处理异常/500，并可能丢弃一次已成功生成但仍未返回的回答。即便不考虑并发，`commit_exchange` 明确抛出的 `ValueError` 在同步路径也没有契约化处理。
- **Suggested Fix**:
  - 最短修复：将同步 `commit_exchange` 包进 `try/except ValueError`，与 load 阶段一致转成 `HTTPException(status_code=422, detail=str(exc))`。
  - 更稳修复：`ConversationMemory.load` 返回包含 session 句柄/代数标识的快照，`commit_exchange` 使用该句柄提交，避免提交前 session 被淘汰或重绑；若匹配失败按 422 返回。
  - 建议补一条并发回归：`max_sessions=1` 下 load 后制造淘汰与不同 scope 的重绑，断言同步入口返回 422 而非 500，流式入口输出 error 且不提交。

### SUG-1 — 单条消息长度未设上限

- **Severity**: Low
- **File & Line**: `services/agent-service/src/agentforge_agent/context.py` `ConversationMemory.__init__` / `commit_exchange`
- **Evidence**: `commit_exchange` 只 `strip()` 后存入 `recent_messages`，未检查 `len(content.encode("utf-8"))`；`recent_turns/max_sessions` 限制数量但不限制单条大小。
- **Description**: 1000 个 session × 4 轮 × 未限制单条长度，理论上单条超大消息即可放大内存占用。V2-03 的 bounded 语义应覆盖单条消息尺寸或总字节上限。
- **Suggested Fix**: 增加 `max_message_bytes` 或复用 `TokenCounter`，对进入 recent/summary 的单条内容设置上限并测试超限行为。

### SUG-2 — 预算极端时可能破坏 Recent exchange 完整性

- **Severity**: Low
- **File & Line**: `services/agent-service/src/agentforge_agent/llm.py` `PromptComposer.compose` recent 分支
- **Evidence**:
  ```python
  if len(recent) > 2:
      del recent[:2]
      continue
  if recent:
      message = recent[0]
      shortened = self._shrink(message.content, excess)
      ...
  ```
- **Description**: 当 summary 已移除且 recent 只剩最后一轮两条消息时，该逻辑只缩/删第一条 user 消息，可能产生“只有 assistant 或截断 user + 完整 assistant”的不对称历史。文档强调 Recent Messages 以完整 exchange 为单位，建议在预算裁剪时也保持整轮原子性。
- **Suggested Fix**: 当需要继续裁剪 recent 时按轮次整体删除一对 `[user, assistant]`，而不是先单条截断；仅在所有轮次删除后仍未达标才进入 retrieved 保护流程。

### SUG-3 — 未校验 summary budget 与 token budget 的相对关系

- **Severity**: Low
- **File & Line**: `services/agent-service/src/agentforge_agent/config.py` 新增配置字段
- **Evidence**: `context_summary_token_budget` 最大 8192，`context_token_budget` 最小 1024，二者无跨字段校验。
- **Description**: 若用户将 summary budget 配成大于总输入 budget，配置仍可启动，只能在 PromptComposer 运行时剪裁，且错误表意不清晰。加跨字段校验可提前暴露配置错误。
- **Suggested Fix**: 增加 `@model_validator` 检查 `context_summary_token_budget <= context_token_budget` 或至少小于开销后可用空间。

### SUG-4 — 缺少提交阶段作用域与 LRU 竞态回归测试

- **Severity**: Low
- **File & Line**: `services/agent-service/tests/test_api.py`、`test_context.py`
- **Evidence**: 现有 `test_chat_rejects_conversation_id_reused_by_another_project` 只覆盖 load/422；`test_conversation_memory_evicts_least_recently_used_session` 只覆盖普通淘汰，不覆盖 load→evict→rebind→commit 的顺序。
- **Description**: MUST-1 所依赖的竞态路径没有测试证据，修复后应补测试防止回归。
- **Suggested Fix**: 增加并发或确定性顺序测试，模拟 `load` 后会话被 LRU 淘汰并由不同 project/user 重绑，断言同步入口返回 422、流式入口 error 且历史不提交。

## 主开发 (Codex) 评估回填区预留

| 发现 ID | Codex 评估 | 回填状态 |
| --- | --- | --- |
| MUST-1 | 采纳并修复：load 快照新增内部 session generation；commit 校验 conversationId/scope/generation。同步提交移入既有 422 捕获区，流式提交冲突输出安全 error 且不 complete。 | 已修复并验证 |
| SUG-1 | 采纳：每条历史副本复用总 Context Budget 作为存储上限，当前 API 回答不截断。 | 已修复并验证 |
| SUG-2 | 采纳：最后一轮 Recent 超预算时整对移除，不再形成 assistant-only 历史。 | 已修复并验证 |
| SUG-3 | 采纳：Settings 增加 summary budget 不得超过 total context budget 的启动期校验。 | 已修复并验证 |
| SUG-4 | 采纳：增加同步与流式 `load → evict → cross-scope rebind → commit` 确定性回归。 | 已修复并验证 |

> Pi 原始结论：存在 1 个必须修改的并发/契约问题，按规则判定为 `NEEDS_FIX`。

## Codex 修复后核验

- 修复红灯：在 `services/agent-service` 执行四个指定回归，修复前 4 failed、退出 1，分别复现同步 500、流式异常中断、Recent 非原子裁剪和预算配置关系未校验。
- 修复绿灯：相同四个指定回归修复后 4 passed、退出 0；三份相关测试文件随后 49 passed、退出 0。
- Agent 全量：`.\.venv\Scripts\python.exe -m pytest -q --cache-clear` 退出 0，66 passed、0 failed/skipped；真实 pgvector 通过，保留 5 条已记录 warning。
- Java → Python：最终修复源码启动真实 uvicorn 后执行 `.\mvnw.cmd -Dtest=AgentServiceHttpContractIntegrationTest test`，退出 0，7 tests、0 failures/errors/skipped；进程和专用日志已清理。
- 处理结论：MUST-1 及四项建议均已处理；因存在已证实阻断项，按规则执行 Pi Attempt 2，而不是以本节自行宣称通过。
