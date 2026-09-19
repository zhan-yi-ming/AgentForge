# Pi 代码审查报告：pre-v3-voice-input / Attempt 1

- 日期：2026-09-19
- 审查阶段：pre-v3-voice-input
- 审查对象：INDEX@89252ae（基线：89252ae59760ac29dd48f4ecc4ac5cd42421f224）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# P3-05 语音输入 — Milestone Review 报告

## 一、概述与总体结论

- 审查阶段：pre-v3-voice-input（P3-05，Milestone，1/3）
- 审查目标：`89252ae` 相对基线的 30 个文件变更（Agent Service ASR、Core API ASR 代理、Web 语音采集、文档与部署配置）
- 本次审查仅依据给定 Diff、文件清单与显式路线/ADR/功能文档，未执行任何命令、测试或 Git 操作；变更记录中 Codex 自述的 pytest/Java/Web 结果仅作为外部证据引用，未由 Pi 复现。
- 总体结论：**需修复后交付**。核心方向、边界与 ADR-0027 决策一致：浏览器只采集 PCM16 并经 Bearer HTTP 分块上传、服务端持有阿里云 Key、Java 每次重校验项目访问、Python 会话按 project/user 绑定；未发现越权、密钥下发浏览器、聊天历史写入或 Action 自动触发。但存在 2 个有明确证据的正确性/配额一致性问题，其中配额问题直接违背功能文档“未配置时语音入口返回 503，文字聊天照常运行”的承诺。
- 已知限制（非 Pi 判定，转述证据）：本机未配置阿里云 Key/Workspace，未做真实语音识别验收；Docker 镜像拉取未获新构建证据。该限制属于环境阻断，不构成本轮独立问题。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（估算） | 核心问题 |
|----|----------|------|--------------|----------|
| M-1 | 高 | `apps/web/src/pages/VoiceInput.tsx` | 91–97、147–155 | 采集被外部终止后 `phase` 未复位，UI 永久停在“停止录音/连接语音…”且无法再启动 |
| M-2 | 高 | `services/core-api/src/main/java/com/agentforge/core/agent/application/AgentAsrService.java` | 31 | 在调用 Agent Service 之前扣减 AI 日配额，ASR 未配置/不可用时白扣配额并可耗尽当日 AI 额度 |
| S-1 | 中 | `services/agent-service/src/agentforge_agent/api.py` | 约 99–104 | `asr_audio` 先把整个 body 读入内存，之后才做 64 KB 校验，缺少流式/长度上限保护 |
| S-2 | 中 | `services/core-api/.../AgentAsrService.java` | 约 66–71 | 上游 400 被统一映射为 503，与 Python 契约（无效音频 400）不一致 |
| S-3 | 中 | `apps/web/src/pages/VoiceInput.tsx` | 24–35 | 降采样用最近邻抽样且无抗混叠滤波，可能降低 ASR 识别率 |
| S-4 | 中 | `services/core-api/src/test/.../AgentAsrServiceTest.java`、`AgentAsrApiTest.java` | 全文 | Java 侧仅覆盖 `start` 与 audio 401/204；`append/status/finish/cancel`、404 映射、超大 chunk 400 无测试 |
| S-5 | 低 | `docs/03-features/README.md`、`docs/02-architecture/system-overview.md`、`frontend-architecture.md` | 1–3 | 新段落/条目被插到文档前言或元数据列表之前，破坏既有结构 |
| S-6 | 低 | `.env.production.example` | 约 19–20 | 示例文件含固定 demo 邮箱与明文密码（本变更前已存在，非本次引入） |

（未发现需要归入“必须修改”的越权、契约破坏或并发/幂等缺陷。）

## 三、逐个 Issue 展开

### M-1（高）采集被外部终止后 `phase` 未复位

- File & Line：`apps/web/src/pages/VoiceInput.tsx` 约 91–97（`start()` 提前返回）、约 147–155（`useEffect` 清理）
- Evidence：

