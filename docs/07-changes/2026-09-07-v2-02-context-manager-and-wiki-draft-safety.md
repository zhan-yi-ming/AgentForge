# V2-02 Context Manager 与 Wiki 草稿安全修复

- 日期：2026-09-07
- 状态：Implemented
- 阶段：V2-02
- 交付目标：`codex/v2-02-context-manager` / `origin`

## 背景

Agent Graph 当前把 `project_id`、`normalized_message`、`retrieved_context`、`sources` 与 `tool_proposal` 分散在 LangGraph State 中，Retriever、Planner 和 Responder 分别按字段拼装输入。State 同时承担流程状态和模型上下文，后续加入摘要与预算时容易产生重复上下文、项目边界隐式传递和 Tool Result 无界回灌。

用户同时复现两个 Web 问题：AI 文档整理必须消费服务端真实 SSE delta，不能等待完整响应后伪装流式；不完整 Markdown 围栏在增量预览时可能把整个文档显示成深色代码块。整理结果应用到 Wiki 时还必须进入新页面草稿并补齐标题，不能保留旧页面 ID/version 后覆盖原页面。

## 目标

- 新增内部 `ContextBundle`，统一 Working、Conversation、Project、Retrieved 与 Tool Context，并明确每部分的生产者和消费者。
- LangGraph State 只负责请求输入、ContextBundle 与流程输出；prepare 之后的 Agent Node 通过 ContextBundle 读取上下文。
- 同步和流式 Chat 使用同一个 Context Builder/Manager，不改变 Java/Python HTTP 契约。
- Web 直接展示 `chatStream` 收到的 delta；流式期间使用不会因未闭合全文围栏而黑屏的预览策略，complete 后展示最终 Markdown。
- “应用到 Wiki 草稿”创建新的未保存页面状态，清空旧页面 ID/version，并从第一个 H1 提取标题；没有 H1 时使用明确默认标题。只有后续“保存 Wiki”才调用创建接口。

## 非目标

- 不实现 V2-03 Conversation Summary 生成、压缩或 Token Budget。
- 不实现 V2-04 Memory Namespace、长期记忆或聊天持久化。
- 不改变公共 SSE/NDJSON schema、Java DTO、数据库 schema、权限、配额或审批行为。
- 不自动保存 AI 整理结果，也不让 Python 直接写 Wiki。

## 受影响文档

- `docs/03-features/context-management.md`：定义 ContextBundle、State/Context 边界、生产者/消费者与限制。
- `docs/03-features/agent-chat.md`、`docs/04-api/agent-service.md`：记录同步/流式入口共享 Context Manager 且契约不变。
- `docs/03-features/web-workspace.md`、`docs/02-architecture/frontend-architecture.md`：记录真实增量预览和新页面草稿语义。
- `docs/05-development/testing-strategy.md`：固定 V2-02 公共测试 seam 与门禁。
- `docs/01-product/roadmap.md`、`docs/01-product/v2-v3-node-roadmap.md` 与文档索引：同步 V2-02 状态和下一节点边界。

## 设计决定

`ContextBundle` 是 Python Agent Service 内部的显式结构，不是新的 HTTP DTO。Working Context 保存当前规范化消息；Conversation Context 在本节点只保留可空 summary 槽位；Project Context 保存本次已授权请求的 project/user/admin/request 标识；Retrieved Context 保存受项目边界约束的检索文本与结构化来源；Tool Context 保存本次确定性 planner 生成的 proposal。Context Manager 负责构建和替换这些部分，Agent Node 不直接改写嵌套结构。

Web 继续复用既有公共 SSE API。增量文本立即可见，但只有 complete 后才允许应用；对尚未闭合的全文 Markdown fence 使用纯文本流式预览，避免整块深色代码区域，完成后恢复安全 Markdown 渲染。应用动作只改浏览器草稿状态：先解除旧 Wiki 选择，再写入标题和正文；持久化仍由 Java Wiki create API 完成。

本次没有新增框架、服务边界、持久化或公共接口，不新增 ADR。

## 预先约定的测试 seam

- Python FastAPI `/internal/v1/chat`、`/internal/v1/chat/stream` 与 LangGraph 构建入口。
- LLM Responder 接收到的 `ContextBundle`，外部模型保持 boundary fake。
- Web DOM 与 typed `ApiClient`，观察 delta 可见性、新草稿标题及保存时 create/update 调用。

## 诊断假设

1. 黑屏来自增量内容暂时形成未闭合的全文代码围栏；若成立，首个 fence delta 会产生整块 `<pre>`，改用流式安全预览后消失。
2. 并非后端缺少流，而是现有组件测试只在同一异步调用内同步触发全部 delta，未证明 promise complete 前首段已渲染；受控延迟 stream 测试应暴露证据缺口。
3. 覆盖旧 Wiki 是 `applyFormattedText` 只替换 content、未清空 `wikiId/wikiVersion`；若成立，应用后保存仍调用 update API。
4. title 缺失是应用动作未从 Markdown 提取 H1、也未设置默认标题；带/不带 H1 的 DOM 测试可分别证伪。

## 实现

