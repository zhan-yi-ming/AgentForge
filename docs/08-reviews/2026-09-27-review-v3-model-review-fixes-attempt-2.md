# Pi 代码审查报告：v3-model-review-fixes / Attempt 2

- 日期：2026-09-27
- 审查阶段：v3-model-review-fixes
- 审查对象：INDEX@ff68052（基线：ff6805282ea96b87e326a60dc31e27518344f8fb）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- 审查阶段：v3-model-review-fixes
- 审查模式：Milestone（只读；未运行命令、未修改文件、未改变 Git 状态）
- 审查轮次：2 / 3
- 审查目标：`INDEX@ff68052`（40 文件，+752 / −63）
- 依赖依据：本次 Git Diff、文件清单、`v2-v3-node-roadmap.md`、节点开发协议、Attempt 1 报告及其 Codex 回填
- 固定模型：`deepseek/deepseek-flash`（本轮未发生模型切换）

## 一、概述与总体结论

本轮是在 Attempt 1 唯一阻塞项 M1 修复后的复验。结论：**通过（PASS）**，无“必须修改”。

1. **M1 已修复且被测试固定**：`infra/nginx/production.conf.template` 同步 `location ~ ^/api/v1/projects/[^/]+/agent/chat$` 已显式声明 `proxy_read_timeout 360s;`，与 Core `PT330S`、SSE emitter `360_000L`、流式 location `360s` 对齐；`location = /mcp` 的 `proxy_read_timeout 300s` 恢复为原业务预算（不再被模型回答预算污染）。新增 `test_nginx_chat_locations_cover_core_model_wait_budget` 对 `chat` / `chat/stream` 两个 location 做“缺少显式 timeout 即失败”的断言，能在旧代码上真实红灯，验证有效。
2. **核心逻辑无回归**：显式有限 `taskType`（Java 枚举校验 + Python `Literal` 校验，省略/null→ANSWER）、消息前缀不再控制路由、Tool 意图固定 PLAN、静态 JSON 备模型能力校验、流式回退 usage/cost 隔离均在 diff 中保持且测试自洽。
3. **无安全/权限/幂等/契约破坏**：`taskType` 只影响部署配置内的模型排序，不提供任何业务权限；Java 仍独占授权与写入；HTTP 状态码语义（未知/数字枚举→400）未被改变。

剩余均为建议性改进：配置默认值与 provider 能力耦合、两个新测试的构造/解析偏弱、`confirm/resume` 路径未纳入同一预算覆盖、以及少量可读性/格式问题。均不构成可运行性、正确性、安全、权限、并发或契约缺陷。

## 二、详细发现清单

### 必须修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 无 |

### 建议修改

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| A1 | Medium | `.env.example` / `.env.production.example` / `infra/compose.yaml` / `infra/compose.prod.yaml` | 新增段 | `AGENTFORGE_AGENT_LLM_JSON_OUTPUT:-true` 恒覆盖 `config.py` 的 provider 感知默认，切换非 DeepSeek provider 时强制 JSON 模式 |
| A2 | Low | `services/agent-service/tests/test_deployment_model_budget.py` | L18-25 | Nginx 断言用字符串 `split` 抽取 location，并硬编码 `330`；未断言 SSE emitter 与 Nginx 的关系 |
| A3 | Low | `services/agent-service/tests/test_model_gateway.py` | `test_stream_fallback_does_not_inherit_failed_attempt_usage_or_cost` | 失败主模型 chunk 用 `.usage` 注入，但 `_usage_details` 读取 `.usage_metadata`，测试并未真正触发其声称的泄漏路径 |
| A4 | Low | `infra/nginx/production.conf.template` | 通用 `/api/` location（不在本次 diff 内） | `confirm`/`resume` 可能触发 Agent 恢复，但未纳入 360s 读超时覆盖；无显式证据，故仅建议 |
| A5 | Low | `services/core-api/.../application/AgentChatService.java` | `chat()` 内 `persist(command(...))` | 同步路径持久化时用 `command(...)` 新建 `AgentChatCommand`，丢弃原 `taskType`（当前 `persist` 不使用该字段，无实际影响） |
| A6 | Low | `.../api/AgentChatApiTest.java`、`.../infrastructure/AgentTaskModeContractTest.java` | 文件末尾 | 仍缺行尾换行（`\ No newline at end of file`） |
| A7 | Low | `AgentChatController.java`、`AgentChatService.java` | `chat`/`chatStream`/`streamToAgent` | `ANSWER ? 5参 : 6参` 三元分支在多处重复，可维护性一般 |
| A8 | Low | `AgentChatApiTest.java` | 新增测试区 | 未覆盖显式 `"taskType": null`（文档承诺 null→ANSWER；Python 已覆盖）；本轮仍无该红灯 |

### 无需修改（已确认正确）

