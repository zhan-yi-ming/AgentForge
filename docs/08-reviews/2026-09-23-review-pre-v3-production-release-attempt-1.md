# Pi 代码审查报告：pre-v3-production-release / Attempt 1

- 日期：2026-09-23
- 审查阶段：pre-v3-production-release
- 审查对象：30af771440e2db6381b3df97e879d8b199d1e32d（基线：af7afa3a2e743a3a8e4dce63845a7b62d20e28da）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：pre-v3-production-release（Milestone Review，第 1 / 3 轮）
- 审查模式：Milestone（只读）
- 审查对象：`af7afa3a2e743a3a8e4dce63845a7b62d20e28da .. 30af771440e2db6381b3df97e879d8b199d1e32d`
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- 审查性质：完全只读。本轮未运行命令、未修改文件、未改变 Git 状态。
- 证据来源：启动器提供的 Git Diff（**已截断，仅首/中/尾采样**）、文件清单与改动统计、显式上下文（`v2-v3-node-roadmap.md`、`production-single-host.md`）、`docs/08-reviews/` 中既有阶段报告。变更记录中的测试结论仅作外部证据引用，不代表 Pi 执行过。

---

## 一、概述与总体结论

**结论：通过（PASS），未发现具备明确代码证据的“必须修改”项。**

本轮是 V3 前置体验修复阶段的**累计发布差异**（146 文件、+8240/−219），覆盖 P3-01 路由与会话隔离、P3-02 历史生命周期、P3-03 聊天界面、P3-04 自然语言 Tool 与引用、P3-05 语音输入、P3-06 审批时限、P3-07 Wiki 图谱，以及单机 Grafana/Loki 观测栈与部署脚本。审查结论基于采样可见的 `App.tsx`、`api.ts`、`MarkdownPreview/markdown`、Core API 测试（`AgentChatApiTest`、`AgentActionServiceTest`、`AgentActionWorkflowServiceTest`、`AgentAsrServiceTest`、`AgentChatServiceTest`、`ConversationHistory*Test`）和 `.env.production.example`。

方向与边界核验（Milestone 视角）：

- 路线图 `v2-v3-node-roadmap.md` 中 P3-01 至 P3-07 已全部标注 `Implemented`，与前一阶段反复出现的“状态未同步”建议（history-lifecycle S-05 / R2-05 / R-09）一致收口。
- ADR-0023~0028 与本批实现一一对应（单机日志、路由懒加载、删除 tombstone、Intent 与最终引用、服务端中转实时 ASR、低风险审批超时），未见把 V3 组件（Neo4j/GraphRAG、LiteLLM、MCP、完整 Langfuse Trace）提前引入。
- 采样可见的审批与引用链路仍在 Java 侧收口：`AgentActionServiceTest` 验证自动审批仅对 `LOW` 风险、必须在 60 秒到期后才生效、同 `Idempotency-Key` 重放仅写一条 `AUTO_APPROVED` 审计、不同 key 冲突；`AgentChatServiceTest` 验证流式完成事件携带的 `sources` 既回传也持久化，删除的会话在扣配额与调用 Python 前被拒。
- 前端会话/路由失效逻辑在采样中保持上一轮已通过的守卫（`activeConversationLoad`、`loadingConversationId`、流中止、离开聊天中止回调）。

以下 7 项均为“建议修改”，不触发 NEEDS_FIX。同时必须在报告中声明：**由于 diff 被截断，`VoiceInput.tsx`、`asr.py`、`AgentAsrController/AgentAsrService` 最终实现、`WikiGraphPage.tsx`、`ActionApprovalDialog.tsx`、`infra/compose.prod.yaml`、`infra/nginx/production.conf.template` 与 `scripts/deploy/*` 的具体内容不在可见采样内**，本轮无法对其作断言；相关结论将在审查边界中说明。

