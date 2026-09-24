# Pi 代码审查报告：v3-02-litellm-model-gateway / Attempt 2

- 日期：2026-09-24
- 审查阶段：v3-02-litellm-model-gateway
- 审查对象：INDEX@8166a2a（基线：8166a2a7e2d690c9aa9208afada64d344d6481a0）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- 状态：RESOLVED
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# Pi 代码审查报告：v3-02-litellm-model-gateway / Attempt 2（Milestone 第 2/3 轮）

- 日期：2026-09-24
- 审查阶段：v3-02-litellm-model-gateway
- 审查模式：Milestone（只读，无工具；仅依据 Git Diff、文件清单、上下文包与 Attempt 1 报告）
- 审查对象：`8166a2a7e2d690c9aa9208afada64d344d6481a0 .. INDEX@8166a2a`
- 审查工具：Pi Agent（DeepSeek V4.1 Flash）

---

## 概述与总体结论

- **节点达成度**：V3-02 Scope 达成。进程内 `LiteLlmGateway` 统一同步回答、流式回答与 JSON Action Intent 的模型调用，集中处理 timeout、一次性静态 fallback、usage 与正数成本估算、脱敏错误；Agent 生产路径只经 Gateway；Embedding/ASR 未进入；Java 的 RBAC/Risk/Approval/写入信任边界未改变；公共 HTTP 字段与错误契约未改变。
- **Attempt 1 阻断项复核**：
  - **M1（必须修改）已修复**：`_recoverable` 改为按名称逐项 `getattr` 收集可用类型，任一名称缺失不再整体失效；全部缺失时显式 `RuntimeError`（fail-loud，不再静默关闭 fallback）。新增真实 `litellm.Timeout` 且删除一个可选异常名的回归用例，以及 Timeout/RateLimitError/InternalServerError 应 fallback、AuthenticationError/BadRequestError 不 fallback、备用再失败只调用一次的异常矩阵。
  - **S1 已修复**：`stream_observed` 增加 `model`/`provider` 类型与键存在性保护，消除 `KeyError`。
  - **S3 已修复**：成本仅在 `cost > 0` 时写入，`0` 视为未知，并有红绿测试 `test_zero_cost_estimate_is_reported_as_unknown`。
  - S2/S4/S5 按 Attempt 1 结论保持“非阻塞”，本轮不升级。
- **总体结论**：**通过（PASS）**。本轮未发现具备明确证据的必须修改项；剩余均为建议级可扩展性或可运维性事项，不阻塞 V3-02 收口。

---

## 详细发现清单（按严重级别排序，最多十项）

### 必须修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| S1 | Medium | services/agent-service/src/agentforge_agent/model_gateway.py | 57–58 | `_kwargs` 仍按 `index == 0` 硬编码 role，隐式假设消息恒为 `[system, user]` 两条；未来多轮消息会静默错位（当前调用点不触发） |
| S2 | Low | services/agent-service/src/agentforge_agent/model_gateway.py | 138–144 | 全部 LiteLLM 瞬态异常名缺失时在 `except` 处理器内抛 `RuntimeError`，原始 provider 异常被替换，丢失根因分类 |
| S3 | Low | services/agent-service/src/agentforge_agent/llm.py | 166、205 | 公开错误从 `from exception` 改为 `from None`，有意防泄漏，但同时使服务端日志/traceback 不再保留任何上游失败类别，排障只能看到通用 503 |
| S4 | Low | services/agent-service/src/agentforge_agent/model_gateway.py | 62 | `num_retries=0` 且 fallback 默认关闭，默认部署相对旧 `ChatOpenAI(max_retries=1)` 少了单次重试，属有意取舍但为行为变化 |
| S5 | Low | services/agent-service/src/agentforge_agent/model_gateway.py | 117 | 流式成本查价固定用 `openai/{model}`，非 OpenAI 兼容模型可能命中不了价格表，成本长期 unknown（文档已声明为已知限制） |

