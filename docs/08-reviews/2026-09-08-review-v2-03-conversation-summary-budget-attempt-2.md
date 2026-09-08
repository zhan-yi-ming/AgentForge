# Pi 代码审查报告：v2-03-conversation-summary-budget / Attempt 2

- 日期：2026-09-08
- 审查阶段：v2-03-conversation-summary-budget
- 审查对象：INDEX@69fb54d（基线：69fb54df3e8a4fe4577009d32f599d4978c30502）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- 状态：RESOLVED
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：v2-03-conversation-summary-budget / Attempt 2（Round 2/3）

- 审查阶段：v2-03-conversation-summary-budget
- 审查模式：Milestone Review
- 审查轮次：2 / 3
- 审查对象：INDEX@69fb54d（基线 69fb54df3e8a4fe4577009d32f599d4978c30502）
- 审查工具：Pi（DeepSeek V4-pro，只读）
- 上一轮结论：`NEEDS_FIX`（1 项 High MUST-1，4 项 Low SUG）

---

## 概述与总体结论

本轮为 V2-03 的第 2 轮 Milestone Review，重点核验 Attempt 1 发现的 MUST-1 及四项建议是否修复，并检查修复是否引入新问题。

结论：**通过，无需阻断性修改**。

- Attempt 1 的 MUST-1 已在 `api.py` 与 `context.py` 中修复：`commit_exchange` 现在必须匹配 `expected_session_generation`，同步路径的提交已进入 `except ValueError → 422` 契约，流式路径在提交冲突时输出安全 `error` 事件且不发送 `complete`。
- SUG-1（单条消息无上限）已通过每条历史副本的 `message_token_budget` 截断修复。
- SUG-2（Recent exchange 非原子裁剪）已通过 `recent.clear()` 整轮移除修复。
- SUG-3（summary budget 与 total budget 关系未校验）已通过 `model_validator` 修复。
- SUG-4（缺少提交阶段竞态回归）已新增同步与流式 `load → evict → cross-scope rebind → commit` 测试。
- 本轮 diff 未发现新的可确认 Bug、权限绕过、API 契约破坏、数据一致性或方向性问题，也未发现修复引入的回归证据。

以下仅保留 2 项 Low 建议，不阻塞交付。

---

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| SUG-1 | Low | `services/agent-service/src/agentforge_agent/api.py`、`config.py`、`context.py` | api.py `get_conversation_memory`；config.py `context_token_budget` | 每条历史消息上限直接复用总 Context Budget，配置上限放开时进程内 store 的峰值内存较高，虽仍有界但可考虑独立消息上限 |
| SUG-2 | Low | `services/agent-service/src/agentforge_agent/context.py` | `ConversationMemory._session_for_commit` | `expected_generation=None` 时回退到 `_session`，直接调用者在 session 被淘汰且同 ID 未重绑时会新建绑定并提交，缺少与 API 路径一致的保护语义 |

### 无需修改

无新增需要复核的疑似问题。

---

## 逐个 Issue 展开

### SUG-1 — 单条历史消息上限复用总 Context Budget，峰值内存偏大

- **Severity**: Low
- **File & Line**: `services/agent-service/src/agentforge_agent/api.py` `get_conversation_memory`；`config.py` `context_token_budget`；`context.py` `ConversationMemory.__init__`
- **Evidence**:
  ```python
  # api.py
  return ConversationMemory(
      recent_turns=settings.context_recent_turns,
      summary_token_budget=settings.context_summary_token_budget,
      max_sessions=settings.context_max_sessions,
      token_counter=TokenCounter(),
      message_token_budget=settings.context_token_budget,
  )

  # config.py
  context_token_budget: int = Field(default=8192, ge=1024, le=131072)
  ```
- **Description**: 上一轮 SUG-1 的修复方向正确，`commit_exchange` 会按 `message_token_budget` 截断 user/assistant 历史副本。但该上限等于总 Context Budget，且 `context_token_budget` 最大可配置到 131072 字节。若同时将 `context_max_sessions` 开到 10000、`context_recent_turns` 开到 20，理论峰值内存可按“消息数 × 单条上限”放大，属于有界但偏宽。默认配置（8192 / 4 轮 / 1000 session）不会造成问题。
- **Suggested Fix**: 后续可增加独立的 `context_message_token_budget`（如默认 2048），或在文档和配置注释中明确单条消息上限与总预算共享同一上限的意图。当前实现满足 V2-03“有界”验收，无需本轮阻塞。