- `services/agent-service/src/agentforge_agent/context.py`：新增不可变的 Working、Conversation、Project、Retrieved、Tool Context 与 `ContextBundle`；`ContextManager` 集中完成初始构建和 Retrieved/Tool 整体替换。
- `graph.py`：同步/流式 Graph 复用一组 prepare/retrieve/plan Node；prepare 后各 Node 只从 `context_bundle` 取得项目边界、当前消息、检索结果和 proposal，不再维护同义平铺字段。
- `llm.py`：同步与流式 responder 从 bundle 组合 Working + Retrieved Context；空 Summary 与 Tool proposal 不进入模型 messages。
- `api.py`：从最终 bundle 映射 conversation、request、sources 和 tool proposal，保持既有 JSON/NDJSON schema 不变。
- `apps/web/src/App.tsx`：新增显式 `formatComplete`；complete 前把真实 delta 作为可换行纯文本展示并移除开头的外层 fence 标记，complete 后才渲染 Markdown；应用动作清空 Wiki ID/version、从首个 H1 生成 title（无 H1 时使用“AI 整理文档”），后续保存固定走 create API。整理 prompt 同时要求一个明确的一级标题。
- `styles-v12.css`：为流式纯文本预览增加浅色、换行和溢出保护样式。
- 更新 Agent/Web 公共 seam 测试；没有 Java、数据库、公共 API 或依赖变更。

## 验证结果

- 门禁规划：仓库根执行 `.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -Paths <本记录列出的 22 个范围文件> -Json`，退出码 0；最终为 L3、AgentService/Docs/Governance/Web、Milestone Review，change fingerprint `97430556805f24856d2b2f297b018fa837a84786bd5ef1de3228bb960aa8d1d6`。`Governance` 来自节点专属测试策略文档，不改变通用治理规则。
- Context TDD：在 `services/agent-service` 执行 `.\.venv\Scripts\python.exe -m pytest tests/test_context.py -q`。首次因 `agentforge_agent.context` 不存在在 collection 阶段退出 1；建立 ContextBundle 后 1 passed、退出 0。加入 Graph bundle seam 后同命令以 1 failed/1 passed、退出 1 捕获 responder 收不到 bundle；最小接线后 2 passed、退出 0。
- LLM TDD：同目录执行 `.\.venv\Scripts\python.exe -m pytest tests/test_llm.py -q`，迁移测试先以 7 failed/6 passed、退出 1 捕获 responder 仍读取平铺字段；改为消费 bundle 后 13 passed、退出 0，并证明 Tool proposal 不进入模型 prompt。
- API/Trace 回归切片：同目录分别执行 `.\.venv\Scripts\python.exe -m pytest tests/test_api.py -q` 与 `.\.venv\Scripts\python.exe -m pytest tests/test_observability.py -q`。API 首次 5 failed/10 passed、退出 1，准确捕获 sources/proposal 映射和 test responder 漂移；修复后 15 passed、退出 0。Trace 首次 1 failed/11 passed、退出 1，捕获 usage responder fixture 未迁移；更新公共输入后 12 passed、退出 0。
- Web 缺陷红灯：在 `apps/web` 执行 `npm test -- --run tests/app.test.tsx -t "shows real formatting deltas|applies formatted text as a titled new wiki page"`。沙箱内因 esbuild 无权读取祖先目录在测试启动前退出 1；受控环境重跑后 2 failed、21 skipped、退出 1，分别证明 complete 前未闭合 fence 被渲染为 `<pre>`，以及应用后 title 仍是旧页面标题。实现后同命令 2 passed、21 skipped、退出 0。
- Title 约束 TDD：同目录执行 `npm test -- --run tests/app.test.tsx -t "applies formatted text as a titled new wiki page"`，新增“一级标题”请求断言后先 1 failed/22 skipped、退出 1；更新整理 prompt 后 1 passed/22 skipped、退出 0。
- Web 回归中的预期迁移：首次 `npm test -- --run` 为 26 passed/2 failed、退出 1；两个旧测试仍要求流式半成品成为 Markdown heading，与新的安全纯文本语义冲突。只更新公开 DOM 断言后重跑为 28 passed、0 failed/skipped、退出 0。
- 最终 Web：Node `v24.14.0`、npm `11.9.0`；Pi 建议修复前在 `apps/web` 执行 `npm test -- --run`，退出 0，3 files、28 tests 全部通过。S2/S3 测试命令 `npm test -- --run tests/app.test.tsx -t "applies formatted text as a titled new wiki page|uses the default wiki title"` 先以 2 failed/22 skipped、退出 1 捕获重复应用按钮仍可点击和代码块伪 H1；修复后 2 passed/22 skipped、退出 0。最终再次执行 `npm test -- --run`，退出 0，3 files、29 tests 全部通过，0 failed/skipped；执行 `npm run build`，退出 0，TypeScript/Vite 7.3.6 生产构建完成，283 modules transformed。npm 输出既有 unknown user config `home` warning；测试与构建未忽略该 warning。
- Agent 全量：Python `3.14.3`、pytest `8.4.2`；在 `services/agent-service` 首次执行 `.\.venv\Scripts\python.exe -m pytest -q --cache-clear`，46 passed/1 failed、退出 1，唯一失败为沙箱拒绝 Docker named pipe，产品断言未失败；受控环境原命令重跑 47 passed、0 failed/skipped、退出 0，真实 pgvector Testcontainers 通过。最终相关源码稳定后再次执行原命令，47 passed、退出 0。保留 3 个 warning：Starlette/AnyIO 与 422 常量弃用，以及既有 `.pytest_cache` ACL warning。
- Core API：Java `21.0.12.1`。在 `services/core-api` 首次执行 `.\mvnw.cmd clean verify`，Maven Wrapper 因沙箱网络/缓存权限在测试前退出 1；受控环境原命令重跑退出 0，重新编译 95 个主源码和 19 个测试源码，83 tests、0 failures/errors、7 skipped。7 项为环境变量控制的真实 Python 契约，随后单独全部实跑。
- Java → Python 契约：仓库根用本项目 `.venv` 隐藏启动 `python -m uvicorn agentforge_agent.main:app --host 127.0.0.1 --port 18000`，设置测试 token、RAG disabled、`AGENTFORGE_AGENT_CONTRACT_TEST=true`，在 `services/core-api` 执行 `.\mvnw.cmd -Dtest=AgentServiceHttpContractIntegrationTest test`；退出 0，7 tests、0 failures/errors/skipped，覆盖真实同步 JSON 与流式 NDJSON。进程按 PID 停止，临时 stdout/stderr 均删除。
- 清理：删除本轮 `apps/web/dist`、`services/core-api/target` 与 Agent `__pycache__`；精确 Testcontainers 容器查询返回空。`.pytest_cache` 因既有 ACL warning 保留且已被 Git 忽略。
- 静态与安全预检：仓库根对 22 个 V2-02 范围文件执行 `git diff --check -- <paths>`，退出 0；私钥头、Bearer credential 与 JWT literal 正则均 0 命中。执行 `git diff -- <paths> | docker run --rm -i zricethezav/gitleaks:v8.30.1 detect --pipe --no-banner --redact --exit-code 1`，退出 0，扫描约 72.12 KB，`no leaks found`。用户原有未暂存文档与未跟踪 DOCX 未进入扫描输入或审核暂存区。
- Pi 报告、状态回填和建议修复纳入后，仓库根执行 `.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Json`，退出 0；24 个暂存文件仍为 L3、AgentService/Docs/Governance/Web、Milestone，change fingerprint `a98b47b16845439b6d2171ab3b7e5bfc1a8d4279c20cc5a6906e9b8fa9b8c9bd`。最终凭据/调试标记正则 0 命中；完整 INDEX 通过 Gitleaks v8.30.1，约 82.75 KB、`no leaks found`。
- Pi Milestone Review：Attempt 1 使用 DeepSeek V4-pro，退出 0、结论 `PASS`，无必须修改项；Pi 不执行上述测试，也不能替代这些机器证据。Low 建议判断与处理见下文。
- 效率记录：本任务从首次 preflight 到最终验证约 3 小时 35 分钟，包含用户暂停；规划上下文约 20 份文档/源码入口，测试/构建命令 28 次（含红灯、环境失败与必要重跑），Pi 1 次。返工原因是旧流式 heading 断言与新安全预览语义冲突，以及采纳 Pi 指出的重复 create/伪 H1 风险；未拆成独立提交或逐问题审核。

