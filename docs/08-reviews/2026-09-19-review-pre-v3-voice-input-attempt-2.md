# Pi 代码审查报告：pre-v3-voice-input / Attempt 2

- 日期：2026-09-19
- 审查阶段：pre-v3-voice-input
- 审查对象：INDEX@89252ae（基线：89252ae59760ac29dd48f4ecc4ac5cd42421f224）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# Pi 代码审查报告：pre-v3-voice-input / Attempt 2

- 日期：2026-09-19
- 审查阶段：pre-v3-voice-input
- 审查模式：Milestone（2 / 3）
- 审查对象：INDEX@89252ae（基线：`89252ae59760ac29dd48f4ecc4ac5cd42421f224`）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 审查方式：仅依据给定 Git Diff、文件清单、ADR-0027、功能/接口文档与 Attempt 1 报告；未执行命令、测试或 Git 操作。变更记录中 Codex 自述的 pytest/Java/Web 结果只作为外部证据引用，未由 Pi 复现。

---

## 一、概述与总体结论

- 审查范围：`89252ae` 相对基线的 30 个文件（新增 Python ASR 服务与会话、Core API ASR 代理、Web 语音采集、部署配置与文档）。
- Attempt 1 的“必须修改”项已完成且与回归测试一致：
  - **M-1（切项目/外部终止后 UI 卡死）** — `VoiceInput.tsx` 两处 `ticket` 提前返回与 `useEffect` 清理均已 `setPhase("idle")`；新增 `voice-input.test.tsx` 覆盖“切项目复位”和“离开后授权才返回”两条路径，修复方向正确。
  - **M-2（ASR 不可用仍扣配额）** — `AgentAsrService.start` 已改为“上游会话建立成功后再 `consume`”，并在扣减失败时回滚取消会话；新增 `unavailableProviderDoesNotConsumeChatQuota`、`deniedProjectCannotOpenProviderSessionOrConsumeQuota`。
  - **S-1（内部音频请求体无上限）** — `api.py asr_audio` 已改为流式读取并在超过 `MAX_CHUNK` 时立即 400；`test_asr_api.py` 覆盖 64 002 字节提前拒绝。
- 总体结论：**需修复后交付**。方向与 ADR-0027 一致，未发现越权、供应商 Key 泄露、音频/转写进入历史或自动触发 Action。但 Attempt 1 的 M-2 修复引入了新的配额/资源语义问题，并且 ASR 会话状态机存在两个可确认缺陷（“已结束会话仍占用并发槽”与“异步端点内阻塞事件循环”），因此本轮不通过。
- 已知限制（转述证据，非 Pi 判定）：本机未配置阿里云 Key/Workspace，未做真实语音识别验收；Docker 镜像未获新构建证据。该限制属环境阻断，不单列为本轮问题。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号（估算） | 核心问题 |
|----|----------|------|--------------|----------|
| M-1 | 高 | `services/agent-service/src/agentforge_agent/asr.py` | `start` 约 60–66；`finish` 约 120–134 | `finish` 不释放会话，已结束会话仍占用“单用户/全局并发槽”，停止后立即重录或客户端未发 DELETE 时被 503 拒绝，最长锁死 180 秒 |
| M-2 | 中 | `services/agent-service/src/agentforge_agent/api.py`、`asr.py` | `asr_audio` 约 99–106；`append` 约 108–120 | `async def` 端点内同步执行 `socket.send`（阻塞 I/O 与锁），阻塞单一事件循环，可能拖垮同进程全部接口 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（估算） | 核心问题 |
|----|----------|------|--------------|----------|
| S-1 | 中 | `services/core-api/.../AgentAsrService.java` | `start` 约 24–47 | 配额改为“会话建立后扣减”，超额用户仍会触发真实上游会话创建与 Python 槽位占用，配额失去前置门禁作用 |
| S-2 | 中 | `services/core-api/.../AgentAsrService.java` | `unavailable` 约 95–101 | 上游 400（无效音频）统一映射为 503，与 Python 契约不一致（公共入口已前置校验，正常不可达） |
| S-3 | 中 | `apps/web/src/pages/VoiceInput.tsx` | `pcm16` 约 24–35 | 48 kHz→16 kHz 最近邻抽样、无抗混叠，可能降低中文识别率 |
| S-4 | 中 | `AgentAsrServiceTest.java`、`AgentAsrApiTest.java`、`test_asr_realtime.py` | 全文 | Java 侧 `status/finish/cancel`、404/400 映射、超大 chunk 无测试；Python 缺“`finish` 后立即 `start`”回归 |
| S-5 | 低 | `docs/03-features/README.md`、`docs/02-architecture/system-overview.md`、`frontend-architecture.md` | 第 1–3 行 | 新段落/条目仍插在正文前言或元数据列表之前，破坏既有结构（Attempt 1 S-5 未改） |
| S-6 | 低 | `apps/web/src/pages/VoiceInput.tsx` | `start` catch 约 100–110 | catch 未做 `generation` 校验，旧启动失败可能把新采集引用置空，形成窄竞态泄漏 |
| S-7 | 低 | `.env.production.example` | 约 19–20（既有） | 示例文件含固定 demo 邮箱与明文密码 |

