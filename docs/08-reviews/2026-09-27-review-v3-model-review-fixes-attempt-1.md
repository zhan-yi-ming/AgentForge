# Pi 代码审查报告：v3-model-review-fixes / Attempt 1

- 日期：2026-09-27
- 审查阶段：v3-model-review-fixes
- 审查对象：INDEX@ff68052（基线：ff6805282ea96b87e326a60dc31e27518344f8fb）
- 审查工具：Pi Agent（DeepSeek V4.1 Flash，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- 审查阶段：v3-model-review-fixes
- 审查模式：Milestone（只读，未执行命令、未修改文件、未改变 Git 状态）
- 审查轮次：1 / 3
- 审查目标：`INDEX@ff68052`（39 文件，+498 / −64）
- 依赖依据：本次 Git Diff、文件清单、`v2-v3-node-roadmap.md`、节点开发协议、无上一轮报告
- 固定模型：本轮仅依据给定材料完成审查（未发生模型切换）

## 一、概述与总体结论

本次改动属于 V3-02/V3-03 的审核修复，方向正确且落地较为完整：

1. **显式 taskType 取代消息前缀分类**：有限枚举 `FORMAT/REWRITE/PLAN/REVIEW/ANSWER`，省略/null 默认 `ANSWER`；Java 与 Python 双重校验；消息正文不再控制路由；Tool 意图仍固定 `PLAN`。这一改动消除了“用户正文伪造 `FORMAT:` 前缀改变路由”的不确定性，方向正确。
2. **Java 校验数字 ordinal**：`AgentTaskType.fromJson` + HTTP 测试拒绝 `taskType:0`，修复了 Jackson 默认按 enum ordinal 反序列化的问题。
3. **流式回退观测隔离**：provider/model 切换时重置 `latest_usage/latest_cost_metadata`，避免失败主模型 usage/cost 污染成功备模型观测，逻辑与测试自洽。
4. **静态 JSON 能力校验**：`LiteLlmGateway.bind` 在 JSON 模式下若备模型未声明 JSON 能力则丢弃 fallback，`plan_tool` 失败关闭为“无意图”，不会产生未经校验的写入，安全语义正确。
5. **外层超时预算**：Core read 75s→330s、SSE emitter 120s→360s、Nginx stream 120s→360s、MCP 300s→360s，并新增部署预算回归测试。

总体判定：**需修复后交付（NEEDS_FIX）**。核心逻辑无回归，但有 1 项与本次“外层超时预算”目标直接相关的部署一致性缺陷，导致同步 Chat 路径仍可能在 Nginx 默认 60s 处被截断，与 Core 330s 预算和文档描述不一致；其余为建议性改进。

## 二、详细发现清单（按严重度排序，最多十项）

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| M1 | High | `infra/nginx/production.conf.template` | ~L47 | 同步 `/agent/chat` location 未设置 `proxy_read_timeout`，按 Nginx 默认 60s，与 Core 330s 预算冲突 |
| S1 | Medium | `services/core-api/src/test/java/.../AgentChatApiTest.java` | 末尾 | 缺少 `taskType: null` 的 HTTP 契约测试（文档承诺 null→ANSWER，Python 已覆盖） |
| S2 | Medium | `.env.example` / `.env.production.example` / `infra/compose*.yaml` | 新增段 | `LLM_JSON_OUTPUT` 默认 `true` 与所选 provider 能力解耦，切换模型时易产生错误默认 |
| S3 | Low | `services/agent-service/tests/test_deployment_model_budget.py` | L1-16 | 仅校验 Compose 默认值，未覆盖运行时 override、SSE emitter 与 Nginx；且用正则解析字符串，脆弱 |
| S4 | Low | `services/core-api/.../AgentServiceClient.java` | ~L20 | 默认方法抛 `UnsupportedOperationException`，未更新的实现会得到 500，缺少明确契约失败语义 |
| S5 | Low | `.../AgentChatController.java`、`.../AgentChatService.java` | ~L47、~L43 | `taskType == ANSWER ? 5参 : 6参` 三元分支复制调用，可读性与可维护性差 |
| S6 | Low | `AgentChatApiTest.java` / `AgentTaskModeContractTest.java` | 文件末尾 | 缺少行尾换行（`\ No newline at end of file`） |
| S7 | Low | `docs/07-changes/2026-09-27-v3-model-review-fixes.md`、`docs/01-product/v3-04-development-plan.md` | 全文 | V3-04 规划文档与路线图级内容混入 V3-02/03 修复提交，偏离“单一目的提交” |
| S8 | Low | `infra/nginx/production.conf.template` | ~L60 | stream 的 Nginx read 360s 与 SSE emitter 360s 无余量，边界时可能由 Nginx 先断流 |

