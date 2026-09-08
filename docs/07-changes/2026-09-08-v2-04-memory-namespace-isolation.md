# V2-04 Memory Namespace 隔离

- 日期：2026-09-08
- 状态：Implemented
- 阶段：V2-04
- 交付目标：`origin/codex/v2-04-memory-namespace-isolation`
- 基线：`00c6438eb95c2874686023dc1231aeefacfd7ef9`

## 背景

V2-03 的进程内 ConversationMemory 以 conversationId 为主键，并把 projectId/userId 作为 session 绑定校验。该实现能拒绝明显的跨作用域复用，但 Memory、Context 与 Retrieval 仍没有共享一个强类型 Namespace；thread、project、user 的组合边界也缺少完整负向矩阵。V2-04 需要把所有会话读写显式归属到 `tenant → workspace → project → user → thread`，同时保持现有 Java 权限边界和 HTTP 契约。

## 目标

- 用不可变 `MemoryNamespace` 表达 deployment tenant、deployment workspace、project、user 与 thread。
- ConversationMemory 的读取、提交和 LRU key 全部使用完整 Namespace。
- load 返回不可省略的 lease；commit 必须匹配 Namespace 与 generation。
- 同步、流式、ContextBundle、Prompt 与 Retrieval 路由使用同一个 Namespace。
- 通过 Project/User/Thread 交叉负向测试证明历史不会跨边界泄露。

## 非目标

- 不创建 tenant/workspace/membership 业务表，不把部署级作用域宣传为完整 SaaS 多租户。
- 不实现跨进程共享、重启恢复、长期记忆或 LangGraph checkpoint。
- 不实现 V2-05 RBAC/Risk Engine，也不改变 Tool、Approval、Audit 或业务写入规则。
- 不改变 Web/Core 公共 API 或 Java→Python 内部 HTTP schema。

## 计划文档与实现

- 更新 `docs/03-features/context-management.md`、`docs/03-features/README.md` 与 `docs/04-api/agent-service.md`，说明 Namespace、可信来源和限制。
- 新增 `docs/02-architecture/decisions/ADR-0018-process-local-memory-namespace.md` 并更新 ADR 索引，记录不提前建立 workspace 数据库的决定。
- 更新 `docs/05-development/testing-strategy.md` 与 `docs/05-development/local-development.md`。
- 在 Agent Service 的 Context、Graph、API 和配置边界实现 Namespace；同步更新 Compose、生产环境生成器及其 Shell 合约。RAG 继续以 Namespace 中的 projectId 查询既有 `rag_chunk`。
- 使用 Context/store、FastAPI、模型边界与真实 pgvector 公共 seam 做 TDD 和负向隔离验证。

## 风险与验证计划

本节点属于 L3 隔离和 Agent 状态变更。主要风险是相同 thread ID 跨 Project/User 复用、同步与流式构造不同作用域、LRU 淘汰后陈旧提交，以及把部署级 tenant/workspace 误写为业务权限。实现前运行门禁规划器；实现后执行 Agent 全量 pytest、真实 pgvector 隔离测试、Java→Python JSON/NDJSON 契约 smoke、配置解析、diff check、清理与敏感扫描，并在 Codex 审核后执行 DeepSeek Pi V4-pro 一次性只读 Milestone Review。验证命令、版本、退出码、数量、warning/skip 和清理结果将在完成后回填。

## 实际实现

- `context.py` 新增不可变 `MemoryNamespace` 与 `ConversationLease`。ConversationMemory 的 LRU key 改为完整 Namespace；load 生成 lease，commit 只能用该 lease，并同时校验 Namespace 与 generation。
- `ContextBundle` 的 Conversation/Project Context 共享同一个 Namespace；旧 project/user/conversation 访问改为从 Namespace 派生，避免多个平行字段发生漂移。
- `graph.py` 只接受已构造的 Namespace；prepare、retrieval、plan 与 responder 沿同一 bundle 传递。Retriever 继续只得到 Namespace 内的 project/user，RAG schema 与 SQL 不变。
- `api.py` 在内部 token 验证后的服务端边界，用配置 tenant/workspace、Java 请求 project/user 和实际 conversationId 构造 Namespace。同步与流式都以 load 返回的 lease 完成提交。
- `config.py`、两个 env example、两个 Compose 文件和生产环境生成器增加 deployment tenant/workspace；Shell 合约验证生成值。
- 新增 `test_memory_namespace.py`，并迁移 Context、API、LLM、Observability 测试到强类型 seam。Store 对五层 Namespace 分别做负向测试，HTTP 对 Project/User/Thread 做负向隔离；客户端伪造 Namespace 字段不会覆盖服务端配置。
- 没有修改 Web、Java 源码、HTTP schema、数据库迁移、依赖或 V2-05 权限能力。

