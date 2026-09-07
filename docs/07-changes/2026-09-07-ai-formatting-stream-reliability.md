# AI 文本整理流式可靠性修复

- 日期：2026-09-07
- 状态：Implemented
- 阶段：V1.2 线上缺陷修复
- 交付目标：当前分支 / 生产更新

## 背景

线上“AI 整理并预览”仍调用同步 `POST /agent/chat`。一次包含 4757 个字符的 Markdown 整理请求通过认证后返回 503；浏览器控制台同时出现其他 401 和本机 `ERR_INTERNET_DISCONNECTED`。同步模型调用要等待完整回答后才返回首字节，而 Agent Service 的模型请求默认超时为 10 秒、Core API 同步读取超时为 30 秒，长文本整理容易被统一包装为 503，且前端只能在请求结束后一次性显示结果。

用户粘贴的复现 curl 含 Bearer JWT；本记录不保存该凭据或请求正文，只记录脱敏后的路径、状态和输入规模。该令牌应撤销或随会话失效，不进入测试、日志、提交或审核输入。

## 目标

- “AI 整理并预览”复用已存在的公共 SSE Chat 接口，收到模型 delta 后立即增量显示 Markdown，完成后保留最终结果。
- 整理请求使用独立、无 conversationId 的调用，不改变项目 Chat 的 conversation、answer、sources 或 pending action。
- 项目 Chat 与 AI 整理互斥；切换项目或卸载组件时取消仍在进行的整理，旧项目迟到事件不得污染新项目。
- 整理流若在 complete 前失败，清除半成品并禁止应用到 Wiki；取消请求不显示为普通错误，旧请求结束不得解除新请求的 busy 状态。
- 整理提示明确要求不写入；即使下游意外返回 tool proposal，整理入口也不展示或覆盖项目 Chat 的待确认操作。
- 生产 Agent 模型/回调单次等待预算显式设为 60 秒，Core 下游读取预算设为 75 秒，并保持小于浏览器、SSE emitter 与 Nginx 的 120 秒流式总预算。
- 保持 401、403、404、429、503 Problem Details 和流内通用错误的既有安全边界。

## 非目标

- 不改变 Agent Service 的公共 API、数据库、配额计数或 HITL 写入契约。
- 不用前端重试掩盖无效 key、余额不足、RAG 故障或真实上游不可用。
- 不把模型/RAG 原始错误、令牌或上游响应正文暴露给浏览器。
- 不进入 V2 Trace、持久化、重试或状态机节点。

## 受影响文档

- `docs/02-architecture/frontend-architecture.md`：补充整理流的独立状态、取消和互斥边界。
- `docs/03-features/web-workspace.md`：更新用户流程、错误状态和测试 seam。
- `docs/04-api/agent-service.md`：注明长文本整理客户端优先使用既有 SSE 入口，不改变契约。
- `docs/06-operations/production-single-host.md`：细化 401、503 与浏览器离线错误的排障顺序。

## 设计决定

采用现有 `POST /api/v1/projects/{projectId}/agent/chat/stream`，不新增专用 formatter API。同步 Chat 保持兼容；浏览器整理入口改为 SSE 后，模型可在完整答案生成前发送首段，Nginx 已对该路径关闭缓冲且 Core 流式生命周期为 120 秒。格式化流使用独立 AbortController 和局部展示状态，不复用项目 Chat conversation，也不接受 pending action。

生产 Compose 显式传递 `AGENTFORGE_AGENT_REQUEST_TIMEOUT_SECONDS`，默认 60 秒；Core 的 `AGENTFORGE_AGENT_READ_TIMEOUT` 默认 75 秒。顺序保持 Agent 单次等待 < Core 下游读取 < SSE/Nginx 总预算，防止内层仍工作而外层先超时。两项均可通过部署环境覆盖，不增加自动重试或请求总次数。

该修复不改变系统边界、数据模型或公共接口，无需新增 ADR。

## 实现

- `apps/web/src/App.tsx`：AI 整理由同步 `api.chat` 改为独立 `api.chatStream`；delta 增量写入整理预览，最终 answer 收口；独立 AbortController 在项目切换/卸载时取消；意外 pending action 不进入项目 Chat 状态；Chat 流式期间禁用整理入口。
- `apps/web/tests/app.test.tsx`：通过 DOM 与 `ApiClient` 公共 seam 覆盖无 conversationId 的流式整理、增量预览、显式应用、Chat 状态隔离、proposal 忽略、生成互斥和项目切换取消。
- `infra/compose.yaml`、`infra/compose.prod.yaml`、`.env.example`、`.env.production.example`：显式传递模型/回调 60 秒等待预算，把 Core 下游读取预算调整为 75 秒，并补齐本地 Compose 的输出 Token 配置透传。
- 本机忽略的 `.env` 仅把两个非敏感超时值同步为 60/75 秒；未读取、输出或修改其中凭据。
- 更新前端架构、Web 功能、Agent API、生产排障和变更索引文档；公共 API、Schema 与数据库无变化。