| ID | 项 | 结论 |
| --- | --- | --- |
| N1 | M1：同步 Agent Chat Nginx 读超时 | 已修复为 `360s`；新增部署契约测试可捕获旧缺陷，MCP 恢复 `300s` 合理 |
| N2 | 有限 `taskType` + 省略/null→ANSWER | 正确；record compact constructor 归一，Jackson 对 null 属性走空值提供者，不调用 `fromJson(null)`；未知/数字枚举→400 |
| N3 | 消息前缀不再路由 | 正确；`classify` 删除无残留引用，`test_message_prefix_cannot_override_default_answer_route` 有覆盖 |
| N4 | 流式回退 usage/cost 隔离 | 正确；provider/model 变化即重置，真实成功调用回填，语义自洽 |
| N5 | 静态 JSON 备模型能力校验 | 正确；未声明 JSON 的备模型被 `bind` 丢弃，`plan_tool` 失败关闭为 None，不产生写入 |
| N6 | Tool 意图固定 PLAN | 正确；模型目标仍由部署配置决定，用户 `taskType` 不改变审批/写入边界 |

## 三、逐个 Issue 展开

### A1（建议修改）JSON 能力默认值与 provider 解耦

- Severity：Medium
- File & Line：`.env.example` / `.env.production.example`（新增两行）、`infra/compose.yaml`、`infra/compose.prod.yaml`（`AGENTFORGE_AGENT_LLM_JSON_OUTPUT`）
- Evidence：

```yaml
AGENTFORGE_AGENT_LLM_JSON_OUTPUT: ${AGENTFORGE_AGENT_LLM_JSON_OUTPUT:-true}
AGENTFORGE_AGENT_LLM_FALLBACK_JSON_OUTPUT: ${AGENTFORGE_AGENT_LLM_FALLBACK_JSON_OUTPUT:-false}
```

- Description：`config.py` 的 `llm_json_output: bool | None = None` 会在省略时按 provider 判定（仅 DeepSeek 默认 JSON）。但 Compose/示例把它显式设为 `true`，等于在部署层恒定覆盖该判定。部署者切到智谱/千问等端点若未同步修改，会强制 `response_format={"type":"json_object"}`。由于 `plan_tool` 失败关闭为无意图，不会误写，只是功能静默退化。文档已提示“切换须核对能力”，故为部署风险而非已复现缺陷。
- Suggested Fix：在 `model-routing.md` / `agent-service/README.md` 的“切换 provider 必查清单”中显式列出该变量；或将其默认值改为按 provider 推导（`disabled`/非 DeepSeek 默认 `false`）。

### A2（建议修改）Nginx 预算测试解析脆弱

- Severity：Low
- File & Line：`services/agent-service/tests/test_deployment_model_budget.py` L18-25
- Evidence：

```python
location = template.split(marker, 1)[1].split("}", 1)[0]
read = re.search(r"proxy_read_timeout\s+(\d+)s;", location)
assert int(read.group(1)) >= 330, "Nginx must cover the Core model waiting budget"
```

- Description：`split("}", 1)` 依赖 location 内无嵌套块；`330` 为硬编码，未与 Core 值联动；也未断言 `AgentChatController` 的 SSE emitter 与 Nginx 的关系（Attempt 1 的 S3/S8）。
- Suggested Fix：解析为结构化结果或至少让阈值由 Core 值推导（`>= core_read`），并补一条对 `SseEmitter(360_000L)` 与 stream location 的关系断言。

### A3（建议修改）流式回退观测测试未真正触发泄漏路径

- Severity：Low
- File & Line：`services/agent-service/tests/test_model_gateway.py`，`test_stream_fallback_does_not_inherit_failed_attempt_usage_or_cost`
- Evidence：

```python
yield SimpleNamespace(choices=[], usage=SimpleNamespace(prompt_tokens=7, completion_tokens=2, total_tokens=9))
```

- Description：`CompatibleLlmResponder._usage_details` 读取的是 `response.usage_metadata`，而该测试把用量挂在 `.usage` 上，因此失败主模型的用量根本不会被采集，`observed.usage is None` 是平凡通过，未验证“重置”逻辑。
- Suggested Fix：把失败 chunk 的用量放到 `usage_metadata={"input_tokens":..., "output_tokens":..., "total_tokens":...}`，再断言重置为 None，才能形成有效的红灯。

### A4（建议修改）`confirm`/`resume` 路径未纳入读超时覆盖

- Severity：Low
- File & Line：`infra/nginx/production.conf.template` 内处理 `/api/v1/.../agent/actions/{actionId}/confirm` 的通用 location（本次 diff 未展示其内容）
- Description：Core 的 `read-timeout` 已统一为 330s，涵盖 Agent 服务调用；但 Nginx 只为 `chat`、`chat/stream`、`/mcp` 显式设置了超时。若 confirm 触发的 resume 会继续执行模型生成，通用 location 的默认 60s 仍可能截断，形成与 M1 同类但不完全等价的缺口。因未能从 diff 取得通用 location 的文本证据，此处仅作建议。
- Suggested Fix：确认通用 `/api/` location 的 `proxy_read_timeout` 至少覆盖 resume 的最长预算；若是，请在文档中写明依据。

### A5（建议修改）同步 persist 使用新建 command 丢弃 taskType

