## 验证报告

VALIDATION_RESULT: NEEDS_FIX

### 执行命令与结果记录

| # | 命令 | 退出码 | 结果 / 测试数 / 跳过 / 耗时 |
|---|---|---|---|
| 1 | `python --version`（services/agent-service） | 0 | `Python 3.14.3` —— **不满足 3.12–3.13**（仅安装 3.14.3 与 3.11.5，`py -0p` 确认无 3.12/3.13） |
| 2 | `python -m venv .venv` | 0 | 成功创建 `.venv`（3.14.3） |
| 3 | `.\.venv\Scripts\python.exe -m pip install -e ".[test]"` | 1 | 失败：`Package 'agentforge-agent-service' requires a different Python: 3.14.3 not in '<3.14,>=3.12'` |
| 4 | `.\.venv\Scripts\python.exe -m pytest -q` | 未执行 | **阻断**：依赖安装失败，0 测试运行（test_api.py 共 5 项测试） |
| 5 | `.\mvnw.cmd verify`（`AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`） | 1 | **编译失败**，0 测试运行，耗时 3.215s（详见问题 1） |
| 6 | `git diff --check` | 0 | 通过，无空白/冲突标记问题 |
| 7 | 密钥扫描（见下） | — | 通过，无真实密钥（见密钥扫描小节） |
| 8 | 清理（见下） | 0 | 完成 |

### 可确认问题（按严重度排序）

1. **【阻断】Core API 无法编译** — `services/core-api/src/main/java/com/agentforge/core/agent/infrastructure/AgentServiceConfiguration.java:17` 调用 `requestFactory.setConnectTimeout(properties.connectTimeout())`，但 `JdkClientHttpRequestFactory`（Spring Web 6.2.19）只提供 `setReadTimeout(int/Duration)`，**不存在 `setConnectTimeout` 方法**。`mvnw verify` 因此失败，全部 Java 测试（agent 4 项 + PersistenceIntegrationTest 2 项）未执行。已用 `javap` 反编译 spring-web-6.2.19.jar 确认方法签名。
   - 建议：改用 `SimpleClientHttpRequestFactory`（提供 `setConnectTimeout(Duration)` 与 `setReadTimeout(Duration)` 两个重载），或改用 `java.net.http.HttpClient.newBuilder().connectTimeout(...).build()` 后传入 `new JdkClientHttpRequestFactory(httpClient)`。

2. **【阻断/环境】Python 版本不满足 3.12–3.13** — 机器默认 `python` 为 3.14.3（另装 3.11.5），无 3.12/3.13。`pyproject.toml` 的 `requires-python = ">=3.12,<3.14"` 正确，但当前环境 3.14.3 超出上限，导致 `pip install -e ".[test]"` 报错、pytest 无法执行。
   - 建议：安装 Python 3.12 或 3.13 后重跑步骤 1–3；或确认 `python --version` 指向 3.12/3.13。

### 只读契约核验（静态检查）

- **Java/Python JSON 字段**：PASS。请求 `projectId/userId/message/conversationId/requestId`、响应 `conversationId/answer/requestId` 两侧一致（Python 用 `to_camel`，Java 用 camelCase 记录）。
- **内部 token header**：PASS。Java 通过 `defaultHeader("X-AgentForge-Internal-Token", ...)` 注入，Python 用 `Header(alias="X-AgentForge-Internal-Token")` + `hmac.compare_digest` 校验。
- **request ID**：PASS。`RequestIdFilter` 校验/生成并透传 `X-Request-Id`，下游 `HttpAgentServiceClient` 转发、响应与错误体回写 `requestId`。
- **400/401/403/503**：PASS（代码路径）。400 校验、401 未认证、403 无项目权限、404 项目不存在、503 由 `ServiceUnavailableException` → `ApiExceptionHandler` 映射；`ProblemDetail` 含 `requestId`。
- **项目授权先于下游调用**：PASS。`AgentChatService` 先 `projectAccess.requireAccess(...)` 再 `agentServiceClient.chat(...)`，`AgentChatServiceTest` 用 `InOrder` 断言。
- **禁止的 V2/V3 组件**：PASS。仅文档提及 V2/V3 作为“后续/延期”，代码无 checkpoint、RAG、Tool Calling、LiteLLM、Langfuse、MCP 等实现。

### 密钥扫描

对全部改动文件（含未跟踪文件）扫描真实密钥/私钥/Bearer/JWT/生产连接串：**未发现真实凭据**。命中的仅两类占位/误报：`MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=`（Base64 解码为 `0123456789abcdef0123456789abcdef`，测试用 JWT 占位）、`test-only-internal-token` 与 `REPLACE_WITH_*` 占位符、Git 提交哈希与文档 URL——均按规则**不**视为真实密钥。

### 清理结果

- 删除 `services/agent-service/.venv`（含其内 pip `__pycache__`）：已删除 ✅
- Python cache / `.pytest_cache` / `*.egg-info`：本次未产生（安装失败、pytest 未运行），已核查无残留 ✅
- `%TEMP%\agentforge-review-test-*`：本次未创建 ✅
- 本次自建临时目录 `%TEMP%\springweb-inspect`、`%TEMP%\springweb-inspect2`（javap 反编译用）：已删除 ✅
- Maven `target/`：保留（被 Git 忽略）✅
- `git status --short` 与执行前完全一致，未改动源码/文档/配置/Git 状态 ✅

### 结论

交付内容本身存在 1 处确定性编译错误（`setConnectTimeout`），且本机 Python 环境不满足 3.12–3.13，导致 Python 侧测试与 Java 侧测试均无法执行。建议 Codex 修复 `AgentServiceConfiguration` 的请求工厂后，在具备 Python 3.12/3.13 的环境重新执行完整验证。
