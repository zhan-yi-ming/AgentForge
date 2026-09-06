# Pi 代码审查报告：v2-01-langfuse-base-trace / Attempt 1

- 日期：2026-09-06
- 审查阶段：v2-01-langfuse-base-trace
- 审查对象：INDEX@bb286a1（基线：bb286a166a8cd1005035f66e80fecd7381c86c2c）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- 状态：RESOLVED（M-01 已修复，Attempt 2 PASS）
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立只读代码审查报告

- 审查阶段：v2-01-langfuse-base-trace
- 审查模式：Milestone Review（第 1 / 3 轮）
- 审查目标：Commit INDEX@bb286a1
- 审查依据：本轮 Git Diff、变更文件清单、显式审查上下文（节点路线图 / observability 文档 / ADR-0016 / 变更记录）
- 审查方法：完全只读，未运行任何命令、未修改 Git 状态。

## 概述与总体结论

本轮交付范围集中在 Python Agent Service 的 Langfuse 基础 Trace，包括：新增 `observability.py` fail-open adapter、`api.py` 同步/流式 Trace 生命周期、`graph.py` 节点观测、`llm.py` Token/模型元数据提取、配置与部署脚本、以及 491 行 `test_observability.py`。

总体上该实现与 V2-01 节点 Scope 和 ADR-0016 高度吻合：默认关闭、fail-open 隔离、字段白名单、敏感正文不入 Trace、不改变 Java 确定性业务边界、没有数据库/公共 API 迁移。测试用 fake 作为外部系统边界是合理 seam。

存在一个明确的异常闭合缺陷：流式入口在 Graph 前置阶段（prepare/retrieve/plan）只捕获 `ValueError` 与 `RagDependencyError`，缺少与 JSON 入口一致的兜底异常闭合。一旦该阶段抛出其他未知异常，`request`/`agent` 观测不会 fail/end，造成 Trace 悬挂未闭合。这与节点验收标准“异常链路也能正确结束”以及本轮变更记录“同步和流式成功、RAG/LLM/未知异常均结束观测”直接冲突，属于必须修改。

结论：需修复后交付。仅此一项必须修改，其余为建议项，不阻塞节点主线。

---

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| M-01 | High | services/agent-service/src/agentforge_agent/api.py | `chat_stream` 前置 Graph `invoke` 的 try/except 块 | 流式入口缺少未知异常兜底，导致根/Agent 观测未 fail/end，Trace 悬挂不闭合 |

### 建议修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
| --- | --- | --- | --- | --- |
| S-01 | Medium | services/agent-service/src/agentforge_agent/observability.py | `Observation.child` | `trace_context` 手工构造 dict，未确认真实 Langfuse 4.x SDK 的 `TraceContext` 对象契约；fake 测试无法验证 |
| S-02 | Low | services/agent-service/src/agentforge_agent/llm.py | `_usage_details` | 仅接受 dict 型 `usage_metadata`，若 LangChain 返回 pydantic `UsageMetadata` 对象，Token 会静默丢失 |
| S-03 | Low | scripts/deploy/validate-env.sh | LANGFUSE case | 启用时未校验 `AGENTFORGE_AGENT_LANGFUSE_HOST` 的 URL 格式 |
| S-04 | Low | services/agent-service/tests/test_observability.py | 全文 | 缺少流式前置阶段未知异常闭合与 disabled responder stream fallback 的自动化测试 |

### 无需修改

| ID | 说明 |
| --- | --- |
| N-01 | fail-open 隔离逻辑：`start_observation/update/end/shutdown` 异常均被吞并返回 No-op/静默，符合 ADR-0016 |
| N-02 | 敏感字段边界：`request_id/thread_id/project_id`、节点类型、source count、proposal bool、action type、脱敏异常类名、provider/model、Token 均在白名单；message/answer/检索正文/Tool 参数/异常正文未进入 payload |
| N-03 | 默认关闭配置：`.env*`、compose、config 均以 `false` 与 No-op 为默认，开启需完整 key/host |
| N-04 | 测试使用 fake 模拟 Langfuse 外部系统边界，符合 testing-strategy.md 约定，不测试 SDK 私有实现 |
| N-05 | 文档与实现一致性总体良好：ADR-0016、observability、roadmap、变更记录表述与代码行为匹配 |

---

## 逐个 Issue 展开

### M-01 — 流式入口前置阶段未知异常不闭合 Trace（High）

