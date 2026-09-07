# Pi 代码审查报告：ai-formatting-stream-reliability / Attempt 1

- 日期：2026-09-07
- 审查阶段：ai-formatting-stream-reliability
- 审查对象：INDEX@bb286a1（基线：bb286a166a8cd1005035f66e80fecd7381c86c2c）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：600 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：ai-formatting-stream-reliability
- 审查模式：Diff
- 审查轮次：1 / 3
- 审查目标：bb286a1（12 个文件，+166/-30）
- 审查模型：deepseek/deepseek-v4-pro
- 审查方式：完全只读，仅依据提供的 Git Diff、文件清单与历史报告

## 概述与总体结论

本次变更把 Web 端“AI 整理并预览”从同步 `POST /agent/chat` 迁移到既有公共 SSE 接口 `POST /agent/chat/stream`，并为整理流引入独立的 `AbortController`、无 conversationId 调用、Chat/整理互斥、项目切换取消与意外 proposal 隔离；同时在 Compose/环境模板中显式配置了 Agent 60s 与 Core 下游 75s 的超时预算，并同步了前端架构、Web 功能、Agent API、生产排障与变更索引文档。

对照 diff 逐项检查后发现：

- 核心逻辑（delta 增量渲染 + `result.answer` 收口 + `activeProjectId` 守卫 + 互斥 + abort 清理）方向正确，未发现可证据化的可运行性、正确性、安全、权限、并发、数据一致性或契约冲突级别的必然缺陷。
- 本次未发现“必须修改”项。以下问题均为防御性/可观测性/测试补强层面的“建议修改”，不阻塞交付；环境要求与契约声明在本次 diff 范围内自洽。

建议总数为 5 项，按严重级排序；另有 2 项“无需修改”说明。

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|---------|------|------|---------|
| S1 | 中 | apps/web/src/App.tsx | ~208 | `finally` 中无条件 `setBusy(false)` 与条件式 `formatAbort.current` 清理不一致，存在潜在竞态模式 |
| S2 | 低 | apps/web/src/App.tsx | ~203–205 | 非 Abort 错误时已累积的 partial `formattedText` 被保留并与 error 同屏展示，缺乏 partial/error 语义区分 |
| S3 | 低 | apps/web/src/App.tsx | ~202–203 | 意外 `pendingAction` 被静默丢弃（而按契约其可能已在服务端持久化），缺少可观测性 |
| S4 | 低 | apps/web/tests/app.test.tsx | 新增测试区 | 缺少格式化流中途非 Abort 错误（如 503）与 `result.answer`/delta 收口不一致的自动化覆盖 |
| S5 | 低 | apps/web/src/App.tsx | ~205 | AbortError 判断依赖 `instanceof DOMException`，跨环境/封装库鲁棒性可再收紧 |

### 无需修改

| ID | 说明 |
|----|------|
| N1 | 超时链 60/75/120s 在本次改动文件（.env 模板、两个 compose、docs）中自洽；Nginx/SSE 120s 的实现在本次 diff 之外，无法在此验证，不构成阻塞。 |
| N2 | `.env.production.example` 中的固定 Demo 凭据属本次 diff 的上下文行（非新增），且项目文档已明确其为“非秘密”的公开演示凭据，不构成本次问题。 |

---

## 逐个 Issue 展开

### 建议 S1 —— finally 无条件 `setBusy(false)` 与 ref 条件清理不一致（潜在竞态模式）

- **Severity**: 中（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~208
- **Evidence**:
```tsx
} finally {
  if (formatAbort.current === controller) formatAbort.current = undefined;
  setBusy(false);
}
```
- **Description**:
  `formatAbort.current === controller` 的守卫用于防止旧请求清理掉新请求的引用，但紧跟着的 `setBusy(false)` 却是无条件执行。若出现“旧请求的 finally 晚于新请求启动”的时序（例如 abort 拒绝被延迟到下一个 macrotask 之后、或被上层封装推迟），旧请求会把新请求置为的 `busy=true` 提前复位为 `false`，使按钮提前解锁、可能造成重复提交。
  当前 UI 门控（`formatAbort.current?.abort()` 同步 abort + 微任务时序）使该窗口在现实交互中几乎不可达，且与既有 `sendChat` 的 `setStreaming(false)` 模式一致，故不判为必须修改；但该模式应做防御性收敛。
- **Suggested Fix**:
  将 `setBusy(false)` 移入同一守卫内部，与 ref 清理保持一致：
```tsx
} finally {
  if (formatAbort.current === controller) {
    formatAbort.current = undefined;
    setBusy(false);
  }
}
```
  （若采纳，建议对既有 `sendChat` 的 `setStreaming(false)` 一并对齐，避免两处行为漂移。）

### 建议 S2 —— 非 Abort 错误时 partial 结果残留

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~203–205
- **Evidence**:
```tsx
} catch (cause) {
  if (!(cause instanceof DOMException && cause.name === "AbortError")) report(cause);
} finally { ... }
```
  `onDelta` 已经累积写入 `formattedText` 后，若流中途以非 Abort 错误终止（如 503），catch 仅调用 `report(cause)`，不重置 `formattedText`，导致“半成品文本 + 错误提示”同时呈现。
- **Description**:
  用户可能把部分生成的 Markdown 误认为有效结果，尤其在“应用到 Wiki 草稿”按钮仍可用时（partial 非空即触发 `{formattedText && <button …>}`）造成误用。此外整理过程仅有按钮禁用状态，缺乏类似 Chat 的“生成中”提示，进行中/失败/成功三态区分不完整。