- Severity：Low
- File & Line：`services/core-api/.../application/AgentChatService.java`，`chat(...)` 末尾 `persist(command(projectId, actor, message, conversationId, requestId), finalized);`
- Description：`command(...)` 调用 5 参重载，固定 `AgentTaskType.ANSWER`。当前 `persist` 仅使用 projectId/actor/conversationId/message，无实际影响；但若将来把 taskType 纳入会话记录，会把 FORMAT/REVIEW 误写为 ANSWER。
- Suggested Fix：让 `command(...)` 接受并透传 `taskType`，或在 `persist` 明确不依赖该字段并加注释。

### A6（建议修改）Java 测试文件缺行尾换行

- Severity：Low
- File & Line：`services/core-api/.../api/AgentChatApiTest.java`、`.../infrastructure/AgentTaskModeContractTest.java`（diff 末尾 `\ No newline at end of file`）
- Description：仅影响 diff 整洁与部分静态检查，无功能影响（Codex 报告 diff check 已 PASS）。
- Suggested Fix：补一个行尾换行。

### A7（建议修改）三元分支重复调用

- Severity：Low
- File & Line：`AgentChatController.java`（chat/chatStream）、`AgentChatService.java`（chat/streamToAgent）
- Evidence：

```java
return AgentChatResponse.from(request.taskType() == AgentTaskType.ANSWER
        ? agentChatService.chat(...5参...) : agentChatService.chat(...6参...));
```

- Description：为兼容旧签名在调用点复制分支，后续新增任务模式需改动多处。
- Suggested Fix：5 参重载直接委托 6 参（显式传 `ANSWER`），调用点统一走 6 参版本。

### A8（建议修改）缺少显式 `taskType: null` 的 Java 契约测试

- Severity：Low
- File & Line：`services/core-api/.../api/AgentChatApiTest.java` 新增测试区
- Description：代码路径经分析可通过（record compact constructor 在属性 null 时归一为 ANSWER，Jackson 不会用 null 调用 `fromJson`），但文档明确承诺“省略或 null 为 ANSWER”，Java 侧仍无独立红灯；Python 已有 `null_mode` 断言。
- Suggested Fix：补参数化 HTTP 测试，同时发送省略与 `"taskType": null`，断言落到 5 参（ANSWER）服务方法。

## 四、主开发（Codex）评估回填区

| Issue ID | Codex 判断（成立/不成立/部分成立） | 事实与原因 | 处理（修复/不修复+理由） | 验证方式 |
| --- | --- | --- | --- | --- |
| A1 | 部分成立 | 部署显式声明须核对 provider/model 能力 | 文档已列 flags 与限制，不改变非阻塞语义 | JSON fallback 兼容性已实测 |
| A2 | 建议 | 当前类型化预算规格为 330s，解析覆盖现有模板 | 无当前缺陷，不测试私有 emitter 代码 | 部署预算 4 passed、真实 Nginx 校验 |
| A3 | 不成立 | fake 模拟 provider SDK 的 usage；真实 Gateway.stream 将 usage 转成 usage_metadata，Responder 再读取 | 保留有效测试，旧源码实际红灯已记录 | 第一次运行 provider 被 stale cost 改回 deepseek，修复后绿灯 |
| A4 | 不成立为当前缺陷 | action_runtime 恢复只执行 _await_decision 状态校验，无模型调用 | 不将模型预算扩散到通用 API | resume 源码、真实重启恢复/契约已通过 |
| A5 | 建议 | persist 只保存 message/answer/sources，不保存任务模式 | 未引入未来历史字段，不扩展范围 | Java history 回归通过 |
| A6 | 建议 | 纯格式且 diff check 通过 | 不修改行为无关文件 | diff check PASS |
| A7 | 建议 | 保留已有调用重载和默认模式兼容 | 不做无关重构 | Java API/服务/HTTP 契约通过 |
| A8 | 建议 | compact constructor null 归一，Pi N2 亦确认正确 | 无新缺陷不扩展测试范围 | omitted 既有测试、Python null 及代码分析 |

M1（上一轮唯一阻塞）复验回填：

| 原 Issue | 复验结论 | 证据 |
| --- | --- | --- |
| M1 | 已修复 | 同步 chat location 新增 `proxy_read_timeout 360s;`；MCP 回到 `300s`；新增部署契约测试对缺失 timeout 直接失败 |

## 五、结论

- 必须修改：0 项。上一轮 M1（同步 Agent Chat Nginx 读超时）已在本次 diff 中修复，并被新增的部署预算契约测试覆盖。
- 建议修改：8 项（A1–A8），均为配置默认值、测试有效性、路径覆盖完整性、可读性与格式问题，不阻塞交付。
- 无需修改：6 项核心逻辑确认正确，无安全、权限、并发、幂等或契约回归。
- 本轮为只读审查：未运行命令、未修改文件、未改变 Git 状态；Codex 提供的测试记录仅作为证据引用，未改写为 Pi 执行结果。
- 结论：**REVIEW_RESULT: PASS**（允许进入 Close Gate / Commit 流程）。
