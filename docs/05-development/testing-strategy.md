# 测试策略

- 状态：Accepted

## 测试分层

- 领域/Service 单元测试：快速覆盖业务规则、规范化、重复和不存在分支。
- Web 切片测试：验证路由、Bean Validation、状态码、DTO 和统一错误。
- Repository 集成测试：使用真实 PostgreSQL/Testcontainers 验证 SQL、约束和查询；Day 1 若环境不可用需明确记录未运行。
- 端到端测试：跨 Web、Java、Python 的关键闭环，进入对应天次后建立。

## 基于风险选择验证范围

每次修改分别回答“失败后果有多严重”和“实际影响哪些系统”，再按 `../00-governance/efficient-validation.md` 选择最低成本但充分的验证：

| 等级 | 典型影响 | Codex 验证 | Pi |
| --- | --- | --- | --- |
| L0 | 文案、CSS、注释、README、无行为整理 | 必要语法/类型/lint/构建，可有依据地跳过自动化测试 | 默认不需要 |
| L1 | 单组件、小函数、局部交互、接口不变且边界明确 | 直接相关测试，必要时核心 smoke | 默认不需要 |
| L2 | API 逻辑、数据库读写、缓存、Tool、Agent 节点、Workflow、状态、外部调用、多模块 | 相关模块测试 + 核心 smoke | Diff Review |
| L3 | 架构、Schema、权限安全额度限流、Agent Runtime/状态机、全局上下文、核心流程、API Contract、公共模块 | 受影响系统完整回归；仅 Release/共享全仓/范围不明时全仓 | Milestone Review |

连续 5 次 L0/L1、节点结束或进入下一节点前触发 Milestone Review，但机器测试覆盖累计或当前真实影响域。Release Gate、共享全仓变化、范围无法可靠还原才触发全量测试。每份变更记录维护累计计数；L2/L3 Review 后归零。未知影响自动按至少 L2 处理。

执行前运行 `scripts/validation/plan-change-gates.ps1`，记录其风险、影响域、门禁与 change fingerprint。规划器只给最低集合；调用关系、安全边界或失败证据可以向上升级。

## 本任务证据复用与输出

- 同一任务内，测试覆盖的源码、配置、依赖、契约与 base 均未变化时，可以复用已通过结果。
- Review 修复后只重跑受影响测试；finding 或代码变化跨域时再扩大。
- 跨任务、其他工作树、历史报告和旧缓存不得复用。
- 每个成功命令必须逐条记录实际执行的完整命令、工作目录/模块上下文、版本、退出码、通过/失败/跳过数量和清理状态；仅写 `PASS`、✅、“同一命令”或数量不算测试证据。完整日志仅在失败排障时展开。

## Day 2 质量门槛

- Java 编译通过。
- Service 的成功与主要失败分支有测试。
- HTTP 契约的创建、查询、400、404、409 有代表性测试。
- Flyway 迁移在 PostgreSQL 上成功，JPA `validate` 通过。
- 不以 H2 成功替代 PostgreSQL 特有行为验证。
- 注册保存 BCrypt 哈希，登录成功 / 失败和 JWT 签名、issuer、时效有测试。
- 匿名请求 401、跨用户请求 403、owner 与 ADMIN 成功路径有测试。
- Wiki / Task CRUD、默认值、嵌套项目匹配和乐观锁冲突有测试。
- 密钥、token 与 passwordHash 不出现在 HTTP 响应、测试快照或提交内容中。

## Day 3 质量门槛

- Codex 执行 Agent Service pytest，覆盖 health、内部 token、输入校验、conversationId 与 graph 输出。
- Codex 执行 Core API clean verify，覆盖项目授权先于下游调用、请求字段映射和下游失败 503。
- Java/Python 字段名、UUID、requestId 和 400/401/403/503 契约一致。
- Codex 在隔离 Python 环境启动真实 uvicorn，并设置 `AGENTFORGE_AGENT_CONTRACT_TEST=true` 后运行 Java verify，验证真实 HTTP header 与 JSON 往返；普通 Java 测试环境不依赖 Python 进程。
- 跨进程契约测试通过 Spring 测试上下文取得 Boot 自动配置的 `RestClient.Builder`，不得以静态裸 builder 代替生产序列化配置。
- 跨进程契约至少覆盖 200、内部 token 错误和下游不可达；出站请求断言必须验证 null 字段省略，以及 requestId header/body 一致，不能只凭 Python 的宽松解析推断序列化正确。
- Day 3 收口及后续阶段验证必须先确认 Docker daemon 可用，并真实执行 PostgreSQL/Testcontainers 测试；不得把容器测试跳过视为通过。验证记录需包含容器测试数量、跳过数和清理状态。
- 真实 token/LLM key 不进入仓库或测试输出。

