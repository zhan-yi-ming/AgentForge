# Pi 代码审查报告：v3-02-litellm-model-gateway / Attempt 1

- 日期：2026-09-24
- 审查阶段：v3-02-litellm-model-gateway
- 审查对象：INDEX@8166a2a（基线：8166a2a7e2d690c9aa9208afada64d344d6481a0）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX（历史结果；Attempt 2 PASS 后状态 RESOLVED）
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# V3-02 LiteLLM Model Gateway — Milestone Review 报告（第 1/3 轮）

## 概述与总体结论

- **审查对象**：`8166a2a7e2d690c9aa9208afada64d344d6481a0 .. INDEX@8166a2a`（V3-02 LiteLLM Model Gateway），19 个文件，+815/-24。
- **审查模式**：Milestone（只读，无工具，仅依据 diff、文件清单与既有上下文）。
- **节点达成度**：V3-02 Scope 基本达成——进程内 `LiteLlmGateway` 统一同步/流式/JSON 意图调用，统一 timeout、usage、best-effort cost、脱敏错误与一次性静态 fallback；`Agent` 生产路径只经 Gateway；HTTP 契约、Java 信任边界、Embedding/ASR 均未越界；provider 切换不改 Agent/RAG/Tool/Java。
- **总体结论**：**需修复后交付**。功能设计合理、测试整体扎实，但 fallback 的“真实 SDK 异常分类”路径零覆盖，且分类函数依赖 `try/except ImportError`，任一异常名缺失即会**静默关闭整个 fallback**。该风险直接命中 V3-02 Pi Review 重点（Provider exception / timeout / fallback），并属于“关键测试范围不充分 + 静默失效”。其余为建议项，不阻塞。

---

## 详细发现清单

| ID | 严重级别 | 文件 | 行号（约） | 核心问题 |
|----|----------|------|-----------|----------|
| M1 | 必须修改（High） | services/agent-service/src/agentforge_agent/model_gateway.py | 133–147 | `_recoverable` 对真实 LiteLLM 异常零测试覆盖，且 `from litellm import ...` 任一名称缺失会整体 ImportError，静默使 fallback 失效 |
| S1 | 建议修改（Medium） | services/agent-service/src/agentforge_agent/llm.py | 168–205 | `stream_observed` 中 `metadata["provider"]` 无键存在性保护，畸形 `response_metadata` 会抛 KeyError |
| S2 | 建议修改（Medium） | services/agent-service/src/agentforge_agent/model_gateway.py | 49–67 | `_kwargs` 按 `index == 0` 硬编码 role，假设消息恒为 `[system, user]` 两条，扩展多轮即静默错位 |
| S3 | 建议修改（Medium） | services/agent-service/src/agentforge_agent/model_gateway.py | 78–91, 130–146 | 成本为 0 时被当作“已知成本”写入 `cost_usd=0`，与文档“未知成本不写成零”不一致 |
| S4 | 建议修改（Low） | services/agent-service/src/agentforge_agent/llm.py | 188–222 | `num_retries=0` 且 fallback 默认关闭，默认部署相比旧 `ChatOpenAI(max_retries=1)` 少了单次重试，可靠性回退 |
| S5 | 建议修改（Low） | services/agent-service/src/agentforge_agent/model_gateway.py | 71–79, 108–127 | `stream_cost_func` 默认用 `openai/{model}` 查价，非 OpenAI 模型价格表可能无法命中，成本长期为 unknown |

> 未提供证据支持的其他猜测（如统一前缀是否影响某些 provider 参数、文档措辞）不列入发现。

---

## 逐个 Issue 展开

### M1（必须修改）fallback 分类逻辑对真实 SDK 异常零覆盖，且可静默失效

- **Severity**：High（功能性/可靠性 + 测试缺口）
- **File & Line**：`services/agent-service/src/agentforge_agent/model_gateway.py` L133–147；测试 `services/agent-service/tests/test_model_gateway.py`
- **Evidence（实现）**：

```python
def _recoverable(error: Exception) -> bool:
    if isinstance(error, (TimeoutError, ConnectionError)):
        return True
    try:
        from litellm import APIConnectionError, InternalServerError, RateLimitError, ServiceUnavailableError, Timeout
        return isinstance(error, (APIConnectionError, InternalServerError, RateLimitError, ServiceUnavailableError, Timeout))
    except ImportError:
        return False
```

- **Evidence（测试）**：所有 fallback 相关用例抛出的都是**内建异常**，不会走到 `from litellm import ...` 分支：
  - `test_transient_primary_failure_uses_one_configured_fallback`：`raise TimeoutError("primary timed out")`
  - `test_stream_timeout_before_text_uses_fallback_once`：`raise TimeoutError("primary unavailable")`
  - `test_stream_failure_after_first_token_does_not_switch_provider`：`raise TimeoutError("late provider timeout")`
  - `test_authentication_failure_never_uses_fallback...`：`raise ValueError(...)`

  真实 provider 失败（连接拒绝、超时、429、500/502/503）由 LiteLLM 抛出的是 `litellm.*` 异常，而非内建 `TimeoutError`。因此**生产环境 fallback 的实际触发分支从未被任何测试执行**。