## 验证结果

- Git preflight：首次在受限沙箱执行 `git fetch origin codex/v2-03-conversation-summary-budget; git switch -c codex/v2-04-memory-namespace-isolation 00c6438...` 因 `.git/FETCH_HEAD`/`index.lock` 权限退出 1；受控权限原命令退出 0，从已远端核验的 V2-03 `00c6438eb95c2874686023dc1231aeefacfd7ef9` 创建分支。用户原有未暂存 Markdown 和未跟踪 DOCX 保持范围外。
- 门禁规划：仓库根执行 `./scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Milestone -Paths <19 个计划路径> -Json`，退出 0；结果 L3、AgentService/Deployment/Docs/Governance、Milestone，fingerprint `0e41e92ae54142c3c50b7eb3a58c6bda698bde9d1d4516a8d831f85739bb1130`。最终以 INDEX 重算。
- Namespace TDD：在 `services/agent-service` 执行 `.\.venv\Scripts\python.exe -m pytest tests/test_memory_namespace.py -q`，首次因 `MemoryNamespace` 不存在 collection error、退出 1；最小实现后 9 passed、退出 0，覆盖五层负向隔离、空服务端 scope、强制 lease、LRU stale lease 与 Context 一致性。
- 相关回归：同目录执行 `.\.venv\Scripts\python.exe -m pytest tests/test_memory_namespace.py tests/test_context.py tests/test_api.py tests/test_llm.py -q`，迁移前 24 failed/25 passed、退出 1，失败均为旧 seam 或旧“跨 project 返回 422”语义；统一迁移后 61 passed、退出 0。随后 `tests/test_api.py -q` 为 25 passed、退出 0，覆盖 HTTP Project/User/Thread 负向矩阵与服务端配置优先。
- Agent 全量：首次 `.\.venv\Scripts\python.exe -m pytest -q --cache-clear` 为 75 passed/3 failed、退出 1；两项失败是 Observability 测试仍传旧 graph 字段，第三项是沙箱拒绝 Docker named pipe。迁移 Observability 后局部 12 passed；受控权限最终在同目录重跑全量退出 0，Python `3.14.3`、pytest `8.4.2`，80 passed、0 failed/skipped，真实 pgvector Testcontainers 通过。保留 4 warnings：Starlette AnyIO alias、两处既有 422 常量弃用、既有 `.pytest_cache` ACL 写入 warning。
- Java：在 `services/core-api` 受控权限执行 `.\mvnw.cmd clean verify`，退出 0，Maven `3.9.11`、Java `21.0.12.1`，83 tests、0 failures/errors、7 条条件式跨进程契约 skipped；真实 PostgreSQL 17.11、5 条 Flyway migration 与 JPA validate 通过。Java 源码和 schema 未改。
- Java→Python：首次隐藏启动本项目 uvicorn 后的专测未产生新报告，检查仍为全量 Java 阶段的 7 skipped，因此明确不计为通过。使用显式 `AGENTFORGE_AGENT_CONTRACT_TEST=true`、测试 token、RAG/LLM disabled 重跑，7 tests、0 failures/errors/skipped；最终源码再次在端口 18006 执行 `.\mvnw.cmd '-Dtest=AgentServiceHttpContractIntegrationTest' test`，退出 0、7/7、BUILD SUCCESS，精确 PID、端口与专用临时日志已清理。
- 配置与脚本：仓库根执行两个 `docker compose ... config --quiet`，首次虽因沙箱读取 Docker config warning 但退出 0；受控权限最终重跑本地/生产配置均退出 0且无 warning。`wsl bash -n scripts/deploy/generate-production-env.sh` 首次因 WSL 沙箱权限退出 1；受控权限语法检查通过。首次把 `REPO_UNDER_TEST` 仅设置为 PowerShell 环境导致 WSL 脚本仍使用 `/repo` 并退出 1；改为 `wsl env REPO_UNDER_TEST=/mnt/c/Users/86134/Documents/ChatGPT/AgentForge bash scripts/validation/tls-public-host-contract.sh` 后退出 0，IPv4/IPv6/domain、renewal、bootstrap、validation、generation、rejection 全通过。
- 静态与治理：Agent Service 执行 `.\.venv\Scripts\python.exe -m compileall -q src tests`，退出 0；仓库根执行 `.\scripts\validation\test-plan-change-gates.ps1`，退出 0、15 checks。首次 staged `git diff --cached --check` 发现新增 ADR 尾部多余空行并退出 2，修正后原命令退出 0。
- 清理：仅删除本轮在 Agent `src/tests` 生成的 2 个 `__pycache__` 和 Java `services/core-api/target`；Docker Testcontainers label 查询为 0，契约端口 18004/18005/18006 listener 为 0。既有 `.pytest_cache` 因 ACL 保持不动。
- 敏感扫描：仅本节点文件暂存后，对 `git diff --cached` 执行私钥、AWS/GitHub/provider key 及非占位 secret/token/password 正则，0 命中；同一 staged diff 通过本机已有 `zricethezav/gitleaks:v8.30.1` 的 `stdin --redact --no-banner` 扫描约 79.01 KB，退出 0、`no leaks found`。用户未暂存 Markdown 和 DOCX 未进入扫描输入、Pi 输入或暂存区。
- Pi 与最终 INDEX planner 将在提交前继续回填。

