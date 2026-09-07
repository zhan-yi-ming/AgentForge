# Pi 代码审查报告：ai-formatting-main-integration / Attempt 1

- 日期：2026-09-07
- 审查阶段：ai-formatting-main-integration
- 审查对象：INDEX@ca4ecfc（基线：ca4ecfcbd41baee83220313ec11400ed79fe87e0）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- 处理状态：RESOLVED（Codex 已按 TDD 修复 M1，并采纳同一状态边界内的 S1–S4；等待 Attempt 2 只读复审）
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- 审查阶段：ai-formatting-main-integration
- 审查模式：Diff
- 审查轮次：1 / 3
- 审查对象：INDEX@ca4ecfc（ca4ecfcbd41baee83220313ec11400ed79fe87e0 .. INDEX@ca4ecfc）
- 审查模型：deepseek/deepseek-v4-pro
- 审查方式：完全只读，仅依据提供的 Git Diff、文件清单与上一轮报告

## 概述与总体结论

本次提交把 Web 端“AI 整理并预览”从同步 `POST /agent/chat` 迁移到既有公共 SSE 入口 `POST /agent/chat/stream`，并引入独立 `AbortController`、无 conversationId 调用、Chat/整理互斥、项目切换取消、失败半成品清理，以及 Agent 60s / Core 下游 75s 的超时预算。整体方向正确，上一轮报告的 S1/S2/S4/S5 多数建议已在代码中落地。

但本次审查确认一个可复现的交互缺口：流式整理期间，`formattedText` 只要收到首个 delta 就变为非空，随即渲染“应用到 Wiki 草稿”按钮；该按钮不受 `busy` 禁用，也不存在“仅完成态可应用”的显式门控。因此用户可能在 `complete` 前就把半成品 Markdown 应用到 Wiki 草稿，违反本变更及文档明确声明的“只有完成的整理内容才能复制到草稿”“失败/半成品不可应用”边界。这是本次流式改造新引入的问题，判定为必须修改。另有若干防御性与测试补强建议。

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|---------|
| M1 | 中 | apps/web/src/App.tsx | ~283 | 流式整理期间的 Apply 按钮仅依赖 `formattedText` 非空，未随 `busy` 禁用，半成品可被应用到 Wiki 草稿 |

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|---------|
| S1 | 中 | apps/web/src/App.tsx | ~203–207 | `formatText` 非取消错误分支缺少 `activeProjectId` 守卫，旧项目迟到失败可能清空/报错到新项目 |
| S2 | 低 | apps/web/src/App.tsx | ~26–28 | `isAbortError` 仍先要求 `cause instanceof Error`，跨环境 DOMException 鲁棒性不足 |
| S3 | 低 | apps/web/src/App.tsx | ~182–185 | `sendChat` finally 仍无条件 `setStreaming(false)`，与新整理路径的守卫模式不一致，存在旧请求清除新流状态的低概率竞态 |
| S4 | 低 | apps/web/tests/app.test.tsx | 新增测试区 | 缺少“流式中途不可应用”与反向互斥的自动化覆盖，现有测试无法捕获 M1 |

### 无需修改

| ID | 说明 |
|----|------|
| N1 | Nginx / SSE emitter 120 秒总预算不在本次 diff 中，无法验证；60/75/120 秒链在本次 env/compose/文档范围内自洽，不构成阻塞。 |
| N2 | `.env.production.example` 中的固定 Demo 凭据是既有上下文行，项目文档已定义其为公开演示凭据；本次未扩大泄露面。 |

---

## 逐个 Issue 展开

### 必须 M1 —— 流式整理半成品可被应用到 Wiki 草稿

- **Severity**: 中（必须修改）
- **File & Line**: `apps/web/src/App.tsx` ~283（format-panel JSX）

- **Evidence**:
```tsx
onDelta: (text) => {
  if (activeProjectId.current === requestedProjectId) {
    setFormattedText((current) => current + text);
  }
},
...
<div>
  <p className="section-label">整理结果</p>
  <MarkdownPreview content={formattedText} />
  {formattedText && <button className="ghost" onClick={applyFormattedText}>应用到 Wiki 草稿</button>}
</div>
```

- **Description**: `onDelta` 在首个 delta 到达时就把 `formattedText` 置为非空，Apply 按钮立即渲染；此时生成状态 `busy` 为 `true`，但该按钮没有 `disabled={busy}`。用户可在模型仍持续输出时点击，把不完整 Markdown 复制进 Wiki 草稿。虽然草稿仍需用户再次点击“保存 Wiki”才会持久化，但已可能让用户基于半成品继续编辑并保存；这与变更文档中“完成后保留最终结果”“只有用户操作才能把完成的整理内容复制到草稿”“失败半成品不可应用”的边界冲突。原同步实现只在 `answer` 完成后才一次赋值，因此这是本次流式改造新引入的行为差异。

- **Suggested Fix**:
```tsx
{formattedText && (
  <button className="ghost" onClick={applyFormattedText} disabled={busy}>
    应用到 Wiki 草稿
  </button>
)}
```
  更稳妥可再引入独立 `formatting`/`formatComplete` 状态，并在生成中展示“整理中…”提示，确保 Apply 只在 `complete` 后可用。同时补充测试：mock `chatStream` 先调用 `onDelta` 后挂起 Promise，断言 Apply 不存在或处于禁用态。