- **Description**：
  1. 由于该 `import` 是**一次导入整个元组**，只要其中任一异常名在当前 LiteLLM 版本不存在或被重命名，`ImportError` 会被吞掉并 `return False`。此时所有非内建异常一律不可恢复，**fallback 与“暂时性错误”判定将整体失效**，且没有任何测试或日志能暴露，表现为“主 provider 一挂就直接 503”。
  2. 这与 V3-02 验收/审查重点（Provider exception、timeout/retry/fallback）直接相关，属于关键分支未验证 + 潜在静默失效。
- **Suggested Fix**：
  - 将异常分类收敛为模块级导入或按名称逐个 `getattr`，避免“一处缺失、全盘 False”：

```python
import litellm

_RECOVERABLE_TYPES: tuple[type[BaseException], ...] = tuple(
    t for name in ("APIConnectionError", "InternalServerError", "RateLimitError",
                   "ServiceUnavailableError", "Timeout", "InternalServerError")
    if (t := getattr(litellm, name, None)) is not None
)

def _recoverable(error: Exception) -> bool:
    if isinstance(error, (TimeoutError, ConnectionError)):
        return True
    return bool(_RECOVERABLE_TYPES) and isinstance(error, _RECOVERABLE_TYPES)
```

  - 补测真实类型（可用最小构造或 `litellm.Timeout`/`litellm.InternalServerError` 实例）：
    - `litellm.Timeout` → 触发一次 fallback；
    - `litellm.AuthenticationError` → **不** fallback；
    - `litellm.BadRequestError` → **不** fallback；
    - fallback 自身再抛可恢复异常 → 只尝试一次并上抛，不递归。

---

### S1（建议修改）`stream_observed` 对 `response_metadata` 的键访问无保护

- **Severity**：Medium
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py` L~168–205
- **Evidence**：

```python
metadata = getattr(response, "response_metadata", None)
if observation is not None and isinstance(metadata, dict) and metadata.get("model") != observed_model:
    observed_model = metadata["model"]
    observation.update(model=observed_model, metadata={"provider": metadata["provider"]})
```

- **Description**：只要 chunk 带有 `response_metadata` 字典但**不含 `model`**（或含 `model` 却缺 `provider`），`metadata.get("model")` 返回 `None`，`None != observed_model` 成立，随后 `metadata["provider"]` 触发 `KeyError`。当前 `LiteLlmGateway` 始终填入两键，故生产路径不触发；但这是公共 seam（旧 `model_factory` 注入的替身模型仍可走到此分支），属于无保护的字典访问。
- **Suggested Fix**：

```python
model = metadata.get("model")
provider = metadata.get("provider")
if observation is not None and isinstance(model, str) and model != observed_model and isinstance(provider, str):
    observed_model = model
    observation.update(model=model, metadata={"provider": provider})
```

---

### S2（建议修改）消息 role 按索引硬编码，隐式假设只有两条消息

- **Severity**：Medium
- **File & Line**：`model_gateway.py` L49–67
- **Evidence**：

```python
"messages": [{"role": "system" if index == 0 else "user", "content": message.content}
             for index, message in enumerate(messages)],
```

- **Description**：当前所有调用点（`CompatibleLlmResponder._messages`、`plan_tool`）都恰好是 `[SystemMessage, HumanMessage]`，因此行为正确。但一旦未来引入多轮消息或非 system 首条，会把所有后续消息（包括可能的 assistant 历史）静默标成 `user`，且丢弃 `HumanMessage/AIMessage` 的语义类型。属于 Gateway 契约的可扩展性缺口。
- **Suggested Fix**：按消息类型映射，或直接透传 langchain message 的 role，例如：

```python
_ROLE = {"system": "system", "human": "user", "ai": "assistant"}
"messages": [{"role": _ROLE.get(getattr(m, "type", "user"), "user"), "content": m.content} for m in messages]
```

---

### S3（建议修改）成本 0 被当作已知成本写入

- **Severity**：Medium
- **File & Line**：`model_gateway.py` L78–91（invoke）与 L122–146（stream）；文档 `docs/03-features/model-routing.md`
- **Evidence**：

```python
cost = self.cost_func(response)
if isinstance(cost, (int, float)) and isfinite(cost) and cost >= 0:
    metadata["cost_usd"] = float(cost)