### SUG-2 — `expected_generation=None` 绕过淘汰保护

- **Severity**: Low
- **File & Line**: `services/agent-service/src/agentforge_agent/context.py` `_session_for_commit`
- **Evidence**:
  ```python
  def _session_for_commit(
      self,
      conversation_id: UUID,
      project_id: UUID,
      user_id: UUID,
      expected_generation: UUID | None,
  ) -> _ConversationSession:
      if expected_generation is None:
          return self._session(conversation_id, project_id, user_id)
      ...
  ```
- **Description**: API 两条入口总是从 `bundle.conversation.session_generation` 传入非空 generation，因此生产路径不会走到该分支。但 `ConversationMemory` 是测试和后续节点直接依赖的公共 seam；若调用方省略 generation，在 session 已被 LRU 淘汰且同 ID 尚未重绑时，`_session` 会新建 session 并成功提交，实际上绕过了 Attempt 1 的竞态保护语义。当前不构成安全/正确性阻断，因为授权入口未使用该路径。
- **Suggested Fix**: 将 `commit_exchange` 的 `expected_session_generation` 设为必填，或对 `None` 路径在文档中显式声明其为“无保护简化用法”。若保持可选，建议在 `_session_for_commit` 中对缺失 generation 且 session 不存在的场景也抛错，保持跨入口一致性。

---

## Attempt 1 问题修复核验

| 上轮 ID | 严重级别 | 结论 | 核验依据 |
| --- | --- | --- | --- |
| MUST-1 | High | 已修复 | `api.py` 同步 `commit_exchange` 位于内层 try 内，外层 `except ValueError` 统一转 422；流式在 `events()` 内新增 `except ValueError` 输出安全 error，不先 `complete`。`context.py` 新增 `session_generation` 并在 `_session_for_commit` 强制匹配。 |
| SUG-1 | Low | 已修复 | `ConversationMemory` 增加 `message_token_budget`，`commit_exchange` 对两条消息在入库前 `truncate_text`。 |
| SUG-2 | Low | 已修复 | `PromptComposer.compose` 中 `if recent: recent.clear()` 以整轮为单位清除，不再产生 assistant-only/截断半轮。 |
| SUG-3 | Low | 已修复 | `config.py` 新增 `@model_validator(mode="after")`，拒绝 `summary budget > total budget`。 |
| SUG-4 | Low | 已修复 | `test_api.py` 新增同步/流式两条 `load → evict → cross-scope rebind → commit` 回归测试，断言同步 422、流式 error 且不提交。 |

---

## 主开发 (Codex) 评估回填区预留

| 发现 ID | Codex 评估 | 回填状态 |
| --- | --- | --- |
| SUG-1 | 下阶段处理：默认 8192/4/1000 已有明确上界；只有操作者同时主动放大三个配置才产生较高峰值。独立消息预算会增加当前节点配置面，留给 V2-04/资源策略统一评估。 | 不阻塞，已记录 |
| SUG-2 | 下阶段处理：两条生产 API 总是传入非空 generation；`None` 仅供 store 直接初始化/测试使用，不经过授权入口。V2-04 收紧公开 memory seam 时改为强制句柄，避免本节点在 PASS 后重塑接口。 | 不阻塞，已记录 |

> 本报告不包含必须修改项。两个 Low 建议均不阻塞 V2-03 交付。

## Codex 最终核验

- Codex 确认 Attempt 2 对 MUST-1 的验证与实际 diff 一致；同步竞态返回 422，流式竞态返回安全 error 且不 complete，跨 scope session 无写入。
- 两项 Low 建议均不构成当前生产入口缺陷、安全绕过或验收失败，按仓库规则不触发 Attempt 3。
- 最终结论：`PASS`，报告归档状态 `RESOLVED`。
