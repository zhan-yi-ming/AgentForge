# V3-02 / V3-03 审核修复与 V3-04 规划

- 状态：Implemented（修复；V3-04 计划仍为 Planned）
- 基线：ff6805282ea96b87e326a60dc31e27518344f8fb；分支 codex/v3-model-review-fixes
- 范围：外层超时预算、显式任务模式、流式回退观测隔离、静态 JSON 能力校验；V3-04 仅规划。
- 风险：L3；Agent Service、Core API、Web、部署与跨服务契约。
- 用户已有修改及 .worktrees/、产品路线 docx 均不纳入本次提交。

## 设计与验证计划

有限 taskType 默认为 ANSWER，消息正文不决定模型路由；Tool 意图固定 PLAN。Java 验证任务枚举，模型目标由部署配置决定。流式回退只保留成功尝试的 usage/cost。静态 JSON 调用只使用已声明能力的目标。外层等待预算覆盖检索、规划和回答的主备调用，但不是端到端硬截止。

公共 seam TDD：先复现再最小修复。根 Compose 变更执行全仓回归、跨进程 smoke、敏感扫描及 Pi Milestone Review。V3-04 仅提出图模型、来源证据、权限及验证切片，Start Gate 等待确认。

## 验证结果

见当前机器验证摘要与 Pi 收口。

测试隔离补充：LiteLLM DEV 模式自动 dotenv 加载会使同进程后续 provider 测试受到本地部署配置影响；pytest 固定 PYTHON_DOTENV_DISABLED=1。首次相关回归 78 passed / 5 failed，其中 1 项为构造器兼容性，4 项为 SDK 导入环境污染；分别修复默认能力语义和测试环境，禁止将失败当成通过。

额外校验：Java Jackson 默认允许数字按 enum ordinal 选择任务，公共 HTTP seam 的 taskType=0 测试实际红灯（请求进入服务）。新增 enum 字符串解析，拒绝数字/未知值；不会改变已有业务枚举。完整回归后的相关 Java/跨服务证据将按最终源码重跑。

## 当前机器验证摘要

- 工具：Java Temurin 21.0.12.1，Python 3.14.3 / pytest 8.4.2，Node 24.14.0 / npm 11.9.0，Docker 29.5.3 / Compose 5.1.4。
- Responder TDD 红灯：失败主流 usage/cost 污染；正文前缀覆盖 ANSWER；尚无显式模式；不兼容 JSON fallback 被调用；Compose 75 秒小于主备预算。最小修复后相关 83 passed；最终 HTTP 模式补充 32 passed / 1 warning。
- Java 新契约测试先编译红灯（缺失新 enum），数字模式 HTTP 测试实际红灯；修复后 `mvnw.cmd clean verify -q` 退出 0，139 passed / 0 failed / 8 skipped。8 项 skipped 是明确要求环境开关的 AgentServiceHttpContractIntegrationTest，随后用独立 Python 进程开启开关真实执行 `mvnw.cmd test -Dtest=AgentServiceHttpContractIntegrationTest -q`，退出 0，8 passed / 0 skipped。总计当前源码 147 项全部有通过证据。新 HTTP 延迟测试按 1/100 缩放外层等待配置，120 秒等价延迟超过原 75 秒预算，新配置成功。
- Python 最终模块命令：在 services/agent-service 执行 `.venv/Scripts/python.exe -B -m pytest -q -p no:cacheprovider --basetemp ../../.data/pytest-v3-review-final`，退出 0，175 passed / 0 failed / 0 skipped / 4 warnings（真实 PostgreSQL/pgvector/checkpoint 和本地 LiteLLM HTTP）。之后仅新增五种模式一致测试，相关路由套件 32 passed；未重复未变化套件。
- Web：完整回归中 npm test -- --run，73 passed / 0 failed / 0 skipped；npm run build 退出 0。
- 完整回归：`scripts/validation/v2-release-regression.ps1` 退出 0，11 stages PASS：planner 15 checks、双 Compose、Java/Python/Web、构建、RAG、Tool/HITL、重启恢复、Evaluation、独立完整 Compose 验收。新增 Java 数字校验后重跑最终 Java clean verify，并重跑四个受影响跨进程/完整栈阶段；结果待下文收口。
- Evaluation 保持提交基线：recallAtK/mrr/hitRate=0.75，faithfulness 词项支持代理=0.8333333333333334，toolSelectionAccuracy/taskSuccessRate=1.0；不代表线上语义正确率。
- TLS：tls-public-host-nginx.ps1 退出 0，IPv4/root-domain/existing-www 模板生成及真实 Nginx 语法通过。
- warnings：Java Mockito 动态 agent/JVM CDS 与未来 JDK 限制；预期 history persistence failure 场景 warning；Python Starlette/AnyIO 弃用、HTTP422 弃用和 Pydantic ReadOnly；npm 用户配置 home warning；Docker pip root warning。首次 Maven wrapper 在错误工作目录失败，改在 core-api 执行；未把环境错误当通过。
- 清理：pytest 本任务 basetemp 已验证在 .data 内后删除；临时契约 Python 进程和日志已清理。隔离 Compose 测试各自 down -v，不替换用户 agentforge-* 容器。旧 .pytest-p304 权限 warning 保留。