```

- **Description**：文档明确“没有有效 usage/价格时成本标为未知，不写 0 伪装免费”。而 `>= 0` 会把 `0.0`（例如价格表未命中但 SDK 返回 0、或本地/自定义端点）写成“已知 0 成本”，与文档语义冲突，也会在观测上误导成本判断。
- **Suggested Fix**：对 0 采取显式策略（要么仅当 `> 0` 写入，要么在 metadata 中增加 `cost_known: bool` 区分），并同步文档措辞。

---

### S4（建议修改）默认部署失去旧版单次重试

- **Severity**：Low
- **File & Line**：`model_gateway.py` L71–79（`num_retries: 0`）；`llm.py` 旧 `ChatOpenAI(max_retries=1)` 被移除
- **Description**：fallback 默认关闭，因此未配置 fallback 的既有部署在暂时性抖动下从“自动重试 1 次”变为“直接 503”。ADR-0030 明确关闭 SDK 自动重试以换取“有界 fallback”，属有意取舍；但建议在文档/运维页显式说明这一可靠性变化，或考虑对主调用保留 `num_retries=1` 且 fallback 不叠加。
- **Suggested Fix**：确认设计意图后在 `docs/03-features/model-routing.md` 的“限制”中显式标注“默认不再自动重试”，或为主 provider 保留一次重试。

---

### S5（建议修改）默认流式成本查价使用 `openai/` 前缀

- **Severity**：Low
- **File & Line**：`llm.py` 默认 `stream_cost_func`（`cost_per_token(model=f"openai/{model}", ...)`）；`model_gateway.py` 调用处 L122–146
- **Description**：默认查价以 `openai/{model}` 作为价格键，DeepSeek/智谱/千问等模型在 LiteLLM 价格表中通常需要自身 provider 前缀（如 `deepseek/...`）。结果大概率抛异常并被 `except` 吞掉，成本长期 unknown。虽然文档允许 unknown，但这使“可用时成本估算”在实际 provider 上很难生效，建议至少在文档中说明该限制，或改用响应自带的成本字段。
- **Suggested Fix**：优先读取 LiteLLM 响应/`usage` 上的成本字段；无法获得时保持 unknown，并在文档标注“非 OpenAI provider 成本通常不可得”。

---

## 主开发（Codex）评估回填区

| ID | 是否成立 | 真实原因 | 是否需改 | 最小修复 | 补测范围 | 备注 |
|----|----------|----------|----------|----------|----------|------|
| M1 | 成立 | 内建异常测试未覆盖 SDK 类型，联合 import 可整体失效 | 是 | 按可用类型逐项分类，无可用类型时显式失败 | 真实 LiteLLM 异常矩阵、完整 Agent pytest | 修复后发起 Attempt 2 |
| S1 | 成立 | metadata 缺键时会误入更新 | 是 | 类型与键存在性保护 | 完整 Agent pytest | 随同修复 |
| S2 | 当前不触发 | 当前公共调用均为 system + user 两条消息 | 否 | V3-03 若扩展消息形状再处理 | 当前 SDK smoke 已覆盖现有形状 | 不提前扩展下一 Node |
| S3 | 成立 | SDK 返回 0 不能证明免费 | 是 | 仅记录正数成本估算并更新文档 | 零成本红绿测试、完整 Agent pytest | 兼容模型价格未知保留 unknown |
| S4 | 成立但属有意取舍 | V3-02 关闭同 provider 自动重试，以免隐性重复费用 | 文档修正 | 运维和功能文档明示默认故障语义 | Compose 与 Agent pytest | 不增加隐性重试 |
| S5 | 成立为已知限制 | 非 OpenAI 兼容模型价格表可能缺失 | 文档修正 | 成本保持 unknown，不伪造账单 | 正数/零成本测试 | 后续按有来源价格配置再扩展 |

---

## 无需修改（已验证通过项）

| 项 | 结论 |
|----|------|
| V1/节点边界 | LiteLLM 属 V3-02 授权 Scope，非引入越界；未触碰 Java RBAC/Risk/Approval、RAG、Embedding、ASR 与公共 HTTP 契约 |
| 公共契约 | `docs/04-api/agent-service.md` 仅新增说明段，未改字段/状态码；流式失败仍走通用 `error` |
| 凭据安全 | Gateway/观测 metadata 不含 `api_key`、prompt、response；测试显式断言 `"私密回答" not in repr(updates)`、`"private key" not in str(...)` |
| 流式中途不切换 | `emitted` 置位后不再 fallback，符合“输出开始后失败即结束”的文档与测试（`test_stream_failure_after_first_token...`） |
| 一次性 fallback | 仅 `attempt in (False, True)`，无递归；`test_transient_primary_failure_uses_one_configured_fallback` 断言调用序列 |
| 无效配置 fail-closed | `disabled + fallback`、缺 key、`openai` 带 base URL、fallback 与主模型完全相同，均在构建 responder 时抛 `LlmDependencyError` |
| 文档真实性 | `model-routing.md` / ADR-0030 / 变更记录对“静态 fallback、不实现 V3-03 路由、成本为估算、不部署 Proxy”的描述与实现一致 |

---

## 结论

存在 1 项必须修改（M1：真实 LiteLLM 异常分类零覆盖且可静默失效），因此本节点当前为 **需修复后交付**。修复 M1 并补测真实异常类型后，其余项可作为建议择机处理，无需阻塞 V3-02 收口。
