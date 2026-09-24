# V3-02 LiteLLM Model Gateway

- 日期：2026-09-24
- 状态：Implemented（Milestone Review Attempt 2 PASS）
- 阶段：V3-02
- 目标分支：`codex/v3-02-litellm-model-gateway`
- Base：`8166a2a7e2d690c9aa9208afada64d344d6481a0`（V3-01）

## 背景与目标

当前 Python Agent Service 用 `ChatOpenAI` 兼容客户端直接配置 DeepSeek、智谱与通义。回答、流式回答和自然语言 Action Intent 都调用同一模型实例，但 provider 差异、超时、重试、usage 和成本没有统一 Gateway 契约。V3-02 在 Python 边界内引入 LiteLLM SDK，并让 Agent 仅通过 Gateway 使用模型。

## 范围

- 统一同步生成、流式生成和 JSON Action Intent 的模型调用。
- 配置主 provider 与至多一个静态 fallback；限定何时可以 fallback，避免循环和流式输出重复。
- 统一脱敏错误、provider/model、token usage 与可用时的成本信息。
- 保留 Java 独占 RBAC、Risk、Approval 和业务写入。

## 非目标

- 不实现 V3-03 的任务类型路由、动态选模或模型自行选模。
- 不引入独立 LiteLLM Proxy、模型凭据数据库或新的业务表。
- 不改变 Java/Python 公共 HTTP 字段、RAG 检索或 Tool 执行权限。

## 文档与实现计划

先更新 Model Routing 功能文档、系统架构、新 ADR 与本地配置文档；再在 `services/agent-service` 的公共 responder seam 使用 TDD 接入 LiteLLM。测试覆盖 provider 切换、JSON 意图、流式输出、超时/失败 fallback、usage/成本与凭据脱敏。最后运行 Agent Service 全量 pytest、相关配置检查、Milestone Review、diff 与敏感扫描，回填本记录。

## Preflight

已 fetch `origin`。当前分支从 `origin/codex/v3-01-mcp-adapter` 的同一 commit 建立。用户已有未提交内容：`docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md`、`.worktrees/`、`AgentForge_产品规划与三阶段迭代路线.docx`；不纳入本节点提交。

## 已实现

- Python Agent Service 新增进程内 `LiteLlmGateway`；回答、流式回答与自然语言 Action Intent 经同一模型调用边界。默认 `disabled` 不变；显式支持 DeepSeek、智谱、千问和需单独配置模型名的 OpenAI GPT。
- LiteLLM SDK 统一调用参数、超时和 provider 响应；关闭 SDK 自动重试，主调用仅在输出前的暂时性故障尝试一个静态 fallback。输出开始后不切换；认证/参数/内容故障不 fallback。
- 统一 token usage 与可用时的美元成本估算，Trace 记录实际使用的 fallback provider/model。未知成本不写成零；公共错误不暴露上游正文。
- 本地/生产 Compose 透传 fallback 配置；`.env.example`、`.env.production.example` 为可选项保留空值。无数据库或公共 HTTP 字段变化。

## 风险和影响域

规划器在完整工作区给出 L2 / Agent Service、Deployment、Docs、Unknown。Unknown 仅由用户已有 `.worktrees/` 与规划文档 `.docx` 造成，不属于本节点；人工按 Gateway 架构与模型凭据边界上调为 L3，Milestone Review。影响限定 Python Agent Service、Compose 和文档，未改 Java/Web/公共跨服务契约，因此不运行无关模块全量测试。最终暂存范围规划器：`riskLevel=L2`、`areas=AgentService,Deployment,Docs`、`changeFingerprint=8994e0f38c4db5b8128e30fe3212ef04663d2df69e30904f119d8a9688618a09`（HEAD..INDEX）；Codex 按架构和密钥处理人工上调 L3。

## 当前机器验证

