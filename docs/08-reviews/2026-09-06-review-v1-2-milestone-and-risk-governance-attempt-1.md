# Pi 代码审查报告：v1-2-milestone-and-risk-governance / Attempt 1

- 日期：2026-09-06
- 审查阶段：v1-2-milestone-and-risk-governance
- 审查对象：INDEX@a1fb652（基线：a1fb65209a89c992255b82b3bae6bfd9f0a9b412）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge V1.2 Milestone Review（第 1 / 3 轮）

## 概述与总体结论

**结论：通过，可在记录建议项后进入提交与远端核验流程。**

本次审查范围为 `a1fb652` 对应的 V1.2 面试演示体验节点：固定/随机 Demo 凭据、SSE/NDJSON 真实流式链路、登录页与工作区视觉升级、L0–L3 风险分级与 Pi Diff/Milestone 双模式治理。

在“必须修改”级别未发现可复现的安全越权、数据一致性破坏、契约冲突、不可运行代码或架构边界破坏。核心安全检查已确认到位：

- Java 在返回 `SseEmitter` 前，`prepareStream` 已同步完成 `projectAccess.requireAccess` + `aiUsageQuota.consume`，满足 ADR-0014 “先鉴权、扣配额、再开流”。
- Python 内部流入口仍受 `require_internal_token` 保护；内部 NDJSON 不对外暴露；Nginx 未新增端口。
- Tool proposal 在流中只由 Java `createPending` 持久化并转换为 pending action，浏览器与 Python 都不能绕过 HITL。
- 固定密码只进入 600 权限的 `.env`，Compose 不声明该环境变量，seed 脚本 stdout 不回显固定密码。
- Nginx `chat$` 与 `chat/stream$` 正则互不重合，顺序不影响匹配。

以下为“建议修改”项，按严重程度排序，均不阻塞交付。

---

## 详细发现清单

### 必须修改

无。

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
| --- | --- | --- | --- | --- |
| S1 | Medium | apps/web/src/App.tsx | sendChat / formatText | 流式期间不再置 `busy`，使 AI 整理等操作可与流并发，共享 `pendingAction` 状态出现 last-writer-wins 竞态 |
| S2 | Low | services/agent-service/src/agentforge_agent/api.py | `events()` | 流生成器只捕获 `LlmDependencyError`，其他异常会无 `error` 事件直接截断 NDJSON，破坏客户端契约 |
| S3 | Low | services/core-api/.../AgentChatController.java | catch 分支 | 客户端断开时二次 `send(error)` 可再次抛异常，导致 emitter 未被 complete，资源悬置至超时 |
| S4 | Low | 三端测试 | — | 新契约的流式 error 分支缺少自动化测试（Python 中途 LlmDependencyError、Java 非 2xx 映射、前端 error 事件） |
| S5 | Low | scripts/deploy/seed-demo-v12.sh、docs | authenticate_or_register | 固定账号改密后登录失败会落到 register → 409 硬失败，提示不友好；且密码经 `jq --arg` 短暂进入进程列表 |
| S6 | Info | docs/04-api/agent-service.md | SSE 事件序列 | 文档表述“最后一个 complete”与 error 事件可替代 complete 的实际契约不完全一致 |

### 无需修改（已确认项）

- 流开始前的 400/401/403/404/429/503 语义与 JSON Chat 一致。
- React `TextDecoder` 分块 UTF-8 + SSE frame 解析正确，切换项目会 abort 旧请求并阻止迟到事件写入。
- 配额扣减顺序与既有 JSON Chat 实现一致，属既有行为，非本次引入。
- Nginx 位置匹配、`proxy_buffering off`、120s 超时与 800 max_tokens 预算匹配，方向正确。
- `AgentChatService.stream` 用 `AtomicReference` 以 metadata 的 conversationId 落 pending action，逻辑正确。

---

## 建议修改项详情

### S1 — 流式期间并发操作可改写共享 `pendingAction`（Medium）

**Severity:** Medium
**File & Line:** `apps/web/src/App.tsx`，`sendChat` / `formatText` / `decideAction`

**Evidence:**
```tsx
async function sendChat(event: FormEvent) {
  ...
  setStreaming(true); setError(""); ...
  // 未调用 setBusy(true)
  const result = await api.chatStream(...);
  ...
  setPendingAction(result.pendingAction);
  ...
}

async function formatText() {
  if (!projectId || !formatInput.trim()) return;
  setBusy(true); setError("");
  const result = await api.chat(...);
  ...
  if (result.pendingAction) setPendingAction(result.pendingAction);
  ...
}
```
发送按钮用 `busy || streaming || !chatMessage.trim()` 禁用，但 `saveWiki` / `decideAction` / `formatText` 的按钮只基于 `busy`（或 `busy || 输入为空`）。

