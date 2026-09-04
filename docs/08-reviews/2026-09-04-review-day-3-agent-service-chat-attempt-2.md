# Pi 代码审查报告：day-3-agent-service-chat / Attempt 2

- 日期：2026-09-04
- 审查阶段：day-3-agent-service-chat
- 审查对象：ad543b01d16a5d0e98b8cc1f95e00eff1eafe612（基线：90e2402c29a6cb613070ff12463fdccfa84c2b05）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- **审查阶段**：day-3-agent-service-chat
- **审查轮次**：2 / 3
- **审查目标**：Commit `ad543b01d16a5d0e98b8cc1f95e00eff1eafe612`
- **审查模式**：REVIEW（完全只读，仅依据启动器提供的变更、文件清单与历史报告）

---

## 一、概述与总体结论

**结论：需修复后交付（NEEDS_FIX）。**

本批为 Attempt 1 三项修复后的第二轮交付。上一轮三个问题的**生产代码修复均正确落地**：

1. `HttpClient` 显式固定 `HTTP_1_1`，消除了 JDK HttpClient 对明文 uvicorn 发起 h2c 升级导致请求体为空的真实缺陷；
2. `AgentServiceConfiguration` 改为注入 Boot 自动配置的 `RestClient.Builder`，并通过 `builder.clone()` 复用生产 Jackson/消息转换器配置；
3. `HttpAgentServiceClient` 对空 `requestId` 生成 UUID，且 header 与 body 复用同一值。

未发现新引入的生产级 Bug，也未发现引入 V2/V3 阶段组件。

但**新增契约集成测试仍存在两个测试有效性缺口**，使本轮不能判定 PASS：

- 真实 HTTP 契约测试**只覆盖 200 成功路径**，上一轮明确建议的 401/503 错误映射跨进程测试未补齐；
- 测试**未实际断言 `non_null` 序列化行为**，也没有断言 `X-Request-Id` header 与 body `requestId` 的一致性；由于 Python 对 null `conversationId` 会自行生成 UUID，现有断言即使 `"conversationId": null` 被错误下发也会通过。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号 | 核心问题 |
| --- | --- | --- | --- | --- |
| 1 | 中 | `services/core-api/src/test/java/com/agentforge/core/agent/infrastructure/AgentServiceHttpContractIntegrationTest.java` | 41–50 | 跨进程契约测试仅有 1 个 200 成功用例，真实 HTTP 上的内部 token 401、下游不可用 503 及 Java 错误归一化路径仍无覆盖 |
| 2 | 低 | `services/core-api/src/test/java/com/agentforge/core/agent/infrastructure/AgentServiceHttpContractIntegrationTest.java` | 48–50 | 未断言请求体 null 字段省略及 `X-Request-Id` header/body 一致性，Boot `non_null` JSON 生产配置未被真实验证 |

---

## 三、逐项 Issue 展开

### Issue 1 —— 真实 HTTP 契约测试仅覆盖成功路径，错误映射仍无跨进程验证（中）

- **Severity**：中
- **File & Line**：`AgentServiceHttpContractIntegrationTest.java:41-50`
- **Evidence**：新增测试类中仅有唯一一个 `@Test`：

  ```java
  @Test
  void javaClientCallsRealPythonServiceOverHttp() {
      HttpAgentServiceClient client = new HttpAgentServiceClient(restClient);

      AgentChatResult result = client.chat(
              UUID.randomUUID(), UUID.randomUUID(), "  contract check  ", null, null);

      assertThat(result.answer()).isEqualTo("Agent service received: contract check");
      assertThat(result.conversationId()).isNotNull();
      assertThat(result.requestId()).isNotBlank();
  }
  ```

  交付内附带的复验报告也明确记录为 `1 run / 0 fail / 0 error / 0 skipped`。

- **Description**：
  上一轮 Issue 1 的核心诉求是“Java ↔ Python 真实 HTTP 边界错误会静默漏测”，其建议至少覆盖三类场景：200 字段映射、内部 token 错误 401、下游不可达 503。本轮只补了 200 路径。目前：
  - Python 返回 401（错误内部 token）时，`HttpAgentServiceClient` 如何将 `RestClientResponseException` 归一化为 `ServiceUnavailableException` 仍未在真实 HTTP 层验证；
  - 下游不可达/读超时（503）仅由 Java 侧 mock 覆盖，真实 `JdkClientHttpRequestFactory` + `HttpClient` 的超时触发路径未验证；
  - 无效输入 400 与 Java 映射也未做跨进程验证。

  该测试类通过 `AGENTFORGE_AGENT_CONTRACT_TEST=true` 显式开启，属于批次门禁的一部分；因此这些错误路径仍属于核心跨语言契约的测试缺口。

