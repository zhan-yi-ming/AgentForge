# Pi 代码审查报告：pre-v3-format-layout-feedback / Attempt 1

- 日期：2026-09-20
- 审查阶段：pre-v3-format-layout-feedback
- 审查对象：INDEX@0317d18（基线：0317d18d61f658d7f5b73ffa1167a141e783ccb0）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 代码审查报告

## 一、概述与总体结论

- **审查阶段**：pre-v3-format-layout-feedback
- **审查模式**：Milestone（第 1 / 3 轮）
- **审查目标**：Commit INDEX@0317d18
- **审查范围**：本次 19 个文件的完整交付范围（Web 交互/布局修复 + Java/Python 消息上限对齐 + 文档 + 测试）
- **总体结论**：**通过（PASS）**。本次变更围绕“长文整理 400 修复、聊天快捷操作收敛、Wiki 图谱全视口、登录首屏布局”四条主线，前后端与 Web 的消息长度常量已一致对齐（16,000），未发现真实 Bug、权限绕过、契约冲突、并发/幂等或数据一致性问题；新增测试对“提升后的上限”形成了红→绿证据。剩余发现均为不阻塞的建议项与文档/测试范围提示，无需修复后交付，可继续 Milestone 流程。

一致性核验（通过）：
- Java `@Size(max=16000)`、Python `max_length=16000`、Web `MAX_FORMAT_INPUT_LENGTH = 16000 - prefix.length` 三处上限方向一致，且 Web 以 UTF-16 单元计数，与 Java `@Size` 语义一致（比 Python 的 code point 计数更严格，不会产生“Web 放行、服务端拒绝”的路径）。
- 新增的表单内按钮均为 `type="button"`，未引入误触发表单提交（发送按钮仍是唯一 submit）。
- 认证分支先于 `wiki-graph` 提前返回，未认证访问 `/wiki/graph` 仍走登录页，无越权面变化。
- 本次未新增网络入口、未触碰权限/配额/审批/写入链路。

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| F1 | 建议修改（Medium） | `apps/web/src/App.tsx` | 665–667 | `/wiki/graph` 提前返回后不再渲染全局 `error` 横幅，Wiki/项目加载失败在该路由静默，表现为误导性的“空图谱” |
| F2 | 建议修改（Medium） | `services/core-api/.../AgentChatRequest.java`、`services/agent-service/src/agentforge_agent/schemas.py` | 8 / 15 | 上限“拒绝路径”与边界值缺少自动化测试，文档宣称的 400/422 无红绿证据，常量后续漂移不会被发现 |
| F3 | 建议修改（Low） | `services/core-api/src/test/.../AgentChatApiTest.java` | 105–118 | 长消息流式测试仅断言 `asyncStarted()`，未 `asyncDispatch` 校验 `complete` 事件，未覆盖流真正完成 |
| F4 | 建议修改（Low） | `docs/07-changes/2026-09-20-format-and-layout-fixes.md` | 22–36 | “验证回填：待完成”占位与下方已填写的“实测进度”并存，文档状态自相矛盾 |
| F5 | 建议修改（Low） | `apps/web/src/styles-v12.css` | 登录布局段 | `.auth-shell { overflow: hidden }` 仅在 `max-height: 590px` 兜底，中等高度/横屏移动端存在裁切风险，需目测确认 |

说明：以上均不满足“必须修改”的判定门槛（无明确的可运行性、安全、权限、并发、数据一致性或契约冲突证据），因此不触发 `NEEDS_FIX`。

## 三、逐个 Issue 展开

### F1 —— `/wiki/graph` 丢失全局错误可见性（建议修改 / Medium）

**File & Line**：`apps/web/src/App.tsx`，新增提前返回分支（约 665–667）

**Evidence**：
```tsx
if (route.page === "wiki-graph") {
  return <Suspense fallback={<p role="status">正在打开知识图谱…</p>}><WikiGraphPage projectId={projectId} pages={wikiPages} onBack={() => navigate("/wiki")} onOpenPage={...} /></Suspense>;
}
```
改动前该页面渲染在 `<main className="workspace centered-workspace">` 内，其首行是：
```tsx
{error && <p role="alert" className="error banner">{error}</p>}
```
提前返回后，该分支不再包含任何 `error` 渲染点。

