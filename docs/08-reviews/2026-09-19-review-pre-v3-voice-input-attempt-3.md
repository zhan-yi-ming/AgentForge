# Pi 代码审查报告：pre-v3-voice-input / Attempt 3

- 日期：2026-09-19
- 审查阶段：pre-v3-voice-input
- 审查对象：INDEX@89252ae（基线：89252ae59760ac29dd48f4ecc4ac5cd42421f224）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：pre-v3-voice-input / Attempt 3（Milestone，3/3）

- 日期：2026-09-19
- 审查阶段：pre-v3-voice-input（P3-05 语音输入）
- 审查模式：Milestone（3 / 3）
- 审查对象：`INDEX@89252ae`（基线：`89252ae59760ac29dd48f4ecc4ac5cd42421f224`）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 审查方式：仅依据给定 Git Diff、文件清单、ADR-0027、功能/接口文档与 Attempt 1、Attempt 2 报告；未执行命令、测试或 Git 操作。变更记录中 Codex 自述的 pytest/Java/Web 结果只作为外部证据引用，未由 Pi 复现。

---

## 一、概述与总体结论

- 审查范围：`89252ae` 相对基线的 30 个文件（Agent Service 实时 ASR 会话、Core API ASR 代理、Web 语音采集、部署配置与文档）。
- Attempt 2 的两项“必须修改”已完成，并与新增回归一致：
  - **M-1（`finish` 不释放会话，已结束会话占用并发槽）** — `AsrService.finish` 现在取快照后执行 `self.sessions.pop(session_id, None)`，公共 seam 回归 `test_finished_voice_session_allows_immediate_new_recording_without_delete` 覆盖“`finish` 后不 DELETE 立即重录成功”。停止后立即重录与关页后重开被 503 拒绝的主路径已消除。
  - **M-2（异步端点内阻塞事件循环）** — `asr_audio` 改为异步读取后 `await run_in_threadpool(_asr_call, lambda: service.append(...))`，阻塞 `socket.send` 移出事件循环；`test_asr_audio_runs_blocking_transport_outside_event_loop` 断言 `asyncio.get_running_loop()` 在 append 中不可用，验证隔离生效。
- Attempt 1 的 M-1（切项目/外部终止 UI 卡死）与 M-2（ASR 不可用仍扣配额）修复仍保持有效，`voice-input.test.tsx`、`AgentAsrServiceTest.unavailableProviderDoesNotConsumeChatQuota` 覆盖。
- 总体结论：**通过（可交付）**。核心方向与 ADR-0027 一致：浏览器只采集 PCM16 经 Bearer HTTP 分块上传、服务端持有阿里云 Key、Java 每次重校验项目访问、Python 会话按 `project/user` 绑定并限制并发、块大小、总字节与寿命；未发现越权、IDOR、供应商 Key 下发布局、音频/转写进入历史或自动触发 Action。剩余为配额后置语义、错误码映射、测试覆盖与文档排版等**建议级**问题，不阻塞本节点。
- 已知限制（转述证据，非 Pi 判定）：本机未配置阿里云 Key/Workspace，未做真实语音识别验收；Docker 镜像未获新构建证据。属环境阻断，不单列为问题。

---

## 二、详细发现清单

### 必须修改

（无）

### 建议修改

| ID | 严重级别 | 文件 | 行号（估算） | 核心问题 |
|----|----------|------|--------------|----------|
| S-1 | 中 | `services/core-api/.../application/AgentAsrService.java` | `start` 约 24–47 | 配额在会话建立后扣减，超额用户仍会先真实建立上游会话与 Python 槽位（Attempt 2 S-1 遗留，Codex 已说明原子 `consume` 语义） |
| S-2 | 中 | `services/agent-service/src/agentforge_agent/asr.py` | `finish` 约 120–140 | `finish` 的 `failed`/超时提前 `raise` 路径不 `pop` 会话，依赖客户端 DELETE 清理；若取消未送达，用户槽位最长占用 `MAX_AGE_SECONDS=180` |
| S-3 | 低 | `services/core-api/.../application/AgentAsrService.java` | `unavailable` 约 95–101 | 上游 400（无效音频）统一映射为 503，与 Python 契约不一致（公共入口已前置校验，正常不可达） |
| S-4 | 低 | `AgentAsrServiceTest.java`、`AgentAsrApiTest.java`、`test_asr_realtime.py` | 全文 | Java 缺 `status/finish/cancel`、404/400 映射、配额回滚（取消会话）用例 |
| S-5 | 低 | `apps/web/src/pages/VoiceInput.tsx` | `stop` 约 125–155；轮询约 90–96 | `stop` 期间在途轮询若在会话释放后 404，会在 `finish` 完成前触发 `cancel()`，可能丢弃最终转写并误报“识别中断” |
| S-6 | 低 | `apps/web/src/pages/VoiceInput.tsx` | `pcm16` 约 24–35、catch 约 100–110 | 降采样无抗混叠；`start` 的 catch 未做 `generation` 校验 |
| S-7 | 低 | `docs/03-features/README.md`、`docs/02-architecture/system-overview.md`、`frontend-architecture.md` | 第 1–3 行 | 新段落/条目插在正文前言或元数据列表之前，破坏既有结构（Attempt 1/2 遗留） |
| S-8 | 低 | `.env.production.example` | 约 19–20（既有） | 示例文件含固定 demo 邮箱与明文密码（非本次引入，仅提示轮换确认） |