### 无需修改

见第四节。

---

## 三、逐个 Issue 展开

### M-1（高）已结束的 ASR 会话仍占用并发槽，导致重录被拒与最长 180 秒锁死

- **Severity**：高
- **File & Line**：`services/agent-service/src/agentforge_agent/asr.py`，`AsrService.start` 约 60–66，`AsrService.finish` 约 120–134
- **Evidence**

```python
# start()：并发判定把“已结束但未 DELETE”的会话也算作活跃
with self.lock:
    if len(self.sessions) + len(self.pending_users) >= self.MAX_SESSIONS or user_id in self.pending_users or any(
        session.user_id == user_id for session in self.sessions.values()   # ← 不区分 finished
    ):
        raise AsrUnavailable()
```

```python
# finish()：只等待并返回快照，不把会话移出 self.sessions
def finish(self, session_id, project_id, user_id):
    session = self._get(session_id, project_id, user_id)
    ...
    if not session.done.wait(15):
        raise AsrUnavailable()
    if session.failed:
        raise AsrUnavailable()
    return session.snapshot(session_id)      # ← 会话仍驻留，直到 DELETE / MAX_AGE_SECONDS
```

```tsx
// VoiceInput.stop() finally：先复位 UI 再“发后不管”地取消
} finally {
  if (capture.current === current) { capture.current = null; setPhase("idle"); setPreview(""); }
  void api.cancelVoice(projectId, current.sessionId).catch(() => undefined);  // 未 await
}
```

- **Description**：`finish` 成功后会话仍留在 `self.sessions`，只有显式 `DELETE` 或 `MAX_AGE_SECONDS=180` 过期才释放。而 `start` 的“单用户同时最多一段录音”判定只按 `user_id` 匹配、不排除 `finished`。因此存在两条正常可达路径导致误报 503：
  1. 用户在 `stop()` 后立即再次点录音。`setPhase("idle")` 让按钮立刻可点，而 `cancelVoice` 是 fire-and-forget；在 HTTP/2 多路复用下 DELETE 与新的 POST 到达 Python 的顺序无保证，POST 可能先到并命中 `any(... user_id ...)` → `AsrUnavailable` → 前端显示“无法开始语音输入”。
  2. 用户停止录音后直接关标签页，卸载清理的 DELETE 未必送达，该用户随后重开页面在 180 秒内无法开始任何录音（持续 503）。

  同理，`MAX_SESSIONS=32` 的全局容量也被这些“僵尸完成会话”占用，短时间多次 `finish` 而未 `cancel` 时可拒绝所有用户。功能文档写的是“单用户同时最多一段录音”，finished 会话不构成“同时录音”。现有 `test_asr_realtime.py` 只覆盖 start→finish→cancel，未覆盖 finish 后立即 start，所以回归未被捕获。
- **Suggested Fix**：`finish` 成功后（或 `session.finished` 置位时）释放用户/全局槽位，至少不把 finished 会话计入并发判定：

```python
# start() 并发判定排除已结束会话
if len(self.sessions) + len(self.pending_users) >= self.MAX_SESSIONS or user_id in self.pending_users or any(
    session.user_id == user_id and not session.finished for session in self.sessions.values()
):
    raise AsrUnavailable()
```