### 无需修改

| 项 | 结论 |
|----|------|
| 节点边界 | LiteLLM 属路线图显式授权的 V3-02 Scope；未越界到 V3-03 任务路由、未引入独立 Proxy、未触碰 Java RBAC/Risk/Approval/RAG/Embedding/ASR |
| 公共契约 | `docs/04-api/agent-service.md` 仅新增说明段，未改 HTTP 字段/状态码；流式失败仍走通用 `error` 事件 |
| 凭据与内容安全 | Gateway/观测 metadata 仅 provider/model/usage/cost，不含 key、prompt、response；测试断言 `"私密回答" not in repr(updates)`、`"private key" not in str(captured)` |
| 流式中途不切换 | `emitted` 置位后不再 fallback，`test_stream_failure_after_first_token_does_not_switch_provider` 覆盖 |
| 一次性 fallback、无递归 | `for attempt in (False, True)` 上限两次；备用再失败只调用一次，`test_fallback_provider_failure_is_not_retried` 覆盖 |
| 无效配置 fail-closed | `disabled + fallback`、缺主 key、`openai` 携带 base URL、fallback 与主完全相同、缺 fallback key/model 均在 `build_responder` 抛 `LlmDependencyError`，并有对应用例 |
| 真实 SDK 路径 | `test_litellm_sdk_calls_configured_compatible_endpoint` 用本地 HTTP 假 provider 经真实 LiteLLM 验证 Chat Completions 路径、模型与上下文传递 |
| 文档真实性 | `model-routing.md`/ADR-0030/变更记录对“静态 fallback、不做 V3-03 路由、成本为估算、不部署 Proxy、成本未知不写 0”的描述与实现一致 |

---

## 逐个 Issue 展开

### S1（建议修改）消息 role 仍按索引硬编码

- **Severity**：Medium（可扩展性，当前不触发）
- **File & Line**：`services/agent-service/src/agentforge_agent/model_gateway.py` L57–58
- **Evidence**：

```python
"messages": [{"role": "system" if index == 0 else "user", "content": message.content}
             for index, message in enumerate(messages)],
```

- **Description**：当前两个调用点（`CompatibleLlmResponder._messages` 的 `[SystemMessage, HumanMessage]` 与 `plan_tool` 的 `[SystemMessage, HumanMessage]`）都恰好两条，因此行为正确、无实际风险。但一旦 V3-03/V3-04 之后引入多轮或 assistant 历史，所有非首条消息会被静默标为 `user`，语义错位且无告警。
- **Suggested Fix**：按 langchain message 类型映射，而非索引：

```python
_ROLE = {"system": "system", "human": "user", "ai": "assistant"}
"messages": [
    {"role": _ROLE.get(getattr(message, "type", "user"), "user"), "content": message.content}
    for message in messages
],
```

（本轮不强制，留待真正扩展消息形状的节点处理。）

### S2（建议修改）`_recoverable` 在异常处理器内抛 `RuntimeError` 会替换原始异常

- **Severity**：Low
- **File & Line**：`model_gateway.py` L138–144
- **Evidence**：

```python
types = tuple(
    candidate for name in names
    if isinstance((candidate := getattr(litellm, name, None)), type)
    and issubclass(candidate, Exception)
)
if not types:
    raise RuntimeError("LiteLLM transient error types are unavailable.")
return isinstance(error, types)
```

- **Description**：逐名收集与 fail-loud 已解决 Attempt 1 的“静默失效”M1。残留问题是当（极少数）所有异常名都不可用时，`_recoverable` 在 `except` 块内抛出的 `RuntimeError` 会取代原始 provider 异常向上传播，原始分类信息彻底丢失，只剩 `LlmDependencyError("Configured LLM provider is unavailable.")`。
- **Suggested Fix**：保留 fail-loud，但把原始异常显式附加上下文，例如：

```python
if not types:
    raise RuntimeError("LiteLLM transient error types are unavailable.") from error
```