## 风险与回滚

风险等级预估 L3：Agent 全局上下文和 LangGraph State 职责发生变化，同时触达 Web。主要风险是同步/流式路径漂移、项目上下文串用、来源或 Tool proposal 丢失、流式失败状态误应用，以及旧 Wiki 被覆盖。回滚时恢复旧 ChatState 字段和 Web apply 行为；无数据库迁移或业务数据回滚。

## Pi Attempt 1 判断

- 结论 `PASS`，无必须修改项；报告为 `docs/08-reviews/2026-09-07-review-v2-02-context-manager-attempt-1.md`。
- S1 不修改：Context Manager 与两个 Web 缺陷是用户明确要求同批处理的范围，Start Gate、变更记录和测试域均已显式拆分；它不提前实现 V2-03/V2-04，也不需要为了形式再拆提交。
- S2 成立并修复：应用完成结果后按钮仍可再次点击，保存后重复应用会清除新页面 identity 并造成重复 create，属于可复现的数据一致性陷阱。增加一次应用后禁用状态和 DOM 回归测试。
- S3 成立并修复：简单 multiline 正则会命中 fenced code block 内的 `# comment`，偏离“缺少真实 H1 时使用默认 title”的目标。标题解析改为逐行跟踪 fence，只接受 fence 外 H1，并补充 DOM 回归测试。
- S2/S3 是同一 Web 应用状态边界内的 Low 建议，按现行规则在一次批量修复后只重跑受影响 Web 测试/构建与安全扫描；没有阻断 finding，不启动 Pi Attempt 2。
- 最终 `git diff --cached --check` 首次因 Pi 生成报告中的 3 处 Markdown 行尾双空格退出 2；只移除报告行尾空格后重跑，不改变审核内容或结论。