## 限制

未调用真实付费厂商；JSON 能力仍需部署者验证。timeout 是外层等待与每次调用配置，不是完整检索/数据库/生成的硬截止；额外数据库处理和流式持续输出可能延长耗时，SSE emitter 有独立 360 秒期限。成本未知仍保持 unknown。taskType 只是有限任务模式，不赋予任何业务权限。

V3-04 文档为 Planned，不包含领域图实现、抽取、消歧或 GraphRAG。

最终复验：`v2-release-regression.ps1 -Only rag-cross-process,tool-hitl-cross-process,restart-resume-cross-process,full-stack-acceptance` 退出 0，4 stages PASS。来源更新/删除与跨项目隔离、confirm/reject/replay、checkpoint 重启恢复及最终 Compose 公共 API 验收均通过；测试项目 061044d8 和 82b55c9a 的容器/网络/卷均清理。最终 staged planner 为 L3，AgentService/CoreApi/Web/Deployment/TlsDeployment/Docs，Milestone；fingerprint c173dc8dbeafeb5b0ed118be7491004f537b413b37a503b2dd3a156ffd9d60f3。暂存只含本任务 39 文件；diff check 和 Gitleaks v8.30.1 staged 扫描退出 0，无泄漏。

TLS shell 契约补充：只读仓库挂载 `docker run --rm ... python:3.14-slim bash scripts/validation/tls-public-host-contract.sh`，退出 0，IPv4/IPv6/domain issue、renewal、bootstrap、validation/generation、rejection 和 observability fail-open 全部通过；容器自动清理。

## Pi Attempt 1 判断与阻塞修复

报告：docs/08-reviews/2026-09-27-review-v3-model-review-fixes-attempt-1.md，NEEDS_FIX。M1 成立：误把 template 原 120 秒（stream）与 300 秒（MCP）两处当作 sync/stream，遗漏同步 Chat location 的显式 read timeout；原同步路径继承默认 60 秒。先补部署公共契约红灯，再给同步路径 read=360 秒，MCP 回到原 300 秒（MCP 业务 Tool 不进入模型回答预算）。文档的 sync/stream Nginx 360 秒保持一致。按影响域仅重跑预算、双 Compose、Nginx/TLS；完整栈脚本使用本地 Web nginx，不读取生产 template，无需重复该已通过验收。Java/Python 回答逻辑、Web 和数据库输入未变化，不重复无关测试。