---

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|----------|------|------|----------|
| — | — | — | — | 采样证据内未发现具备明确证据的阻塞性问题 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| S1 | 建议（中） | `apps/web/src/App.tsx`、`services/core-api/src/test/java/.../AgentChatApiTest.java` | `FORMAT_PROMPT_PREFIX` / `MAX_FORMAT_INPUT_LENGTH`；新增两条长度测试 | 前端上限由 `16_000 - prefix.length` 推导，但后端测试只证明 10,000 通过、16,001 拒绝，未证明 **16,000（恰好等于前端可达上界）通过**；任一端改动都可能让长文本“AI 整理”静默 400 |
| S2 | 建议（中） | `services/core-api/src/test/java/.../AgentActionServiceTest.java`、`AgentActionWorkflowServiceTest.java` | `approveAutomatically` / `confirmAutomatically` 用例集合 | 自动审批的 PENDING→APPROVED 幂等已测，但“**已 EXECUTED 后同 key 重放**”这一 P3-06（L3、审批域）关键分支无测试，无法证明“执行成功但响应超时”场景不会向用户回吐冲突错误 |
| S3 | 建议（中） | `.env.production.example`、`docs/06-operations/production-single-host.md` | `PUBLIC_URL_HOST=203.0.113.10` 行 | 新增生产环境变量 `PUBLIC_URL_HOST` 未出现在运维文档的服务器 `.env` 清单中，也未说明既有服务器升级需补写；若 nginx 模板或 `validate-env.sh` 依赖该值，`update.sh` 后可能渲染/校验失败（模板内容不在采样内，需核对） |
| S4 | 建议（低） | `apps/web/src/App.tsx` | `formatText()` 首行守卫 | 输入超长时静默 `return`，无错误提示；虽有 `maxLength` 兜底，但该防御分支对用户不可见、对排障不可见 |
| S5 | 建议（低） | `apps/web/src/App.tsx` | 删除确认对话框键盘 effect `if (!conversationToDelete \|\| deleteBusy) return;` | `deleteBusy` 期间整个键盘监听（含 Tab 焦点陷阱）被移除，焦点可逃逸到背景内容；Esc 关闭同时被禁用，属未显式说明的取舍 |
| S6 | 建议（低） | `apps/web/src/App.tsx` | `openConversation` 失败分支 | 详情加载失败（含 404）时仅 `report(cause)`，URL 仍停留在不可加载的 `/chat/:id`，地址与状态不一致，无回退到 `/chat` |
| S7 | 建议（低） | `apps/web/tests/app.test.tsx`、`services/core-api/src/test/java/.../AgentActionWorkflowServiceTest.java` | 用例命名 | 命名与断言不一致：`automaticConfirmation...ExecutesOnce` 未验证重放；`...AWholeTenThousandCharacter...` 与当前 16,000 上限口径不同，易误导后续维护 |

### 无需修改（本轮已核验的正确点）

| 项 | 说明 |
|----|------|
| 会话/路由失效 | 采样中 `openConversation` 先 `navigate` 目标路径、以 `activeConversationLoad` + `loadingConversationId` 作废迟到响应；离开聊天/切换面板统一 `streamAbort.current?.abort()`；`onMetadata` 同时校验 `AbortSignal` 与当前路由，前几轮认定的 F1/F2/F6 类问题在可见代码中保持修复 |
| 删除会话与 tombstone | `ConversationHistoryServiceTest` 覆盖：已删除会话再写入被拒（409）、存在未决审批时不删消息（409）、他人会话不可删（404）、Chat 路径不泄露他人会话存在性（404） |
| 删除后旧 ID 转发边界 | `AgentChatServiceTest` 断言同步与流式路径均在消费配额、调用 `client` 之前拒绝已删除会话 |
| 引用契约 | `AgentChatServiceTest`/`AgentChatApiTest` 断言 `complete` 事件携带并持久化 `sources`，SSE 正文包含来源 ID；相较上一轮“最终引用只能靠返回值”的隐患，服务与控制器两侧均有断言 |
| 自动审批安全边界 | `AgentActionServiceTest` 断言 59 秒未到期拒绝、非 LOW 风险即使是 UPDATE 也拒绝、同 idempotency key 只落一条 `AUTO_APPROVED` 审计、不同 key 冲突 |
| 常量抽取 | `normalizeMarkdownContent` 迁移到 `markdown.ts` 并在 `MarkdownPreview.tsx` 保留 re-export，`App.tsx` 无悬空引用 |

---

## 三、逐个 Issue 展开

### S1（建议，中）16,000 上界是前端可达上界，却只测了 10,000 与 16,001

- **File & Line**：`apps/web/src/App.tsx`（`FORMAT_PROMPT_PREFIX` / `MAX_FORMAT_INPUT_LENGTH`）；`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentChatApiTest.java`（新增两条长度用例）
- **Evidence**

```ts
const FORMAT_PROMPT_PREFIX = "请将以下内容整理为 Markdown，保留事实，使用一个明确的一级标题，不执行写入：\n\n";
const MAX_FORMAT_INPUT_LENGTH = 16_000 - FORMAT_PROMPT_PREFIX.length;
...
if (!projectId || !formatInput.trim() || formatInput.trim().length > MAX_FORMAT_INPUT_LENGTH) return;
... `${FORMAT_PROMPT_PREFIX}${formatInput.trim()}`
```

