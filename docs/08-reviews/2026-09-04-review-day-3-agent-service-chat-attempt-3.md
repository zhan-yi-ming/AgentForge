# Pi 代码审查报告：day-3-agent-service-chat / Attempt 3

- 日期：2026-09-04
- 审查阶段：day-3-agent-service-chat
- 审查对象：2363461e67d6a39897536bfed6de5e0f8db5bc43（基线：ad543b01d16a5d0e98b8cc1f95e00eff1eafe612）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: PASS

# AgentForge 独立代码审查报告

- **审查阶段**：day-3-agent-service-chat
- **审查轮次**：3 / 3
- **审查目标**：Commit `2363461e67d6a39897536bfed6de5e0f8db5bc43`
- **审查模式**：REVIEW（完全只读，仅依据启动器提供的变更、文件清单与历史报告）
- **审查工具**：Pi Agent（DeepSeek V4-pro）

---

## 一、概述与总体结论

**结论：通过（PASS）。**

本批为 Attempt 2 两项测试有效性缺口修复后的最终交付。经逐项核对，上一轮两项发现均已在 `AgentServiceHttpContractIntegrationTest.java` 中正确落地，且未发现新引入的生产级 Bug、权限绕过、API 契约破坏或测试有效性缺口：

1. **Issue 1（真实 HTTP 仅覆盖 200）已修复**：新增 `javaClientMapsInvalidInternalTokenToServiceUnavailable`（真实 uvicorn + 错误内部 token → 归一化为 `ServiceUnavailableException`）与 `javaClientMapsUnavailableAgentServiceToServiceUnavailable`（不可达 `127.0.0.1:1` → 归一化为 `ServiceUnavailableException`），覆盖了此前缺失的 401/连接不可达真实传输路径。
2. **Issue 2（未断言 `non_null` 序列化与 requestId 一致性）已修复**：新增 `bootBuilderOmitsNullConversationAndKeepsGeneratedRequestIdConsistent`，通过 Boot `RestClient.Builder` 绑定 `MockRestServiceServer`，精确断言出站 JSON 中 null `conversationId` 被省略、requestId 为合法 UUID，且 `X-Request-Id` header 与 body `requestId` 完全一致。

新增测试逻辑正确、断言有效，测试数量由 1 项补齐至 4 项，与验证 Prompt 中“至少 4 项实际执行且未跳过”的门禁要求一致。文档变更（`agent-chat.md`、`local-development.md`、变更记录与复验报告）与新增测试行为描述相符，未引入 V2/V3 阶段组件，未发现敏感信息泄露。

---

## 二、上轮问题修复核验

| 上轮 Issue | 修复落地情况 | 核验结论 |
| --- | --- | --- |
| 1：真实 HTTP 契约测试仅覆盖 200 成功路径 | 新增错误内部 token 与不可达下游两个真实传输路径测试，均断言归一化为 `ServiceUnavailableException` | 已修复 |
| 2：未验证 `non_null` 序列化及 header/body requestId 一致性 | 新增 Boot builder + `MockRestServiceServer` 出站请求契约测试，断言 null 字段省略、requestId 为 UUID 且与 header 一致 | 已修复 |

---

## 三、新增代码逐项核验

### 1. `javaClientMapsInvalidInternalTokenToServiceUnavailable`

- 通过 `restClient.mutate().defaultHeaders(headers -> headers.set("X-AgentForge-Internal-Token", "invalid-test-token"))` 替换真实 RestClient 的默认 token，随后 `HttpHeaders.set` 行为保证同名 header 值被替换，不会残留正确 token。
- 断言抛出 `ServiceUnavailableException`，与“内部 401 对 Core API 调用方统一表现为 503”的契约一致。
- 该用例与真实 uvicorn 交互，覆盖 Java 端对 Python 401 的归一化路径，测试有效性成立。

### 2. `javaClientMapsUnavailableAgentServiceToServiceUnavailable`

- 使用 `URI.create("http://127.0.0.1:1")` 构造不可达基址，通过 `AgentServiceConfiguration#agentServiceRestClient(restClientBuilder, unavailableProperties)` 复用生产 RestClient 构建逻辑。
- 连接拒绝/不可达路径会由 `HttpAgentServiceClient` 归一化为 `ServiceUnavailableException`，断言正确。
- 将 connect/read timeout 均设为 2 秒，避免测试长时间挂起；端口 1 通常立即拒绝连接，测试确定性良好。

### 3. `bootBuilderOmitsNullConversationAndKeepsGeneratedRequestIdConsistent`

- `restClientBuilder.clone().baseUrl("http://contract.test")` 复用了 Boot 自动配置的 `RestClient.Builder` 与生产 Jackson/消息转换器，确保 `spring.jackson.default-property-inclusion=non_null` 真正进入被验证链路。
- `MockRestServiceServer.bindTo(...)` 保留原 builder 的消息转换器，仅替换请求工厂，适合验证序列化契约。
- 出站断言完整：`conversationId` 字段不存在、`requestId` 可解析为合法 UUID、`X-Request-Id` header 等于 body 中的 `requestId`。
- 响应体固定为合法 JSON，`server.verify()` 确保期望请求确实发生，测试不会空转通过。

---

## 四、详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| — | — | — | — | 本次未发现需 Codex 处理的问题 |

---

## 五、逐项 Issue 展开

无。本轮未发现可疑 Bug、权限绕过、契约不一致、测试缺口或敏感信息泄露。

---

## 六、主开发（Codex）评估回填区

| Issue ID | 是否采纳 | 处理说明 | 修复后验证 |
| --- | --- | --- | --- |
| （上轮）1 | 已采纳 | 在真实 uvicorn 契约类补充错误内部 token 与不可达下游场景，验证统一抛出 `ServiceUnavailableException` | Pi 复核通过：契约类 4 项全部覆盖、0 跳过 |
| （上轮）2 | 已采纳 | 使用 Boot builder 绑定 `MockRestServiceServer`，断言 null `conversationId` 省略、requestId 为 UUID 并与 header 一致 | Pi 复核通过：出站 JSON 与 requestId 精确断言通过 |

---

## 七、边界合规确认

- 本轮变更未引入 Neo4j/GraphRAG、Langfuse 完整 Trace、LiteLLM、MCP、checkpoint 持久化等 V2/V3 组件。
- HTTP/1.1 显式固定、Boot `RestClient.Builder` 注入与空 `requestId` UUID 兜底均未改变系统边界或公共契约。
- 文档教程未包含真实密钥、私钥、Bearer/JWT 或生产连接串；生成随机值的命令仅输出到被 Git 忽略的 `.env`。
