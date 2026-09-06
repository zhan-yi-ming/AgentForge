# V2-01 Langfuse 基础 Trace

- 日期：2026-09-06
- 状态：Implemented
- 阶段：V2-01
- 交付目标：当前远程分支

## 背景

V1 已用 `request_id` 串联 Java 与 Python 日志，但一次 Agent 请求内部的准备、检索、Tool 规划与模型调用仍无法按父子关系查看，耗时、Token usage 与异常也没有统一 Trace。V2-01 需要在不改变 Java 确定性业务边界的前提下建立基础 Langfuse 链路。

## 目标

- 对 JSON 与流式 Chat 建立 `request -> agent -> prepare / retrieval / tool / llm` 父子观测。
- 在根观测记录 `request_id`、`thread_id`（当前 `conversation_id`）与 `project_id`，由 SDK 自动记录各观测起止时间。
- generation 记录 provider/model 与可获得的 input/output/total Token；retrieval 记录来源数量；tool 记录类型、是否产生 proposal 与安全的结果状态；异常观测以脱敏错误类别闭合。
- Langfuse 默认关闭；未配置、SDK 上报或网络失败不得改变 Chat 业务响应。
- 用自动化测试证明成功、异常、流式和关闭模式的 Trace 行为，并完成全量回归、核心 smoke、敏感扫描与 Pi Milestone Review。

## 非目标

- 不部署 Langfuse Server，不增加复杂 Dashboard、Eval、成本策略或大规模日志重构。
- 不持久化聊天历史，不实现 V2-02 Context Manager 或任何后续 Node。
- 不把用户 message、回答正文、检索正文、Tool 参数、JWT、密码、API key、内部 token 或上游异常正文发送到 Langfuse。
- 不让 Python 执行业务写入，也不改变 Java 对 proposal 的校验、确认与写回职责。

## 受影响文档

- `docs/02-architecture/decisions/ADR-0016-langfuse-fail-open-observability.md`：记录外部观测边界、脱敏和 fail-open 决策。
- `docs/02-architecture/system-overview.md`：补充 Observability 组件与信任边界。
- `docs/03-features/observability.md` 与索引：定义 Trace 行为、字段、安全和排障。
- `docs/03-features/agent-chat.md`：说明 JSON/流式请求如何进入同一观测模型。
- `docs/04-api/agent-service.md`：明确内部请求字段与 Trace 关联，不扩展公共响应。
- `docs/05-development/local-development.md`：记录可选 Langfuse 配置与本地验证。
- `docs/05-development/testing-strategy.md`：固定 V2-01 测试 seam 与门禁。
- `docs/01-product/roadmap.md`、`docs/01-product/v2-v3-node-roadmap.md`：把当前状态更新为 V2-01 进行中。

## 设计决定

采用 ADR-0016：只在 Python Agent Service 集成 Langfuse Python SDK。根 Trace 使用已有跨服务 `request_id`、当前会话 UUID 和项目 UUID 做可搜索关联；所有子观测通过当前上下文继承父 Trace。观测 adapter 是唯一外部边界，默认 No-op，启用后仍对初始化、写入与 flush 失败 fail-open。

约定测试 seam 为现有 Python FastAPI `/internal/v1/chat`、`/internal/v1/chat/stream`，LangGraph 图构建入口和兼容模型 responder；Langfuse client 作为外部系统边界使用 fake，不测试 SDK 私有实现。Java 公共契约保持不变，只通过既有真实跨进程契约回归确认 `requestId` header/body/response 连续。

## 实现

- `services/agent-service/src/agentforge_agent/observability.py` 新增 No-op/Langfuse adapter，以显式 `trace_context` 维持父子关系，所有 SDK start/update/end/shutdown 均 fail-open；错误只记录 exception class。
- `api.py` 在内部认证后为 JSON/NDJSON 入口生成一次有效 thread UUID，管理 request/agent 生命周期；同步和流式成功、RAG/LLM/未知异常均结束观测。
- `graph.py` 在原有 prepare/retrieve/plan/respond 节点建立 chain/retriever/tool/generation 观测，只输出状态、source count、proposal bool 和 action type。
- `llm.py` 从 LangChain `usage_metadata` 读取 provider 实际返回的 input/output/total token；同步和流式都记录，缺失时不估算。`ChatOpenAI` 启用 `stream_usage`，模型名写入 generation 一等字段，provider 写入安全 metadata。
- `config.py`、两个 Compose 文件、两个 env example 和部署 env 生成/验证脚本加入默认关闭的 Langfuse 配置；public/secret key 使用 `SecretStr`，启用的生产配置要求 key 与 host 完整。
- `main.py` 在 FastAPI shutdown 时尽力 flush/shutdown。`pyproject.toml` 使用 `langfuse>=4.0,<5`；本次实际验证解析为 Langfuse 4.15.1。
- `tests/test_observability.py` 从 FastAPI、Graph、responder 和 SDK adapter seam 覆盖层级、关联、Token、脱敏、异常闭合、fail-open 和 shutdown。
- 没有数据库迁移、Java/Web 公共 API 变化或 V2-02 Context Manager 实现。