## 命令

在 `services/core-api`：

```text
mvnw.cmd test
mvnw.cmd verify
```

构建和数据库验证的实际结果写入 `docs/07-changes/` 当前记录。Docker 已作为阶段验证前置条件；若 daemon 不可用，本轮不得以“未运行”或跳过容器测试交付。

## 测试命名

测试名描述场景与结果，如 `createUser_normalizesEmail`、`createProject_rejectsMissingOwner`。测试应验证公共行为，避免绑定无意义的实现细节。

## V2-09 Release Regression 门槛

- V2-09 固定为 L3 Release Gate，不按本次 diff 裁剪：必须执行 Java `clean verify`、Agent Service 全量 pytest、Web 全量测试与 production build、Compose 配置检查、真实跨服务关键链路、Evaluation runner、敏感扫描和 Pi V2 Release Milestone Review。
- 已确认 seam 为 Core `/api/v1` REST/SSE 与 application service、Agent `/internal/v1` HTTP/NDJSON 与 Context/Memory/Action Runtime 公共接口、Java→Python 跨进程最终业务结果、Web DOM/typed client、Evaluation CLI/dataset/report，以及 Compose/validation script 公共入口。
- Release Regression 必须覆盖普通问答、RAG、Create/Update Task、High-risk Approval、Reject、Duplicate Request、Agent Retry、Service Restart/Resume、Cross Project、Unauthorized、Long Conversation、Trace 和 Evaluation；任何未执行项都必须记为未完成，不能用历史 Node 报告代替。
- 跨服务测试只通过公开 API 观察业务结果和状态；允许额外检查 checkpoint 存在以证明持久化，但不能用直接数据库改写代替用户路径。
- runner 必须失败关闭、逐阶段输出脱敏摘要、使用运行时随机测试凭据，并在成功或失败后精确清理专用进程、Compose project、网络、volume、日志和临时目录。
- 补测试时继续使用 TDD：一个已确认公共 seam、一个真实红灯、一个最小实现；禁止测试私有实现、复制生产算法计算期望值或用过度 mock 替代 PostgreSQL/跨进程边界。
- 全部门禁、文档一致性和 Close Gate 通过前，不得创建 `v2-stable` 标签。

## Day 4 质量门槛

- Codex 执行 Agent Service pytest，覆盖 Chunk、hash Embedding、BM25、RRF、字符预算、来源去重、索引版本替换/删除和跨项目隔离。
- Codex 使用 pgvector PostgreSQL 17 容器执行 Python 索引集成测试；`vector` 扩展、384 维写入与 cosine 查询必须真实运行，不得 mock 后宣称通过。
- Codex 执行 Core API `clean verify`，覆盖内部来源 token、用户存在、owner/admin、跨用户拒绝、授权先于 Repository、Wiki/Task DTO 映射和公共 Chat sources 透传。
- Codex 启动真实 Core API、Agent Service 与 pgvector 容器，执行至少一个从公共 Chat 到来源回调、索引、混合检索和 sources 返回的跨进程闭环；所有进程和容器必须清理。
- 固定语料必须证明 Wiki 与 Task 都可召回、无匹配不伪造来源、另一个项目的 Chunk 不会泄漏。
- Docker/PostgreSQL 测试、跨进程测试不得跳过；报告记录命令、版本、测试数量、0 失败/错误/跳过和清理状态。
- 敏感信息扫描覆盖两个内部 token、JWT secret、数据库密码和 Embedding API key；真实值不得进入仓库或报告。

Day 4 的约定测试 seam 是：Agent Service HTTP Chat、Core API 公共/内部 HTTP 契约，以及 `RagStore.synchronize/search` 对真实 pgvector 的公开适配器接口。回归测试从这些边界观察行为，不测试私有函数；外部 HTTP 可以 stub，数据库优先使用真实容器。