**Description:** 旧实现中 `sendChat` 置 `busy=true`，因此聊天期间“AI 整理并预览”等按钮被禁用。本次改为 `streaming` 后，`formatText` 仍可并发执行。`formatText` 产生 proposal 时与聊天流的 complete 事件会先后写入同一个 `pendingAction` state，最终由“后完成者”决定展示哪张确认卡；用户可能在聊天语境下确认了一张实际来自“AI 整理”对话的 action。Java 侧仍会做 owner/action 校验，不构成越权，但会让 HITL 呈现产生歧义。

**Suggested Fix:** 在流式进行期间禁用其他可产生写入/待确认操作的按钮（`formatText` 的 disabled 加入 `streaming`；或让 `decideAction`/`saveWiki` 也感知 `streaming`）；更彻底的做法是按来源给 `pendingAction` 标注 conversationId 并由 `decideAction` 校验显示来源。最小修改：
```tsx
<button onClick={() => void formatText()} disabled={busy || streaming || !formatInput.trim()}>…</button>
```

---

### S2 — Python 流生成器未兜底未知异常，可能静默截断（Low）

**Severity:** Low
**File & Line:** `services/agent-service/src/agentforge_agent/api.py`，`chat_stream` 的 `events()` 内层 try/except

**Evidence:**
```python
try:
    stream = getattr(responder, "stream", None)
    chunks = stream(state) if callable(stream) else (responder(state),)
    for chunk in chunks:
        if isinstance(chunk, str) and chunk:
            yield encode({"type": "delta", "text": chunk})
    ...
except LlmDependencyError:
    yield encode({"type": "error", "message": "LLM provider is unavailable."})
```

**Description:** 仅捕获 `LlmDependencyError`。若 provider SDK 抛出的异常未被 `CompatibleLlmResponder.stream` 包装，或出现其他编码/运行时异常，生成器直接终止，Java 读到空白 EOF 后按 `ServiceUnavailableException` 处理，浏览器收到的是不完整事件序列而非明确的 `error` 事件，与文档声明的“响应开始后的失败以 error 终止”不完全一致。

**Suggested Fix:** 内层改为 `except LlmDependencyError: yield error(LLM unavailable)` + `except Exception: log 服务端详情后 yield {"type":"error","message":"AI service is temporarily unavailable."}`，保证任何流中失败都以安全 `error` 事件终止，不泄漏上游正文。

---

### S3 — 客户端断开时控制器 error 兜底可能二次抛异常（Low）

**Severity:** Low
**File & Line:** `services/core-api/src/main/java/com/agentforge/core/agent/api/AgentChatController.java`，`chatStream` 的 catch 分支

**Evidence:**
```java
catch (RuntimeException exception) {
    send(emitter, new AgentStreamEvent("error", null, null, List.of(), null, null, null,
            "AI service is temporarily unavailable."));
    emitter.complete();
}
```
`send(...)` 在 `IOException` 时会抛 `IllegalStateException`（属 `RuntimeException`）。

**Description:** 当浏览器在流中切项目/断连触发 `AbortController`，`emitter.send` 可能失败并抛 `IllegalStateException`；外层 catch 再次调用 `send(error)` 又失败后，异常逃逸出虚拟线程，`emitter.complete()` 未执行，emitter 悬置至 120s 超时才能回收。属资源回收健壮性问题，不影响权限与正确性。

**Suggested Fix:**
```java
catch (RuntimeException exception) {
    try { send(emitter, new AgentStreamEvent("error", ...)); }
    catch (RuntimeException ignored) { /* client gone */ }
    finally { sqlSafeComplete(emitter); }
}
```
或使用 `emitter.completeWithError(...)` 并容忍二次发送失败。

---

### S4 — 流式 error 分支缺乏自动化测试（Low）

**Severity:** Low
**File & Line:** `services/agent-service/tests/test_api.py`、`services/core-api/.../AgentChatApiTest.java`、`apps/web/tests/api.test.ts`

**Evidence:** 现有 Python 测试 `test_chat_sanitizes_llm_provider_failure` 只覆盖 JSON 入口；`test_chat_stream_emits_metadata_deltas_and_complete_in_order` 只覆盖成功序列；Java 侧新增的 `chatStreamReturnsIncrementalSseContract` 只覆盖成功 SseEmitter；前端 `api.test.ts` 未覆盖 error 事件分支。

**Description:** 流式是新契约面，其“中途失败以 `error` 事件终止”的路径在三个端点均无自动化覆盖。真实 smoke 已证明成功链路，但异常链路只能靠代码阅读保证。本项不阻塞，但作为 L3 节点收口建议补齐。