- **Suggested Fix**：
  在同一集成测试内增加至少两个真实 uvicorn 场景：

  1. 错误内部 token：

     ```java
     @Test
     void javaClientMapsInvalidInternalTokenToServiceUnavailable() {
         RestClient wrongTokenClient = restClient.mutate()
                 .defaultHeader("X-AgentForge-Internal-Token", "wrong-token")
                 .build();
         HttpAgentServiceClient client = new HttpAgentServiceClient(wrongTokenClient);

         assertThatThrownBy(() -> client.chat(
                 UUID.randomUUID(), UUID.randomUUID(), "msg", null, null))
             .isInstanceOf(ServiceUnavailableException.class);
     }
     ```

  2. 下游不可达：使用一个指向未监听端口（如 `127.0.0.1:1`，读/连接超时保持现有配置）的 `RestClient`，断言同样归一化为 `ServiceUnavailableException`，并记录耗时以确认超时配置真实生效。

---

### Issue 2 —— 测试未验证 `non_null` 序列化与 requestId 一致性，生产 JSON 契约仍可能回归漏测（低）

- **Severity**：低
- **File & Line**：`AgentServiceHttpContractIntegrationTest.java:48-50`
- **Evidence**：

  ```java
  assertThat(result.answer()).isEqualTo("Agent service received: contract check");
  assertThat(result.conversationId()).isNotNull();
  assertThat(result.requestId()).isNotBlank();
  ```

  测试调用 `client.chat(..., null, null)` 传入了 null `conversationId` 与 null `requestId`，但只对响应做字段非空断言，未捕获或检查出站请求体。

- **Description**：
  变更说明与 `agent-chat.md` 均声称契约测试“取得 Boot 自动配置的 `RestClient.Builder`，覆盖生产 JSON 序列化配置”。但 Python 端本身接受 `conversationId=null` 并生成 UUID，因此即使 Java 错误地把 `"conversationId": null` 发下去，`result.conversationId()` 依然非空，当前测试**无法区分** Boot `non_null` 配置是否真正生效。

  同理，测试传入了 null `requestId`，`HttpAgentServiceClient` 会生成 UUID 并填入 header/body，但测试只断言响应 `requestId` 非空，未断言 `X-Request-Id` header 与 body `requestId` 是否为同一值。若未来某次重构只修 header 不修 body（或反之），Python 可能因 body 缺失 `requestId` 自行生成新值，当前测试仍会通过，契约漂移无法被发现。

- **Suggested Fix**：
  建议在上行真实 HTTP 测试之外，增加一个基于 Boot `RestClient.Builder` 的 `MockRestServiceServer` 请求体契约断言，或者用受限的本机 echo 服务验证出站 JSON：

  ```java
  MockRestServiceServer server = MockRestServiceServer
          .bindTo(restClient.mutate().baseUrl("http://test").build())
          .build();

  server.expect(requestTo("/internal/v1/chat"))
          .andExpect(header("X-Request-Id", matchesPattern("[0-9a-f-]{36}")))
          .andExpect(jsonPath("$.conversationId").doesNotExist())
          .andExpect(jsonPath("$.requestId").isNotEmpty())
          .andRespond(withSuccess(...));
  ```

  关键断言为：
  - `conversationId` 为 null 时请求体**不得出现**该字段（验证 `spring.jackson.default-property-inclusion=non_null` 进入生产链路）；
  - `X-Request-Id` header 与请求体 `requestId` 一致且均为生成的 UUID。

---

## 四、主开发（Codex）评估回填区

| Issue ID | 是否采纳 | 处理说明 | 修复后验证 |
| --- | --- | --- | --- |
| 1 | 采纳 | 在同一真实 uvicorn 契约类补充错误内部 token 和不可达端口场景，验证 `HttpAgentServiceClient` 统一抛出 `ServiceUnavailableException`。 | Pi 最终复验：契约类 4 项全部通过、0 跳过 |
| 2 | 采纳 | 使用 Boot 自动配置的 builder 绑定 `MockRestServiceServer`，精确解析出站 JSON，断言省略 null `conversationId`，且 requestId 为 UUID 并与 header 完全一致。 | Pi 最终复验：出站 JSON 与 requestId 精确断言通过 |

---

## 五、边界合规确认

- 本轮变更未引入 Neo4j/GraphRAG、Langfuse 完整 Trace、LiteLLM、MCP、checkpoint 持久化等 V2/V3 组件；`graph.py` 仅保持无状态 `StateGraph`。
- HTTP/1.1 显式固定、Boot `RestClient.Builder` 注入与 `requestId` 旁路兜底均未改变系统边界或公共契约。
- 上述两项发现仅为测试有效性缺口，修复建议限于补测试断言与错误路径跨进程覆盖，不涉及生产逻辑边界调整。
