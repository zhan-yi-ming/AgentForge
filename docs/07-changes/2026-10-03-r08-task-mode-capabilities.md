# R08 任务模式能力隔离

- 状态：Completed
- 日期：2026-10-03
- 风险：L3（Tool/审批能力边界、跨服务契约）
- 影响域：Agent Service、Core API、Web 回归、Docs

## 问题确认

深层审查 R08 成立。taskType 当前只选择回答模型，所有模式仍经过 Tool planner。FORMAT/REWRITE/REVIEW 只要被确定性规则、模型误分类或输入注入诱导出 proposal，Python 就会建立 WAITING checkpoint，Java 随后保存 PENDING Action；Web 的格式化视图却只消费 answer，导致不可见审批和删除阻塞。

## 目标与边界

- 明确服务端能力矩阵：ANSWER、PLAN 可提出受 Java 审批保护的写入 Intent；FORMAT、REWRITE、REVIEW 仅生成文本，不得规划 Tool。
- Python 在 planner 上游按可信请求 taskType 跳过 Tool planner，因而不建立 Action checkpoint。
- Java 不能只信任 Python；受限模式若收到异常 proposal，必须精确 Abort 对应等待轮次、丢弃 proposal，且不得创建 Action/Task。
- 同步与流式行为一致；正常 ANSWER/PLAN Tool 流程保持不变。
- Web 仍不把格式化任务接入审批 UI，但测试改为断言后端契约，而不是把意外 pendingAction 静默当成正常结果。

## 验证计划

- Python 内部 `/internal/v1/chat` 与 `/chat/stream` seam：注入总是返回 CREATE_TASK 的 planner，先证明 FORMAT 会调用 planner并返回 proposal，再验证 FORMAT/REWRITE/REVIEW 均不调用 planner、不建立 WAITING；ANSWER/PLAN 继续返回 proposal。
- Java `AgentChatService` seam：伪造受限模式 proposal，验证同步/流式都调用精确 Abort 且从不调用 `createPending`；ANSWER/PLAN 既有正向测试继续通过。
- Web 保留 FORMAT taskType 与格式化状态隔离回归，并移除“忽略异常审批即成功”的错误契约夹具。
- 最终运行门禁规划、Python/Java/Web 相关测试与全域相称回归、构建、diff check、敏感扫描和 Pi Milestone Review。
- 跨进程门禁若因实际测试数与硬编码期望漂移而失败，只在核对 Surefire 明细确认所有目标类零失败、零错误、零跳过后同步计数，并重新运行真实跨进程门禁；不得降低断言强度。

## 验证回填

- TDD 红灯：Python FORMAT seam 在实现前返回 CREATE_TASK proposal；Java FORMAT seam 在实现前调用 `createPending`，两项均按预期失败。最小实现后，Python 只读模式同步/流式矩阵 7 项通过；Java `AgentChatServiceTest` 22 项通过。
- Agent Service 全量 `pytest -q` 使用工作树源码和隔离 basetemp：219 passed，4 个既有依赖弃用/TypedDict warning；首次并行尝试受系统 Temp ACL 和回环代理干扰，不计作通过证据，串行重跑退出 0。
- Core API `mvnw.cmd clean verify`：234 tests，0 failures，0 errors，12 skipped，BUILD SUCCESS；skip 为独立跨进程开关用例，随后由真实门禁覆盖。测试日志保留既有 Mockito 动态 agent、已关闭 Testcontainers 连接的定时线程 warning，不隐藏。
- Web `npm test -- --run`：6 files、79 tests 全部通过；`npm run build` 成功。仅修正错误测试夹具，生产 Web 无改动。
- `v3-release-regression.ps1 -Only java-python-contracts` 首次真实执行的 13 项全部为零失败/错误/跳过，但脚本仍硬编码 12 而失败；核对三份 Surefire 明细为 9 + 3 + 1 后，将期望值同步为 13，重跑得到 `PASS_PARTIAL: 1 stage(s) completed`，且 PostgreSQL/Agent 进程、网络、卷和临时 venv 链接均清理。
- 最终路径规划：L3，影响域 AgentService/CoreApi/Docs/Web/Unknown（验证脚本），Review 为 Milestone；要求 cross-process-smoke、diff-check、docs-consistency、gitleaks-final、java-clean-verify、manual-impact-review、python-test、web-core-contract、web-test；fingerprint `9b291d53d2f72a46179ea51ce341a5e06b91b8cded5f48e1f69cd5c853c3e0c6`。
- 手工影响审查确认验证脚本仅把实际测试总数 13 同步到严格等值断言，没有放宽 failures/errors/skips；目标文档与同步/流式代码、正负向测试一致。`git diff --check` 退出 0。
- 首次暂存差异约 29.98 KB，经 `zricethezav/gitleaks:v8.30.1 detect --pipe --redact` 扫描退出 0、no leaks found；纳入 Pi 报告和最终回填后的完整暂存差异约 43.78 KB，再次扫描退出 0、no leaks found。
- Pi DeepSeek V4.1 Flash Milestone Review Attempt 1：`PASS`，无阻塞项或必须修改项。SUG-01 会重新固化前端静默忽略隐藏 Action 的错误契约，驳回；SUG-02 在 `AgentChatRequest`/`AgentChatCommand` null 正规化后无生产可达路径，驳回；SUG-03 为已有双路径与 Python 全矩阵之外的同义测试广度建议，延后。完整判断见 `docs/08-reviews/2026-10-03-review-r08-task-mode-capabilities-attempt-1.md`，纯建议不触发复审。