### Pi Attempt 1 修复范围

- Pi Milestone Review Attempt 1 发现流式入口在 context graph 前置阶段遇到 `ValueError` / `RagDependencyError` 以外的未知异常时，只创建了 request/agent 观测却没有标记失败并结束。该发现可由控制前置 retriever 抛出 `RuntimeError` 稳定复现，判定为必须修复。
- 修复目标是在响应尚未开始时补齐未知异常兜底：以脱敏异常类别标记 request/agent 为 ERROR、结束二者，并保持原异常继续上抛；不新增 HTTP/SSE 契约。
- 先增加流式前置未知异常回归测试并确认红灯，再做最小实现修改；修复后重跑 Python 全量、跨进程核心 smoke、全量回归、安全扫描与 Pi 复审。
- Pi 的 `trace_context` 建议经 Langfuse 4.15.1 运行时签名确认：`TraceContext` 是含 `trace_id` 与可选 `parent_span_id` 的 TypedDict，当前显式字典符合 SDK 公共契约，且真实 SDK 离线父子调用已成功，因此不改实现。
- Pi 的 usage object 建议经当前 LangChain 运行时确认：`UsageMetadata` 继承 `dict`，现有逻辑已覆盖当前依赖返回类型；不为未出现的第三方未来类型扩展范围。
- Langfuse host 的 URL 格式增强与 disabled responder fallback 测试均不构成当前节点可运行性、安全或架构阻塞，维持最小改动并记录为后续非阻塞建议。

## 验证结果

