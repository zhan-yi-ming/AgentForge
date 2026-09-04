All verification and cleanup steps are complete. I have definitive, evidence-backed findings. Here is my full validation report.

---

VALIDATION_RESULT: NEEDS_FIX

**模型身份**：DeepSeek V4-pro（`deepseek/deepseek-v4-pro`），独立验证员（VALIDATION 模式，只读 + 仅执行 Prompt 列出的测试命令）。

## 一、总体结论

本轮门禁**未通过**：`AgentServiceHttpContractIntegrationTest` 真实执行（未跳过）但**报错失败**，导致 `mvnw.cmd verify` BUILD FAILURE。根因是一个**真实的生产级 Bug**（Java `HttpClient` 默认走 HTTP/2，对纯 HTTP 的 Python 服务发起 h2c 升级，请求体被置空），而非测试本身缺陷。`PersistenceIntegrationTest` 两项 PostgreSQL/Testcontainers 测试均真实执行、0 跳过、全部通过。

## 二、可确认问题清单（按严重度排序）

| # | 严重度 | 位置 | 问题 |
|---|--------|------|------|
| 1 | **高** | `services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/AgentServiceConfiguration.java`（`HttpClient.newBuilder()` 处） | `HttpClient` 未指定 `.version(...)`，JDK `HttpClient` 默认 HTTP/2。调用纯 HTTP（`http://127.0.0.1:18000` / 生产 `http://localhost:8000`）时触发 h2c 升级（`Upgrade: h2c` + `HTTP2-Settings` + `Connection: Upgrade, HTTP2-Settings` + `Transfer-encoding: chunked`），**请求体为空**。uvicorn（仅 HTTP/1.1）返回 `422 {"detail":[{"type":"missing","loc":["body"],"msg":"Field required"}]}`，被 `HttpAgentServiceClient` 包装成 `ServiceUnavailableException`。**既使契约测试报错，也使生产 Java→Python 链路不可用**。 |
| 2 | 低 | `services/core-api/src/test/java/com/agentforge/core/agent/infrastructure/AgentServiceHttpContractIntegrationTest.java` | 测试用静态 `RestClient.builder()` 传入 `agentServiceRestClient(...)`，而非 Spring Boot 自动配置的 `RestClient.Builder`，因此未复用生产 `spring.jackson.default-property-inclusion: non_null` 的 ObjectMapper。修复 #1 后，测试仍无法验证生产侧的 null 序列化配置（当前只验证了传输层，未完全验证 JSON 配置层）。 |

## 三、根因证据（问题 #1）

用临时 echo 服务器捕获 Java 客户端真实原始请求（该服务器仅诊断用，已清理）：

```
POST /internal/v1/chat
Connection: Upgrade, HTTP2-Settings
HTTP2-Settings: AAEAAEAAAAIAAAAAAAMAAAAAAAQBAAAAAAUAAEAAAAYABgAA
Transfer-encoding: chunked
Upgrade: h2c
User-Agent: Java-http-client/21.0.12.1
Content-Type: application/json
X-AgentForge-Internal-Token: test-only-internal-token
X-Request-Id: 7aadd054-121f-480c-8883-ca632cbba21c
BODY_STR=''
```

配套验证：直接对运行中的 uvicorn 发送空 body → 得到与测试完全一致的 `422`；发送合法 body → `200`。uvicorn stderr 同时出现 `WARNING: Unsupported upgrade request.` 与 `Invalid HTTP request received.`。

**修复建议（#1）**：在 `AgentServiceConfiguration.agentServiceRestClient` 显式指定 HTTP/1.1：

```java
HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(properties.connectTimeout())
        .build();
```

**修复建议（#2）**：契约测试改为注入 Spring Boot 自动配置的 `RestClient.Builder`（例如改用 `@SpringBootTest` 并注入 `RestClient.Builder`），或在静态 builder 上显式注册与生产一致的 `MappingJackson2HttpMessageConverter`（`setSerializationInclusion(NON_NULL)`）。

## 四、命令执行记录（退出码 / 测试数量 / 跳过 / 耗时）