Day 4 跨进程闭环由仓库脚本执行：

```powershell
.\scripts\validation\day4-e2e.ps1
```

脚本使用独立 Compose project 和专用端口，启动 pgvector、Core API 与 Agent Service；通过公共 HTTP 创建两名用户、两个项目、Wiki 与 Task，验证两类召回、跨项目隔离、来源版本替换、删除清理和无匹配不伪造来源。无论成功失败都按精确 PID 停止服务并执行 `docker compose down -v`；只清理由本轮创建的临时日志和隔离资源。

## V2-05 质量门槛

- Risk Engine 公共 seam 覆盖每个 Tool 的 role/risk/approval 固定映射、未知 Tool 和 USER/ADMIN 拒绝矩阵。
- Core HTTP 覆盖 Agent 与直接 Wiki/Task API，证明不能绕过 ProjectAccess/Risk Engine，客户端 Metadata 不能降权。
- Conversation HTTP 与应用服务测试共同覆盖列表、详情、Project/User/Thread 负向隔离、稳定排序和不泄露内部 Context。
- 同步成功与 SSE complete 保存完整 exchange；下游失败、流错误和提前结束不保存不完整历史。
- 真实 PostgreSQL/Testcontainers 执行 Flyway、JPA validate、唯一/外键约束与会话查询。
- Web ApiClient/DOM 覆盖历史入口、项目切换、选择恢复及 conversationId 复用。
- Agent pytest 与 Java→Python 契约证明 Python 只提供 Intent，服务端 Metadata 不可伪造。

## V2-06 质量门槛

- Core Application 公共 seam 覆盖五态迁移、执行前 RBAC/Risk 复核、同 key replay、不同 key 冲突、拒绝后不可执行和业务失败终态。
- Core HTTP seam 覆盖必需且受限的 `Idempotency-Key`、统一 Problem Details、401/403/404/409 和安全响应字段。
- PostgreSQL/Testcontainers 覆盖 V7、JPA validate、幂等唯一索引、悲观行锁、双线程 confirm 只写一次，以及 Approval/Task/Audit 原子事实。
- Web API/DOM 覆盖 Idempotency-Key 请求头、网络失败后复用同 key、`EXECUTED` 成功刷新 Task、`FAILED` 不误刷新及拒绝不写。
- 回归 Agent proposal/Java→Python 契约，证明 Python 仍只产生 Intent；V2-06 不引入 checkpoint/resume。

## V2-07 质量门槛

- 用 Agent Service 单元/HTTP 测试覆盖动态 interrupt、成功 Resume、同 key replay、错误 action/decision/key、旧 state schema 和完整 Namespace 隔离。
- 用两个独立 runtime 共享真实 PostgreSQL，证明第一个进程写入等待点并关闭后，第二个进程能恢复同一 workflow；生产启动不得静默退回内存 saver。
- Core application/HTTP 测试验证 `APPROVED` 提交、事务外 Resume、再次锁定授权与确定性执行的顺序；无 checkpoint 的 V2-06 旧 Action 必须保持兼容，V2-07 新 Action 必须强制 Resume。
- 运行真实 Core API、Agent Service 与 PostgreSQL 的跨进程闭环：Tool proposal interrupt 后重启 Python，再 confirm、same-key replay，并断言 Task 只写一次且 checkpoint 已持久化；所有进程、容器和卷必须清理。
- 本节点是状态机、Schema 和跨服务契约 L3 变化：最低门禁为 Agent pytest、Core `clean verify`、Compose config、上述 restart smoke、diff/docs/敏感扫描与 Pi Milestone Review。未修改 Web 契约或实现时不机械运行 Web 套件。

## V2-08 质量门槛

- 固定 dataset 至少覆盖 Wiki、Task、多 gold/无命中、Create/Update Tool、完整 Tool 参数与 no-tool；gold 不得进入 subject 输入。
- RAG 指标使用独立手算样例验证 Recall@K、MRR、Hit Rate 和宏平均，检索 source 排名与 Answer Faithfulness 分开报告。
- Tool Selection Accuracy 验证 Tool/no-tool 选择；Task Success Rate 必须比较规范化后的完整 Tool 参数，不能只比较工具名。
- Faithfulness 使用的确定性方法、阈值与限制必须写入报告，不得宣称等价于语义事实判断。
- runner 必须可从 CLI 重复执行，输出带 dataset hash、逐样本证据与指标定义的真实 JSON report；除生成时间外，同输入报告稳定。
- 本节点最低门禁为 evaluation 定向红绿测试、Agent Service 全量 pytest、真实 runner/report、diff/docs/敏感扫描与 Pi Milestone Review。未修改 Java、Web、数据库或公共 HTTP 契约时不机械运行对应套件。