- TDD 红灯包括：缺少 `observability` 模块、adapter 外部失败未隔离、Graph 不接受 observation、FastAPI 未创建根 Trace、流式入口没有 Trace、generation 未记录 provider/model/usage、应用 shutdown 未调用 observer；对应最小测试均先以退出码 1 失败，再逐片实现为绿。
- Pi M-01 回归红灯：`test_stream_context_unknown_error_closes_request_and_agent_trace` 在流式前置 retriever 抛出 `RuntimeError` 时退出码 1，根观测没有 ERROR update；补充未知异常兜底后，同测试与既有流式 LLM 异常测试共 2 passed、退出码 0，原异常仍继续上抛且敏感错误正文未进入 Trace。
- 诊断记录：初版 Langfuse environment 负向前瞻正则在 Pydantic Rust regex import 阶段稳定失败；最小化后改为字符/长度正则加显式 reserved-prefix validator，同一测试由红转绿，且无 `[DEBUG-...]` 临时日志残留。
- Python 3.14.3 / pytest 8.4.2：Pi 修复后的 `.venv\Scripts\python.exe -m pytest -q --cache-clear` 在受控 Docker 权限下退出码 0，44 passed、0 failed、0 skipped；包含真实 pgvector Testcontainers。初版第一次沙箱运行是 41 passed/1 Docker named-pipe permission failure，未跳过而以相同命令提权重跑。最终 3 条警告为 Starlette/状态码弃用与 pytest cache，不影响断言。
- Langfuse 4.15.1 SDK：离线 exporter smoke 实际构造 `Langfuse`，调用 `start_observation`、显式 parent `trace_context`、generation update/end 与 shutdown，退出码 0，输出 `LANGFUSE_API_OK`；未使用真实 key 或向外部项目发送 Trace。
- Java Temurin 21.0.12.1：Pi 修复后 `services/core-api/.\mvnw.cmd clean verify` 退出码 0，83 tests，0 failures/errors，7 skipped（只在显式双服务契约门禁开启）。Testcontainers 使用 PostgreSQL 17.11 并验证 5 个迁移，Maven `BUILD SUCCESS`。
- Web Node 24.14.0 / npm 11.9.0 / Vite 7.3.6：Pi 修复后 `npm test -- --run` 退出码 0，3 files、16 tests 全部通过；`npm run build` 退出码 0，283 modules production build 成功。初次沙箱运行因 esbuild 无权读取 worktree 祖先失败，受控重跑通过。
- 跨进程核心 smoke：预置仅当前进程使用的测试 token 后运行 `scripts/validation/day4-e2e.ps1 -ComposeProject agentforge-v2-01-e2e`，退出码 0；Wiki/Task source 各 1、更新后旧版本 0、删除 Task chunk 0、unmatched source 0、cross-project row 0。第一次未预置 token 时 Compose v5 在目标 service 启动前插值全文件而退出 1，清理分支成功；这是既有脚本前置条件，本节点未扩展范围修改。
- 配置/脚本：本地与生产 `docker compose ... config --quiet` 均退出码 0；第一次未传示例 env 的本地校验因缺少 `POSTGRES_PASSWORD` 退出 1，使用对应 example env 原配置重跑通过。`bash -n scripts/deploy/generate-production-env.sh scripts/deploy/validate-env.sh` 在沙箱中因 WSL `E_ACCESSDENIED` 退出 1，受控权限下原命令重跑退出 0；`scripts/validation/v2-prep-demo-experience.ps1`、`python -m compileall -q src tests` 与 `git diff --cached --check` 均退出码 0。Windows 与 WSL 均未安装 ShellCheck，因此未运行，已用 Bash 语法检查和既有 PowerShell 合约覆盖本次两处 Shell 变化。
- 安全：Gitleaks v8.30.1 通过本机缓存容器只扫描暂存 V2-01 diff；实现初扫约 75.79 KB、补入证据后约 80.46 KB，两次退出码均为 0。最终首次误用不支持的 `--source=-` 参数而退出 1、未形成扫描；按镜像帮助改用 `detect --pipe` 后扫描约 97.63 KB，切到最新 `origin/dev` 并完成最终文档回填后再扫约 107.21 KB，两次均退出码 0、`no leaks found`。Trace recorder 测试明确断言用户 message、模型回答、检索正文和原始上游错误不出现在 payload。
- Pi Milestone Review：Attempt 1 退出码 0、结论 `NEEDS_FIX`，M-01 指出流式前置未知异常未闭合；按文档先行与 TDD 修复。Attempt 2 使用 DeepSeek V4-pro 一次性只读复审，退出码 0、结论 `PASS`，无必须修改项；两项客户端未消费流与 host URL 格式建议均为 Low、非阻塞并保留记录。审核报告均标记 `RESOLVED` 并随节点提交。
- 清理：Python/Java Testcontainers 由 Ryuk/上下文退出；隔离 `agentforge-v2-01-e2e` PostgreSQL container、network 与 named volume 均已删除，最终 Docker 精确过滤查询为空；Java/Python smoke 进程和临时日志由脚本 finally 清理。已删除本轮 `apps/web/dist`、`services/core-api/target` 和 Python `__pycache__`。`services/agent-service/.pytest_cache` 因宿主 ACL 拒绝（包括受控权限下 `takeown`）无法删除；该路径已被 Git 忽略、不进入提交，保留为已知本地清理限制。
- GitHub Maintenance：新增正式 Observability 功能文档与 ADR，并更新文档索引、架构、API、开发和路线图；README 不需要为尚无真实外发 Trace 截图而机械更新，避免把未验证的平台展示写成已实现证据。
- 已知限制：未提供真实 Langfuse 项目凭据，因此没有外发真实 Trace 或 README 截图；SDK adapter 与实际 4.15.1 API 已本地验证，平台连接需部署者启用配置后用新请求确认。Token usage 取决于 provider 是否返回 metadata，缺失保持缺失。

## 风险与回滚

风险等级预判为 L3：涉及 Agent Runtime、跨节点异常生命周期、外部调用和敏感字段边界。最大风险是 Trace 断链、异常未结束、Token 丢失、敏感正文外发，或观测故障拖垮业务。

回滚时关闭 `AGENTFORGE_AGENT_LANGFUSE_ENABLED` 即退回 No-op；如需代码回滚，移除 Python adapter 与 SDK 依赖即可。没有数据库迁移和公共 API 字段迁移，Java、Web 与业务数据不需回滚。