- TDD：`services/agent-service` 的 `tests/test_model_gateway.py` 首个用例先因 `build_responder` 不接受 `completion_func` 红灯（退出 1），最小实现后 1 passed（退出 0）；fallback、观测、流式 usage 与无效配置切片逐个红绿。最终用本地 HTTP 假 provider 经真实 LiteLLM SDK 证明 Chat Completions 路径、模型与上下文传递。
- 命令（工作目录 `services/agent-service`）：`.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider --basetemp .pytest-v302-20260924`。最终退出 0；Python 3.14.3 / pytest 8.4.2；135 passed、0 failed、0 skipped、4 warnings。警告来自 Starlette/AnyIO 弃用及 LiteLLM 所依赖 Pydantic 的 ReadOnly 提示。测试专用 basetemp 已精确清理。
- 同一全量命令首次未指定 `--basetemp` 时，129 passed、5 个 evaluation fixture setup 因本机默认 `%TEMP%/pytest-of-86134` 权限拒绝而报错（退出 1）；指定仓库内专用 basetemp 后 134 passed（退出 0），新增真实 SDK 本地 HTTP smoke 后最终 135 passed（退出 0）。保留失败证据，不把环境错误记成测试通过。
- 命令（仓库根目录）：`docker compose --env-file .env.example -f infra/compose.yaml config --quiet` 与 `docker compose --env-file .env.production.example -f infra/compose.prod.yaml config --quiet`，均退出 0；Docker 29.5.3 / Compose v5.1.4。
- 命令（仓库根目录）：`docker compose --env-file .env.example -f infra/compose.yaml build --no-cache agent-service`，退出 0；从零安装 LiteLLM 1.102.1 并构建镜像。pip 在容器构建中提示 root 安装警告，镜像构建成功。
- 命令（仓库根目录）：`& services/agent-service/.venv/Scripts/python.exe -m pip check`，退出 0，无依赖冲突。
- 付费 provider 的真实密钥与线上成本账单未用于测试；本节点只声称本地 SDK/假 provider 和机器回归证据，不宣称真实厂商调用通过。

## 待完成门禁

- 仅暂存本节点文件并执行 scoped 规划器、diff check、敏感信息扫描。
- 通过唯一 Pi 连接运行 V3-02 Milestone Review；逐项研判 finding，必要时修复并补测。
- 更新路线节点状态与本记录，输出 Node Close Gate 后提交、非 force 推送并核验。

## Pi Milestone Review Attempt 1 与修复决定

报告：`../08-reviews/2026-09-24-review-v3-02-litellm-model-gateway-attempt-1.md`；结果 NEEDS_FIX。M1 成立：真实 LiteLLM 异常分类未被测试，异常类名称缺失可使整个 fallback 静默失效，须补测试并修复。S1 的畸形 metadata 可触发 KeyError，作为同一观测边界的防御修复。S3 成本 0 与“未知不能写 0”冲突，需改为只记录正数估算。S4 属有意禁用自动重试，补文档说明可靠性取舍。S2 仅影响未来多角色消息，不改变当前两消息契约，留 V3-03 按需扩展。S5 当前兼容端点的非 OpenAI 模型可能无法查价，记录为成本未知的已知限制，不伪造价格。

## Attempt 1 修复后验证

- 真实 `litellm.Timeout` 且一个可选异常名缺失的测试先红灯、修复后绿灯；又覆盖真实 Timeout/RateLimitError/InternalServerError 应 fallback，AuthenticationError/BadRequestError 不 fallback，备用 provider 再失败仅调用一次。零成本估算测试先红灯、修复后绿灯。
- 命令（工作目录 `services/agent-service`）：`.\.venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider --basetemp .pytest-v302-reviewfix-20260924`；退出 0，143 passed、0 failed、0 skipped、4 warnings（Starlette/AnyIO 弃用与 LiteLLM/Pydantic ReadOnly 提示）。专用 basetemp 已精确清理。
- 命令（仓库根目录）：`docker compose --env-file .env.example -f infra/compose.yaml build agent-service`；退出 0，已用修复后源码重新构建镜像；pip root 安装警告仍在。
- `docs/08-reviews/2026-09-24-review-v3-02-litellm-model-gateway-attempt-1.md` 已逐项回填判断；阻断项 M1 已修复，待 Attempt 2 只读复审确认。

## Node 收口

- Pi Attempt 2：`docs/08-reviews/2026-09-24-review-v3-02-litellm-model-gateway-attempt-2.md`，PASS，M1 阻断项确认关闭。Codex 已回填全部建议项判断；S2 消息角色扩展留对应未来 Node，其余低级别限制已在功能/运维文档中说明。
- 公开 README 与路线图状态同步为 V3-02 Implemented；V3-03 任务类型路由保持 Planned。
- 最终暂存 diff check 与敏感扫描通过；Node Close Gate、提交和远端核验待执行。用户原有三处未提交内容仍在工作区，未纳入本节点范围。
