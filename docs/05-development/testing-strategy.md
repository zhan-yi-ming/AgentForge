# 测试策略

- 状态：Accepted

## 测试分层

- 领域/Service 单元测试：快速覆盖业务规则、规范化、重复和不存在分支。
- Web 切片测试：验证路由、Bean Validation、状态码、DTO 和统一错误。
- Repository 集成测试：使用真实 PostgreSQL/Testcontainers 验证 SQL、约束和查询；Day 1 若环境不可用需明确记录未运行。
- 端到端测试：跨 Web、Java、Python 的关键闭环，进入对应天次后建立。

## 基于风险选择验证范围

每次修改先回答“如果这里出错，最多影响哪里”，再按 L0–L3 选择最低成本但充分的验证：

| 等级 | 典型影响 | Codex 验证 | Pi |
| --- | --- | --- | --- |
| L0 | 文案、CSS、注释、README、无行为整理 | 必要语法/类型/lint/构建，可有依据地跳过自动化测试 | 默认不需要 |
| L1 | 单组件、小函数、局部交互、接口不变且边界明确 | 直接相关测试，必要时核心 smoke | 默认不需要 |
| L2 | API 逻辑、数据库读写、缓存、Tool、Agent 节点、Workflow、状态、外部调用、多模块 | 相关模块测试 + 核心 smoke | Diff Review |
| L3 | 架构、Schema、权限安全额度限流、Agent Runtime/状态机、全局上下文、核心流程、API Contract、公共模块 | 全量测试 + 核心业务回归 | 必须 Review |

连续 5 次 L0/L1、节点结束、重要 merge/commit、进入下一节点前、多模块扩散或测试异常触发全量测试、核心 smoke 和 Milestone Review。每份变更记录维护累计计数；L2/L3 Review 或 Milestone Review 通过后归零。未知影响自动按至少 L2 处理。

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