**Suggested Fix:**
- Python：新增 responder 在 `stream()` 中抛出 `LlmDependencyError` 的用例，断言事件序列为 `metadata(, delta*)` + `error` 且无 `complete`。
- Java：`AgentChatApiTest` 模拟 `stream` 回抛 `ServiceUnavailableException`，断言 SseEmitter 输出 `error` 事件。
- Web：`api.test.ts` 构造含 `event: error` 的 SSE 流，断言 `chatStream` reject `ApiProblem(503)`。

---

### S5 — 固定账号改密后 seed 硬失败，且密码短暂进入 jq 进程参数（Low）

**Severity:** Low
**File & Line:** `scripts/deploy/seed-demo-v12.sh`，`authenticate_or_register` / `api_post`

**Evidence:**
```bash
if auth="$(api_post '/api/v1/auth/login' "${login_body}" 2>/dev/null)"; then
    printf '%s' "${auth}"
    return
fi
register_body="$(jq -n --arg email "${email}" --arg password "${password}" ...)"
api_post '/api/v1/auth/register' "${register_body}"
```
密码经 `jq --arg password "${password}"` 传入。

**Description:** 固定账号存在但密码已轮换时，login 401 → 落到 register → 409，`curl --fail-with-body` 非零 + `set -e` 使脚本以不友好的错误终止。ADR-0014 已声明密码轮换需受控流程，此属可接受的限制，但失败提示可更明确。另外密码作为 `jq` 的 CLI 参数会短暂出现在宿主机进程列表（仅 root 可见，文件本身 0600），属可选加固点。附带：`production-single-host.md` 声称“脚本只输出固定邮箱与随机备用凭据”，而实际 stdout 只输出固定邮箱与凭据文件路径，随机凭据仅写入 600 文件——建议文档措辞对齐实际行为。

**Suggested Fix:** 在 `authenticate_or_register` 中对邮箱已存在场景给出明确报错（可先以登录 401 与 register 返回体区分“凭据错误/已存在”）；将密码改为经 stdin/环境变量传给 jq（如 `jq -n --rawfile` 读自临时受限文件或 `--arg` 改为环境变量 `JQ_PASSWORD`），减少进程列表暴露；同步修正运维文档“输出”措辞。

---

### S6 — SSE 事件序列文档表述不完全一致（Info）

**Severity:** Info
**File & Line:** `docs/04-api/agent-service.md`，SSE 契约段落

**Evidence:** 文档描写“事件顺序为一个 `metadata`、零到多个 `delta`、最后一个 `complete`；响应开始后的失败以 `error` 终止”，并给出“最后一个 complete”的示例。

**Description:** 成功路径正确的最终事件是 `complete`；但流开始后失败时最终事件是 `error`（无 `complete`）。两处表述并存，严格阅读存在轻微冲突，不影响实现正确性（Java 与 React 均已正确处理 error 分支）。

**Suggested Fix:** 改为“成功时事件顺序为 `metadata` → 零到多个 `delta` → `complete`；响应开始后失败则以 `error` 终止、不发送 `complete`”，与错误语义段落统一。

---

## 主开发 (Codex) 评估回填区

| ID | Codex 是否采纳 | 处理方式/技术依据 | 关联提交/变更记录 |
| --- | --- | --- | --- |
| S1 | 后续采纳，不阻塞 | Java 仍校验 owner/action，不构成越权或错误写入；将“流式期间禁用其他产生 action 的入口”登记为后续局部 UI 加固，避免改变已通过的里程碑快照。 | `2026-09-06-v1-2-interview-demo-experience.md` |
| S2 | 后续采纳，不阻塞 | Java 对不完整 EOF 已转换为通用不可用错误，当前不会泄漏上游信息；未知异常统一 error 事件纳入后续流式韧性切片。 | 同上 |
| S3 | 后续采纳，不阻塞 | 仅发生在客户端主动断开后的资源回收边缘，120 秒 emitter 超时提供最终回收；与 S2/S4 合并做异常链路加固。 | 同上 |
| S4 | 后续采纳，不阻塞 | 成功链路已有三端自动化与真实 DeepSeek smoke；异常事件三端测试作为 S2/S3 修复的 TDD 验收。 | 同上 |
| S5 | 记录限制，不阻塞 | 固定账号首次创建/重复使用已满足当前目标；主动轮换密码仍需受控流程。凭据只对 root 可见且不输出到日志，后续改善错误提示和进程参数暴露。 | 同上、ADR-0014 |
| S6 | 后续 L0 文档整理 | 当前相邻句已说明失败由 error 终止，客户端行为正确；下次同主题文档批次改为明确的成功/失败二选一序列。 | 同上 |

> 回填要求：采纳项需补充同步文档并重跑相关测试；不采纳项需写明技术依据；全部处理完成后在 `docs/07-changes/2026-09-06-v1-2-interview-demo-experience.md` 与 `docs/07-changes/2026-09-06-risk-based-validation-and-review.md` 记录并归零累计计数。
