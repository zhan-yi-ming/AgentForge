# Pi 代码审查报告：v2-01-tls-production-release / Attempt 1

- 日期：2026-09-07
- 审查阶段：v2-01-tls-production-release
- 审查对象：INDEX@f002914（基线：18064bf79b81213d2b0821792e56144f9a05939f）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立只读代码审查报告

- 审查阶段：v2-01-tls-production-release
- 审查模式：Milestone Review（第 1 / 3 轮）
- 审查目标：Commit INDEX@f002914（18064bf79b81213d2b0821792e56144f9a05939f .. INDEX@f002914）
- 审查模型：deepseek/deepseek-v4-pro
- 审查方法：完全只读，未运行任何命令、未修改 Git 状态

## 概述与总体结论

本轮是生产发布门禁（Release Gate）的里程碑审查，合并范围包含 V2-01 Langfuse 基础 Trace、验证流程优化、TLS 公网 IP/域名兼容，以及发布记录与索引。节点边界、确定性职责、敏感信息边界与 TLS 兼容方向总体正确：Java 确定性业务边界未被改动，无 Neo4j/MCP/LiteLLM/V2-02 越界；Langfuse 默认关闭、fail-open、字段白名单与异常闭合符合 ADR-0016；IPv6 issuer 方括号、www 派生、cert-name/live 目录与 Nginx 渲染已由先前 diff 审查和契约测试确认。

但存在一项必须修复的正确性问题：generation 在向 Langfuse SDK 更新 Token usage 时使用了 `usage_details=` 关键字，而 Langfuse SDK 观测对象 `update()` 的公共参数名为 `usage`（内部字典形状 `input/output/total` 本身正确）。由于 `Observation.update` 对任何异常静默吞并（fail-open），真实 SDK 会以 `TypeError` 静默丢弃 Token usage，导致 V2-01 验收标准“记录 Token usage”在生产启用时实际不生效；现有 fake span 接受任意关键字，测试无法发现该漂移。此项必须修复后交付。

另外，变更记录仍标注“待完成：Gitleaks、dev/main/tag 推送、生产备份部署与公网核验”。这些属于 Codex/部署职责，不在 Pi 只读范围，但 Release Gate 只有在这些条目真实完成并回填后才能关闭。

结论：**需修复后交付（NEEDS_FIX）**。

## 详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
|---|---|---|---|---|
| M-01 | High | services/agent-service/src/agentforge_agent/llm.py、observability.py | `_update_usage`、`stream_observed`、`Observation.update` | Token usage 以非 SDK 参数名 `usage_details` 传入 `span.update()`，真实 Langfuse SDK 抛 `TypeError` 后被 fail-open 吞掉，V2-01“记录 Token usage”在生产不生效 |

### 建议修改

| ID | 严重级别 | 文件 | 位置 | 核心问题 |
|---|---|---|---|---|
| S-01 | Medium | services/agent-service/tests/test_observability.py、observability.py | `FakeLangfuseSpan.update` | fake span 接受任意 kwargs，无法验证与真实 SDK 的字段名契约，是 M-01 漏检的根因 |
| S-02 | Low | scripts/deploy/validate-env.sh | LANGFUSE enabled 分支 | 启用 Langfuse 时仅校验 host 非空，未校验 http/https URL 格式，错误只在 fail-open 后静默降级 |
| S-03 | Low | services/agent-service/src/agentforge_agent/api.py | `chat_stream` 的 `events()` | 流式成功路径 request/agent 观测闭合依赖生成器被实际消费；客户端在首次迭代前断开时根观测不闭合 |
| S-04 | Low | docs/03-features/README.md | observability 索引行 | 索引写“正在实现”，而 observability.md 状态为 Implemented，文档状态不一致 |

### 无需修改

| ID | 说明 |
|---|---|
| N-01 | IPv6 issuer 方括号、`is_ipv6_address` 结构校验、www 派生（根域名加 www、已带 www 不重复、IP 为空）、cert-name/live 目录稳定化、临时自签 SAN：实现正确并与文档一致 |
| N-02 | 敏感字段白名单与异常脱敏：message/answer/检索正文/Tool 参数/凭据/原始异常文本均不入 Trace；`fail()` 只发送异常类名；默认关闭与 `SecretStr` 用法正确 |
| N-03 | `.env.production.example`/`generate-production-env.sh` 中的固定 Demo 凭据：属 ADR-0015 声明的公开登录页凭据，非服务密钥，不构成泄漏 |
| N-04 | 节点边界：未越界实现 V2-02 及后续 Node，未引入 Neo4j/MCP/LiteLLM，roadmap 状态更新与本次发布范围一致 |

## 逐个 Issue 展开

### M-01 — Token usage 关键字与真实 Langfuse SDK 不匹配，usage 被静默丢弃（High）

- **Severity**：High
- **File & Line**：`services/agent-service/src/agentforge_agent/llm.py`（`_update_usage`、`stream_observed` 中写入 usage），`services/agent-service/src/agentforge_agent/observability.py`（`Observation.update`）
- **Evidence**：

