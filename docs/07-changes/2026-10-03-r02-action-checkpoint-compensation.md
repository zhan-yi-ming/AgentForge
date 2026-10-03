# R02：补偿未落地的 Action checkpoint

- 状态：Verified（待提交）
- 日期：2026-10-03
- 基准：`codex/main-review-fixes` / `2dab22eaf4b14e2eca1d4ebd489492e703267e31`
- 风险：L3（跨服务状态机、一致性、内部 HTTP 契约）
- 影响域：Agent Service、Core API、Action workflow、同步/流式 Chat、文档

## 背景与已确认问题

外部审查 R02 指出 Python 在返回 proposal 前先持久化 WAITING checkpoint，Java 随后才校验并保存 PENDING Action。当前代码确认该顺序存在：`AgentChatService` 对 `createPending(...).orElseGet(result::withoutToolProposal)` 不做 checkpoint 补偿；流式 `finalizeEvent` 同样把空结果降级为无 Action。模型若给出空白 description，Python planner 接受而 Java `normalizeText` 拒绝；Task version 变旧或 Java 事务失败也会留下没有审批票据的 WAITING，下一请求被阻断。

## 目标

- Python 与 Java 对 title/description 的空白和长度语义一致，已知无效 proposal 不创建 checkpoint。
- Java 收到 proposal 后若校验拒绝、版本冲突或 Action 事务失败，调用只针对该 Namespace/request/workflow 的补偿接口，把 WAITING 终结为 ABORTED。
- 同步和流式入口使用同一补偿逻辑；补偿失败不得静默伪装成普通回答。
- 新会话的 conversation ID 在 Core 内按 project/user/request ID 确定性派生，使响应丢失后使用相同 `X-Request-Id` 的重试仍命中同一 Namespace；已落地 Action 继续由 R01 workflow ID 幂等复用。

## 非目标

- 不让 Python批准、拒绝或执行 Tool；ABORT 只表示本轮 proposal 未形成 Java Approval。
- 不删除 checkpoint 历史，不补偿已 RESUMED 的 workflow，不清除 request/workflow 不匹配的另一轮。
- 不处理 R03 管理员代审批身份、R06 刷新恢复 UI 或其它审查项。
- 不为普通无 Tool 回答新增完整的跨服务 exactly-once 会话历史协议。

## 公共测试 seam 与设计

1. `ToolProposal` 规范化非空 title/description 并限制 Java 同值长度；自然语言 planner 对空白 description 返回无 proposal。
2. `ActionWorkflowRuntime.abort(namespace, workflow_id?, request_id)` 在 workflow 锁内校验 schema、完整 Namespace、request ID 与可选 workflow ID；仅 WAITING 可转 ABORTED，相同 abort 可重放，RESUMED 或其它轮次冲突。
3. 新增内部 `POST /internal/v1/agent/abort`；只接受 Core 内部 token，返回 conversation/workflow、`status=ABORTED` 与 request ID，不携带业务 Action 决定。
4. Core `AgentChatService` 封装 create-or-abort：空 Optional、版本冲突和持久化异常都先补偿精确轮次；补偿成功后保持原有成功/冲突语义，补偿失败返回依赖不可用而不是隐藏不一致。
5. 新会话 ID 由 project/user/request ID 确定性派生；同步与流式在调用 Python 前均已知道补偿 Namespace。相同请求 ID 重试得到同一 conversation/workflow/Action，不同请求不能误清除当前轮次。

## 计划验证

- Python 先红后绿：空白 description；WAITING abort 后可建立新轮次；错 request/workflow 不能 abort；abort replay；已 RESUMED 不能 abort；真实 PostgreSQL 并发边界。
- Java 先红后绿：同步/流式 normalize 拒绝、Task version 冲突、repository 异常均调用精确 abort；abort 失败不静默；无 conversation 的同 request 重试使用同一 ID。
- 内部 HTTP 真实契约、Python 全量、Java clean verify、跨进程故障注入与重试、diff/敏感扫描、Pi Milestone Review。

