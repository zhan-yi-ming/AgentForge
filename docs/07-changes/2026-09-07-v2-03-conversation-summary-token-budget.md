# V2-03 Conversation Summary + Token Budget

- 日期：2026-09-07
- 状态：Implemented
- 阶段：V2-03
- 交付目标：`origin/codex/v2-03-conversation-summary-budget`
- 基线：`69fb54df3e8a4fe4577009d32f599d4978c30502`

## 背景

V2-02 建立了 `ContextBundle`，但 Conversation Context 仍为空槽位，LLM Prompt 只包含当前消息和检索结果。相同 `conversationId` 的多轮请求不会带入近期对话，Prompt 也没有统一输入预算。V2-03 需要在不改变 Java/Python HTTP schema 的前提下，让多轮上下文可用且有硬预算。

## 目标

- 组合 Current Request、Recent Messages、Conversation Summary、Retrieved Context 与 Project Context。
- 使用配置化 Token Budget 限制实际发送给模型的完整输入 Prompt。
- 超预算时先缩减 Summary 和较旧历史，保护当前请求、近期消息与检索上下文的独立语义。
- 同步和流式请求共享同一会话读取、Prompt 组合和提交规则。
- 防止摘要反复总结自身、Tool proposal/result 混入摘要，以及跨 project/user 复用 conversationId。

## 非目标

- 不实现 V2-04 的 tenant/workspace/project/user/thread Memory Namespace。
- 不增加数据库、迁移、跨进程共享或重启恢复；这些属于后续持久化节点。
- 不改变 Core API、Agent Service 或 Web 的 HTTP 字段。
- 不引入新的 LLM provider、Node Runtime、Embedding 或长期记忆。
- 不公布没有真实对比数据支持的 Token 节省比例。

## 受影响文档

- `docs/03-features/context-management.md`：定义多轮会话、摘要生命周期、组合优先级和限制。
- `docs/04-api/agent-service.md`：说明既有 conversationId 在 V2-03 中的内部状态语义及新增配置。
- `docs/05-development/testing-strategy.md`：增加 V2-03 的风险匹配测试门槛。
- `docs/05-development/local-development.md`：记录本地与容器运行时的预算配置和重启语义。
- `docs/02-architecture/decisions/ADR-0017-process-local-bounded-conversation-context.md`：记录为何本节点选择进程内有界状态，而不提前引入持久化或完整 namespace。

## 设计决定

- Agent Service 维护线程安全、容量受限的进程内 conversation store。conversation 首次出现时绑定 project/user；后续作用域不一致时拒绝请求。
- load 快照携带内部 session generation；commit 必须匹配 conversationId、scope 与 generation，防止生成期间 LRU 淘汰/重绑后把回答提交到错误 session。
- store 只接收已完成的 user/assistant exchange。Tool Context、Tool proposal、检索正文和异常不写入会话历史。
- 每条历史副本复用总 Context Budget 作为存储上限，避免 session 数/轮数有界但单条异常输出仍放大内存；不截断当前 API 响应。
- Recent Messages 保留最近配置轮数；更旧的原始消息转换为带 role 的确定性摘要条目。摘要只由原始消息生成，不把已有 Summary 作为新的待总结输入。
- 摘要与 session 数量均有硬上限；达到 session 上限时按最久未使用淘汰。进程重启会清空状态。
- Prompt Composer 以 UTF-8 字节数作为跨 provider 的保守 Token 上界，测量最终 System + Human messages，并保证测量值不超过输入预算。裁剪顺序为 Summary → 较旧 Recent Messages → Retrieved Context；当前请求和 Project Context 最后才缩减。
- `AGENTFORGE_AGENT_CONTEXT_TOKEN_BUDGET`、`AGENTFORGE_AGENT_CONTEXT_RECENT_TURNS`、`AGENTFORGE_AGENT_CONTEXT_SUMMARY_TOKEN_BUDGET` 与 `AGENTFORGE_AGENT_CONTEXT_MAX_SESSIONS` 提供显式配置。
- Project Context 在内部保留 project/user/request 标识用于路由和作用域校验，但 Prompt 只暴露“已授权项目”与调用者角色，避免把无业务价值的内部 UUID 发送给模型 provider。

相关决定见 [ADR-0017](../02-architecture/decisions/ADR-0017-process-local-bounded-conversation-context.md)。

## 实现