```python
# finish() 完成后立即释放，避免依赖客户端 DELETE
def finish(self, session_id, project_id, user_id):
    ...
    snapshot = session.snapshot(session_id)
    with self.lock:
        self.sessions.pop(session_id, None)
    return snapshot
```

并补充回归：`start → finish →（不 DELETE）→ start` 必须成功；`finish` 后不取消也应在合理时间释放全局槽位。

### M-2（中）`asr_audio` 在异步事件循环中执行阻塞 WebSocket 发送

- **Severity**：中
- **File & Line**：`services/agent-service/src/agentforge_agent/api.py asr_audio` 约 99–106；`asr.py AsrService.append` 约 108–120
- **Evidence**

```python
@router.post("/internal/v1/asr/sessions/{session_id}/audio", status_code=204, ...)
async def asr_audio(session_id: UUID, request: Request, ...):
    audio = bytearray()
    async for chunk in request.stream():
        audio.extend(chunk)
        if len(audio) > AsrService.MAX_CHUNK:
            raise HTTPException(status_code=400, detail="Invalid voice chunk.")
    _asr_call(lambda: service.append(session_id, project_id, user_id, bytes(audio)))  # ← 同步阻塞调用
```

```python
def append(self, session_id, project_id, user_id, audio):
    session = self._get(session_id, project_id, user_id)
    ...
    with session.lock:                                   # ← 同步锁
        ...
        session.socket.send(json.dumps({...}))           # ← websockets.sync 阻塞发送
```

- **Description**：FastAPI 中 `async def` 端点直接运行在事件循环上；`service.append` 是同步实现，会在此线程上获取 `RLock` 并调用 `websockets.sync.client` 的阻塞 `send`。当上游 WebSocket 出现背压（发送缓冲区满）时，该调用会阻塞整个事件循环，连带冻结 `/health`、`/internal/v1/chat` 等所有并发请求，表现为 Agent Service 整体无响应。其余 ASR 端点（`asr_start/status/finish/cancel`）用的是同步 `def`，由 Starlette 线程池执行，不存在该问题，只有 `asr_audio` 例外，属实现不一致。
- **Suggested Fix**：保留异步读取，但把阻塞调用交给线程池：

```python
from starlette.concurrency import run_in_threadpool

async def asr_audio(session_id: UUID, request: Request, project_id: UUID = Query(alias="projectId"),
                    user_id: UUID = Query(alias="userId"), service: AsrService = Depends(get_asr_service)):
    audio = bytearray()
    async for chunk in request.stream():
        audio.extend(chunk)
        if len(audio) > AsrService.MAX_CHUNK:
            raise HTTPException(status_code=400, detail="Invalid voice chunk.")
    await run_in_threadpool(_asr_call, lambda: service.append(session_id, project_id, user_id, bytes(audio)))
```

### S-1（中）配额扣减后置，使超额用户仍触发上游会话创建

- **Severity**：中
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentAsrService.java start` 约 24–47
- **Evidence**

```java
StartResponse result = agentServiceRestClient.post().uri("/internal/v1/asr/sessions")
        .body(new Scope(projectId, actor.userId())).retrieve().body(StartResponse.class);