```python
# llm.py
class CompatibleLlmResponder:
    def stream_observed(self, state, observation):
        ...
        if observation is not None and latest_usage:
            observation.update(usage_details=latest_usage)   # ← 非 SDK 参数名

    @staticmethod
    def _update_usage(observation, response) -> None:
        usage = _usage_details(response)
        if observation is not None and usage:
            observation.update(usage_details=usage)          # ← 非 SDK 参数名

# observability.py
class Observation:
    def update(self, **values: object) -> None:
        if not self._ended and values:
            try:
                self._span.update(**values)
            except Exception:
                return                                        # ← 失败被静默吞并
```

- **Description**：

  Langfuse Python SDK（v3/v4，本仓库实际解析为 4.15.1）观测对象的 `update()` 公共参数名是 `usage`，接受 `Usage`/字典（键为 `input`、`output`、`total`、可选 `unit`），不存在 `usage_details` 参数。当前实现把 `{"input":..., "output":..., "total":...}` 通过 `usage_details=` 传入，真实 SDK 会抛出 `TypeError: update() got an unexpected keyword argument 'usage_details'`，被 `Observation.update` 的 `except Exception: return` 静默吞掉。

  结果：在启用 Langfuse 的生产环境，generation 的 Token usage 不会被记录，直接违反 V2-01 验收标准“generation 记录 provider/model 与可获得的 input/output/total Token”以及变更记录、`observability.md`、ADR-0016 的声明。由于 fake span 的 `update(**values)` 接受任意关键字，全部相关测试仍为绿色，无法暴露该问题；离线真实 SDK smoke 只报告了“generation update/end”与 `LANGFUSE_API_OK`，未单独断言 usage 真正落盘。

  其余字段 `model`、`metadata`、`output`、`level`、`status_message` 均对应 SDK 合法参数，唯一错误是 `usage_details`。

- **Suggested Fix**：

  将写入关键字改为 `usage`：

```python
@staticmethod
def _update_usage(observation, response) -> None:
    usage = _usage_details(response)
    if observation is not None and usage:
        observation.update(usage=usage)

def stream_observed(self, state, observation):
    ...
    if observation is not None and latest_usage:
        observation.update(usage=latest_usage)
```

  并增加一条不依赖 fake 的离线真实 SDK 断言（本地 exporter，不经网络），确认 generation 的 usage 经 `update(usage=...)` 后能被 exporter 捕获；若本机无法构造，至少用当前安装的 4.15.1 运行时检查 `StatefulSpanClient.update` 签名（`inspect.signature`）并固定该契约。最后把 `test_observability.py` 中伪造 span 的 `updates` 断言从 `usage_details` 改为 `usage`。

---

### S-01 — fake span 接受任意关键字的测试 seam 无法验证 SDK 字段契约（Medium）

- **Severity**：Medium
- **File & Line**：`services/agent-service/tests/test_observability.py`（`FakeLangfuseSpan.update`）
- **Evidence**：

```python
class FakeLangfuseSpan:
    def update(self, **values) -> None:
        self.updates.append(values)   # 接受任意关键字，不校验参数名
```

- **Description**：用 `FakeLangfuseClient`/`FakeLangfuseSpan` 作为 Langfuse 外部系统边界符合 testing-strategy.md 约定，但 fake 的 `update(**values)` 不校验参数名，使“usage_details 是否是真实 SDK 参数”“trace_context 词典键是否正确”这类契约无法被测试约束。上一轮的 trace_context 经运行时签名确认为真，但本次 usage 参数名正是因该 seam 漏检。建议把“真实 SDK 公共签名”固化为一条离线契约。

- **Suggested Fix**：在不联网前提下，对真实 `Langfuse` SDK 的 span 执行一次 `update(usage={"input":1,"output":2,"total":3})` 并断言 exporter 收到 usage（或断言 `update` 签名含 `usage` 且不含 `usage_details`）；现有 fake 测试继续用于行为断言。

---

### S-02 — validate-env.sh 未校验 Langfuse host 的 URL 格式（Low）

- **Severity**：Low
- **File & Line**：`scripts/deploy/validate-env.sh`，`AGENTFORGE_AGENT_LANGFUSE_ENABLED == true` 分支
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

- **Description**：只检查非空，未校验 http/https 前缀。非法 host（如漏写协议的 `cloud.langfuse.com`）会在初始化失败后由 fail-open 静默降级为 No-op，生产要到“没有 Trace”时才发现。不构成业务/安全阻塞，但影响可诊断性。

- **Suggested Fix**：

```bash
true)
    [[ -n "${AGENTFORGE_AGENT_LANGFUSE_PUBLIC_KEY:-}" && \
       -n "${AGENTFORGE_AGENT_LANGFUSE_SECRET_KEY:-}" ]] || {
        echo "Enabled Langfuse tracing requires public key and secret key." >&2
        exit 1
    }
    case "${AGENTFORGE_AGENT_LANGFUSE_HOST:-}" in
        http://*|https://*) ;;
        *) echo "AGENTFORGE_AGENT_LANGFUSE_HOST must be an http(s) URL." >&2; exit 1 ;;
    esac
    ;;
```