> 注意：若 `applyFormattedText` 函数内部另有 `busy` 守卫，可缓解数据复制，但本次 diff 未包含该函数体；即便如此，UI 仍在生成中渲染可点击的 Apply 操作，仍应显式禁用或延迟渲染。

---

### 建议 S1 —— 非取消错误会跨项目清空 formattedText 并显示错误

- **Severity**: 中（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~203–207

- **Evidence**:
```tsx
} catch (cause) {
  if (!isAbortError(cause)) {
    setFormattedText("");
    report(cause);
  }
}
```

- **Description**: 成功路径在写入最终结果前检查 `activeProjectId.current !== requestedProjectId`，但错误路径没有相同守卫。若一个属于旧项目的流在项目切换后以非取消错误结束，`setFormattedText("")` 会清空新项目当前的整理预览，`report(cause)` 也会把错误显示到新项目，违反“旧项目迟到事件不得写入当前视图”的设计边界。常规 abort 会产生 AbortError，但非取消拒绝与项目切换交错时仍存在低概率窗口。

- **Suggested Fix**:
```tsx
if (!isAbortError(cause) && activeProjectId.current === requestedProjectId) {
  setFormattedText("");
  report(cause);
}
```

---

### 建议 S2 —— AbortError 识别仍依赖 `instanceof Error`

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~26–28

- **Evidence**:
```tsx
function isAbortError(cause: unknown) {
  return cause instanceof Error && cause.name === "AbortError";
}
```

- **Description**: 相比上一轮的 `instanceof DOMException` 已更鲁棒，但仍要求错误对象是 `Error` 实例。部分浏览器、Web Worker 或跨 realm 环境中的 `DOMException` 可能不是 `Error` 实例，实际 fetch abort 会被误判为普通错误并显示给用户；若 ApiClient 对 SSE abort 做二次封装，也有同样风险。

- **Suggested Fix**:
```tsx
function isAbortError(cause: unknown) {
  return typeof cause === "object" && cause !== null &&
    (cause as { name?: unknown }).name === "AbortError";
}
```

---

### 建议 S3 —— sendChat 的 `setStreaming(false)` 仍未加守卫

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~182–185

- **Evidence**:
```tsx
} finally {
  if (streamAbort.current === controller) streamAbort.current = undefined;
  setStreaming(false);
}
```

- **Description**: 本次整理路径已把 `setBusy(false)` 移入 `formatAbort.current === controller` 守卫内，但 Chat 路径的 `setStreaming(false)` 仍无条件执行。若一个被项目切换中止的旧 Chat 请求的 finally 晚于新 Chat 请求启动，旧请求会把新请求的 streaming 状态提前置为 false。发生概率很低，但与本 diff 新增的互斥/取消治理目标不完全一致。

- **Suggested Fix**:
```tsx
} finally {
  if (streamAbort.current === controller) {
    streamAbort.current = undefined;
    setStreaming(false);
  }
}
```

---

### 建议 S4 —— 现有测试无法捕获半成品应用与反向互斥

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/tests/app.test.tsx`（新增测试区，约 213–300）

- **Description**: 现有新增用例都在 `chatStream` resolve 后才断言最终 heading 与 Apply 行为，因此无法发现 M1（delta 后、resolve 前可点击 Apply）。同时，文档声称 Chat 与整理互斥，但新测试只覆盖“Chat streaming 时禁止 AI 整理”，未覆盖“整理进行中时禁止 Chat 发送”。

- **Suggested Fix**: 增加两个 DOM 层用例：
  1. mock `chatStream` 先 `onDelta("# Partial")` 后返回挂起 Promise，断言 Apply 不存在或禁用；resolve 后解除禁用。
  2. mock 整理流挂起，断言 Chat 发送按钮禁用，确认反向互斥。

---

## 主开发 (Codex) 评估回填区预留表格

| 发现 ID | Codex 是否采纳 | 处理说明 / 修改 commit | 预计完成轮次 |
|---------|---------------|------------------------|-------------|
| M1 | 是 | 应用按钮在 `busy` 时禁用；新增挂起整理流 DOM 测试，先红后绿 | Attempt 2 |
| S1 | 是 | 非取消错误仅在请求项目仍为当前项目时清理并报告 | Attempt 2 |
| S2 | 是 | 改为按非空对象的 `name` 字段识别 AbortError，不依赖同一 realm 的 Error 原型 | Attempt 2 |
| S3 | 是 | Chat finally 只允许当前 controller 清理 `streaming` 状态 | Attempt 2 |
| S4 | 是 | 新增半成品不可应用及整理期间不可发送 Chat 的双向互斥用例 | Attempt 2 |

---

### 结论

本次变更整体架构正确，超时链、状态隔离、失败清理和测试 seam 均已落地；但流式整理期间把“半成品”暴露为可应用到 Wiki 草稿的操作（M1），违反产品声明的“完成后应用 / 半成品不可应用”边界，需要修复并补测后再交付。修复 M1 后，S1–S4 作为防御性加固建议可按需在本轮或下一轮处理。