if (result == null || result.sessionId() == null) throw new ServiceUnavailableException("Voice recognition is unavailable.");
try {
    aiUsageQuota.consume(actor.userId());          // ← 先建立真实上游会话，再扣配额
} catch (RuntimeException exception) {
    try { /* 回滚 DELETE 该会话 */ } catch (RestClientException ignored) { }
    throw exception;
}
```

- **Description**：Attempt 1 的 M-2 要求“确认 ASR 可用后再扣减”，本实现只做了后半段（建立后扣减），未做前置可用性/配额检查。结果是：**已耗尽当日配额的用户每次点击录音，都会先真实创建一条阿里云 WebSocket 会话与一个 Python 会话槽，随后才因 429 被拒绝并立即取消**。配额原本是 AI 用量的前置闸门，现在失去了对上游资源与成本的前置约束；若回滚 DELETE 失败，该 Python 槽位还会残留到 `MAX_AGE_SECONDS`。这与“每次开始录音消耗现有用户 AI 日配额”的限流意图不一致。
- **Suggested Fix**：改为两段式——创建前做只读配额/可用性预检（不扣除），会话建立成功后再原子扣减；若 `AiUsageQuota` 暂无只读接口，可先确认其存在后再实现，同时保留当前失败回滚：

```java
projectAccess.requireAccess(projectId, actor);
aiUsageQuota.requireAvailable(actor.userId());   // 只读预检，不消耗；无此 API 时需先补齐
StartResponse result = /* 建立会话 */;
if (result == null || result.sessionId() == null) throw new ServiceUnavailableException(...);
try { aiUsageQuota.consume(actor.userId()); }
catch (RuntimeException exception) { /* 回滚 DELETE */ throw exception; }
```

### S-2（中）上游 400 被映射为 503

- **Severity**：中
- **File & Line**：`services/core-api/.../AgentAsrService.java unavailable` 约 95–101
- **Evidence**

```java
private RuntimeException unavailable(RestClientException exception) {
    if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 404) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice session was not found.");
    }
    return new ServiceUnavailableException("Voice recognition is unavailable.", exception);
}
```

- **Description**：Python 对无效音频（空、奇数字节、超长）返回 400，Java 统一转 503。Java `append` 已做相同的空/`>64_000`/奇偶校验，正常公共调用无法触及上游 400，故不构成本轮阻塞；但契约层面“无效音频=400”未在 Core 边界保持，属既知偏差。
- **Suggested Fix**：

```java
if (response.getStatusCode().value() == 400) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid voice chunk.");
}
```

### S-3（中）PCM16 降采样无抗混叠

- **Severity**：中
- **File & Line**：`apps/web/src/pages/VoiceInput.tsx pcm16` 约 24–35
- **Evidence**

```tsx
const ratio = sourceRate / 16000;
for (let index = 0; index < count; index += 1) {
  const sample = Math.max(-1, Math.min(1, samples[Math.floor(index * ratio)]));  // 最近邻
```

- **Description**：48 kHz→16 kHz 直接最近邻抽取会产生混叠，可能降低中文 ASR 识别率。属质量优化，无真实语音样本前不阻塞本阶段。
- **Suggested Fix**：对每个输出样本取对应输入窗口均值（或加一阶低通）后再量化。

### S-4（中）异常分支与 Java 代理路径测试覆盖不足

- **Severity**：中
- **File & Line**：`AgentAsrServiceTest.java`、`AgentAsrApiTest.java`、`test_asr_realtime.py` 全文
- **Evidence**：`AgentAsrApiTest` 仅覆盖 start 200/401 与 audio 401/204；`AgentAsrServiceTest` 仅 start 成功、上游 500、无权限三项；Python 覆盖 start/status/audio/finish/cancel 与作用域不匹配，但无“`finish` 后立即 `start`”与“全局/单用户槽位释放”用例。
- **Description**：`status/finish/cancel` 的项目访问重校验与 404 映射、超大 chunk 的 400 分支、以及 M-1 引入的槽位语义均无自动化保护；结合 M-1 已成为可回归风险。
- **Suggested Fix**：补 Java MockRestServiceServer 对 404→NOT_FOUND、400→BAD_REQUEST、`finish/cancel` 透传的用例；补 Python `finish` 后不 DELETE 即 `start` 成功、以及 `finished` 会话不计入并发的回归。

### S-5（低）文档插入位置破坏既有结构

- **Severity**：低
- **File & Line**：`docs/03-features/README.md` 第 3 行前；`docs/02-architecture/system-overview.md` 第 1–3 行；`docs/02-architecture/frontend-architecture.md` 第 1–2 行
- **Evidence**：新增条目位于 README 前言句之前、system-overview/frontend-architecture 的“状态/所属阶段”元数据列表之前，且条目与紧随段落之间无空行。
- **Suggested Fix**：将新段落移入对应文档正文（如“系统组件”之后、“边界”之内），README 条目移至“当前功能”列表。

### S-6（低）`start` 的 catch 未参与 generation 竞态保护

- **Severity**：低
- **File & Line**：`apps/web/src/pages/VoiceInput.tsx start` catch 约 100–110
- **Evidence**

```tsx
} catch {
  stream?.getTracks().forEach((track) => track.stop());
  if (context) void context.close();
  capture.current = null;            // ← 未校验 ticket === generation.current
  if (sessionId) void api.cancelVoice(projectId, sessionId).catch(() => undefined);
  setError("无法开始语音输入，请检查麦克风权限或语音服务配置。");
  setPhase("idle");
}
```

- **Description**：两处 `ticket` 提前返回已复位状态，但 catch 分支直接改写 `capture.current`。若一次旧启动的 `getUserMedia` 在“切项目/卸载后用户已重新开始并进入 recording”之后才 reject，会把新采集引用置空，导致新会话的音频与麦克风轨道泄漏（需极窄时序，非正常路径）。
- **Suggested Fix**：catch 内先 `if (ticket !== generation.current) { stream?.getTracks().forEach(t => t.stop()); return; }`，仅在仍是当前代次时才执行 `capture.current = null` 与状态复位。

### S-7（低）示例文件含固定明文凭据（既有）

- **Severity**：低
- **File & Line**：`.env.production.example` 约 19–20（diff 上下文，非本次引入）
- **Description**：含固定 demo 邮箱与明文密码，本变更未引入，属既有问题，仅提示确认线上凭据已轮换。
- **Suggested Fix**：示例值改为 `REPLACE_ON_SERVER_ONLY`。

---

## 四、已验证无需修改的要点

- **授权与作用域**：Python `_get` 同时校验 `project_id` 与 `user_id`，不一致返回 404；Java 每个入口（start/append/status/finish/cancel）均 `projectAccess.requireAccess`；`test_asr_realtime.py` 覆盖“换 userId 取预览 404”。
- **凭据边界**：阿里云 Key 仅在 `AsrService.start` 使用，未进入响应、浏览器 URL、日志或聊天历史；测试断言 `"test-only-asr-key" not in final.text`。浏览器侧 `api.ts` 只走同源 Bearer 路径。
- **音频约束**：Java 与 Python 双侧校验空、`>64_000`、奇数字节；`asr_audio` 改为流式读取并在超限时提前 400，`MAX_TOTAL`、`MAX_AGE_SECONDS`、单用户/全局并发在 Python 侧落实。
- **Attempt 1 修复**：M-1（UI 状态复位）与 M-2（不可用不扣配额）方向正确并有回归；S-1（请求体上限）已修复。
- **写入与幂等**：语音链路只回传文字，不写 Chat、不产生 Action、不触碰乐观锁与幂等键，符合 P3-05 范围与 ADR-0027“由用户编辑并主动发送”的边界。
- **异常闭合**：`stop()`/`flush()`/轮询的异常均被捕获并转为用户可见错误，未发现空指针或未捕获异常导致的主链路 500。

---

## 五、主开发（Codex）评估回填区

| Issue ID | 是否认可 | 处理方式（修复/驳回+理由） | 修复提交/位置 | 备注 |
|----------|----------|----------------------------|----------------|------|
| M-1 | 待回填 | | | 已结束会话占用并发槽；建议 `finish` 释放并补回归 |
| M-2 | 待回填 | | | 异步端点阻塞事件循环；建议线程池执行 |
| S-1 | 待回填 | | | M-2 修复引入的配额后置问题；建议两段式 |
| S-2 | 待回填 | | | 400→503 映射 |
| S-3 | 待回填 | | | 降采样抗混叠 |
| S-4 | 待回填 | | | 测试覆盖 |
| S-5 | 待回填 | | | 文档排版（Attempt 1 遗留） |
| S-6 | 待回填 | | | catch 竞态保护 |
| S-7 | 待回填 | | | 既有示例凭据 |

> 提醒：请按“文档先行后修改”流程处理，在下一轮仅提交本轮修复与新增回归测试；Pi 不执行测试，验证证据由 Codex 提供。

## Codex 处理结论

- M-1 确认并修复：`finish` 返回快照后立即释放会话；新增先红后绿的立即重录回归。
- M-2 确认并修复：音频分块读取后使用线程池执行同步上游发送；新增先红后绿的事件循环隔离回归。
- S-1 配额前置预检建议：现有 `AiUsageQuota` 只有原子 `consume`，先读后扣仍有竞态；当前会话成功后扣减且拒绝时回滚，保留原子额度语义。后续若要避免被拒用户短暂建立上游连接，需单独设计配额预留接口。
- S-2/3/4/5/6/7 为建议或既有问题；不影响本阶段已验证公共路径。生产示例中的既有敏感样式内容在本报告中已遮盖。