## Pi Review 与 Codex 判断

- DeepSeek Pi V4-pro Attempt 1 对已扫描 INDEX 做 Milestone Review，报告 `docs/08-reviews/2026-09-08-review-v2-04-memory-namespace-isolation-attempt-1.md`，结论 `PASS`、无必须修改项；Pi 未执行测试或修改文件。
- S1（建议，路线图状态过期）：属实。路线图是 Node 唯一来源，V2-03/V2-04 状态必须与实际交付一致；本轮更新当前状态并把下一候选收敛为尚未授权的 V2-05。
- S2（建议，无 memory 测试图构造 synthetic lease）：属实但不影响生产。生产 API 恒定注入 ConversationMemory 并先 load；无 memory 仅为直接 graph 测试 seam。本轮在 fallback 处明确注释该 lease 不受 store 支持、提交会安全失败，不扩大运行接口。
- S3（建议，直接传 None 会触发 AttributeError）：属实但生产 Settings 已拒绝 None。补充公共 `MemoryNamespace` seam 的 None 负向红灯测试，并把它归入既有清晰 `ValueError` 路径。
- 三项均为建议而非阻断；按 Pi 连接制度不触发 Attempt 2。S3 修改后重跑受影响 Namespace/Agent/跨进程测试并重新扫描。
- S3 TDD：`tests/test_memory_namespace.py::test_memory_namespace_rejects_none_server_scopes_with_clear_error -q` 修复前以 `AttributeError: NoneType has no attribute strip` 失败、退出 1；最小防御修复后 Namespace 文件 10 passed、退出 0。
- Pi 建议处理后 Agent 全量在 `services/agent-service` 执行 `.\.venv\Scripts\python.exe -m pytest -q --cache-clear`，退出 0，81 passed、0 failed/skipped、4 个同上 warning，真实 pgvector 通过；最终 Java→Python 契约在端口 18007 执行 7 tests、0 failures/errors/skipped、BUILD SUCCESS，临时 PID/日志/端口均清理。
- 最终清理再次仅删除 Agent `src/tests` 的 2 个 `__pycache__` 和 Java target；Testcontainers 与端口 18007 均无残留。路线图已同步 V2-03/V2-04 completed，并把 V2-05 标为尚未授权的下一候选。
- Close Gate 前对包含 Pi 报告的 INDEX 执行 `.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Milestone -Json`，退出 0；25 个范围文件为 L3、AgentService/Deployment/Docs/Governance/Unknown、Milestone，fingerprint `714afaacb6276daf0fc801bc7027b723719e196f4ea320aa40818ed2923190a5`。Unknown 仅为给既有 TLS/生产环境 Shell 合约增加两条 Namespace 生成断言，已由完整 `tls-public-host-contract.sh` 成功覆盖并完成人工影响审查。

## 风险与回滚

主要风险已由完整 Namespace 负向矩阵和 stale lease 回归覆盖。回滚只需回退本节点提交；没有数据库迁移或公共契约回滚。回滚、重启或多实例切换仍会丢失进程内历史，这是明确限制而非持久化保证。