## 门禁规划

预计路径 planner 退出 0：L3 / AgentService、CoreApi、Docs / Milestone Review；最低门禁 `python-test`、`java-clean-verify`、`cross-process-smoke`、docs/diff/Gitleaks；fingerprint `a94e2a3af040e6764c98f471825496606749850bee65af1c39c553f4fbd6db30`。

## 验证回填

### 实现

- Python `ToolProposal` 统一 trim 及 title 1–200 / description 1–2,000 字符边界；确定性与模型 planner 对无效文本都安全返回无 proposal，不建立 WAITING。
- workflow v2 增加 `ABORTED`；`ActionWorkflowRuntime.abort` 在现有 workflow 锁中核对完整 Namespace、原 chat request ID 与可选 workflow ID，只允许当前 WAITING 转换，且相同 Abort 可重放。
- 新增受内部 token 保护的 `POST /internal/v1/agent/abort`，404/409 保持失败关闭；Core HTTP client 将其映射为冲突或依赖不可用。
- Core 同步与流式入口共用 create-or-abort：Java 校验返回空、Task version 冲突或事务异常均精确补偿；Abort 失败显式失败。新会话在调用 Python 前由 project/user/request ID 稳定派生 conversation ID，同请求重试命中同一 Namespace。

### 红灯与回归证据

- Python 公共 HTTP 红灯：201 字符 title 原先返回 proposal 并留下 WAITING，测试退出 1；字段约束和安全规划后通过，紧接的合法新轮次可建立。
- Python runtime 红灯：新增 Abort seam 时因方法不存在失败；实现后覆盖精确 request/workflow、缺失 workflow ID、重放、RESUMED 拒绝和新轮次。
- Java 红灯：新增补偿客户端契约前编译失败；新增会话稳定性测试实际得到 `[null, null]` 而失败。最小实现后同步、流式、版本冲突、数据库写失败、无效 proposal、Abort 失败及响应丢失重试测试通过。
- Python 全量：项目 Python 3.14.3，`PYTHONPATH=src`、localhost 绕过代理、工作树内 basetemp 执行 `pytest -q`，退出 0，`210 passed`、4 个既有 Starlette/AnyIO/Pydantic warning。
- PostgreSQL checkpoint：定向容器测试退出 0，`1 passed`；ABORTED 跨 runtime 重建可重放，并允许后续新轮次。
- Core clean verify：Java 21.0.12.1，退出 0，`208 tests`、0 failures/errors、12 skipped；skip 为环境开关控制的 9 项真实 Agent 契约及其它既有可选契约。日志保留 Mockito 动态 agent warning、测试故意产生的持久化/图同步 warning，以及旧 Spring 测试上下文停止后调度线程访问已关闭容器的 warning，均未造成失败。随后仅新增数据库异常单测，定向 `AgentChatServiceTest` 再次退出 0。
- 真实 Java→Python 契约：一次性 PostgreSQL/schema 与当前源码 uvicorn 下显式开启开关，`AgentServiceHttpContractIntegrationTest` 为 `9 tests`、0 failures/errors/skipped；新增用例验证真实 WAITING → ABORTED → 下一轮 WAITING。第一次准备因容器初始化重启窗口未建 schema、第二次因 Maven wrapper 工作目录错误，均在测试执行前失败且 finally 清理；修正真实查询就绪条件和工作目录后通过，未把环境失败计作通过。
- 验证回填前的 INDEX planner：退出 0，L3 / AgentService、CoreApi、Docs / Milestone Review；门禁为 Python、Java clean verify、cross-process smoke、docs/diff/Gitleaks；fingerprint `eebb2d814cb9c7cff7c6afc9fd024097b3cda397660bddc864a483c10b87a12f`。最终暂存内容在 Pi 前再次运行同一 INDEX 规划器并保持相同风险、影响域和门禁集合。
- `git diff --cached --check` 与目标文档/ADR 索引一致性检查退出 0。系统 PATH 没有原生 Gitleaks；复用本机已有 `zricethezav/gitleaks:v8.30.1` 镜像扫描 `HEAD..INDEX` 约 69.03 KB，退出 0、no leaks。全目录预扫命中 18 个仓库既有 fixture/历史样例，未误记为本次泄露或通过证据。
- 测试进程、一次性 PostgreSQL 容器与临时日志均已清理；原工作区用户修改始终未进入隔离 worktree。

