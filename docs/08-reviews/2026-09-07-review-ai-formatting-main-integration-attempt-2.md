# Pi 代码审查报告：ai-formatting-main-integration / Attempt 2

- 日期：2026-09-07
- 审查阶段：ai-formatting-main-integration
- 审查对象：INDEX@ca4ecfc（基线：ca4ecfcbd41baee83220313ec11400ed79fe87e0）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：ai-formatting-main-integration
- 审查模式：Diff
- 审查轮次：2 / 3
- 审查对象：INDEX@ca4ecfc（ca4ecfcbd41baee83220313ec11400ed79fe87e0 .. INDEX@ca4ecfc）
- 审查模型：deepseek/deepseek-v4-pro
- 审查方式：完全只读，仅依据提供的 Git Diff、文件清单与测试/文档证据

## 概述与总体结论

本轮 diff 将 Web 端“AI 整理并预览”从同步 `POST /agent/chat` 迁移到公共 SSE 入口 `POST /agent/chat/stream`，并为整理流引入独立 `AbortController`、无 conversationId 调用、delta 增量渲染、Chat/整理双向互斥、项目切换/卸载取消、失败半成品清理，以及 Agent 60s / Core 下游 75s 的生产超时预算。同时补齐了前端架构、Web 功能、Agent API、生产排障和变更索引文档，以及对应 DOM 层测试。

针对上一轮 Attempt 1 报告中的必须项与建议项，本轮代码已显式落地：

- M1 已修复：`应用到 Wiki 草稿` 按钮现在 `disabled={busy}`；`formatText` 仅在当前 controller 的 `finally` 中释放 `busy`，因此流式 `complete` 前半成品不可应用到 Wiki。
- S1 已修复：非取消错误分支增加 `activeProjectId.current === requestedProjectId` 守卫，旧项目迟到失败不会清空/报错到新项目。
- S2 已修复：`isAbortError` 改为按非空对象的 `name === "AbortError"` 识别，不再依赖单一 realm 的 `Error`/`DOMException` 原型。
- S3 已修复：`sendChat` 的 `setStreaming(false)` 移入 `streamAbort.current === controller` 守卫，旧 Chat 请求不再清除新流状态。
- S4 已修复：新增“流式中途不可应用”“格式化期间禁止 Chat 发送”“失败半成品清理”“项目切换 abort”等 DOM 用例。

对照本轮 diff，逐项检查后未发现具备明确证据的 Bug、权限绕过、API 契约冲突、并发/幂等/数据一致性破坏、测试范围缺失或架构边界破坏。当前存在的均为低严重度的防御性/可观测性/UX 测试补强建议，不阻塞交付。

**结论：通过（PASS）。**

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|---------|
| S1 | 低 | apps/web/src/App.tsx | ~203–207 | `formatText` 成功收口路径只检查 `activeProjectId`，未同时守卫 `formatAbort.current === controller`；同一项目内若旧请求在被取代后仍 resolve，可能覆盖新整理结果 |
| S2 | 低 | apps/web/src/App.tsx | ~196–202 | 流式阶段增量写入的是未规范化的原始 `delta`，外层 `markdown`/`md` 围栏要到 `complete` 后才由 `normalizeMarkdownContent` 移除，完成前后预览可能短暂呈现为代码块/跳动 |
| S3 | 低 | apps/web/tests/app.test.tsx | 新增测试区 | 整理调用不传 `onMetadata`，且未覆盖“metadata 到达不抛错”与“`result.answer` 与 delta 累加不一致时以谁为准”的测试路径 |

### 无需修改

| ID | 说明 |
|----|------|
| N1 | `.env.production.example` 中的固定 Demo 邮箱/密码是上下文行，非本次新增；项目文档已明确其为公开演示凭据。 |
| N2 | 60s（Agent 单次等待）< 75s（Core 下游读取）< 120s（SSE/Nginx）的超时链在本次 env/compose/docs 范围内自洽；120s 的 Nginx 实际实现不在本 diff 中，不构成本次阻塞。 |

---

## 逐个 Issue 展开