```tsx
// start() 中两处提前返回，均未调用 setPhase("idle")
stream = await navigator.mediaDevices.getUserMedia({ ... });
if (ticket !== generation.current) { stream.getTracks().forEach((t) => t.stop()); return; }   // ①仍停留在 "starting"
sessionId = (await api.startVoice(projectId)).sessionId;
if (ticket !== generation.current) {
  stream.getTracks().forEach((t) => t.stop());
  void api.cancelVoice(projectId, sessionId).catch(() => undefined);
  return;                                                                                     // ①仍停留在 "starting"
}
```

```tsx
useEffect(() => () => {
  generation.current += 1;
  const current = capture.current;
  capture.current = null;
  if (current) { closeLocal(current); void api.cancelVoice(projectId, current.sessionId).catch(() => undefined); }
}, [api, projectId]);   // ②只清理采集，不 setPhase
```

- Description：`App.tsx` 中 `<Suspense>` 内渲染 `ChatPage`，`projectId` 作为 prop 变化时组件不会卸载，`VoiceInput` 实例被复用（切换项目同时 `navigate("/chat")` 同路径不触发重挂载）。此时依赖数组 `[api, projectId]` 触发清理：②在“recording”阶段只 `closeLocal` 并取消服务端会话，却不把 `phase` 复位为 `"idle"`，按钮继续显示“停止录音”；用户再点击只会进入 `stop()`，而 `stop()` 因 `capture.current === null` 直接 `return`，从此无法再启动语音。①在“starting”阶段（麦克风授权弹窗或 `startVoice` 在途中切换项目）提前返回，同样把 `phase` 永久留在 `"starting"`，按钮显示“连接语音…”且持续 `disabled`。这属于真实、可复现的状态机缺陷；功能文档承诺“切项目会关闭采集与服务端 ASR 会话”，会话确实关闭了，但 UI 进入不可恢复状态。
- Suggested Fix：抽出统一的本地复位函数，在清理与所有提前返回路径中调用；项目切换路径允许 `setState`：

```tsx
function resetLocal(current: Capture | null, message?: string) {
  if (current) { closeLocal(current); void api.cancelVoice(projectId, current.sessionId).catch(() => undefined); }
  if (message) setError(message);
  setPhase("idle"); setPreview("");
}

// ②
useEffect(() => () => {
  generation.current += 1;
  const current = capture.current;
  capture.current = null;
  if (current) { closeLocal(current); void api.cancelVoice(projectId, current.sessionId).catch(() => undefined); }
  setPhase("idle");   // 组件已卸载时 React 18 安全忽略
  setPreview("");
}, [api, projectId]);

// ①
if (ticket !== generation.current) { stream.getTracks().forEach((t) => t.stop()); setPhase("idle"); return; }
if (ticket !== generation.current) {
  stream.getTracks().forEach((t) => t.stop());
  void api.cancelVoice(projectId, sessionId).catch(() => undefined);
  setPhase("idle"); return;
}
```

### M-2（高）配额在确认 ASR 可用之前被扣减

