# Pi 代码审查报告：workspace-preview-history-agent-resilience / Attempt 1

- 日期：2026-09-07
- 审查阶段：workspace-preview-history-agent-resilience
- 审查对象：INDEX@bf28da5（基线：bf28da52d3cceb56543dea58dd175fec77e97ddd）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# 审查报告

**审查模式**：Diff Review（只读，未运行任何命令、测试或 Git 变更）
**审查阶段**：workspace-preview-history-agent-resilience
**审查目标**：INDEX@bf28da5
**本轮结论**：通过。未发现可证实的 Bug、安全/权限问题、API 契约冲突或数据一致性缺陷；发现 3 项低风险建议，不阻塞交付。

---

## 概述与总体结论

本次变更目标一致：统一 Markdown 边界剥离逻辑、澄清“执行任务”语义、在前端内存中保留当前项目的对话折叠历史，并为 Agent 模型客户端配置一次有限重试。实现与文档描述相符：

- `normalizeMarkdownContent` 只剥离一个包裹全文的 `markdown` / `md` 围栏，正文内部代码块保留；react-markdown 仍默认不渲染原始 HTML，未引入 XSS 面。
- Chat 历史状态由 `chatHistory` + `expandedChatIds` 管理，项目切换清空；`activeProjectId` + `streamAbort` 复用既有跨项目竞态防护。
- `max_retries=1` 仅影响模型客户端瞬时失败重试，不与 HITL、配额、SSE 契约冲突，且有测试断言覆盖。
- 新增测试覆盖历史展开/收起、任务说明、围栏剥离和重试参数，测试证据与补丁属实一致。

依据提供的 diff 和文档测试记录，未发现进入“必须修改”的问题。

---

## 详细发现清单

| ID | 严重级别 | 分组 | 文件 | 行号/位置 | 核心问题 |
|---|---|---|---|---|---|
| S1 | 低 | 建议修改 | `apps/web/src/App.tsx` | `sendChat` 的 `finally` 块 | 旧流被替换后，其 `finally` 仍会无条件 `setStreaming(false)`，若两个流重叠可能提前清除新流的生成中状态 |
| S2 | 中 | 建议修改 | `apps/web/src/App.tsx` | `sendChat` 起始处 `setPendingAction(undefined)` | 对话历史已保留旧问答，但旧回答中的待确认动作仍在新提问时被全局清除，界面可能丢失待确认动作 |
| S3 | 低 | 建议修改 | `apps/web/src/MarkdownPreview.tsx` | `normalizeMarkdownContent` 正则 | 未允许外层围栏结束行后跟空格/制表符，部分合法 CommonMark 围栏不会被剥离 |

---

## 逐条 Issue 展开

### S1 — `finally` 中无条件 `setStreaming(false)` 可能干扰新流

- **Severity**：建议修改（低）
- **File & Line**：`apps/web/src/App.tsx`，`sendChat` 的 `finally`
- **Evidence**：
  ```ts
  finally {
    if (streamAbort.current === controller) streamAbort.current = undefined;
    setStreaming(false);
  }
  ```
- **Description**：
  正常情况下，发送按钮在 `streaming` 为真时禁用，用户很难产生重叠流。但 `sendChat` 本身没有 `streaming` 守卫，而 `finally` 中 `setStreaming(false)` 又没有与“当前流是否为最新流”绑定。一旦通过快速双击、回车绕行或程序化提交形成新旧流重叠，旧流的 `finally` 会在新流仍在输出时提前关闭全局 `streaming`，使发送按钮提前恢复可用。这与本次新增的多条目流式历史状态叠加后，风险面略增。
- **Suggested Fix**：
  ```ts
  finally {
    if (streamAbort.current === controller) {
      streamAbort.current = undefined;
      setStreaming(false);
    }
  }
  ```

### S2 — 历史保留旧回答，但旧回答对应的待确认动作被新提问清除

- **Severity**：建议修改（中）
- **File & Line**：`apps/web/src/App.tsx`，`sendChat` 起始处的 `setPendingAction(undefined)`
- **Evidence**：
  ```ts
  setStreaming(true); setError(""); setPendingAction(undefined);
  setChatHistory((current) => [...current, { id: historyId, question, answer: "", sources: [] }]);
  ```
- **Description**：
  本次已将问答、来源保留在历史条目中，但 `pendingAction` 仍是全局单一状态。若用户收到一个需要确认的动作后，没有先确认/拒绝而是发起新提问，`setPendingAction(undefined)` 会立刻清掉该动作卡片，使已经生成、可能仍处于 PENDING 状态的服务端动作在界面上无法找回。这会让当前项目管理内存中的历史语义不一致：旧回答可见，但旧回答的 HITL 动作不可恢复。建议将 `pendingAction` 归入对应历史条目，或至少在存在待确认动作时禁止/提示用户不能直接发起新提问。
- **Suggested Fix**：
  将 `ChatHistoryItem` 扩展为包含 `pendingAction?: AgentAction`，在 `result.pendingAction` 到达时写入对应条目，并在该条目内渲染动作卡片；或在 `sendChat` 入口若已有 `pendingAction` 则先提示用户处理。

### S3 — 外层围栏结束行未允许尾随空格

- **Severity**：建议修改（低）
- **File & Line**：`apps/web/src/MarkdownPreview.tsx`，`normalizeMarkdownContent`
- **Evidence**：
  ```ts
  const fenced = trimmed.match(/^```(?:markdown|md)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/i);
  ```
- **Description**：
  CommonMark 允许闭合围栏符后跟空格/制表符。当前正则闭合部分为 `\r?\n```$`，若历史 Wiki 的外层结束行是 ` ```   `（尾随空格），不会被识别为外层围栏，整篇内容仍会渲染成深色代码块，与本次目标“历史 Wiki 与实时草稿保持一致”存在边缘不一致。
- **Suggested Fix**：
  ```ts
  const fenced = trimmed.match(/^```(?:markdown|md)?[ \t]*\r?\n([\s\S]*?)\r?\n```[ \t]*$/i);
  ```

---

## 无需修改（已核查项）

- `max_retries=1` 与文档“最多额外重试一次”一致，仅影响模型调用，不改变 API 契约、HITL 或配额；`test_llm.py` 的断言覆盖了该参数。
- Markdown 剥离后仍经 react-markdown 渲染，raw HTML 默认不解析，未扩大 XSS 面；链接 `target="_blank" rel="noreferrer noopener"` 保持不变。
- 项目切换清空 `chatHistory`、`expandedChatIds`、`conversationId` 与 `pendingAction`，继续沿用 `activeProjectId` ref 防迟到写入，逻辑正确。
- 新增测试与本次修改目标一一对应，测试方式通过 DOM 与网络 client 公共接口观察行为，未断言私有 state。

---

## 主开发 (Codex) 评估回填区

| Issue ID | Codex 结论 | 处理方式 | 备注 |
|---|---|---|---|
| S1 | 不阻塞，本次不改 | 记录后续 | UI 在 `streaming` 时禁用发送，当前公共交互无法重入；程序化并发守卫可在后续流状态整理中统一处理。 |
| S2 | 不属于本次历史展示契约 | 记录后续 | 将 pending action 持久到历史或禁止继续提问会改变 HITL 交互语义，需要独立设计，不能随本次 UI 热修静默加入。 |
| S3 | 当前实现已覆盖 | 无需修改 | 正则前先执行 `content.trim()`，结束围栏之后的空格或制表符已被移除，现有测试目标成立。 |

---

**最终结论**：`REVIEW_RESULT: PASS`
本次变更可交付；上述 3 项均为建议修改，不构成阻塞。