## 验证结果

- TDD 红：`npm test -- --run tests/app.test.tsx -t "keeps the wiki draft until AI text is explicitly applied"`，退出码 1；1 failed、11 skipped，准确证明旧实现仍调用同步接口，流式 mock 未被消费。
- TDD 绿：同一命令退出码 0；1 passed、11 skipped。第二切片 `-t "prevents AI formatting while project chat is streaming"` 先退出码 1（按钮未禁用），最小修复后退出码 0（1 passed、12 skipped）。
- Pi 建议对应的第三个 TDD 切片：`-t "discards a partial formatting result when the stream fails"` 先退出码 1（半成品仍可见），清理 partial、收紧 busy 守卫与 AbortError 识别后退出码 0（1 passed、13 skipped）。
- Web 最终完整回归：`npm test -- --run`，Vitest 3.2.7，退出码 0；3 files、18 tests 全部通过，0 failed、0 skipped。
- Web 最终生产构建：`npm run build`，TypeScript + Vite 7.3.6，退出码 0；283 modules transformed，产物生成成功。
- Agent 定向测试：首次误用系统 Python 3.14.3，因未安装 pytest 退出码 1；改用仓库 `.venv` 后 `python -m pytest tests/test_api.py tests/test_llm.py -q` 退出码 0，27 passed、0 failed/skipped、2 warnings。
- Agent 全量测试：仓库 `.venv` 执行 `python -m pytest -q`，退出码 0；32 passed、0 failed/skipped、3 warnings。warning 为既有 Starlette/AnyIO deprecation 与 pytest cache 路径告警，不影响行为结论。
- Core 定向契约：第一次 Maven Wrapper 因沙箱阻止网络/用户缓存访问而退出码 1，未运行测试；获准读取用户级 Maven 缓存后 `mvnw.cmd -Dtest=AgentChatApiTest test` 退出码 0，6 passed、0 failed/errors/skipped。
- Core 全新门禁：Maven 3.9.11、Java 21.0.12.1 执行 `mvnw.cmd clean verify`，退出码 0；重新编译 95 个主源码和 19 个测试源码，83 tests、0 failures/errors、7 skipped。7 项为既有需独立 Python 进程的跨进程 HTTP 契约条件测试；本轮相关 API controller 6 项已单独 0 skipped 通过，Python API/LLM 也已单独通过。
- Compose 配置：第一次生产渲染因缺少 `PUBLIC_HOST` 占位值退出码 1；设置 `example.invalid` 后，本地 `.env` 与 `.env.production.example` 两种渲染均退出码 0，且只输出脱敏值 `Agent=60`、`Core=PT75S`。
- 静态门禁：`git diff --check` 退出码 0；JWT、私钥、Bearer credential 扫描 0 命中，按文件复核后的 API key/token/password 字面量扫描 0 命中；临时诊断标记 0 命中。
- 清理：`docker ps --filter label=org.testcontainers=true` 返回 0 个容器；没有创建临时请求文件。用户原有未提交文档改动和未跟踪 DOCX 未触碰。
- Pi Diff Review：`deepseek/deepseek-v4-pro`，Attempt 1，结果 PASS，报告为 `docs/08-reviews/2026-09-07-review-ai-formatting-stream-reliability-attempt-1.md`。无必须修改项；采纳 S1/S2/S4/S5 的 busy 竞态、半成品清理、失败测试和 AbortError 鲁棒性建议。S3 的孤立 pending action 可观测性需要 Core mode/Trace 契约，记录到 V2 后续，不在本次前端修复中扩大公共接口。
- 生产限制：出于凭据与项目正文外发风险，自动审批拒绝了对用户附件中 Bearer 请求的公网重放；因此尚未用该真实令牌验证线上响应。修复结论由旧公共调用路径的确定性失败测试、SSE 既有契约与超时配置渲染支持；部署后仍须用新登录会话完成不泄露 token 的真实浏览器 smoke。

## 风险与回滚

最大影响范围为 Web 的 Chat/整理并发与既有 SSE 客户端，初评 L2（流式状态、异常/超时、跨模块契约）。回滚时恢复 Web 整理入口到同步 Chat；API 与数据库无需回滚。若流式入口仍返回 503，应依据 requestId 检查 Agent 日志中的 RAG、模型认证/余额、模型请求超时和 Core 下游状态，不能把浏览器离线提示当作服务端根因。
