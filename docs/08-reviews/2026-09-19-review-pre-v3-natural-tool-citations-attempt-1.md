# Pi 代码审查报告：pre-v3-natural-tool-citations / Attempt 1

- 日期：2026-09-19
- 审查阶段：pre-v3-natural-tool-citations
- 审查对象：INDEX@03261b3（基线：03261b3be35d8282d1827fe87b8b855b7da75e70）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge P3-04 自然语言 Tool 与引用 — Milestone 审查报告

## 一、概述与总体结论

- **审查阶段**：`pre-v3-natural-tool-citations`（Milestone，第 1/3 轮）
- **审查目标**：`INDEX@03261b3`，24 个文件，+433/-29
- **总体结论**：**通过（可交付，无需修复后交付）**
- **阻断性问题**：无

本次变更把两件事解耦：由 LLM 在 retrieve 之后独立产出受严格 schema 校验的 Action Intent（`natural_tool_planner`），以及把结构化 `sources` 从“检索候选”改为“完成回答实际标注的编号”（`cited_sources`）。我逐条核对了三条链路的一致性：

1. **编号一致性**：`_build_context` 按来源首次出现顺序分配 `【来源N】`（`source_numbers` 只在 chunk 被接纳时写入），`_deduplicate_sources` 亦按相同首次出现顺序去重，因此 `candidates[N-1]` 与上下文编号在重复来源、部分截断、预算中断等场景下均保持一致，未发现错位。
2. **契约一致性**：Python 同步 JSON 用 `cited_sources`；流式 `metadata.sources=[]`、`complete.sources=最终引用`；Java `AgentChatService.stream` 先从 metadata 再被 complete 覆盖，`finalizeEvent`/`persist` 均使用最终 sources；Web 客户端在 complete 覆盖 metadata；Controller complete 分支输出 sources。四方一致。
3. **安全边界**：模型输出经 `set(raw) <= ALLOWED_FIELDS` 白名单（拒绝 `projectId` 等）、动作白名单、字段类型/长度、以及 `taskId+expectedVersion` 必须命中本次检索到的已授权 `TaskTarget` 才放行，权限/审批/写入仍全在 Java，未见越权或跨项目路径。

结论：未发现“必须修改”级别的真实 Bug、权限、契约或并发/幂等缺陷；另有若干可验证项与改进建议，不阻塞交付。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
|----|----------|------|------|----------|
| R-01 | 建议（中） | `apps/web/src/api.ts` | 121-125 | 最终 sources 只写入返回对象，`onMetadata` 已用空数组触发，依赖该回调的来源 UI 可能始终为空 |
| R-02 | 建议（中） | `services/core-api/.../infrastructure/AgentServiceClient.java`（未在 diff 中） | — | 缺少直接验证 Java 从 NDJSON `complete` 行解析 `sources` 的测试/证据 |
| R-03 | 建议（低） | `services/agent-service/src/agentforge_agent/llm.py` | `plan_tool` | 规划调用未接入 observation / 异常被裸 `except Exception: return None` 吞掉，无排障信息 |
| R-04 | 建议（低） | `services/agent-service/src/agentforge_agent/retrieval.py` | `_build_context` / `cited_sources` | 未包含 `【来源N】` 的跨 chunk 截断与“越界编号”直测；确定性 responder 会原样回显编号导致全量引用 |
| R-05 | 建议（低） | `services/agent-service/src/agentforge_agent/llm.py` | `plan_tool` 内 `max_tokens=512` | 规划输出上限硬编码，未复用 Settings 配置 |
| R-06 | 无需修改 | `services/agent-service/src/agentforge_agent/llm.py` | `PROVIDER_DEFAULTS` | `deepseek-flash` 改名有官方依据且保留 `llm_model` 覆盖，属预期修复 |

---

## 三、逐个 Issue 展开