> 说明：`无需修改` 项不列入上表，见第四节。

## 三、逐个 Issue 展开

### M1（必须修改）同步 Agent Chat 未对齐外层超时预算

- Severity：High
- File & Line：`infra/nginx/production.conf.template`，同步 location `location ~ ^/api/v1/projects/[^/]+/agent/chat$`（约 L47 起）
- Evidence（来自本次 diff 上下文）：

```nginx
location ~ ^/api/v1/projects/[^/]+/agent/chat$ {
    limit_req zone=agent_per_ip burst=5 nodelay;
    proxy_pass http://core-api:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    # 无 proxy_read_timeout -> Nginx 默认 60s
}
```

同文件仅新增/调整了两处：

```nginx
location ~ ^/api/v1/projects/[^/]+/agent/chat/stream$ {
    ...
    proxy_read_timeout 360s;   # 120s -> 360s
}
location = /mcp {
    ...
    proxy_read_timeout 360s;   # 300s -> 360s
}
```

而本次同时把 Core read timeout 提升到 330s：

```yaml
read-timeout: ${AGENTFORGE_AGENT_READ_TIMEOUT:PT330S}
```

- Description：
  - 同步 `/chat` 与流式 `/chat/stream` 共用同一套 Agent 预算（检索 ≤60s + 规划主备 2×60s + 回答主备 2×60s ≈ 300s，Core read 330s）。
  - Nginx 对同步 `/chat` location 未设置 `proxy_read_timeout`，按 Nginx 默认 **60s** 计；即便 server/http 层存在继承值，也明显小于 330s（否则无需为 stream/MCP 显式覆盖）。
  - 直接后果：一次触发备模型回退（主模型 60s 超时 + 备模型继续生成）的同步 Chat，总耗时很容易超过 60s，Nginx 先返回 504，而 Core/Agent 仍在处理，用户看到“AI 服务不可用”，与本次“外层超时预算”目标及文档中“Nginx read 360 秒”的表述不一致。
  - 这是本次改动目标内的未完成项，不是纯风格问题。
- Suggested Fix：

```nginx
location ~ ^/api/v1/projects/[^/]+/agent/chat$ {
    limit_req zone=agent_per_ip burst=5 nodelay;
    proxy_pass http://core-api:8080;
    proxy_http_version 1.1;
    proxy_read_timeout 360s;   # 与 Core read / SSE 预算对齐
    proxy_send_timeout 360s;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}
```

建议同时评估 `/api/v1/projects/{id}/agent/actions/{actionId}/confirm`（resume 可能触发 Agent 恢复）所在 `location /api/` 的读超时，避免同类截断；并在 `test_deployment_model_budget.py` 中补充对 Nginx/SSE 预算的静态断言（见 S3）。

### S1（建议修改）缺少 `taskType: null` 的 Java HTTP 契约测试

