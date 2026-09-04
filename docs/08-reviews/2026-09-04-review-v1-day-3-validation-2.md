验证完成。以下是本次 Day 3 Agent Service 集中验证的完整报告。

---

`VALIDATION_RESULT: NEEDS_FIX`

## 结论摘要

- Python agent-service 全部通过（Python 3.14.3、`pip install -e ".[test]"`、5 个测试全绿）。
- Java core-api 的 `mvnw verify` 在 **testCompile 阶段编译失败**（2 个错误，同一根因），**0 个 Java 测试被执行**，阻断后续验证。
- 静态核验的契约项（JSON 字段、内部 token header、request ID、400/401/403/503、授权先于下游调用、V2/V3 未引入）全部符合。
- 密钥扫描未发现真实密钥；测试占位值均未误报。
- 环境限制：Docker 未运行，Testcontainers 集成测试将被跳过（属环境限制，非代码缺陷）。

## 命令执行记录

| # | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 1 | `services/agent-service: python --version` | 0 | Python 3.14.3（满足 3.12–3.14） |
| 2 | `python -m venv .venv` | 0 | 创建成功 |
| 3 | `.\.venv\Scripts\python.exe -m pip install -e ".[test]"` | 0 | 成功，含 fastapi 0.141.1 / langgraph 1.2.11 / pydantic 2.13.5 |
| 4 | `.\.venv\Scripts\python.exe -m pytest -q` | 0 | **5 passed**, 2 warnings, 3.02s；0 skipped / 0 failed |
| 5 | `core-api: .\mvnw.cmd verify`（`AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`） | **1** | **BUILD FAILURE**（testCompile，2 个编译错误）；0 tests |
| 6 | `git diff --check` | 0 | 无空白/冲突错误 |
| 7 | 密钥/私钥/JWT/连接串扫描 | — | 仅占位值与 localhost 本地默认值，无真实密钥 |
| 8 | 清理 | 0 | 全部成功（见下） |

Maven 具体错误（testCompile）：
```
AgentChatApiTest.java:[26,36] com.agentforge.core.security.SecurityProblemWriter
   在 com.agentforge.core.security 中不是公共的；无法从外部包对其进行访问
AgentChatApiTest.java:[31,39] （同上）
```

## 问题清单（按严重度排序）

1. **【阻断】core-api 测试无法编译，`mvnw verify` 失败**
   - 位置：`services/core-api/src/test/java/com/agentforge/core/agent/api/AgentChatApiTest.java` 第 26、31 行。
   - 根因：`SecurityProblemWriter` 声明为包私有（`class SecurityProblemWriter`，位于 `com.agentforge.core.security`），而测试位于 `com.agentforge.core.agent.api`，直接 `import` 并在 `@Import` 中引用，触发跨包访问编译错误。
   - 影响：整个 core-api 的 `verify` 在测试编译阶段即失败，13 个测试文件无一执行，Day 3 交付无法完成验证。
   - 建议：将 `SecurityProblemWriter` 改为 `public class SecurityProblemWriter`（构造器可保持包私有，`@Import` 仍可实例化）；或在测试侧改用 `@Import(SecurityConfiguration.class)` 并让其依赖的 `SecurityProblemWriter` 通过公开可访问的方式注册。改由 Codex 处理。

2. **【低】测试缺口：agent-service 未覆盖“错误 token”的 401 分支**
   - `tests/test_api.py` 仅覆盖 `test_chat_rejects_missing_internal_token`（缺 token），未覆盖携带**错误** token 的 401 场景。
   - 实现本身正确（`hmac.compare_digest` 会对错误 token 返回 401），仅缺一个断言用例。
   - 建议：补一条 `headers={"X-AgentForge-Internal-Token": "wrong-token"}` 期望 401 的用例（非阻断）。

## 契约静态核验（Prompt 第 5 项，全部只读核验）

- **Java/Python JSON 字段**：PASS。Java `InternalChatRequest(projectId,userId,message,conversationId,requestId)` 与 Python `ChatRequest`（`to_camel` 别名）均为 camelCase 一一对应；响应 `AgentChatResult` ↔ `ChatResponse` 亦匹配。
- **内部 token header**：PASS。Java `RestClient` 默认头 `X-AgentForge-Internal-Token` ↔ Python `Header(alias="X-AgentForge-Internal-Token")`，并用 `hmac.compare_digest` 常量时间比较。
- **request ID**：PASS。`RequestIdFilter` 校验/生成 requestId → 控制器透传 → 客户端 `X-Request-Id` → Python 回显 → 响应返回；异常处理器在 ProblemDetail 中注入 `requestId`。
- **400/401/403/503**：PASS。400（`@NotBlank`/解析错误）、401（`UnauthorizedException` + security entry point + Python 内部 token）、403（`ForbiddenException` + `ProjectService.requireOwnerOrAdmin`）、503（新增 `ServiceUnavailableException` handler + `HttpAgentServiceClient` 映射）。
- **项目授权先于下游调用**：PASS。`AgentChatService.chat` 先 `projectAccess.requireAccess(...)` 再 `agentServiceClient.chat(...)`，且有 `AgentChatServiceTest` 的 `InOrder` 校验。
- **禁止的 V2/V3 组件未引入**：PASS。代码中无 Neo4j/GraphRAG、Langfuse 全链路、LiteLLM、MCP、checkpoint 持久化；`graph.py` 仅无状态 `StateGraph`，langgraph 属 Day 3 允许范围。

## 密钥扫描（Prompt 第 6 项）

- 未发现真实私钥、`-----BEGIN ... PRIVATE KEY-----`、AKIA、ghp_、sk-、Bearer/JWT 明文、生产数据库连接串。
- 命中的仅：`.env.example` 的 `REPLACE_WITH_*` 占位、`application.yml` 的 `jdbc:postgresql://localhost:5432/agentforge` + `agentforge_local_only` 本地默认值、测试用 base64 JWT 占位 `MDEyMzQ1...`。均非真实密钥，未误报。

## 清理结果（Prompt 第 7 项）

- `services/agent-service/.venv`：已删除 ✅
- `.pytest_cache`：已删除 ✅
- `src/agentforge_agent/__pycache__`、`tests/__pycache__`：已删除 ✅
- `*.egg-info`：本次未生成（hatchling editable 安装不产生 egg-info），无需删除 ✅
- `%TEMP%\agentforge-review-test-*`：无匹配项 ✅
- Maven `target/`：保留（已被 `.gitignore` 忽略）
- 清理后 `git status` 复核：无本次测试遗留产物，Git 状态与验证前一致 ✅

## 环境阻断说明

- Docker 守护进程未运行（`npipe://./pipe/dockerDesktopLinuxEngine` 不可达），因此 `PersistenceIntegrationTest`（`@Testcontainers(disabledWithoutDocker=true)`）即使编译问题修复后也会被自动跳过，无法真实执行 Testcontainers/PostgreSQL/Flyway 集成链路。此为环境限制，如实记录，非代码缺陷。

**建议下一步**：由 Codex 修复问题 1（`SecurityProblemWriter` 可见性），可选补问题 2 的测试用例；随后可重新执行 `mvnw verify`（在 Docker 可用时）完成全量 Java 验证。
