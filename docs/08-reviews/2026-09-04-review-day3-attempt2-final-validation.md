验证执行完毕。以下为一次性完整报告。

---

`VALIDATION_RESULT: NEEDS_FIX`

## 一、结论

功能门禁（Docker、Python 版本、pytest、真实 uvicorn 契约、Maven verify、PostgreSQL/Testcontainers、进程与容器清理）**全部通过**，生产代码契约核验**无缺陷**，未发现真实密钥。但 `docs/05-development/local-development.md` 教程存在与当前实现不一致的可确认问题（见第三节），故本轮判定 `NEEDS_FIX`。

## 二、命令执行记录

| # | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 1 | `docker info` | 0 | Docker daemon 可用（Server 29.5.3，overlayfs，Docker Desktop） |
| 2 | `python --version` | 0 | Python 3.14.3（满足 3.12–3.14） |
| 3 | `java -version` | 0 | openjdk 21.0.12.1（满足 21） |
| 4 | `python -m venv .venv` | 0 | 创建成功 |
| 5 | `.\.venv\Scripts\python.exe -m pip install -e ".[test]"` | 0 | 安装成功 |
| 6 | `.\.venv\Scripts\python.exe -m pytest -q` | 0 | **6 passed, 0 skipped**（2 条弃用警告） |
| 7 | 启动 uvicorn（`AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`，`127.0.0.1:18000`，隐藏，PID 25896）；轮询 `/health` | — | 数秒内返回 `{"status":"UP","service":"agentforge-agent-service"}` |
| 8 | `.\mvnw.cmd verify`（含 `AGENTFORGE_AGENT_SERVICE_URL=http://127.0.0.1:18000`、`AGENTFORGE_AGENT_CONTRACT_TEST=true`、`AGENTFORGE_AGENT_INTERNAL_TOKEN=test-only-internal-token`） | 0 | **Tests run: 54, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS |
| 9 | 按 PID 25896 停止 uvicorn | — | 已停止，`PORT_18000_FREE` |
| 10 | `docker ps -a`（查 postgres:17-alpine / ryuk / 运行中容器） | 0 | 无残留容器，Testcontainers 已释放 |
| 11 | `git diff --check` | 0 | 无空白错误 |

关键测试门禁核验（来自 surefire 报告）：

- `AgentServiceHttpContractIntegrationTest`：**Tests run: 4, Failures: 0, Errors: 0, Skipped: 0** — 覆盖真实 200、真实错误内部 token（401→503）、不可达下游（127.0.0.1:1→503）、出站 JSON/requestId 精确断言（null `conversationId` 省略 + header/body requestId 一致），4 项全部实际执行、零跳过。
- `PersistenceIntegrationTest`：**Tests run: 2, Failures: 0, Errors: 0, Skipped: 0** — 两项 PostgreSQL/Testcontainers 测试均执行、零跳过。

## 三、可确认问题（按严重度排序）

**1. 中 — `docs/05-development/local-development.md` §12 SQL 表名与实现不一致**
教程第 12 节的四条 psql 分别查询 `users`、`projects`、`wiki_pages`、`tasks`，但 Flyway 迁移（V1/V2）与 JPA `@Table` 注解定义的真实表名为 `app_user`、`project`、`wiki_page`、`task_item`。用户照抄会得到 `relation "users" does not exist` 等错误。列名（`id,email,role`、`id,name,owner_id`、`title,version`、`title,status,priority,version`）均正确，仅表名需修正。

**2. 低 — 教程声明“适用系统：Windows PowerShell”，但 §3 命令仅兼容 PowerShell 7+ / .NET 5+**
`[Convert]::ToHexString(...)` 与 `[Security.Cryptography.RandomNumberGenerator]::GetBytes(32)` 均为 .NET 5+ API。已在 Windows PowerShell 5.1（5.1.19041.6456）实测两条命令均抛 “does not contain a method named …”。若目标确为内置 Windows PowerShell 5.1，需换成 5.1 兼容写法（如 `-join ((1..32 | % {'{0:x2}' -f (Get-Random -Max 256)}))`）；若目标为 pwsh 7，应将“适用系统”改为 PowerShell 7+。

**3. 低 — 工作区残留一个“笔记当目录名”的空目录树**
`services/core-api/src/main/javaCodex Java source directory for Day 2 auth\` 内含 7 层空目录、0 个文件，目录名明显是把说明文字误当文件夹名创建。Git 不跟踪空目录，不影响构建，但属工作区卫生问题，建议删除。

## 四、只读核验（均通过，无缺陷）

- **JSON 字段**：Python `to_camel`（`projectId/userId/message/conversationId/requestId` 与响应 `conversationId/answer/requestId`）与 Java `InternalChatRequest`/`AgentChatResult` 字段一一对应。
- **内部 token header**：两侧均为 `X-AgentForge-Internal-Token`，Python 用 `hmac.compare_digest`。
- **request ID**：`RequestIdFilter` 统一注入/回写 `X-Request-Id`，Java 客户端 header 与 body `requestId` 同值，空值兜底生成 UUID。
- **400/401/403/503**：`ApiExceptionHandler` 覆盖 400（校验/解析）、401（`UnauthorizedException`）、403（`ForbiddenException`）、503（`ServiceUnavailableException`）；`SecurityProblemWriter` 覆盖认证入口 401 与访问拒绝 403，均带 `requestId`。
- **项目授权先于下游调用**：`AgentChatService.chat` 先 `projectAccess.requireAccess(...)` 再 `agentServiceClient.chat(...)`。
- **禁止 V2/V3 组件**：`grep` 全仓库扫描 `/api/v2`、`/api/v3`、neo4j/langfuse/litellm/graphrag/checkpoint/mcp 均无匹配，`graph.py` 保持无状态 `StateGraph`。

## 五、密钥扫描

扫描本次差异与新增审查报告，仅有测试占位值：`test-only-internal-token`、`invalid-test-token`、`wrong-internal-token`、`demo-password-123`、`integration-password`、本地开发默认口令 `agentforge_local_only`、测试 JWT `MDEy...=`（解码为 32 字节占位串）。**未发现真实密钥、私钥、Bearer/JWT、生产连接串**，占位值未误报。

## 六、清理结果

- 删除 `services/agent-service/.venv` ✓
- 删除 `src/agentforge_agent/__pycache__`、`tests/__pycache__` ✓
- 删除 `.pytest_cache` ✓
- `*.egg-info`：本次未产生（editable 安装产物在 .venv 内，已随 .venv 删除）✓
- 删除 `%TEMP%\agentforge-review-*`（含 uvicorn 日志/PID、mvn 日志）与 `agentforge-review-test-*` ✓
- 未执行教程中 `down -v` 可选删卷命令；未修改源码/文档/配置/Git 状态（`git status` 与执行前一致）；Maven `target` 按约定保留且被 Git 忽略。

**建议 Codex 修复**：修正 §12 四张表名；明确并统一 §3 的 PowerShell 版本目标；删除误创建的空目录树。修复后无需改动生产代码即可复验转 `PASS`。