- **Suggested Fix**:
  在非 Abort 错误分支显式清空或标记 partial，避免半成品被应用：
```tsx
} catch (cause) {
  if (!(cause instanceof DOMException && cause.name === "AbortError")) {
    setFormattedText("");
    report(cause);
  }
}
```
  可选：为整理面板增加与 Chat 对齐的进行中状态提示（如按 `busy`/专用 `formatting` 状态显示“整理中…”）。

### 建议 S3 —— 意外 `pendingAction` 静默丢弃，缺少可观测性

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~202–203
- **Evidence**:
```tsx
if (activeProjectId.current !== requestedProjectId) return;
setFormattedText(result.answer);
// 不再读取/展示 result.pendingAction
```
- **Description**:
  文档（`docs/04-api/agent-service.md`）明确 `complete.pendingAction` 是“Java 已验证并持久化的待确认操作”。整理入口按设计忽略该字段，方向正确，但若模型在整理语境中意外触发有效写意图，服务端会持久化一个用户从未在 UI 中见过的 PENDING action（孤儿待确认项），既不可确认也不可拒绝，后续可能产生数据一致性困扰。当前仅靠提示词“不执行写入”规避，缺少兜底可观测性。
- **Suggested Fix**:
  建议在客户端忽略处增加轻量可观测性（如仅在 dev 或埋点中记录 “unexpected pendingAction dropped in formatting context” 及 requestId），或由 Core 侧对无 conversationId 的整理调用收紧 proposal 校验；不改变既定的 UI 隔离契约。

### 建议 S4 —— 测试范围缺少非 Abort 错误与收口一致性路径

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/tests/app.test.tsx`（新增测试区，~146–248)
- **Description**:
  现有新增测试覆盖了：无 conversationId 流式整理、增量预览、显式应用、Chat 状态隔离、proposal 忽略、Chat/整理互斥、项目切换取消。但未覆盖：
  1. 格式化流中途返回非 Abort 错误（如 503）时，错误是否可见、`busy` 是否正确复位、partial 是否被正确处理（对应 S2）；
  2. `result.answer` 与 delta 累加结果不一致时的最终收口行为（当前实现以 `result.answer` 覆盖，测试 mock 中两者恒等，未验证不一致时不会出现重复/丢失）。
- **Suggested Fix**:
  补充两个用例：一是 mock `chatStream` 先 `onDelta` 后 reject 非 Abort 错误，断言 `role="alert"` 可见且按钮恢复可用；二是 mock `onDelta` 与 `answer` 不同，断言最终预览以 `result.answer` 为准。

### 建议 S5 —— AbortError 判断的环境鲁棒性

- **Severity**: 低（建议修改）
- **File & Line**: `apps/web/src/App.tsx` ~205
- **Evidence**:
```tsx
if (!(cause instanceof DOMException && cause.name === "AbortError")) report(cause);
```
- **Description**:
  该判断依赖环境提供的 `DOMException` 实例类型。现代 `fetch` abort 会产生 `DOMException`/`AbortError`，测试 mock 也按该类型构造；但若 ApiClient 内部对 SSE 读取的 abort 做了封装（例如转换成自定义 `CanceledError` 或普通 `Error`），项目切换/卸载时的取消就可能被误判为普通错误并通过 `report` 显示给用户。由于本次 diff 未包含 ApiClient 源码，无法确认其实际 abort 错误类型。
- **Suggested Fix**:
  建议改为更稳健的判断（先按 `name === "AbortError"` 或以 `cause instanceof Error && cause.name === "AbortError"` 兜底），并与 ApiClient 层统一 abort 错误归一化约定后再提交。

---

## 主开发 (Codex) 评估回填区预留表格

| 发现 ID | Codex 是否采纳 | 处理说明 / 修改 commit | 预计完成轮次 |
|---------|---------------|------------------------|-------------|
| S1 | 采纳 | `setBusy(false)` 与当前 controller 守卫保持一致，旧请求不能解除新请求状态。 | 本轮 |
| S2 | 采纳 | 非取消错误清空 partial，失败内容不再显示“应用到 Wiki 草稿”。 | 本轮 |
| S3 | 记录后续 | 前端继续按既定安全边界忽略 proposal；服务端 mode/Trace 可观测性会改变公共契约，留给 V2 节点设计。 | V2 后续 |
| S4 | 采纳 | 新增先收到 delta、后返回 503 的 DOM 行为测试，并验证错误、partial 清理和按钮恢复。 | 本轮 |
| S5 | 采纳 | 统一按 `Error.name === "AbortError"` 判断，不再依赖 DOMException 实例类型。 | 本轮 |

---

### 结论

本次审查在 diff 范围内未发现证据充分的“必须修改”级问题，核心目标（流式整理、状态隔离、互斥、取消、超时配置）均已正确实现并配套测试与文档。S1–S5 为防御性与测试增强建议，不阻塞交付。判断本次交付为 **PASS**。

> 备注：本报告仅基于启动器提供的 diff 与文件清单；未运行的命令、未包含在 diff 中的核心/Agent/ApiClient/Nginx 实际源码无法在此验证（见 N1、S5），生产部署后仍需按变更记录要求完成不泄露 token 的真实浏览器 smoke 验证。