- **Severity**：High
- **File & Line**：`services/agent-service/src/agentforge_agent/api.py`，`chat_stream` 函数中 `build_chat_context_graph(...).invoke(...)` 的异常处理块（diff 中新增 `except ValueError` / `except RagDependencyError` 两分支，无 `finally` 或无 `except Exception` 兜底）
- **Evidence**：

```python
def chat_stream(...):
    thread_id = request.conversation_id or uuid4()
    request_observation = observability.start_request(...)
    agent_observation = request_observation.child("agent", "agent")
    try:
        state = build_chat_context_graph(
            retrieval_service.retrieve,
            observation=agent_observation,
        ).invoke({...})
    except ValueError as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise HTTPException(422 ...)
    except RagDependencyError as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise HTTPException(503 ...)
    # 缺少 except Exception: _fail_and_end(...); raise
```

- **Description**：

相比同文件 JSON 入口通过内层 `try/except Exception` 对任意异常标记 `fail` 后再上层转换，流式入口在 `build_chat_context_graph(...).invoke(...)`（含 prepare/retrieve/tool 三个节点）阶段只处理了 `ValueError` 和 `RagDependencyError`。若 `plan_tool` 或检索内部逻辑抛出其它未包装异常（如未预期的 `TypeError`、`httpx` 底层错误等），`request_observation`、`agent_observation` 既不会 `fail` 也不会 `end`，根与 Agent 观测将永久悬挂。节点验收标准明确要求“异常链路也能正确结束”，且本轮变更记录声称“同步和流式成功、RAG/LLM/未知异常均结束观测”，实现与此不符。该阶段异常发生在响应开始前，HTTP/SSE 契约不新增语义，不影响公共契约，但 Trace 生命周期不完整。

- **Suggested Fix**：

在流式前置阶段补充兜底分支，使其与 JSON 入口对齐：

```python
    except RagDependencyError as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="RAG dependencies are unavailable.",
        ) from exception
    except Exception as exception:
        _fail_and_end(request_observation, agent_observation, exception)
        raise
```

---

### S-01 — Langfuse `trace_context` 构造方式未验证真实 SDK 契约（Medium）

- **Severity**：Medium
- **File & Line**：`services/agent-service/src/agentforge_agent/observability.py`，`Observation.child`
- **Evidence**：

```python
values = {
    "name": name,
    "as_type": as_type,
    "trace_context": {
        "trace_id": self.trace_id,
        "parent_span_id": self.observation_id,
    },
}
try:
    return Observation(self._client, self._client.start_observation(**values))
except Exception:
    return NullObservation()
```

- **Description**：

Langfuse 4.x SDK 的 `start_observation` 通常接收 `TraceContext` 对象（如 `parent.trace_context`），此处手工构造 dict 传入。由于 `except Exception` 失败即降级为 `NullObservation`，真实 SDK 若拒绝 dict，整个子级链路会静默丢失、只剩根 span，表现为“漏节点/断链”，且 fail-open 掩盖了问题。现有测试全部使用 `FakeLangfuseClient` 接受任意关键字，离线冒烟记录虽声称调用真实 SDK，但无法从 `LANGFUSE_API_OK` 证明 `parent_span_id` 链路真实生效。

- **Suggested Fix**：

优先使用官方 TraceContext 对象（例如 `trace_context=parent.trace_context`，或构造 SDK 提供的 `TraceContext`），并在 repository 内增加一个轻量离线断言：用真实 `Langfuse` SDK 的本地 exporter 创建父子 observation，断言子观测继承父 trace_id 且 parent 关系成立，而非仅断言不抛异常。

---

### S-02 — `usage_metadata` 仅接受 dict，可能静默丢失 Token（Low）

- **Severity**：Low
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py`，`_usage_details`
- **Evidence**：

```python
raw = getattr(response, "usage_metadata", None)
if not isinstance(raw, dict):
    return {}
