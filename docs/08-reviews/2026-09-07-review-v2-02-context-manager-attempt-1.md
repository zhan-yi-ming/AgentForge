# Pi 代码审查报告：v2-02-context-manager / Attempt 1

- 日期：2026-09-07
- 审查阶段：v2-02-context-manager
- 审查对象：INDEX@17cb7b6（基线：17cb7b6bd731c4828662548fa890ee3de899b741）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge V2-02 Context Manager Milestone Review（Round 1/3）

## 概述与总体结论

本次审查覆盖 V2-02 Context Manager 数据结构，以及同批交付的 Web AI 整理流式预览与 Wiki 草稿安全修复。
后端 `ContextBundle` 设计、`graph.py` 共享 Context 节点、`llm.py` prompt 组合与 `api.py` 响应映射整体正确，LangGraph State 与 Context 职责分离清晰，HTTP 契约未变。测试迁移与新增覆盖与 `docs/05-development/testing-strategy.md` 中 V2-02 seam 基本一致。
未发现可确认的必须修改级 Bug、安全/越权、数据一致性或契约冲突。以下为少量建议，不阻塞交付。

---

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Low | `apps/web/src/App.tsx` / `docs/07-changes/2026-09-07-v2-02-...` | 约 25-29、146-153 | V2-02 Context Manager 与 V1 Web 修复混合在同一 Node 交付，边界与回滚粒度不够清晰 |
| S2 | Low | `apps/web/src/App.tsx` | `applyFormattedText` 约 146-153 | 应用整理结果后 `formatComplete`/`formattedText` 未清理，重复点击可能再次清空当前 Wiki 选择并导致重复 create |
| S3 | Low | `apps/web/src/App.tsx` | `formattedWikiTitle` 约 27-29 | 无真实 H1 时，标题提取可能命中代码块内的 `#` 行，而不是使用默认标题 |

### 无需修改

未发现其他需要列出的问题。

---

## 逐项 Issue 展开

### S1 — V2-02 Node 边界内混合了 V1 Web 修复

- **Severity**：Low
- **File & Line**：`apps/web/src/App.tsx`；`docs/07-changes/2026-09-07-v2-02-context-manager-and-wiki-draft-safety.md`
- **Evidence**：
  - V2-02 Roadmap Scope 仅包含 `ContextBundle`、State/Context 分离与生产/消费者职责。
  - 本次 diff 同时包含 AI 整理流式预览、Wiki 新页面草稿/title/create API 等 V1 用户可见行为修复。
- **Description**：该混合交付在变更记录中已明确说明，且用户可能已认可修复目标，因此不作为阻塞项；但按节点协议“每个 Node 的 diff 必须可理解、可测试、可 Review、可回滚”，建议在提交层或变更记录中更明确区分两类改动，便于回滚和节点边界审计。
- **Suggested Fix**：将 V1 Web 安全修复独立为单独提交/变更记录，或在本变更记录中显式标注“用户授权附带修复，不构成 V2-02 Scope 新增”。

### S2 — 应用整理结果后未清理 `formatComplete`/`formattedText`

- **Severity**：Low
- **File & Line**：`apps/web/src/App.tsx` `applyFormattedText`，约 146-153 行
- **Evidence**：
  ```ts
  function applyFormattedText() {
    if (!formatComplete) return;
    setWikiId("");
    setWikiVersion(0);
    setWikiTitle(formattedWikiTitle(formattedText));
    setWikiContent(formattedText);
    setWikiFeedback("已应用到 Wiki 草稿，请确认后保存");
    wikiPanel.current?.scrollIntoView?.({ behavior: "smooth", block: "start" });
  }
  ```
- **Description**：应用完成后 `formatComplete` 与 `formattedText` 未重置，按钮仍可再次点击。保存新建页面后若再次点击，先清空新页面 `wikiId/version`，下次保存会再次走 create，可能产生重复 Wiki；虽然不是越权或数据损坏，但存在交互陷阱。
- **Suggested Fix**：应用成功后执行 `setFormatComplete(false)`（或同时保留结果但禁用按钮），避免重复进入新建页状态。

### S3 — `formattedWikiTitle` 可能从代码块中提取伪 H1

- **Severity**：Low
- **File & Line**：`apps/web/src/App.tsx` `formattedWikiTitle`，约 27-29 行
- **Evidence**：
  ```ts
  function formattedWikiTitle(content: string) {
    const heading = normalizeMarkdownContent(content).match(/^#[ \t]+(.+?)[ \t]*$/m)?.[1]?.trim();
    return heading?.slice(0, 200) || DEFAULT_FORMATTED_WIKI_TITLE;
  }
  ```
- **Description**：正则使用 multiline，会匹配语义上处于 fenced code block 内的 `# comment`。当模型未输出真实一级标题时，页面标题可能取到代码注释而非默认值。
- **Suggested Fix**：提取 H1 时跳过 fenced code block 区域，或仅在非 fence 行范围内匹配；保持无 H1 时回退到 `AI 整理文档`。

---

## 主开发 (Codex) 评估回填区

| Issue ID | Codex 是否成立 | 原因/处理 | 是否修改 | 相关补测 |
| --- | --- | --- | --- | --- |
| S1 | 不成立为需修改项 | 用户在 Node Start Gate 前后明确要求两个 Web 缺陷与 V2-02 同批处理；范围、风险和测试已分别记录，且未进入后续 Node。 | 否 | 不适用 |
| S2 | 成立 | 保存后重复应用会清空新页面 identity 并导致再次 create，属于可复现的数据一致性陷阱。 | 是 | 应用后按钮禁用，并保持已生成结果可见 |
| S3 | 成立 | multiline 正则确会把 fenced code block 内 `# comment` 当作页面标题，偏离无 H1 时使用默认 title 的目标。 | 是 | 无真实 H1、仅代码块伪 H1 时回退默认标题 |

---

## 结论

本轮 Milestone Review 结论：**通过**。
未发现必须修改的问题。建议 S1-S3 可在不阻塞当前 Node 提交的前提下，由 Codex 决定是否在本轮或后续统一处理。