S1：建议而非阻塞，record compact constructor 已归一 null，后续契约变更可增加 Java null 独立保护；Python 已实测 null。
S2：部分成立为部署风险；示例明确声明主模型 JSON=true，切换 provider 必须按文档核对能力；纯 Python 未声明仅 DeepSeek 默认 JSON，备模型默认 false。无已复现契约/安全缺陷，不扩展配置语义。
S3：部分成立，新增 sync/stream Nginx 预算回归捕获 M1；运行时 override 与数据库/流总时长的限制已明确，不以解析私有 emitter 代码代替公共接口证据。
S4：未来适配器建议；当前唯一生产 HttpAgentServiceClient 已实现新模式，无线上 500 缺陷。
S5：可读性建议；分支保留已有公共调用兼容性，不做无关重构。
S6：格式建议，无功能影响且 diff check 通过，本次不修改行为无关的文件。
S7：不成立为范围违规；用户明确要求同时规划 V3-04，仅保存 Planned 文档，无下一 Node 实现，Close Gate 会再次明确。
S8：部分成立为极限超时风险；Nginx read 是空闲等待，emitter 是总寿命，非相同计时语义；本次保留 360 秒并公开限制，无可复现阻塞不扩展。

M1 修复验证：部署 budget test 实际 1 failed / 3 passed（同步缺少显式 timeout）；最小修复后 4 passed / 0 failed / 0 skipped。生产 Nginx 三种主机模板语法与双 Compose config 退出 0。生产 template 不进入本地完整栈 acceptance 的输入，因此不重复不相关栈构建。MCP 300s 原契约恢复，无模型业务路径变更。Pi Attempt 1 finding 逐项判断已回填报告。仅真实 M1 阻塞项触发 Attempt 2。

## Pi 最终收口与 Close Gate

Attempt 2 PASS：docs/08-reviews/2026-09-27-review-v3-model-review-fixes-attempt-2.md；没有必须修改项。A1/A2/A5/A6/A7/A8 为已公开的部署限制或未来/风格建议；A3 不成立，测试通过真实 Gateway 将 provider usage 转 usage_metadata，旧代码已实际红灯；A4 不成立为模型预算缺陷，resume 仅恢复确定性审批状态。逐项判断已回填报告，纯建议不触发第三轮。

Node Close Gate：V3-02/V3-03 审核修复（非新 Node）。Scope 四项完成，额外收紧任务数字枚举；主备超时、显式模式、观测隔离、JSON 能力均有当前机器证据。V3-04 只规划，无图模型/抽取/消歧/GraphRAG 实现。Tests：Java clean verify + 已开启真实 HTTP 契约合计 147 项，Python 模块 175 项及后续路由 32/部署 4 项，Web 73 项、完整 11-stage 回归和最终相关复验、TLS/Nginx 均通过。Pi：第二轮 PASS，真实阻塞 M1 已修复。README：主入口无需改动、service README updated；Architecture：ADR-0031 updated；Docs：API/feature/change/plan updated；公开描述真实性 PASS；Demo/Screenshot 不需要；Evidence 已记录，paid provider 未验证。

Close Gate = YES。推荐提交：fix(agent): harden task routing and model fallback contracts。提交后非 force 推送 origin/codex/v3-model-review-fixes 并核验，随后停止；V3-04 Start Gate 等待用户确认。

效率记录：收口时间 2026-09-27 18:18 +08:00；总耗时与读取/测试命令数未独立计数，不宣称节省比例。重复测试仅由实际失败、最终输入变化或 Pi M1 触发；Pi 2 次。返工包括构造器兼容、SDK dotenv 测试污染、数字枚举校验和同步 Nginx 漏配。所有初次失败与复验结果保留于本记录。

最终 staged fingerprint：7809f1cdd10bc6bec8c0774064ed47eabd6a5159a4a170888a47a6e2ecebaf4a；范围含修复、已授权计划和两份审核报告，用户原有修改仍未暂存。

## 远端交付阻塞

提交前 `git ls-remote --heads origin refs/heads/codex/v3-model-review-fixes` 失败：GitHub SSH port 22 Connection reset。对同一 origin 做一次限时只读复查（ConnectTimeout=15、ConnectionAttempts=1），仍连接重置。未取得远端历史，不继续推送、不改 remote、不切换凭据。修复和计划保存为本地可读提交；远端同步及 commit 核验待连接恢复后继续。V3-04 实现仍等待计划确认及前置远端核验。此阻塞不改变当前机器测试和 Pi PASS 结果。