## 2026-09-05 历史决定：曾停用 Pi

用户曾撤销 Pi 审查与测试授权，该决定及 Day 1–4 复核保留在 `docs/07-changes/2026-09-05-disable-pi-and-day1-day4-audit.md` 供历史追溯。随后用户重新授权每阶段一次性 Pi 只读代码审核，但没有恢复 Pi 测试、monitor、OnCodexWake 或自动阶段推进；Codex 始终直接执行并记录全部测试证据。

2026-09-06 起的现行门禁是：代码、配置、脚本和文档变更完成并由 Codex 真实验证后，必须在创建提交前直接触发一次 Pi 只读审核；持续授权无需逐次询问。连接失败按 `../06-operations/pi-review-connection.md` 快速停止并请用户处理。

## Day 5 质量门槛与审核分工

- 行为变更遵循仓库 TDD skill：先在公共 seam 运行可观察的失败测试，再做最小实现。
- Python HTTP 测试覆盖 proposal 的成功和歧义降级；Java Web/Service/真实 PostgreSQL 测试覆盖待确认、确认、拒绝、权限、version 和重复确认。
- 双服务 E2E 必须证明 Task 在 confirm 前不存在/未变化，confirm 后才写入，reject 无副作用。
- Codex 是唯一测试执行者，必须记录全新命令、退出码、工具版本、测试数量、失败/跳过和清理。
- 用户已于 2026-09-05 明确重新授权 Pi 仅做只读代码审核。Pi 不执行测试；其 PASS 不能替代 Codex 的机器测试证据。不得恢复 monitor、OnCodexWake 或自动阶段推进。

## Day 6 质量门槛

- Web 单元/组件测试使用 Vitest、Testing Library 与 jsdom，从 DOM 和 typed API client 公共边界验证行为。
- 覆盖登录与项目加载、Bearer/Problem Details、Markdown 安全渲染、Wiki 草稿与保存、Chat 来源、pending action 确认/拒绝及明确应用 AI 文本。
- `npm test -- --run` 必须 0 failed；`npm run build` 必须通过 TypeScript 和 Vite production build。
- 不用快照替代关键交互断言，不测试组件私有 state，不过度 mock React 内部实现。
- Day 6 至少完成真实 Core API/Agent Service 关键链路联调；完整三应用自动化 E2E、容器化和演示数据在 Day 7 固化。
- Codex 执行全部测试；Pi 只审核完成后的 diff。重大问题才阻断，小问题登记到下一轮统一处理，不因风格建议反复复审。

## Day 7 / V1 最终验收门槛

- 从空构建启动 Web、Core API、Agent Service 与 pgvector PostgreSQL，不能依赖旧 JAR、旧虚拟环境或旧前端产物。
- Web 入口及三个后端健康检查通过；公开 API 完成注册/登录、Project、Wiki、Task、RAG Chat、Tool proposal、confirm/reject。
- 演示数据只经公开 API 创建，重复邮箱可回退登录；脚本不打印 access token，不内置真实密码或外部 API key。
- 默认 hash Embedding 与 `disabled` responder 在无外部 key 时完成可重复验收；国内 LLM provider 通过边界 fake 验证兼容调用，真实 key 只用于本地人工体验且不得写入仓库。
- Java clean verify、Python pytest、Vitest、Vite production build和完整 Compose 验收全部记录真实退出码、数量和清理证据。
- V1 禁止因收尾提前引入 V2/V3 组件；生产部署、安全加固和真实生成式模型另立阶段。
- Day 7 当时经用户明确豁免 Pi，由 Codex 以浏览器真实交互完成最终审核；这是历史单次豁免，不适用于 2026-09-06 起的新变更。

## V2-01 Langfuse 基础 Trace 门槛