```java
void chatStreamAcceptsAWholeTenThousandCharacterFormattingInput() { String message = "文".repeat(10_000); ... }
...
void chatStreamRejectsMessagesAboveSixteenThousandCharacters() {
    ... "文".repeat(16_001) ... andExpect(status().isBadRequest());
}
```

- **Description**：Web 侧允许发送的最大整体长度为 `prefix.length + MAX_FORMAT_INPUT_LENGTH = 16_000`，即前端可构造的请求正文恰好落在 16,000 这一上界。后端测试只固化了 10,000 通过（远低于上界，无法区分“上限是 10,000 还是 16,000”）与 16,001 拒绝，**没有一条用例证明 16,000 被接受**。因此若 `AgentChatRequest` 的 `@Size` 仍为 10,000、或前端常量与后端上限任一方漂移，用户粘贴接近 16,000 字的长文档时“AI 整理”会返回 400；由于该路径属 P3-04/P3-07 体验修复的明确范围，属于契约边界的关键测试缺口。当前代码两侧数字自洽，故不升级为必须修改。
- **Suggested Fix**：在被截断的 diff 之外补一条边界用例（先红后绿）并同步注释常量来源。

```java
@Test
void chatStreamAcceptsMessagesAtTheExactSixteenThousandBoundary() throws Exception {
    String message = "文".repeat(16_000);
    ... andExpect(status().isOk()).andExpect(request().asyncStarted());
}
```

同时在 `App.tsx` 常量旁标明其与后端 `@Size(max = 16000)` 的耦合关系。

---

### S2（建议，中）自动审批缺少“EXECUTED 后重放”分支的测试证据

- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/application/AgentActionServiceTest.java`、`AgentActionWorkflowServiceTest.java`
- **Evidence**

```java
AgentActionView first = service.approveAutomatically(projectId, action.getId(), actor, "auto-key", "request-auto");
AgentActionView repeated = service.approveAutomatically(projectId, action.getId(), actor, "auto-key", "request-retry");
assertThat(repeated.status()).isEqualTo(AgentActionStatus.APPROVED);
assertThatThrownBy(() -> service.approveAutomatically(projectId, action.getId(), actor,
        "different-key", "request-different")).isInstanceOf(ConflictException.class);
```

```java
when(actions.executeApproved(projectId, actionId, actor, "auto-key", "auto-request")).thenReturn(executed);
assertThat(workflow.confirmAutomatically(...).status()).isEqualTo(AgentActionStatus.EXECUTED);
```

- **Description**：现有自动审批测试只覆盖 `PENDING → APPROVED` 的幂等与冲突，工作流测试只覆盖**单次**成功链路（approve→resume→execute 顺序）。P3-06 属 L3 审批域，路线图对其 Pi Review 明确要求覆盖“双击确认、重复提交、approval replay、执行成功但响应超时”。自动审批复用同一 `Idempotency-Key` 串起 approve / resume / execute 三步，是否在 Action 已 `EXECUTED` 后仍能对同一 key 的重复 auto-confirm 请求返回一致的终态，现有测试无法判定。若该分支实际抛 `ConflictException`，客户端在“执行成功但响应丢失”时仍会看到错误提示——这不构成数据重复或越权（底层 `executeApproved` 的幂等已由既有确认用例覆盖），但会与体验目标不完全一致。因缺少实现证据，列为建议。
- **Suggested Fix**：补一条聚焦用例，断言 Action 已 `EXECUTED` 后以同一 key 调用 `approveAutomatically/confirmAutomatically` 的返回值与副作用（`actions.save`、`auditEvents.save` 调用次数），并在变更记录里固化“成功但响应超时”的用户可见语义。

---

### S3（建议，中）新增 `PUBLIC_URL_HOST` 未进入运维文档的服务器环境清单

- **File & Line**：`.env.production.example`（新增行）；`docs/06-operations/production-single-host.md`（服务器 `.env` 说明段）
- **Evidence**

```text
 PUBLIC_HOST=203.0.113.10
+PUBLIC_URL_HOST=203.0.113.10
 POSTGRES_DB=agentforge
 ...