- File & Line：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentAsrService.java` 约 31
- Evidence：

```java
public UUID start(UUID projectId, AuthenticatedActor actor) {
    projectAccess.requireAccess(projectId, actor);
    aiUsageQuota.consume(actor.userId());          // ← 先扣配额
    try {
        StartResponse result = agentServiceRestClient.post().uri("/internal/v1/asr/sessions")
                .body(new Scope(projectId, actor.userId())).retrieve().body(StartResponse.class);
        ...
    } catch (RestClientException exception) { throw unavailable(exception); }  // 503
}
```

- Description：`AGENTFORGE_AGENT_ASR_API_KEY/WORKSPACE_ID` 为空时（`.env.example` 默认即为空，`config.py` 的 `start()` 会抛 `AsrUnavailable` → 503），Java 仍已调用 `aiUsageQuota.consume(...)`。生产配置 `AGENTFORGE_AI_DAILY_LIMIT=30`，用户在未配置语音的部署上点击麦克风 N 次即耗尽当日 AI 配额，随后文字聊天返回 429。这与 `docs/03-features/voice-input.md` 明确写的“未配置时语音入口返回 503，文字聊天照常运行”直接冲突，也违反“失败不应消耗额度”的一致性预期。现有 `AgentAsrServiceTest` 只断言成功路径 `verify(quota).consume(...)`，未覆盖失败路径，无法防止回归。
- Suggested Fix：把配额扣减移到上游会话建立成功之后（或引入仅在确认可用后扣减的两段式），确保 503/不可用时不计费：

```java
public UUID start(UUID projectId, AuthenticatedActor actor) {
    projectAccess.requireAccess(projectId, actor);
    StartResponse result;
    try {
        result = agentServiceRestClient.post().uri("/internal/v1/asr/sessions")
                .body(new Scope(projectId, actor.userId())).retrieve().body(StartResponse.class);
    } catch (RestClientException exception) { throw unavailable(exception); }
    if (result == null || result.sessionId() == null) throw new ServiceUnavailableException("Voice recognition is unavailable.");
    aiUsageQuota.consume(actor.userId());   // 会话已建立后再扣减
    return result.sessionId();
}
```

并补充失败路径测试：上游 503 / `result == null` 时 `verifyNoInteractions(quota)`。

### S-1（中）`asr_audio` 缺少请求体长度上限

- File & Line：`services/agent-service/src/agentforge_agent/api.py` 约 99–104
- Evidence：

```python
async def asr_audio(session_id: UUID, request: Request, ...):
    audio = await request.body()                 # 先整体读入内存
    _asr_call(lambda: service.append(session_id, project_id, user_id, audio))  # 之后才校验 MAX_CHUNK
```

- Description：Java 侧已限制单块 ≤ 64 000 字节，`AsrService.append` 也会校验，但内部端点本身没有在读取前拒绝超大 body，持有 internal token 的调用方或被绕过的路径可让进程一次性分配任意大小内存。影响面受 internal token 保护，故列为建议。
- Suggested Fix：在读取前检查 `Content-Length`，或对流式读取并在超过 `AsrService.MAX_CHUNK` 时立即 413/400：

```python
length = request.headers.get("content-length")
if length and int(length) > AsrService.MAX_CHUNK:
    raise HTTPException(status_code=413, detail="Invalid voice chunk.")
audio = await request.body()
```

### S-2（中）上游 400 被映射为 503

- File & Line：`services/core-api/.../AgentAsrService.java` 约 66–71
- Evidence：

```java
private RuntimeException unavailable(RestClientException exception) {
    if (exception instanceof RestClientResponseException response && response.getStatusCode().value() == 404) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Voice session was not found.");
    }
    return new ServiceUnavailableException("Voice recognition is unavailable.", exception);
}
```

- Description：Python 对无效音频返回 400（`AsrInvalidAudio`），Java 却统一转成 503。Java `append` 已做同样校验，正常路径不可达，但契约上“无效音频”应为 400，与 `docs/04-api/agent-service.md` 描述的错误语义不一致。
- Suggested Fix：增加 400 分支映射为 `ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid voice chunk.")`。

### S-3（中）PCM16 降采样缺少抗混叠处理

- File & Line：`apps/web/src/pages/VoiceInput.tsx` 约 24–35
- Evidence：

```tsx
const ratio = sourceRate / 16000;
for (let index = 0; index < count; index += 1) {
  const sample = Math.max(-1, Math.min(1, samples[Math.floor(index * ratio)]));  // 最近邻抽样
```

- Description：48 kHz → 16 kHz 用最邻近抽样不做低通/均值，会产生混叠，可能降低中文 ASR 识别率。非阻塞，但影响功能质量。
- Suggested Fix：对每个输出样本取对应输入窗口均值（或加简单一阶低通）后再量化。

### S-4（中）Java 侧测试范围不足

- File & Line：`services/core-api/src/test/java/.../AgentAsrServiceTest.java`、`AgentAsrApiTest.java`
- Evidence：`AgentAsrServiceTest` 仅有 `authorizedStartSendsScopedRequestAndConsumesOneQuota`、`deniedProjectCannotOpenProviderSessionOrConsumeQuota`；`AgentAsrApiTest` 仅覆盖 start 200/401 与 audio 401/204。
- Description：`append/status/finish/cancel` 的项目访问重校验、404 映射、503 映射、超大 chunk 400 分支均无自动化覆盖；结合 M-2，失败路径缺少测试已成为可回归风险。
- Suggested Fix：补充上述分支的 MockRestServiceServer 测试与 MockMvc 越大 chunk 400 测试。