**Description**：直接访问或切换到 `/wiki/graph` 时，App 壳仍会执行 `listProjects` / `listWikiPages`。若这些请求失败（网络、403、5xx），`error` 状态被设置，但图谱路由不渲染它，页面仅显示 `wiki-graph-empty`（“当前项目还没有 Wiki 页面”），把“加载失败”误报为“无数据”。`docs/03-features/web-workspace.md` 明确要求 “loading、empty、success、error 均有可见状态”，本改动在该路由上回退了这一行为。属于用户可感知的行为回退，但不影响鉴权与数据正确性，故定为建议。

**Suggested Fix**：
```tsx
if (route.page === "wiki-graph") {
  return <Suspense fallback={<p role="status">正在打开知识图谱…</p>}>
    {error && <p role="alert" className="error banner">{error}</p>}
    <WikiGraphPage projectId={projectId} pages={wikiPages} onBack={() => navigate("/wiki")} onOpenPage={(page) => { selectWiki(page); navigate("/wiki"); }} />
  </Suspense>;
}
```
（若希望保持图谱全视口纯净，可在 `WikiGraphPage` 内新增可选 `error` 展示位，或加一个独立的固定定位错误提示。）

### F2 —— 新上限的“拒绝路径/边界值”缺少测试（建议修改 / Medium）

**File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/api/AgentChatRequest.java:8`；`services/agent-service/src/agentforge_agent/schemas.py:15`

**Evidence**：
```java
@NotBlank @Size(max = 16000) String message,
```
```python
message: str = Field(min_length=1, max_length=16000)
```
新增测试仅覆盖“接受”方向：
- Java：`chatStreamAcceptsAWholeTenThousandCharacterFormattingInput`（`"文".repeat(10_000)`）
- Python：`test_chat_stream_accepts_ten_thousand_character_formatting_message`（`"文" * 10_000`）

未新增 `16_001 → 400`（Java）/`16_001 → 422`（Python）的拒绝用例，也未覆盖恰好 16,000 的边界。

**Description**：验收侧红→绿证据（8,000→16,000）已成立，这满足了本次修复的核心目标。但上述“接受”用例在常量被误改为 100,000 时仍会通过，无法锁定上限；而 `docs/04-api/agent-service.md`、`docs/04-api/core-api.md` 明确宣称“超限由入口验证拒绝 / 返回 400 Problem Details”，当前缺少对应自动化证据。因 Bean Validation 与 Pydantic 属于声明式约束、行为由框架保证，判定为测试范围提示而非必须修改。

**Suggested Fix**：补充边界用例（同一处测试文件即可）：
```java
@Test
void chatRejectsMessagesOverSixteenThousandCharacters() throws Exception {
    // "文".repeat(16001) → status().isBadRequest()
}
@Test
void chatAcceptsExactlySixteenThousandCharacters() throws Exception { /* 16000 → isOk() */ }
```
```python
def test_chat_rejects_messages_over_limit() -> None:
    response = client.post("/internal/v1/chat", headers=..., json=chat_request(message="文" * 16_001))
    assert response.status_code == 422
```

### F3 —— 流式长消息测试未验证流完成（建议修改 / Low）

**File & Line**：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentChatApiTest.java`（约 105–118）

**Evidence**：
```java
mockMvc.perform(post("/api/v1/projects/{projectId}/agent/chat/stream", projectId)
        ... .content("{\"message\":\"" + message + "\"}"))
    .andExpect(status().isOk())
    .andExpect(request().asyncStarted());
```

**Description**：该断言足以证明 10,000 字消息通过了入口校验并进入异步流（若校验失败会是同步 400），因此“上限提升可用”已被证明。但未 `asyncDispatch`，未校验 `event:complete`，不能防止后续流式路径对长消息引入新的中断回归。属于覆盖增强建议。

**Suggested Fix**：仿照同文件既有流式用例：
```java
var result = mockMvc.perform(...).andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
mockMvc.perform(asyncDispatch(result))
    .andExpect(status().isOk())
    .andExpect(content().string(containsString("event:complete")));
```

### F4 —— 变更记录自相矛盾（建议修改 / Low）

**File & Line**：`docs/07-changes/2026-09-20-format-and-layout-fixes.md`（约 22–36）

**Evidence**：
```
## 验证回填

待完成。

## 实测进度

- 故障红绿：Core 公共 SSE 请求 10,000 字符先返回 400，调整后 200 …
- Web：`npm test -- --run`，6 文件 / 70 passed / 0 failed …
```