```

- **Description**：

LangChain 某些版本/渠道可能以 pydantic `UsageMetadata` 对象返回 `usage_metadata`（非运行时 dict），此时会直接返回空 dict，导致 Token 缺失。项目当前约束“缺失保持缺失、不得伪造”，此处不会伪造，但可能在 provider 实际返回 usage 时静默丢失。目前测试用纯 dict 模拟，未覆盖对象形态。属于对未来 LangChain 版本兼容性的建议。

- **Suggested Fix**：

兼容 dict 与对象两种形态，例如先尝试 `raw.model_dump()` 或直接读取 `input_tokens/output_tokens/total_tokens` 属性后再做 int 校验，并保持“缺失则为空、不估算”。

---

### S-03 — 启用 Langfuse 时未校验 host 的 URL 格式（Low）

- **Severity**：Low
- **File & Line**：`scripts/deploy/validate-env.sh`，LANGFUSE enabled 分支
- **Evidence**：

```bash
true)
    [[ -n "${AGENTFORGE_AGENT_LANGFUSE_PUBLIC_KEY:-}" && \
       -n "${AGENTFORGE_AGENT_LANGFUSE_SECRET_KEY:-}" && \
       -n "${AGENTFORGE_AGENT_LANGFUSE_HOST:-}" ]] || {
        echo "Enabled Langfuse tracing requires public key, secret key and host." >&2
        exit 1
    }
    ;;
```

- **Description**：

只检查 host 非空，未验证是否为合法 http/https URL。非法值仍会在 fail-open 语义下降级为 No-op，但生产配置脚本层面无法提前发现配置错误。不构成安全或业务阻塞，仅增强配置校验。

- **Suggested Fix**：

增加 `case "${AGENTFORGE_AGENT_LANGFUSE_HOST}" in http://*|https://*) ;; *) exit 1 ;; esac` 或等效格式校验。

---

### S-04 — 测试缺口：流式前置未知异常与 disabled stream fallback（Low）

- **Severity**：Low
- **File & Line**：`services/agent-service/tests/test_observability.py`
- **Description**：

现有测试覆盖了流式 generation 阶段 LLM 异常的闭合，但未覆盖：① `build_chat_context_graph(...).invoke(...)` 抛出 `ValueError`/`RagDependencyError` 之外的未知异常时的闭合行为（即 M-01 的回归测试）；② responder 无 `stream_observed`/`stream` 时 `(responder(state),)` fallback 分支的观测行为。补齐这两项可作为 M-01 修复的防守性证据。

- **Suggested Fix**：

为 M-01 修复新增一个失败测试：让 context graph 的 `plan_tool`/retriever 抛出非契约异常，断言 request/agent observation 均 `fail`（ERROR + 类名）并 `end`。再补 disabled 流式入口的层级闭合断言。

---

## 主开发 (Codex) 评估回填区

> 以下表格由 Codex 在修复后回填，Pi 下一轮据此验证。

| ID | Codex 处理结论 | 修复提交/说明 | Pi 复审结果 |
| --- | --- | --- | --- |
| M-01 | 接受；以流式前置 retriever 抛出未知异常的回归测试复现并修复。 | 新增 `test_stream_context_unknown_error_closes_request_and_agent_trace`，先以 request span 无 ERROR update 红灯退出 1；`api.py` 补兜底后相关 2 tests passed、退出 0。 | Attempt 2 已验证通过 |
| S-01 | 不改；Langfuse 4.15.1 公共签名声明 `TraceContext`，运行时类型为含 `trace_id` / 可选 `parent_span_id` 的 TypedDict，当前字典符合契约；真实 SDK 离线父子调用已通过。 | 非阻塞，保留现有显式上下文。 | Attempt 2 接受，非阻塞 |
| S-02 | 不改；当前锁定环境的 LangChain `UsageMetadata` 运行时继承 `dict`，现有实现可读取；缺失值不估算符合节点约束。 | 非阻塞，不扩展未出现的未来兼容分支。 | Attempt 2 接受，非阻塞 |
| S-03 | 记录建议；启用时已强制非空，SDK 初始化/上报 fail-open，URL 格式增强不影响本节点验收。 | 后续配置体验改进候选。 | Attempt 2 接受，非阻塞 |
| S-04 | 接受 M-01 对应测试；disabled responder fallback 与 M-01 无关且既有业务 fallback 未改变，不作为本轮阻塞项。 | M-01 回归测试已补；其余不扩展。 | Attempt 2 接受，非阻塞 |

---

## 总体评价

- 节点边界：未跨节点实现，未引入 V2-02 及后续组件，Scope 控制到位。
- 安全边界：字段白名单与脱敏异常类名设计正确，trace 不公开、凭据不回显。
- Fail-open：观测故障不会改变 Chat 返回值或 HTTP/SSE 语义，符合 ADR-0016。
- 主要风险：M-01 造成特定异常路径下 Trace 不闭合，违反当前节点最核心的验收标准“异常链路也能正确结束”，需修复后进入下一轮。