### 无需修改

见第四节。

---

## 三、逐个 Issue 展开

### S-1（中）配额后置使超额用户仍触发上游会话创建

- **Severity**：中
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentAsrService.java` `start` 约 24–47
- **Evidence**

```java
StartResponse result = agentServiceRestClient.post().uri("/internal/v1/asr/sessions")
        .body(new Scope(projectId, actor.userId()))
        .retrieve().body(StartResponse.class);
if (result == null || result.sessionId() == null) throw new ServiceUnavailableException("Voice recognition is unavailable.");
try {
    aiUsageQuota.consume(actor.userId());   // 先建立真实上游会话，再扣配额
} catch (RuntimeException exception) {
    try { /* 回滚 DELETE 会话 */ } catch (RestClientException ignored) { }
    throw exception;
}
```

- **Description**：Attempt 1 的 M-2 要求“确认 ASR 可用后再扣减”，本实现完成该要求（失败不扣配额、拒绝时回滚会话），但未做创建前的只读配额预检。结果是**已耗尽当日配额的用户每次点击录音仍会先真实创建一条阿里云 WebSocket 会话与一个 Python 会话槽，随后才因 429 被拒绝并取消**，配额失去对上游资源与成本的前置约束。Codex 已在 Attempt 2 结论中说明现有 `AiUsageQuota` 仅有原子 `consume`，先读后扣存在竞态，需单独设计预留接口——该判断合理，故列为建议而非阻塞项。
- **Suggested Fix**：若后续提供原子预留/只读预检接口，可改为两段式；当前保留现有失败回滚即可：

```java
projectAccess.requireAccess(projectId, actor);
aiUsageQuota.requireAvailable(actor.userId());   // 只读预检，不消耗；需先补齐该 API
StartResponse result = /* 建立会话 */;
if (result == null || result.sessionId() == null) throw new ServiceUnavailableException(...);
try { aiUsageQuota.consume(actor.userId()); }
catch (RuntimeException exception) { /* 回滚 DELETE */ throw exception; }
```

### S-2（中）`finish` 失败/超时路径不释放会话

- **Severity**：中
- **File & Line**：`services/agent-service/src/agentforge_agent/asr.py` `finish` 约 120–140
- **Evidence**

```python
def finish(self, session_id, project_id, user_id):
    session = self._get(session_id, project_id, user_id)
    with session.lock:
        if session.failed:
            raise AsrUnavailable()            # ← 提前抛出，不 pop
        if not session.finished:
            try: session.socket.send(...)
            except Exception: session.failed = True; raise AsrUnavailable()
    if not session.done.wait(15):
        raise AsrUnavailable()                # ← 提前抛出，不 pop
    if session.failed:
        raise AsrUnavailable()                # ← 提前抛出，不 pop
    snapshot = session.snapshot(session_id)
    with self.lock:
        self.sessions.pop(session_id, None)   # 仅成功路径释放
    return snapshot
```

- **Description**：Attempt 2 的 M-1 主路径（成功 `finish` 不释放）已修复。但 `finish` 的失败/超时分支仍不释放会话，需依赖 Web 客户端 `stop()` 的 `finally` 与轮询错误的 `cancel()` 发送 DELETE 清理。正常路径可自愈（最长约 500ms），只有在取消请求丢失时才回落到 `MAX_AGE_SECONDS=180` 的槽位占用。属于残留的并发/资源健壮性问题，非当前可复现主路径，列建议。
- **Suggested Fix**：在 `finish` 的所有失败/超时分支中同样释放会话：

```python
def finish(self, session_id, project_id, user_id):
    session = self._get(session_id, project_id, user_id)
    try:
        ...
        return session.snapshot(session_id)
    finally:
        with self.lock:
            self.sessions.pop(session_id, None)