- Severity：Medium
- File & Line：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentChatApiTest.java`（新增测试末尾）
- Evidence：新增了 `explicitFormatModeReachesAuthorizedApplicationService`、`unknownTaskModeIsRejectedForSyncAndStream`、`numericTaskModeDoesNotSelectEnumByOrdinal`，但未覆盖显式 `"taskType": null`；而 `docs/04-api/core-api.md` 明确写“省略或 null 为 ANSWER”，Python 侧 test_model_routing 有 `null_mode` 断言。
- Description：Java 依赖 `AgentChatRequest` 紧凑构造器把 null 归一为 `ANSWER`，其正确性取决于 Jackson 对 record creator 属性的 null 传递行为（不受 `@JsonCreator fromJson` 影响）。当前默认省略路径已被既有测试间接覆盖（mock 5 参 `chat`），但显式 null 无独立红灯保护，文档承诺与测试证据不完全对齐。
- Suggested Fix：补充参数化 HTTP 测试，分别发送 `{"message":"notes"}` 与 `{"message":"notes","taskType":null}`，断言均落到 5 参（`ANSWER`）服务方法；如发现 null 被 `valueOf(null)` 处理导致 400，则在 `fromJson` 中显式 `value == null ? ANSWER : valueOf(value)` 并补测。

### S2（建议修改）JSON 能力默认值与 provider 解耦

- Severity：Medium
- File & Line：`.env.example`、`.env.production.example`、`infra/compose.yaml`、`infra/compose.prod.yaml`（新增 `AGENTFORGE_AGENT_LLM_JSON_OUTPUT:-true`）
- Evidence：

```yaml
AGENTFORGE_AGENT_LLM_JSON_OUTPUT: ${AGENTFORGE_AGENT_LLM_JSON_OUTPUT:-true}
AGENTFORGE_AGENT_LLM_FALLBACK_JSON_OUTPUT: ${AGENTFORGE_AGENT_LLM_FALLBACK_JSON_OUTPUT:-false}
```

- Description：默认主模型 JSON=true 在 `provider=disabled` 或 DeepSeek 时无害；但部署者切换为智谱/千问/其他兼容端点时，若未同步修改该变量，会强制以 `response_format={"type":"json_object"}` 调用可能不支持的模型，导致 Tool 意图解析异常（`plan_tool` 失败关闭为无意图，不会误写，但功能静默退化）。文档已提示“切换模型须核对能力”，但与配置默认值的耦合仍是隐患。
- Suggested Fix：保持默认 `true` 仅对已知支持的 provider 生效，或将默认值改为 provider 感知（如 `disabled`/非 DeepSeek 时默认 `false`）；在 README/ops 文档中把该变量列入“切换 provider 必查清单”。

### S3（建议修改）部署预算测试覆盖不足且解析脆弱

- Severity：Low
- File & Line：`services/agent-service/tests/test_deployment_model_budget.py` L1-16
- Evidence：

```python
per_call = float(re.search(r":-(\d+)", str(agent["AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS"])).group(1))
read = int(re.search(r":-PT(\d+)S", core["AGENTFORGE_AGENT_READ_TIMEOUT"]).group(1))
assert read >= 5 * per_call + 30, "Core must cover retrieval + planning/answer primary/fallback and margin"
```

- Description：测试只读取 Compose 中的默认表达式，无法覆盖运行时 `.env` override；也没有断言 SSE emitter（360s）与 Nginx read（stream/MCP）之间的一致性，以及 M1 中的同步 location。数字通过正则从字符串提取，格式变动即失效。
- Suggested Fix：补充对 `AgentChatController` SSE emitter 常量与 Nginx stream location 的解析断言，形成“Core read ≤ SSE emitter ≤ Nginx read”的关系校验；对缺失 timeout 的 agent location 直接失败（可顺带捕获 M1）。

### S4（建议修改）`AgentServiceClient` 默认方法失败语义不明确

- Severity：Low
- File & Line：`services/core-api/src/main/java/com/agentforge/core/agent/application/AgentServiceClient.java` ~L20
- Evidence：

```java
default AgentChatResult chat(..., AgentTaskType taskType) {
    if (taskType != AgentTaskType.ANSWER) throw new UnsupportedOperationException("Task mode unsupported");
    return chat(...);
}
```

- Description：未同步更新的实现（测试替身/未来适配器）在非 ANSWER 模式会抛 `UnsupportedOperationException`，被上层包成 5xx，而非可诊断的契约错误。当前生产实现已 override，不影响线上，但默认方法的存在会掩盖漏实现。
- Suggested Fix：改为在默认方法中抛出受检/领域异常（如 `ServiceUnavailableException` 或专用 `UnsupportedTaskModeException`），或在接入层显式 fail-closed 并补一条“未实现新契约即失败”的测试。

### S5（建议修改）Controller/Service 三元分支复制调用

- Severity：Low
- File & Line：`AgentChatController.java`（chat/chatStream）、`AgentChatService.java`（chat/streamToAgent）
- Evidence：

```java
return AgentChatResponse.from(request.taskType() == AgentTaskType.ANSWER ? agentChatService.chat(...5参...) : agentChatService.chat(...6参...));
```

- Description：为保持旧签名兼容而在调用点做 `ANSWER ? 旧重载 : 新重载`，逻辑重复且可读性差；后续新增任务模式需要改动多处。
- Suggested Fix：将 5 参重载直接委托给 6 参（显式传 `AgentTaskType.ANSWER`），Controller/Service 统一只调用 6 参版本，消除调用点分支。

### S6（建议修改）Java 测试文件缺少行尾换行

- Severity：Low
- File & Line：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentChatApiTest.java`、`.../infrastructure/AgentTaskModeContractTest.java`（diff 末尾 `\ No newline at end of file`）
- Description：仅影响 diff 整洁与部分工具检查，无功能影响。
- Suggested Fix：补一个行尾换行；`diff check` 若已通过则不阻塞。