### R-01 Web 最终来源只覆盖返回值，未重新触发 metadata 回调
- **Severity**：建议（中）
- **File & Line**：`apps/web/src/api.ts:121-125`
- **Evidence**
```ts
if (eventName === "metadata") {
  metadata = { conversationId: ..., requestId: ..., sources: (data.sources ?? []) as AgentSource[] };
  callbacks.onMetadata?.(metadata);          // 此时 sources 恒为 []
} else if (eventName === "complete") {
  pendingAction = ...;
  if (metadata) metadata = { ...metadata, sources: (data.sources ?? []) as AgentSource[] };
  completed = true;                           // 未再触发 onMetadata
}
...
return { ...metadata, answer, pendingAction };
```
- **Description**：新契约下 `metadata.sources` 恒为空数组，最终来源只在 `complete` 结算并写入 `metadata`。若聊天组件仅通过 `onMetadata` 更新来源状态（而不是使用 `chatStream` 的返回值），片尾来源将永远为空。返回对象本身是正确的，因此是否构成真实回归取决于组件实现。
- **Suggested Fix**：在 complete 覆盖后补一次回调（如新增 `callbacks.onSources?.(metadata.sources)`），或明确要求页面从 `await chatStream(...)` 的返回值读取 `sources`；并在 Web 测试中补断言 `onMetadata` 与最终返回值的差异行为。

### R-02 Java 侧对 `complete.sources` 的 NDJSON 解析缺少直接验证
- **Severity**：建议（中）
- **File & Line**：`services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/AgentServiceClient.java`（未出现在本次 diff）
- **Evidence**
```java
// AgentChatServiceTest：直接 mock client，灌入带 sources 的 complete 事件
sink.accept(new AgentStreamEvent("complete", null, null, List.of(citedSource), null, null, null, null));
// AgentChatApiTest：mock service
sink.accept(new AgentStreamEvent("complete", null, null, List.of(...), null, null, null, null));
```
- **Description**：本次新增的 `complete.sources` 要跨越 Python NDJSON → Java `AgentServiceClient` 反序列化。现有测试都绕过该反序列化（mock client 或 mock service）。若 client 采用按事件类型分支解析而非通用映射，则 `complete` 的 sources 会静默为空，单元测试仍全绿。属于 L3 跨服务契约的关键分支覆盖问题。
- **Suggested Fix**：补一条 client 层测试（喂入真实 `{"type":"complete","sources":[...]}` 行并断言 `event.sources()` 非空），或在跨进程 smoke 中显式断言 SSE `complete` 事件包含来源。

### R-03 规划调用可观测性缺失、异常被静默吞掉
- **Severity**：建议（低）
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py` `plan_tool`
- **Evidence**
```python
try:
    response = planner_model.invoke([...])
except Exception:
    return None
return parse_tool_intent(getattr(response, "content", None), bundle)
```
- **Description**：Provider 故障、限流、超时与“模型正常返回 NONE/无效”不可区分，且该调用未进入 `generation`/`tool` 观测，排障时无法判断“为何没有提案”。不影响正确性（fail-closed 到确定性/无动作是正确的），但会显著增加线上定位成本。
- **Suggested Fix**：复用既有 observation（或在 `plan` 节点的 `_observe` 内）记录规划失败类别，异常仅记录类型不记录正文/凭据。

### R-04 引用筛选与确定性回显的边界测试缺口 / 行为提示
- **Severity**：建议（低）
- **File & Line**：`services/agent-service/src/agentforge_agent/retrieval.py` `_build_context`、`cited_sources`
- **Evidence**
```python
def cited_sources(answer, candidates):
    for match in re.finditer(r"【来源([1-9][0-9]*)】", answer):
        index = int(match.group(1)) - 1
        if 0 <= index < len(candidates) and index not in seen:
```
```python
# deterministic_responder 原样回显 bundle.retrieved.content（含 【来源N】）
return f"Relevant project context for '{...}':\n\n{context}"
```
- **Description**：逻辑本身正确（越界被 `index < len(candidates)` 过滤）。两点值得记录：① 未直测“越界编号”（如候选 1 个却标注 `【来源9】`）与“块被字符预算截断后编号不丢”的组合；② `disabled` 模式下 responder 原样回显上下文，会导致 `cited_sources` 把所有候选都判为“已引用”，语义上等于回到旧行为，属可接受的降级但应在文档/测试中固化预期。
- **Suggested Fix**：补 `cited_sources("【来源9】", [source]) == []` 及“预算截断后编号仍连续”的单测；如需严格，确定性 responder 可剥离内部 `【来源N】` 前缀。

### R-05 规划输出上限硬编码
- **Severity**：建议（低）
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py` `plan_tool`
- **Evidence**：`self.model.bind(response_format={"type": "json_object"}, max_tokens=512)`
- **Description**：生成阶段使用 `AGENTFORGE_AGENT_LLM_MAX_TOKENS`，规划阶段却写死 512，配置语义不一致；对超短 JSON 意图影响有限。
- **Suggested Fix**：从 Settings 读取或用独立常量集中定义，并保持 64–4096 边界。

### R-06 DeepSeek 默认模型名变更
- **Severity**：无需修改
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py` `PROVIDER_DEFAULTS`
- **Evidence**：`"deepseek": ("https://api.deepseek.com", "deepseek-flash")`，测试参数化同步更新，且 `build_responder` 保留显式 `llm_model` 覆盖。
- **Description**：与变更记录声明的官方依据一致，且未移除覆盖能力，属预期根因修复。离线无法独立核验官方模型名，但实现方式本身无缺陷。

---

## 四、核对通过项（无需修改）

- **编号/索引一致性**：重复来源、部分截断、预算中断场景下 `_build_context` 与 `_deduplicate_sources` 顺序一致，`candidates[N-1]` 无错位。
- **Action Intent 安全边界**：字段白名单拒绝未知字段（含 `projectId`），`UPDATE_TASK` 必须命中本次检索的 `taskId + expectedVersion`，Java 仍是权限/审批/写入唯一权威。
- **契约闭环**：Python metadata/complete、Java Controller/Service、Web 客户端四处语义一致；`AgentStreamEvent` 紧凑构造器保证 `sources` 非空。
- **并发/幂等**：本次未改动 Action 五态、`Idempotency-Key`、版本冲突语义，无新增风险面。
- **文档与实现一致**：ADR-0026、`rag-retrieval.md`、`agent-service.md`、`tool-calling-and-confirmation.md`、`frontend-architecture.md` 与代码行为一致；变更记录如实说明“未执行真实线上模型调用”。

---

## 五、主开发（Codex）评估回填区

| 发现 ID | 是否认同 | 计划处理 | 处理说明 / 反证 | 复核结论 |
|---------|----------|----------|-----------------|----------|
| R-01 | 是，条件性风险 | 本阶段无需修改 | `App.tsx` 的 `await api.chatStream(...)` 返回后明确将 `result.sources` 写入当前消息；`onMetadata` 用于会话路由与流开始时的状态。Web client 测试覆盖最终来源。 | 已排除实际回归 |
| R-02 | 是，测试建议 | 本阶段无需修改 | 实际类是 `HttpAgentServiceClient`，按行使用 Jackson `objectMapper.readValue(line, AgentStreamEvent.class)`，没有按事件类型选择字段的解析分支；Java clean verify 和 Day4/Day5 跨进程 smoke 已通过。可在后续契约测试中补直接断言。 | 已核对实现路径 |
| R-03 | 是，可观测性建议 | 后续观察 | 规划错误保守返回无提案，Java 权限与审批边界保持；本阶段无阻断性故障。 | 非阻断 |
| R-04 | 部分认同 | 无需修改 | 越界编号由索引界限过滤；确定性 responder 显示其上下文原文，相应来源确实在回答中，符合引用筛选语义。截断空块回归测试已补。 | 非阻断 |
| R-05 | 是，配置建议 | 后续按需调整 | 512 token 足够当前短 JSON schema，不影响安全或契约。 | 非阻断 |
| R-06 | 是 | 无需修改 | 已按官方 API 文档核对 `deepseek-flash` 并保留显式覆盖。 | 通过 |

---

**结论**：`REVIEW_RESULT: PASS`。无“必须修改”项；建议优先确认 R-02 的 NDJSON→Java 解析证据与 R-01 的前端来源消费路径，其余为可观测性与测试完善。