+GRAFANA_ADMIN_USER=agentforge-admin
+GRAFANA_ADMIN_PASSWORD=REPLACE_WITH_RANDOM_GRAFANA_PASSWORD
```

运维文档在“配置与首次部署”“TLS”段落中只描述了 `PUBLIC_HOST`、`AGENTFORGE_JWT_ISSUER`、`PUBLIC_WWW_HOST`（运行时派生），以及新增 Grafana 密码的**手工追加**步骤，但没有任何一处提到 `PUBLIC_URL_HOST`，也没有把它列入旧服务器升级时必须补齐的变量。

- **Description**：`infra/nginx/production.conf.template`（+30）与 `scripts/deploy/validate-env.sh`（+12）在本批中同时修改。若模板或校验脚本实际依赖 `PUBLIC_URL_HOST`，则沿用既有 `/opt/agentforge/env/.env`（不含该变量）的服务器在 `scripts/deploy/update.sh` 后可能渲染失败或被预检拒绝；即便它有默认回退，缺少文档也会让 IPv6/DNS 场景的排障缺少依据。文档对 Grafana 变量明确写了“既有服务器升级时手工追加”，对 `PUBLIC_URL_HOST` 却没有对应说明，属文档一致性缺口。由于采样中看不到模板与校验脚本内容，无法确认是否为硬依赖，故列为建议而非必须修改。
- **Suggested Fix**：核对 `infra/nginx/production.conf.template`、`scripts/deploy/validate-env.sh`、`scripts/deploy/generate-production-env.sh` 对 `PUBLIC_URL_HOST` 的使用方式；若为必需项，在 `docs/06-operations/production-single-host.md` 的配置段与升级说明中显式列出（并与 Grafana 变量并列），若为可选派生项则在文档中说明默认值来源。

---

### S4（建议，低）超长输入静默失败，无用户可见反馈

- **File & Line**：`apps/web/src/App.tsx` → `formatText()`
- **Evidence**

```ts
async function formatText() {
    if (!projectId || !formatInput.trim() || formatInput.trim().length > MAX_FORMAT_INPUT_LENGTH) return;
```

- **Description**：`FormatView` 已收到 `maxInputLength`，正常情况下 `maxLength` 会阻止超长输入，因此该分支通常不可达。但一旦被绕过（粘贴被浏览器截断策略差异、后续误改 `maxLength`），用户点击“AI 整理并预览”将没有任何提示，与同文件其它失败路径统一走 `report(cause)` 的行为不一致。
- **Suggested Fix**：

```ts
if (formatInput.trim().length > MAX_FORMAT_INPUT_LENGTH) {
    setError(`待整理内容不能超过 ${MAX_FORMAT_INPUT_LENGTH} 个字符。`);
    return;
}
```

---

### S5（建议，低）删除进行中焦点陷阱被整体移除

- **File & Line**：`apps/web/src/App.tsx` → 删除确认对话框键盘 effect
- **Evidence**

```tsx
useEffect(() => {
    if (!conversationToDelete || deleteBusy) return;
    deleteCancelRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => { ... Tab 循环 ... };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
}, [conversationToDelete, deleteBusy]);
```

- **Description**：`deleteBusy` 变真时 effect 提前返回并卸载监听，删除请求在途期间 Tab 可在背景内容间移动、Esc 不再关闭弹层。禁用 Esc 以避免在途状态下反复触发删除有其合理性，但焦点陷阱一并失效并非有意说明的设计，属可访问性细节。
- **Suggested Fix**：把“是否允许关闭”与“焦点陷阱”拆开处理：`deleteBusy` 时保留 Tab 循环（仅禁用 Esc 关闭），或对两个确认按钮设置 `disabled` 并保留容器级 keydown 拦截。

---

### S6（建议，低）会话详情加载失败后路由不回退

- **File & Line**：`apps/web/src/App.tsx` → `openConversation` 的 catch
- **Evidence**

```tsx
navigate(`/chat/${encodeURIComponent(selectedConversationId)}`);
setBusy(true); setError("");
setChatHistory([]);
setConversationId(undefined);
setPendingAction(undefined);
try {
  const detail = await api.getConversation(requestedProjectId, selectedConversationId);
  ...
} catch (cause) {
  if (activeProjectId.current === requestedProjectId && activeConversationLoad.current === load) report(cause);
}
```

- **Description**：进入会话时先改 URL 再取详情，失败（例如会话已被其他端删除返回 404）后 URL 仍是 `/chat/:id`，页面处于空白错误态；错误横幅可见，但地址栏与“当前会话不存在”状态不一致，刷新会再次失败。
- **Suggested Fix**：在 catch 中，仅当当前路由仍指向 `selectedConversationId` 时 `navigate("/chat")`（或保留 URL 但提供“返回新会话”入口），并补一条“详情 404 后回到空白 chat”的前端回归。

---

### S7（建议，低）两处测试命名与断言不符

- **File & Line**：`services/core-api/src/test/java/.../AgentActionWorkflowServiceTest.java`；`services/core-api/src/test/java/.../AgentChatApiTest.java`
- **Evidence**

```java
void automaticConfirmationResumesTheInterruptedThreadAndExecutesOnce() { ... 仅单次调用与 InOrder 断言 ... }
void chatStreamAcceptsAWholeTenThousandCharacterFormattingInput() { String message = "文".repeat(10_000); ... }
```

- **Description**：`ExecutesOnce` 未包含重放/二次调用断言，无法从名称得知实际覆盖范围；`TenThousand` 与当前 16,000 上限口径不一致（与 S1 同源），后续维护者容易据此误判边界。
- **Suggested Fix**：改名为 `automaticConfirmationCallsApproveResumeExecuteInOrder`，并把长度用例命名为 `chatStreamAcceptsMessagesBelowTheSixteenThousandLimit`（或直接改为 S1 建议的边界值用例）。

---

## 四、审查边界声明

- 本轮为只读审查，未运行任何命令、未修改文件、未改变 Git 状态；`docs/07-changes/` 与 `docs/08-reviews/` 中记录的 Web/Core 测试结论仅作为启动器提供的证据引用。
- 本次 diff 明确标注为**首/中/尾采样**。以下改动域的具体实现不在可见证据内，本轮无法对其作出“正确/存在缺陷”的断言，也不据此判定 NEEDS_FIX：`apps/web/src/pages/VoiceInput.tsx`、`WikiGraphPage.tsx`、`ActionApprovalDialog.tsx`、`ChatPage.tsx`、`route.ts`，`services/agent-service/src/agentforge_agent/asr.py` 与 ASR 相关 API/测试，`AgentAsrController.java` / `AgentAsrService.java` 最终实现，以及 `infra/compose.prod.yaml`、`infra/nginx/production.conf.template`、`infra/observability/*`、`scripts/deploy/*` 的具体内容。对其中若干项（尤其 S3）已给出需以完整 diff 复核的明确指向。
- 审查范围严格限定在本次目标、可见 diff、涉及文件与显式必要接口（Chat 长度契约、审批自动通过状态机、会话删除 tombstone、生产环境变量契约），未扩大到全项目重设计；未发现需要重新设计架构的证据。

---

## 五、主开发（Codex）评估回填区

| 发现 ID | 是否采纳 | 技术研判与依据 | 修复提交 / 验证 |
|---------|----------|----------------|------------------|
| S1 | 暂不改 | Web 上界与 Java/Python `max=16000` 一致；缺少精确边界测试是覆盖建议，当前 10,000 成功、16,001 拒绝以及发布回归通过，无可复现契约冲突。 | 记录后续测试补强。 |
| S2 | 暂不改 | `approveInternal` 对 EXECUTED 先校验同一幂等键并返回已有结果；`confirmInternal` 随即返回，不再 resume 或执行。缺少该专门测试是覆盖建议。 | 当前 Day5/发布跨进程重放通过；后续补边界测试。 |
| S3 | 不采纳 | `common.sh::load_public_config` 根据 PUBLIC_HOST 在运行时导出 PUBLIC_URL_HOST；生产 nginx 模板不读取该变量，生产 Compose 与 TLS/Nginx 契约通过。旧服务器无需手动补写。 | 已核对 `common.sh`、模板和配置门禁。 |
| S4 | 暂不改 | 超限输入由 FormatView 可见长度限制及提示覆盖；内部防御性 return 的独立提示可作为 UX 后续项。 | Web 72 项通过。 |
| S5 | 暂不改 | 删除期间焦点陷阱体验建议，无数据写入或授权边界缺陷证据。 | 保留后续无障碍改进。 |
| S6 | 暂不改 | 会话加载失败 URL 回退建议，不影响已通过的会话隔离和历史数据。 | 保留后续导航改进。 |
| S7 | 暂不改 | 测试命名建议，不影响行为或发布安全。 | 保留后续测试清理。 |

> 说明：本轮 `REVIEW_RESULT: PASS`，无“必须修改”项。S1–S7 可在发布收口前顺手处置或按依据豁免；其中 S1、S2 涉及审批/契约的边界证据，S3 涉及旧服务器升级路径，建议优先核对这三项并保留先红后绿证据。