**Description**：“验证回填：待完成”占位与已完整填写的“实测进度”并存，读者无法判断验证是否收口。文档本身是只读审查的显式证据来源，建议统一状态（`In Progress` → 视证据推进，或删除占位）。

**Suggested Fix**：将“验证回填”内容合并进“实测进度”，或改为“验证回填：见下方实测进度”，并把 `状态` 与实际验证口径对齐。

### F5 —— 登录布局极短视口兜底之外可能裁切（建议修改 / Low，需目测）

**File & Line**：`apps/web/src/styles-v12.css`，登录布局段（约 1000–1020）

**Evidence**：
```css
.auth-shell { display: grid; grid-template-rows: auto minmax(0, 1fr); height: 100dvh; min-height: 0; ... overflow: hidden; }
...
@media (max-height: 590px) { .auth-shell { height: auto; min-height: 100dvh; overflow-y: auto; } }
```

**Description**：单列移动布局（`max-width: 700px`）下仍保持 `overflow: hidden`，滚动兜底仅在视口高度 ≤ 590px 生效。横屏手机或“窄而中等高度”（约 600–700px 高）设备上，品牌 + 标题 + 说明 + 登录卡片的合计高度可能超过 `100dvh` 而被裁切。本次变更记录亦自述“多尺寸像素级目测未能提供机器证据”，故该风险尚未被视觉验证排除。属需人工目测确认项，非确定性缺陷。

**Suggested Fix**：放宽兜底触发条件或对移动单列布局默认允许纵向滚动：
```css
@media (max-width: 700px) { .auth-shell { height: auto; min-height: 100dvh; overflow-y: auto; } }
```

## 四、“无需修改”确认项（用于说明未报原因）

| 项 | 结论 | 依据 |
|----|------|------|
| 消息上限三端一致性 | 通过 | Java/Python 同为 16,000；Web `prefix + trim(input) ≤ 16_000`，UTF-16 计数与 Java 一致，无“前端放行、后端 400”路径 |
| 表单误提交 | 通过 | 新增“新建会话”“语音输入”“取消录音”按钮均为 `type="button"` |
| 认证/权限面 | 通过 | `wiki-graph` 提前返回位于 `!authenticated` 之后；未新增入口、未改动 JWT/项目授权/配额/审批 |
| 图谱节点映射 | 通过 | `graph.pages.indexOf(page) % 7` 对同一引用数组计算，`selected` 回退 `graph.pages[0]` 后所有解引用均有 `selected?` 或分支保护 |
| 长度计数的 astral 差异 | 无风险 | Java `@Size` 按 UTF-16 单元、Python 按 code point，Java 更严格，内部转发方向不会出现 Python 拒绝而 Java 放行的组合 |

## 五、主开发 (Codex) 评估回填区

| 发现 ID | 是否接受 | 处理计划 | 说明 / 反驳 |
|---------|----------|----------|-------------|
| F1 | 接受 | 新增图谱加载失败 DOM 红绿回归，独立页面显示错误并隐藏空数据提示；Web 71 tests 和 build 通过。 | 当前修复属于本轮建议项，无需 Pi attempt 2。 |
| F2 | 接受 | 增加 Java 16,001 字 400 与 Python 16,001 字 422 边界测试；Java clean verify 132 tests，Python 120 passed。 | 声明式限制与文档一致。 |
| F3 | 暂不处理 | 10,000 字入口测试已覆盖校验越界的主要故障信号；Day4 跨进程 smoke 覆盖实际流式链路。 | 完成事件专项断言留待后续关联修改。 |
| F4 | 接受 | 删除“待完成”占位，回填最终验证和本地 smoke。 | 文档状态改为 Complete。 |
| F5 | 记录限制 | ≤590px 高度允许滚动兜底，常见尺寸按紧凑单视口布局。 | 浏览器自动化受本机 ACL 阻挡，用户本地目测是最终视觉验收。 |

> 审查边界声明：本轮 Pi 为只读审查，未运行任何命令、未编辑文件、未改变 Git 状态；所有“通过”结论基于提供的 diff、文件清单与文档证据，不含 Pi 自行执行的测试结果。文档中记录的验证数据均来自 Codex 提交的“实测进度”，其“视觉/语音最终验收待用户确认”的限制随之保留，不构成代码层阻断。