### S7（建议修改）V3-04 规划文档混入 V3-02/03 修复提交

- Severity：Low
- File & Line：`docs/01-product/v3-04-development-plan.md`（新增）、`docs/07-changes/2026-09-27-v3-model-review-fixes.md`
- Description：本次提交主体是 V3-02/V3-03 审核修复，但同 commit 引入了 V3-04 Start Gate 规划文档。内容本身标注 `Planned`、未实现图能力，属合规准备；但节点协议建议“一个 Node 一个清晰 Commit”，混入下一节点材料会削弱可回滚性与提交语义。
- Suggested Fix：保持 V3-04 规划与修复分离（独立提交或至少在 Close Gate 中明确说明），避免后续将规划文档误读为本轮实现范围。

### S8（建议修改）SSE 与 Nginx 超时无余量

- Severity：Low
- File & Line：`infra/nginx/production.conf.template` ~L60、`AgentChatController` `new SseEmitter(360_000L)`
- Description：Nginx stream read = 360s 与 SSE emitter = 360s 相等，且 Core read = 330s。长流接近 360s 时，可能由 Nginx 先断开而非 SSE 正常收尾，错误路径更隐蔽。
- Suggested Fix：令 `Nginx read > SSE emitter > Core read`（例如 Nginx 380–390s、SSE 360s、Core 330s），或在文档中明确说明三者相等时的取舍。

## 四、无需修改（已确认正确）

| ID | 项 | 结论 |
| --- | --- | --- |
| N1 | 消息前缀分类移除、改为显式有限 taskType | 正确；`classify` 删除后无残留引用，Tool 意图仍固定 PLAN，路由/权限边界未变 |
| N2 | 流式回退 usage/cost 隔离 | 正确；provider/model 变化时重置并在同一 chunk 重新采集，测试 `test_stream_fallback_...` 验证通过 |
| N3 | 静态 JSON 备模型能力校验 | 正确；未声明 JSON 的备模型被排除，`plan_tool` 失败关闭为 `None`，不产生写入 |
| N4 | Java 数字 ordinal 拒绝 | 正确；`@JsonCreator` + HTTP 测试覆盖 `taskType:0 → 400`，`UNKNOWN → 400` |
| N5 | Web 显式 `FORMAT` 传参 | 正确；`App.tsx` 第 6 参数位置与 `api.ts` 接口一致，测试断言同步更新 |

## 五、主开发（Codex）评估回填区

| Issue ID | Codex 判断（成立/不成立/部分成立） | 事实与原因 | 处理（修复/不修复+理由） | 验证方式 |
| --- | --- | --- | --- | --- |
| M1 | 成立 | 同步 location 确无显式读超时 | 已修复为 360s，MCP 恢复原 300s | 预算红灯后 4 passed、Nginx 与双 Compose PASS |
| S1 | 建议 | compact constructor 已归一 null，未见缺陷 | 不扩大非阻塞测试范围 | 既有 omitted + Python null 证据 |
| S2 | 部分成立 | 示例是显式能力声明，切换须核对 | 已公开限制，不改默认语义 | 静态兼容/不兼容 fallback 回归通过 |
| S3 | 部分成立 | 原测试未覆盖同步 Nginx | 增加 sync/stream 预算契约 | 旧同步实际红灯，新 4 passed |
| S4 | 建议 | 唯一生产实现已 override | 不修改未来适配器假设 | HTTP 契约验证通过 |
| S5 | 建议 | 为保持已有调用兼容性保留分支 | 不做无关重构 | Java 全量与契约通过 |
| S6 | 建议 | 纯格式，无 diff check 错误 | 本次不修改 | diff check PASS |
| S7 | 不成立为违规 | 用户明确同时要求规划，只有 Planned 文档 | Close Gate 明确无 V3-04 实现 | 对照用户授权与路线 |
| S8 | 部分成立 | idle read 与 emitter 总期限语义不同 | 保留预算并说明极限行为 | Nginx 语法与预算契约通过 |

## 六、结论

- 必须修改：1 项（M1，同步 Agent Chat 的 Nginx 读超时未与 Core/SSE 预算对齐），因此本轮为 **NEEDS_FIX**。
- 建议修改：8 项，均为可维护性、测试完整性、配置默认值或提交治理问题，不单独阻塞。
- 无需修改：5 项核心逻辑确认正确，无安全/权限/幂等回归。
- 本轮为只读审查：未运行命令、未修改文件、未改变 Git 状态；Codex 提供的测试记录仅作为证据引用，未改写为 Pi 执行结果。