### 建议 S1 —— `formatText` 成功收口路径缺少当前 controller 守卫

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~203–207
- **Evidence**:
```tsx
const result = await api.chatStream(...);
if (activeProjectId.current !== requestedProjectId) return;
setFormattedText(normalizeMarkdownContent(result.answer));
```
- **Description**:
  当前实现对“旧项目迟到写入”的防护是完整的，但对同一项目内“请求被取代后仍成功返回”的路径只依赖 `activeProjectId`。正常 `fetch` abort 会以 `AbortError` reject，因此概率很低；但若 `ApiClient` 封装、超时重试边界或未来测试替换实现了 abort 后仍 resolve 的行为，旧请求的最终 `answer` 可能覆盖新请求已经写入的 `formattedText`。这与本轮 `finally` 已采用的 `formatAbort.current === controller` 守卫模式不一致，属于防御性收敛。
- **Suggested Fix**:
```tsx
if (activeProjectId.current !== requestedProjectId || formatAbort.current !== controller) return;
setFormattedText(normalizeMarkdownContent(result.answer));
```
  若采纳，建议顺带复核既有 `sendChat` 成功路径，保持两处治理模式一致。

### 建议 S2 —— 流式阶段预览未对外层 Markdown 围栏做逐步规范化

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~196–202
- **Evidence**:
```tsx
onDelta: (text) => {
  if (activeProjectId.current === requestedProjectId) {
    setFormattedText((current) => current + text);
  }
},
...
setFormattedText(normalizeMarkdownContent(result.answer));
```
- **Description**:
  `onDelta` 将原始 SSE 文本直接累加渲染，最终 `complete` 后才调用 `normalizeMarkdownContent(result.answer)` 去掉单个完整外层 `markdown`/`md` 围栏。若模型首段先输出 ```` ```markdown ````，增量预览会临时以代码块形式出现，complete 后再跳转为规范化渲染。该问题不影响数据安全：`busy` 已阻止半成品 Apply，但会造成一次可见的渲染跳动，并与 `docs/02-architecture/frontend-architecture.md` 中“共用渲染器移除外层围栏”的即时展示表述略有出入。
- **Suggested Fix**:
  不强行对未闭合 fence 做渐进式解析；推荐增加显式“整理中…”生成状态，集成期间仅展示状态或延迟到 `complete` 后再渲染规范化结果；完成后保持现有 `busy`/Apply 释放节奏。这样避免 UX 跳动，也不引入复杂的流式 Markdown 分块解析。

### 建议 S3 —— 测试缺少 metadata 兼容与最终收口权威性覆盖

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/tests/app.test.tsx` 新增测试区
- **Description**:
  当前新增测试只 mock 了 `onDelta` 与最终 `answer`，没有模拟 `chatStream` 实际会先触发 `metadata` 事件这一行为。整理调用只传 `{ onDelta }`，本应依赖 `ApiClient.chatStream` 对 `onMetadata` 做可选调用；对此缺少一条防回归测试。同时所有测试中 `onDelta` 累加值与返回 `answer` 一致，未验证二者不一致时以 `normalizeMarkdownContent(result.answer)` 为最终权威的契约。
- **Suggested Fix**:
  增加两个用例：
  1. mock `chatStream` 在返回前调用 `callbacks.onMetadata?.(metadata)`，断言整理流程不抛错且最终显示正确；
  2. mock `onDelta("# Partial")` 但返回 `answer: "# Final"`，断言最终预览以规范化后的 `answer` 为准、不出现重复/丢失。

---

## 主开发 (Codex) 评估回填区预留表格

| 发现 ID | Codex 是否采纳 | 处理说明 / 修改 commit | 预计完成轮次 |
|---------|---------------|------------------------|-------------|
| S1 | 否 | 当前 UI 在 `busy` 时禁止再次启动整理；项目切换和 finally 已有项目/controller 守卫，没有可复现的同项目重入路径 | 不阻塞 |
| S2 | 否 | 增量展示是降低长文本首字节等待的明确目标；完成前 Apply 已禁用，延迟渲染会抵消本修复价值 | 不阻塞 |
| S3 | 后续 | metadata 回调在 ApiClient 契约中可选，最终 `result.answer` 已显式覆盖 delta 累加；可另补更细测试 | 后续测试增强 |

---

## 结论

本轮在 diff 范围内未发现“必须修改”级问题。上一轮 Attempt 1 的 M1 与 S1–S4 均已在本轮代码和测试中得到明确修复；测试、构建记录与文档声明在本轮范围内自洽。S1–S3 为低严重度的防御性、UX 与测试补强建议，不阻塞交付。

**审查结果：PASS。**