### 待完成门禁

- Pi DeepSeek V4.1 Flash Milestone Review、审查结论回填、提交与远端核验。

### Pi Milestone Review Attempt 1 评估

Pi 使用 `deepseek/deepseek-flash` 对已扫描 INDEX 做一次只读审核，结论 `NEEDS_FIX`，报告为 `docs/08-reviews/2026-10-03-review-r02-action-checkpoint-compensation-attempt-1.md`。Codex 逐条判断如下：

- `R02-01` 同意核心问题：流式成功测试只断言 `pendingAction=null`，确实不能证明 `complete` 已发出；补充 service 失败传播和 Controller SSE `error` 终态测试，并强化成功事件序列。错误事件保持在既有 Controller 边界，不下沉到 application service。
- `R02-02` 同意文档不一致：保留 Python 404/409 → 公共 409 的实现，明确只有网络/5xx/无效响应为 503。
- `R02-03` 不纳入本次：既有 conversation 的相同请求重试原本也会重复展示历史，ADR-0038 已明确普通回答历史 exactly-once 为非目标；Action/Approval 已由 workflow ID 幂等保护。为避免扩散到原本无缺陷的 Conversation 模块，本次不新增数据库迁移。
- `R02-04` 同意测试盲区：把 RESUMED 用例改为匹配恢复后的 request ID，确保实际命中状态守卫；补 Abort 后相同 chat request 重新建立 WAITING 的测试。
- `R02-05` 同意防御性建议：显式与自然语言 planner 共用捕获 Pydantic `ValidationError` 的安全构造器，避免未来约束漂移变成 500。
- `R02-06` 无需修改。

仅修复上述已确认阻断项与直接相关的低风险契约/测试问题后，按规则运行受影响定向测试、diff 与敏感扫描；若没有生产范围扩散，不重复已经通过且输入未变化的 Python/Java 全量与真实跨进程门禁。真实阻断项修复后执行一次 Pi Attempt 2 复审。

Attempt 1 修复验证：共享安全构造器测试先因 import 不存在真实红灯，完成最小实现后与同 request Abort 重试、RESUMED 守卫共 `4 passed`；审查后 Python 生产代码变化触发全量重跑，`212 passed`、4 个既有 warning。Java 新 SSE 测试第一次因 MockMvc 默认输出与虚拟线程并发读取响应头出现 `ConcurrentModificationException`，不计为产品失败；以 latch 固定测试时序后 `AgentChatServiceTest,AgentChatApiTest` 共 30 tests、0 failures/errors/skipped。Java 生产源码未变化，先前 clean verify 与真实 Java→Python 契约证据继续有效。

Pi Attempt 2 对 22 个文件和上一轮修复复审，结论 `PASS`、无必须修改项，报告为 `docs/08-reviews/2026-10-03-review-r02-action-checkpoint-compensation-attempt-2.md`。建议 R02-07 经代码与 PostgreSQL 集成测试确认已由同 workflow advisory transaction lock 串行化并幂等返回同一 Approval，无竞态缺陷；R02-03 保持明确非目标。R02-08/09 是无失败复现的补充测试建议，按只修真实缺陷的规则记录但不继续扩张，也不触发第三轮审核。

最终交付前仅剩：暂存 Attempt 2 报告与本段回填，重跑 diff/INDEX planner/scoped Gitleaks，核对 staged-only 范围，创建可读提交并非 force 推送核验。