---

### S-03 — 流式成功路径观测闭合依赖生成器消费（Low）

- **Severity**：Low
- **File & Line**：`services/agent-service/src/agentforge_agent/api.py`，`chat_stream` 中 `request_observation`/`agent_observation` 创建位置与 `events()` 的 `finally`
- **Evidence**：

```python
request_observation = observability.start_request(...)
agent_observation = request_observation.child("agent", "agent")
try:
    state = build_chat_context_graph(...).invoke(...)   # 成功后 request/agent 不立即结束
...
def events():
    generation_observation = agent_observation.child("llm", "generation")
    try:
        ...
    finally:
        generation_observation.end()
        agent_observation.end()
        request_observation.end()
return StreamingResponse(events(), ...)
```

- **Description**：前置 Graph 成功后的 request/agent 观测结束被延后到 `events()` 生成器的 `finally`。Java 消费方会完整读取 NDJSON，所以常规成功/流式异常路径都能闭合；但若 `StreamingResponse` 在首次迭代前因客户端断开而从未执行，request/agent 观测会保持 open。不影响业务响应与 HTTP/SSE 契约，非阻断。

- **Suggested Fix**：可增加外层生命周期包装或补充“客户端未消费/早断”场景的闭合测试，确保任何情况下根观测最终结束。

---

### S-04 — observability 文档索引状态与功能文档不一致（Low）

- **Severity**：Low
- **File & Line**：`docs/03-features/README.md`（observability 索引行）；对比 `docs/03-features/observability.md` 头部
- **Evidence**：

```markdown
<!-- docs/03-features/README.md -->
- `observability.md`：V2-01 Langfuse 基础 Trace、字段白名单、异常闭合与 fail-open 边界，正在实现。
```

```markdown
<!-- docs/03-features/observability.md -->
- 状态：Implemented
```

- **Description**：同一发布范围内索引仍写“正在实现”，而功能文档与节点路线图均已标记 completed/Implemented。按 DoD“未来状态与已实现状态没有混写”，属轻微文档真实性不一致，不阻塞代码。

- **Suggested Fix**：将索引行改为 `已实现`（或统一使用路线图的状态标记，如 `✅ Implemented`）。

---

## 主开发 (Codex) 评估回填区

| Issue ID | Codex 结论（采纳/误报/豁免） | 判定依据 | 修复或记录位置 |
|---|---|---|---|
| M-01 | 误报，不采纳 | 本次安装的 Langfuse 4.15.1 真实 generation `update()` 签名明确包含 `usage_details: Dict[str, int] | None`；无网络、假凭据运行时探针退出码 0 并打印该签名。改为报告建议的 `usage=` 才会偏离当前 SDK 的显式字段。 | 保持 `llm.py` 正确实现；运行时证据回填发布记录。 |
| S-01 | 不采纳其前提 | fake 用于行为与脱敏边界；真实 SDK 参数契约已由 4.15.1 运行时签名独立核对，未发生报告所称漂移。 | 无代码修改。 |
| S-02 | 记录为后续低风险加固 | Langfuse 默认关闭且初始化 fail-open；host URL 校验不影响本次发布业务正确性。 | 保留既有 V2-01 已知限制。 |
| S-03 | 记录为后续低风险加固 | Java 生产调用方会实际消费 NDJSON；首次迭代前断流不改变业务状态或响应契约。 | 后续流生命周期专项测试处理。 |
| S-04 | 采纳并已修正 | 功能索引应与 `observability.md` 和路线图的 Implemented/completed 状态一致。 | `docs/03-features/README.md` 改为“已实现”。 |

---

## 总体评价

- **节点边界**：合并范围严格限定 V2-01 与 TLS/工作流优化，未越界实现 V2-02 及后续 Node，未引入 V2/V3 禁用组件。
- **安全边界**：Trace 白名单、异常类名脱敏、默认关闭、`SecretStr`、公开 Demo 凭据定位（ADR-0015）均正确。
- **Fail-open 设计整体正确**，但其“吞掉一切异常”的特质放大了 M-01 这类契约漂移——需要靠真实 SDK 契约测试兜底。
- **TLS/域名兼容**：IPv4/IPv6/根域名/已带 www 的派生、cert-name/live 目录、SAN、Nginx 两 server_name 与生产 Compose 渲染经契约测试确认；此前 Attempt 2 的 FORGE-06/07 已记录为后续加固项，不阻塞本节点。
- **发布就绪度**：除 M-01 需修复并回归外，变更记录所列“待完成：Gitleaks、dev/main/tag 推送、生产备份部署与公网核验”须在 Release Gate 关闭前真实完成；这些不属于 Pi 只读范围内的执行项。

## 附注

- 本轮仅基于提供的 diff 证据（首/中/尾采样）、文件清单与显式上下文完成；未运行任何命令、未修改任何文件与 Git 状态。
- 依据固定模型要求，本轮以 `deepseek/deepseek-v4-pro` 完成审查。