或先记录一次受限日志（仅异常类名，不含正文）再抛出。

### S3（建议修改）`from None` 抑制了所有上游失败类别，影响可运维性

- **Severity**：Low
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py` L166、L205（两处 `except`）
- **Evidence**：

```python
except Exception as exception:
    raise LlmDependencyError("Configured LLM provider is unavailable.") from None
```

- **Description**：相比旧实现的 `from exception`，`from None` 有效防止上游异常正文（可能含 key/URL/响应体）进入日志与 trace，安全性正面；副作用是服务端无法从 traceback 区分 timeout、429、5xx、认证失败——运维页只能看到通用 503。
- **Suggested Fix**：保持不泄漏正文，但在捕获处对**异常类名**做一次白名单化 debug 日志或计数指标（如 `provider_error_type=litellm.Timeout`），既保留排障线索又不泄漏敏感内容。

### S4（建议修改）默认关闭同 provider 重试属于行为变化

- **Severity**：Low
- **File & Line**：`model_gateway.py` L62（`"num_retries": 0`）
- **Description**：`fallback` 默认关闭，故未配置 fallback 的既有部署在暂时性抖动下从“自动重试 1 次（旧 `ChatOpenAI(max_retries=1)`）”变为“直接 503”。ADR-0030 与 `model-routing.md` 已明示该取舍（避免隐性重复费用）。属有意设计，仅作为部署行为变化提示。
- **Suggested Fix**：无需改代码；确认运维手册已明确“默认不自动重试”的故障语义（当前 `docs/06-operations/production-single-host.md` V3-02 段已提及）。

### S5（建议修改）非 OpenAI 模型流式成本长期 unknown

- **Severity**：Low
- **File & Line**：`model_gateway.py` L117（`self.stream_cost_func(f"openai/{model}", usage)`）；`build_responder` 默认 `stream_cost_func` 委托 `litellm.cost_per_token`
- **Description**：查价键固定 `openai/{model}`，DeepSeek/智谱/千问等在以自身 provider 前缀入价格表时可能查不到，异常被吞后成本保持 unknown。文档已声明该限制且不伪造 0 成本，符合真实性要求。
- **Suggested Fix**：后续可优先读取响应/usage 自带成本字段；在当前节点维持 unknown 并在文档标注即可。

---

## 主开发（Codex）评估回填区

| ID | 是否成立 | 真实原因 | 是否需改 | 最小修复 | 补测范围 | 备注 |
|----|----------|----------|----------|----------|----------|------|
| S1 | 成立但当前不触发 | 当前仅 system + user 两消息 | 否 | V3-03 扩展消息形状时处理 | 现有 SDK smoke 与全量 pytest | 当前节点不越界 |
| S2 | 成立但低风险 | 全部异常类同时缺失才会触发 | 否 | 保持显式失败 | 真实 SDK 异常矩阵 | 不发送上游正文 |
| S3 | 成立但安全优先 | 异常正文可能包含敏感数据 | 否 | 保持通用公共错误和脱敏 Trace | Agent API 全量 pytest | 后续可加白名单指标 |
| S4 | 成立且有意取舍 | 避免同 provider 隐性重复调用 | 文档已处理 | 功能与生产运维文档说明 | Agent pytest、Compose | 不增加重试 |
| S5 | 成立为限制 | 兼容模型价格表可能无条目 | 文档已处理 | unknown 不伪报零成本 | 正数与零成本测试 | 未来有来源的价格配置再扩展 |

---

## 结论

Attempt 1 的唯一阻断项 M1 已被正确修复并为真实 LiteLLM 异常矩阵补齐测试，S1/S3 亦在本轮修复。节点 Scope、公共契约、Java 信任边界、凭据脱敏、流式中途不切换、一次性无递归 fallback、无效配置 fail-closed 均通过复核。剩余 5 项均为建议级事项，不构成阻断。

**REVIEW_RESULT: PASS**