| 命令 | 退出码 | 结果 |
|------|--------|------|
| `docker info`（仓库根） | 0 | 成功，Docker daemon 29.5.3 可用 |
| `python --version`（services/agent-service） | 0 | `Python 3.14.3`（满足 3.12–3.14） |
| 清理遗留 uvicorn（PID 30368/33360，上一轮未清理残留，锁死 `.venv`） | 0 | 已停止 |
| `python -m venv .venv` | 0 | 成功创建（首次因残留进程锁文件失败，清理后重试成功） |
| `.\.venv\Scripts\python.exe -m pip install -e ".[test]"` | 0 | 成功 |
| `.\.venv\Scripts\python.exe -m pytest -q` | 0 | **6 passed**，0 跳过，4.13s（2 个依赖弃用告警，非失败） |
| 启动 uvicorn（PID 33876 → 子进程 29268，`AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`，127.0.0.1:18000）+ 轮询 `/health` | 0 | `HTTP 200 {"status":"UP","service":"agentforge-agent-service"}` |
| `.\mvnw.cmd verify`（`AGENTFORGE_AGENT_SERVICE_URL`、`AGENTFORGE_AGENT_CONTRACT_TEST=true`、`AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`） | **1** | **BUILD FAILURE**。总计 **Tests run: 51, Failures: 0, Errors: 1, Skipped: 0**，36.4s |
| ↳ `AgentServiceHttpContractIntegrationTest` | — | 已执行、未跳过；**1 run / 1 error / 0 skipped**（422 → ServiceUnavailableException） |
| ↳ `PersistenceIntegrationTest` | — | **2 run / 0 fail / 0 error / 0 skipped**（PostgreSQL 17-alpine Testcontainers 两项全通过，11.18s） |
| `git diff --check` | 0 | 通过（无空白/冲突标记问题） |
| 密钥/私钥/Bearer/JWT/生产连接串扫描（工作树变更 + 未跟踪文件） | 0 | 无真实密钥；仅测试占位值（`test-only-internal-token`、base64 测试 JWT secret、`integration-password`） |

## 五、契约只读核验（Prompt 第 7 项）

- **JSON 字段**：Java `InternalChatRequest/AgentChatResult` 与 Python `ChatRequest/ChatResponse`（`to_camel`）逐字段一致（projectId/userId/message/conversationId/requestId 及响应 conversationId/answer/requestId）。✅
- **内部 token header**：两侧均 `X-AgentForge-Internal-Token`。✅
- **request ID**：`X-Request-Id` header + body `requestId`，`RequestIdFilter` 兜底生成；客户端空值兜底已修复。✅
- **400/401/403/503**：400=Java 边界 `@NotBlank`；401=JWT/内部 token；403=`requireAccess`；503=`ServiceUnavailableException`。✅
- **项目授权先于下游调用**：`AgentChatService.chat` 先 `projectAccess.requireAccess(...)` 再 `agentServiceClient.chat(...)`，有 `InOrder` 测试佐证。✅
- **V2/V3 组件**：源码未引入 Neo4j/GraphRAG、Langfuse、LiteLLM、MCP、checkpoint 持久化；`graph.py` 仅无状态 `StateGraph`。✅

## 六、清理结果（Prompt 第 7/9 项）

- 按精确 PID 停止本轮 uvicorn（29268、33876）→ 确认无 python 进程残留、`127.0.0.1:18000` 已释放。✅
- 停止诊断 echo 服务器（PID 19776）。✅
- Testcontainers 容器已释放：`docker ps -a` 仅剩 4 个与本项目无关的遗留 redis 容器，无 `postgres:17-alpine` / `testcontainers/ryuk` 残留。✅
- 删除 `services/agent-service/.venv`、`.pytest_cache`、全部 `__pycache__`、`*.egg-info`；删除 `%TEMP%\agentforge-review-test-*`（含遗留目录 `agentforge-review-test-day3`）。✅
- 复验 `git status` 与初始一致（仅原有 6 个修改 + 2 个未跟踪项），未改动源码/文档/配置/Git 状态；Maven `target/` 保留且被 Git 忽略。✅

## 七、阻断说明

无环境阻断；唯一失败项为上述**真实代码缺陷 #1**（HTTP/2 空 body），该缺陷使交付的门禁测试失败，必须由 Codex 修复后复验。