```

### S-3（低）上游 400 被映射为 503

- **Severity**：低
- **File & Line**：`services/core-api/.../AgentAsrService.java` `unavailable` 约 95–101
- **Evidence**

```java
private RuntimeException unavailable(RestClientException exception) {
    if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 404) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice session was not found.");
    }
    return new ServiceUnavailableException("Voice recognition is unavailable.", exception);
}
```

- **Description**：Python 对无效音频（空、奇数字节、超长）返回 400，Java 统一转 503。Java `append` 已做相同的空/`>64_000`/奇偶校验，正常公共调用无法触及上游 400；契约层面“无效音频=400”未在 Core 边界保持，属既知偏差。
- **Suggested Fix**：

```java
if (response.getStatusCode().value() == 400) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid voice chunk.");
}
```

### S-4（低）Java 代理路径与异常分支测试覆盖不足

- **Severity**：低
- **File & Line**：`services/core-api/src/test/java/.../AgentAsrServiceTest.java`、`AgentAsrApiTest.java`、`services/agent-service/tests/test_asr_realtime.py`
- **Evidence**：`AgentAsrApiTest` 仅覆盖 start 200/401 与 audio 401/204；`AgentAsrServiceTest` 覆盖 start 成功、上游 500、无权限三项；Python 覆盖 start/status/audio/finish/cancel、作用域不匹配与“`finish` 后立即重录”，但无 `finish` 失败释放、配额回滚 DELETE 的断言。
- **Description**：`status/finish/cancel` 的项目访问重校验与 404 映射、超大 chunk 的 400 分支、以及 S-1/S-2 的语义均无自动化保护；结合 M-1 的修复历史，这些是可回归风险。
- **Suggested Fix**：补 Java `MockRestServiceServer` 对 404→NOT_FOUND、400→BAD_REQUEST、`finish/cancel` 透传与配额回滚 DELETE 的用例；补 Python `finish` 失败即释放会话、失败不计入并发的回归。

### S-5（低）`stop` 期间在途轮询失败可能触发取消并丢弃最终转写

- **Severity**：低
- **File & Line**：`apps/web/src/pages/VoiceInput.tsx` `stop` 约 125–155；`current.poll` 回调约 90–96
- **Evidence**

```tsx
current.poll = window.setInterval(() => {
  void api.getVoice(projectId, sessionId!).then((snapshot) => {
    if (capture.current === current) setPreview(snapshot.text);
  }).catch(() => {
    if (capture.current === current) { setError("语音识别中断，请重试。 "); void cancel(); }   // ← 可能仍在 stop 流程中
  });
}, 500);
```

- **Description**：`stop()` 先 `closeLocal` 清除轮询，但一个在 `stop` 之前已发出的 `getVoice` 若在 `finishVoice` 释放会话后才返回 404，其 `catch` 仍满足 `capture.current === current`，会调用 `cancel()` 将 `capture.current` 置空并把 `phase` 复位；随后 `stop()` 的 `await api.finishVoice` 返回时 `capture.current !== current`，最终转写被静默丢弃且显示误导性的“识别中断”。需极窄时序（轮询与停止重叠），非正常路径。
- **Suggested Fix**：用 `current.closed` 或 `phase` 判断跳过停止后的轮询失败处理：

```tsx
.catch(() => {
  if (!current.closed && capture.current === current) { setError("语音识别中断，请重试。"); void cancel(); }
});
```

### S-6（低）降采样无抗混叠与 `start` catch 竞态

- **Severity**：低
- **File & Line**：`apps/web/src/pages/VoiceInput.tsx` `pcm16` 约 24–35、`start` catch 约 100–110
- **Evidence**

```tsx
const sample = Math.max(-1, Math.min(1, samples[Math.floor(index * ratio)]));  // 最近邻抽样
```

```tsx
} catch {
  stream?.getTracks().forEach((track) => track.stop());
  if (context) void context.close();
  capture.current = null;   // ← 未校验 ticket === generation.current
  ...
}
```

- **Description**：48 kHz→16 kHz 最近邻抽样产生混叠，可能降低中文 ASR 识别率（无真实语音样本前不阻塞）。`catch` 分支直接改写 `capture.current`，若旧启动的 `getUserMedia` 在切项目/卸载后、新采集已进入 recording 时才 reject，会把新采集引用置空并泄漏其麦克风轨道（极窄时序）。
- **Suggested Fix**：对每个输出样本取输入窗口均值/一阶低通；catch 内先做代次校验：

```tsx
} catch {
  if (ticket !== generation.current) { stream?.getTracks().forEach((track) => track.stop()); return; }
  ...
}
```

### S-7（低）文档插入位置破坏既有结构

- **Severity**：低
- **File & Line**：`docs/03-features/README.md` 第 3 行前；`docs/02-architecture/system-overview.md` 第 1–3 行；`docs/02-architecture/frontend-architecture.md` 第 1–2 行
- **Evidence**：新增段落位于 README 前言句之前、system-overview/frontend-architecture 的“状态/所属阶段”元数据列表之前，且与紧随段落之间无空行。
- **Suggested Fix**：将新段落移入对应文档正文（如“系统组件”之后、“边界”之内），README 条目移至“当前功能”列表。

### S-8（低）示例文件含固定明文凭据（既有）

- **Severity**：低
- **File & Line**：`.env.production.example` 约 19–20（diff 上下文行，非本次引入）
- **Description**：含固定 demo 邮箱与明文密码，本变更未引入，属既有问题；本报告已遮盖原值，仅提示确认生产凭据未复用。
- **Suggested Fix**：示例值改为 `REPLACE_ON_SERVER_ONLY`，确认线上密码已轮换。

---

## 四、已验证无需修改的要点

- **Attempt 2 必须修改项**：M-1（`finish` 成功即 `pop` 会话）与 M-2（`run_in_threadpool` 隔离阻塞发送）均已修复，且 Python 公共 seam 回归先红后绿覆盖，方向正确。
- **Attempt 1 必须修改项**：`VoiceInput` 切项目/外部终止的两处提前返回与 `useEffect` 清理均复位 `phase`（`voice-input.test.tsx` 两条路径覆盖）；`AgentAsrService` 改为会话建立后再扣配额并失败回滚（`unavailableProviderDoesNotConsumeChatQuota`）；`asr_audio` 流式读取超 `MAX_CHUNK` 立即 400。
- **授权与作用域**：Python `_get` 同时校验 `project_id` 与 `user_id`，不一致/过期返回 404；Java 每个入口（start/append/status/finish/cancel）均 `projectAccess.requireAccess`，`AuthenticatedActor.from(jwt)` 提供 actor。
- **凭据边界**：阿里云 Key 仅在 `AsrService.start` 使用，未进入浏览器包、URL、响应、日志或聊天历史；`api.ts` 只走同源 Bearer 路径，音频块使用 `byteOffset/byteLength` 精确切片。
- **音频约束**：Java 与 Python 双侧校验空、`>64_000`、奇数字节；`MAX_TOTAL`、`MAX_AGE_SECONDS`、单用户/全局并发在 Python 侧落实。
- **写入与幂等**：语音链路只回传文字，不写 Chat、不产生 Action、不触碰乐观锁与幂等键，符合 P3-05 与 ADR-0027“用户编辑后主动发送”的边界。
- **方向与边界**：未引入 V3 组件（Neo4j/GraphRAG、LiteLLM、MCP 等），未扩大到无关模块，符合 V3 前置体验修复阶段范围。

---

## 五、主开发（Codex）评估回填区

| Issue ID | 是否认可 | 处理方式（修复/驳回+理由） | 修复提交/位置 | 备注 |
|----------|----------|----------------------------|----------------|------|
| S-1 | 待回填 | | | 配额后置；需单独评估原子预留接口 |
| S-2 | 待回填 | | | `finish` 失败/超时分支释放会话 |
| S-3 | 待回填 | | | 400→503 映射 |
| S-4 | 待回填 | | | Java 异常分支与代理路径测试 |
| S-5 | 待回填 | | | `stop` 期间在途轮询失败 |
| S-6 | 待回填 | | | 降采样抗混叠 / catch 代次校验 |
| S-7 | 待回填 | | | 文档排版（Attempt 1/2 遗留） |
| S-8 | 待回填 | | | 既有示例凭据 |

> 提醒：本轮为最终轮（3/3），无“必须修改”项；以上均为建议，可在 P3-05 收尾或后续安全清理中按“文档先行后修改”处理。Pi 不执行测试，验证证据由 Codex 提供。