### S-5（低）文档插入位置破坏既有结构

- File & Line：`docs/03-features/README.md` 第 3 行前、`docs/02-architecture/system-overview.md` 第 1–3 行、`docs/02-architecture/frontend-architecture.md` 第 1–2 行
- Evidence：新增条目被插在 README 的“每个用户可见能力……”前言句之前、system-overview/frontend-architecture 的“状态/所属阶段”元数据列表之前。
- Suggested Fix：把新段落移动到对应文档的正文章节内（如“系统组件”之后、“边界”之内）。

### S-6（低）示例文件含固定明文凭据（既有）

- File & Line：`.env.production.example` 约 19–20（diff 上下文行，非本次新增）
- Evidence：`AGENTFORGE_DEMO_FIXED_EMAIL=[已遮盖]`、`AGENTFORGE_DEMO_FIXED_PASSWORD=[已遮盖]`。
- Description：本变更触及该文件但并未引入该值，属既有问题；因属“敏感信息”审查范围，提示确认生产密码是否已轮换。
- Suggested Fix：示例值改为 `REPLACE_ON_SERVER_ONLY`，确认线上凭据未复用。

## 四、已验证无需修改的要点

- 授权与作用域：Python `_get` 同时校验 `project_id` 与 `user_id`，跨用户/跨项目访问返回 404；Java 每个入口均 `projectAccess.requireAccess`；`test_asr_realtime.py` 已覆盖 scope 不匹配 404。
- 凭据边界：阿里云 Key 仅在 `AsrService.start` 使用，未进入响应、URL（浏览器侧）、日志或聊天历史；测试断言 `"test-only-asr-key" not in final.text`。
- 音频约束：Java 与 Python 双侧均校验空、`>64_000`、奇数字节；总字节/单用户并发/全局并发/寿命在 Python 侧限制。
- 幂等/写入：语音链路只返回文字，不写 Chat、不产生 Action，不涉及乐观锁与幂等键，符合 P3-05 范围。
- 搜索：未发现空指针、未捕获异常导致的 500 主链路，`stop()`/`flush()` 的异常均被捕获并转换为用户可见错误。

## 五、主开发（Codex）评估回填区

| Issue ID | 是否认可 | 处理方式（修复/驳回+理由） | 修复提交/位置 | 备注 |
|----------|----------|----------------------------|----------------|------|
| M-1 | 认可 | 切项目清理与启动竞态均复位 UI；Web 回归先红后绿 | VoiceInput.tsx + voice-input.test.tsx | 已修复 |
| M-2 | 认可 | ASR 建立成功后才消耗配额；配额拒绝时关闭会话；Java 回归先红后绿 | AgentAsrService.java + AgentAsrServiceTest.java | 已修复 |
| S-1 | 认可 | 请求体按流读取并在超过 64 KB 时立即拒绝；Python 回归先红后绿 | api.py + test_asr_api.py | 已修复 |
| S-2 | 部分认可 | Java 已在转发前校验相同音频约束，正常公共调用无法触及上游 400；未见真实契约偏差 | AgentAsrService.java | 记录建议 |
| S-3 | 认可风险 | 最近邻会影响极端音频质量，但缺少真实阿里云识别数据确定改法；P3-05 可先使用 | VoiceInput.tsx | 真实验收后评估 |
| S-4 | 认可覆盖可扩充 | 已覆盖 Core 授权/开始/块边界与修复的失败配额；其余不阻断现有门禁 | AgentAsrServiceTest.java / AgentAsrApiTest.java | 建议 |
| S-5 | 认可 | 文档排版建议，未造成接口或功能错误 | 文档 | 建议 |
| S-6 | 既有问题 | 示例凭据样式内容并非本次新增；审核报告已遮盖原值，后续单独安全清理 | .env.production.example | 不混入 P3-05 |

> 提醒：请按“文档先行后修改”流程处理，并在下一轮仅提交修复与新增回归测试；Pi 不执行测试，验证证据由 Codex 提供。