- 已约定 seam：Python `/internal/v1/chat` 与 `/internal/v1/chat/stream`、LangGraph 图入口、兼容模型 responder，以及既有 Java/Python HTTP 契约。Langfuse 是外部系统边界，可用 recorder/fake 验证发送的结构化事件；不测试 SDK 私有实现。
- 每个纵向切片先运行失败测试，再做最小实现。至少覆盖根/Agent/prepare/retriever/tool/generation、三个关联 ID、同步和流式 Token usage、Tool 安全结果摘要、retrieval/LLM 异常闭合、disabled 和 observer 故障 fail-open。
- V2-01 属于 Agent Runtime 与外部观测 L3 变更：执行 Python 全量 pytest、Java `clean verify`、Web 测试/构建、核心跨进程 smoke、配置解析、敏感信息扫描与节点 Pi Milestone Review。
- Trace 测试断言敏感正文和原始异常不出现在 observer payload；Token 缺失必须保持缺失，禁止通过字符数估算。

## V2-02 Context Manager 门槛

- 约定 seam 为 Python FastAPI `/internal/v1/chat`、`/internal/v1/chat/stream`、LangGraph 构建入口、LLM responder boundary，以及 Web DOM 与 typed API client。
- Context 测试必须覆盖 Working/Conversation/Project/Retrieved/Tool 五部分的生产者与消费者、显式 project 传递、同步/流式一致性、Tool proposal 不进入模型 messages，以及空 Summary 不改变现有 Prompt。
- Web 测试必须让 stream promise 在首个 delta 后保持 pending，证明 complete 前 delta 已进入 DOM；未闭合全文 fence 不得渲染成整块代码区域。应用完成结果后必须清空旧 Wiki identity、生成 title，并在保存时调用 create 而非 update。
- V2-02 作为 Agent 全局 Context/State L3 节点，运行 Agent Service 全量 pytest、Web 全量测试与生产构建、Java `clean verify`、必要跨进程 Chat/SSE smoke、敏感扫描和 Pi Milestone Review。Pi 只审核 diff，不能替代 Codex 的机器证据。

## V2-03 Conversation Summary + Token Budget 门槛

- 约定 seam 为 Python Context/store 公共接口、LLM model boundary、FastAPI `/internal/v1/chat` 与 `/internal/v1/chat/stream`，以及不变的 Java/Python HTTP 契约。
- TDD 必须覆盖多轮 Recent/Summary 分离、关键旧约束可见、Tool/检索内容不进入摘要、跨 project/user conversationId 拒绝、session/summary 有界，以及最终 System + Human 输入计数不超过配置预算。
- 同步与流式分别验证成功后提交完整 exchange；LLM 失败或不完整 stream 不得提交历史。Retrieval Context 必须在历史超预算时仍保留，最近消息必须作为独立 section 而不是被 Summary 替换。
- V2-03 属于 Agent 全局 Context/State L3；执行 Agent Service 全量 pytest、Java `clean verify`、真实 Java/Python chat 与 stream 契约 smoke、配置解析、敏感扫描和 Pi Milestone Review。HTTP schema 未变化且 Web 不读取内部上下文，因此不把 Web 全量测试列为最低门禁；若实现扩散到 Web 或公共字段则立即补跑。

## V2-04 Memory Namespace 隔离门槛

- 约定 seam 为 `MemoryNamespace`/Conversation store 公共接口、ContextBundle、FastAPI `/internal/v1/chat` 与 `/internal/v1/chat/stream`、Retriever/RagStore 公开适配器及不变的 Java/Python HTTP 契约。
- TDD 必须逐一改变 tenant、workspace、project、user、thread，证明另一个 Namespace 得到空历史且不能覆盖原历史；不得只测试正向相等。
- load 必须返回不可省略的 lease，commit 必须同时匹配完整 Namespace 与 generation。LRU 淘汰、session 重建、同步失败和不完整 stream 不得写入陈旧或跨 Namespace 回答。
- Retriever 只能使用 ContextBundle 中同一个 Namespace 的 project/user；真实 pgvector 测试继续证明查询、替换和删除均受 projectId 限定。
- V2-04 属于隔离和 Agent 状态 L3；执行 Agent Service 全量 pytest、真实 pgvector 测试、Java→Python chat/stream 契约 smoke、配置解析、敏感扫描和 Pi Milestone Review。若 HTTP schema、Java 或 Web 实现发生变化，按实际影响追加对应完整门禁。