- `context.py` 新增不可变 `ConversationMessage/ConversationContext`、线程安全 LRU `ConversationMemory` 与保守 `TokenCounter`。Recent 以完整 exchange 为单位保留，溢出原始消息进入受预算限制的 role 摘要。
- `graph.py` 在 prepare 节点按 conversationId 和 project/user scope 读取同一历史快照；同步与流式继续共用 prepare/retrieve/plan。
- `api.py` 提供基于 Settings 的单例 memory。同步回答成功后提交 exchange；流式聚合真实 delta，只在生成完整后提交，error/中断不提交。
- `llm.py` 新增 `PromptComposer`，组合 Current、Recent、Summary、Retrieved 与脱敏 Project Context；预算覆盖最终 System + Human messages。内部 UUID/requestId 不发送给模型，Tool Context 不进入 Prompt。
- `config.py`、两个 env example、两个 Compose 文件及生产 env 生成器增加四项预算/容量配置；Shell 合约断言生成结果。
- `test_context.py`、`test_llm.py`、`test_api.py` 从 store、模型 boundary 和 FastAPI seam 覆盖生命周期、预算、作用域、隐私、同步与流式成功/失败。
- 无 Java/Web 源码、HTTP schema、数据库迁移或依赖变化。

## 验证结果

- Git/Docker preflight：分支从已远程核验的 V2-02 `69fb54df3e8a4fe4577009d32f599d4978c30502` 创建；用户既有 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md` 未暂存改动和未跟踪 DOCX 保持在范围外。仓库根首次执行 `docker version --format '{{.Server.Version}}'` 因沙箱拒绝 Docker config/named pipe 退出 1；受控权限原命令退出 0，Docker Server `29.5.3`。
- 门禁规划：仓库根执行 `./scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths <19 个 V2-03 范围路径> -Json`，退出 0；结果 L3、AgentService/Deployment/Docs/Governance、Milestone，fingerprint `ebabee07c29b2964355a1e3ee3033fd08d45f688cc8d9fb204c9d5aee2131059`。提交前将以 INDEX 重算最终 fingerprint。
- Store TDD：在 `services/agent-service` 执行 `.\.venv\Scripts\python.exe -m pytest tests/test_context.py -q`，首次 collection 因 `ConversationMemory` 不存在退出 1；最小实现后 4 passed、退出 0。后续执行 `.\.venv\Scripts\python.exe -m pytest tests/test_context.py::test_context_manager_rejects_mismatched_conversation_snapshot -q`，先以 DID NOT RAISE 退出 1，增加 bundle conversationId 一致性校验后纳入相关测试通过。
- Prompt TDD：同目录执行 `.\.venv\Scripts\python.exe -m pytest tests/test_llm.py -q`，首次 collection 因 `PromptComposer` 不存在退出 1；实现后第一次为 1 failed/13 passed、退出 1，失败证明测试给出的 220 低于配置合法下限而无法容纳固定 metadata，修正为合法预算后 14 passed。加入内部 UUID 不得进入 provider Prompt 的断言后，单测 `test_prompt_composer_enforces_total_budget_and_protects_retrieval` 先以 UUID 泄漏退出 1；脱敏 Project Context 后 `test_llm.py` 16 passed、退出 0。
- API TDD：同目录执行 `.\.venv\Scripts\python.exe -m pytest tests/test_api.py -q`，首次 collection 因 `get_conversation_memory` 不存在退出 1；接线后 17 passed、退出 0。随后执行 `.\.venv\Scripts\python.exe -m pytest tests/test_context.py tests/test_api.py -q`，26 passed、退出 0，覆盖跨 project/user scope、LRU、摘要上限、stream complete 提交及 partial error 不提交。最终三文件相关回归为 44 passed、退出 0；三轮 HTTP 摘要集成用例单独为 1 passed、退出 0。
- Agent 全量：Python `3.14.3`、pytest `8.4.2`。Pi 修复后在 `services/agent-service` 最终执行 `.\.venv\Scripts\python.exe -m pytest -q --cache-clear`，退出 0，66 passed、0 failed/skipped；真实 pgvector Testcontainers 通过。保留 5 warnings：Starlette/AnyIO alias、三个既有 422 常量弃用、既有 `.pytest_cache` ACL 导致 cache 写入 warning。
- Java：Maven `3.9.11`、Java `21.0.12.1`。在 `services/core-api` 执行 `.\mvnw.cmd clean verify`，退出 0，83 tests、0 failures/errors、7 条条件式 Agent 合约 skipped；真实 PostgreSQL 17.11、5 条 Flyway migration 与 JPA 测试通过。Java 源码与契约未改。
- Java → Python：仓库根以测试 token、RAG/LLM disabled 隐藏启动本项目 uvicorn，再在 `services/core-api` 执行 `.\mvnw.cmd -Dtest=AgentServiceHttpContractIntegrationTest test`。Pi 修复后的最终源码重跑退出 0，7 tests、0 failures/errors/skipped，覆盖真实 JSON 与 NDJSON chat/stream；`finally` 确认精确 PID 停止、专用临时 stdout/stderr 目录删除。
- 配置与脚本：仓库根执行 `docker compose --env-file .env.example -f infra/compose.yaml config --quiet` 和 `docker compose --env-file .env.production.example -f infra/compose.prod.yaml config --quiet`，均退出 0；`wsl bash -n scripts/deploy/generate-production-env.sh` 退出 0。`./scripts/validation/test-plan-change-gates.ps1` 退出 0、15 checks；`./scripts/validation/v2-prep-demo-experience.ps1` 退出 0。首次直接运行 `wsl bash scripts/validation/tls-public-host-contract.sh` 因脚本默认 `/repo` 不存在退出 1；显式设置 `REPO_UNDER_TEST=/mnt/c/Users/86134/Documents/ChatGPT/AgentForge` 重跑退出 0，IPv4/IPv6/domain、renewal、bootstrap、validation、generation 和 rejection 全部通过。
- 静态：在 Agent Service 执行 `.\.venv\Scripts\python.exe -m compileall -q src tests`，退出 0；仓库根对 V2-03 范围执行 `git diff --check -- <paths>`，退出 0。最终 INDEX diff check、敏感正则和 Gitleaks 在 Pi 前执行并回填。
- 清理：真实合约的 uvicorn PID 与专用日志均删除；Testcontainers label 查询无残留；`services/core-api/target` 删除。Python bytecode 清理命令递归移除了 Agent Service 源码/测试及 `.venv` 下的 `__pycache__`（均为可再生缓存），遍历既有 `.pytest_cache` 时仍出现 ACL warning；该任务开始前已存在且 Git 忽略的目录保持未删除。
- 影响域裁剪：Web 未修改、未消费内部 ContextBundle 或新增字段，因此未运行 Vitest/Vite build；HTTP schema 未改，已由 Java clean verify 与真实双服务 7 条契约覆盖。没有用旧 V2-02 报告替代本轮证据。
- Pi Attempt 1：模型预检 `deepseek/deepseek-v4-pro` 通过。首次 review 命令在外发前因变更记录文件名含 `token-budget` 被本地敏感文件名规则拒绝，移除重复 ContextFiles 项后同一次 Attempt 1 成功运行。报告 `docs/08-reviews/2026-09-08-review-v2-03-conversation-summary-budget-attempt-1.md` 结论 `NEEDS_FIX`：1 个 High 阻断项和 4 个 Low 建议。Codex 复现 load 后 LRU 淘汰/跨 scope 重绑导致 commit 未捕获 ValueError；四条指定测试修复前 4 failed、退出 1，generation 提交校验及同步 422/流式 error 修复后 4 passed。另为单条历史副本加存储上限；相关三文件 49 passed、退出 0。因存在真实阻断项，按规则在重新扫描后执行 Attempt 2。
- Pi Attempt 2：Pi 修复后 INDEX 的 `git diff --cached --check` 与敏感正则均退出 0；Gitleaks v8.30.1 扫描约 92.17 KB，`no leaks found`。DeepSeek V4-pro Milestone Review 报告 `docs/08-reviews/2026-09-08-review-v2-03-conversation-summary-budget-attempt-2.md` 返回 `PASS`，确认 High 阻断项和四项建议均已修复，未发现新阻断问题。剩余两项 Low 分别为极端手工配置下的理论峰值与非 API 直接调用时 generation 可空；当前生产入口始终传 generation、默认容量有界，登记至 V2-04/资源策略统一收紧，不触发 Attempt 3。
- Close Gate 预检：包含两份 Pi 报告的 INDEX 再次执行 `./scripts/validation/plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Json`，退出 0；22 个范围文件为 L3、AgentService/Deployment/Docs/Governance/Unknown、Milestone，fingerprint `a09e806c6885b5379cead130b89cb94b1f6300e5b3828679734410173d05e23f`。Unknown 仅为新增生产 env 断言的既有 Shell 合约脚本，已由完整 `tls-public-host-contract.sh` 真实通过完成手工影响审查。`git diff --cached --check` 与最终敏感正则均退出 0，Testcontainers label 查询为空；Gitleaks v8.30.1 扫描约 100.99 KB，`no leaks found`。
- 效率记录：从分支创建至首次 Codex 门禁完成约 34 分钟；规划上下文包直接读取 24 个不同文件；测试/校验命令在 Pi 修复前 21 次，Pi 修复新增 4 次相关/全量/跨进程验证；重复测试源于源码或测试输入变化；Pi 共需 2 次（第二次仅因已证实阻断项修复）；返工原因包括非法低预算测试数据、外部模型 UUID 泄漏、WSL 默认 `/repo` 路径不适用于当前启动方式及 Pi 发现的 LRU commit 竞态。

## 风险与回滚

主要风险是并发请求顺序、跨作用域会话串用、Prompt 预算只在局部生效、摘要吞掉近期消息或检索资料。回滚方式是回退本节点提交；HTTP schema 和数据库均无迁移，因此无需数据回滚。回滚或进程重启会丢失尚未持久化的会话摘要，这是本节点明确限制。
