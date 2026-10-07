# 跨轮次审批恢复重放修复

- 日期：2026-10-06
- 状态：Implemented
- 风险：L3（审批幂等、Agent 状态机）；影响域 Agent Service、Java/Python 恢复契约。
- 基线：origin/main = ab94cb8c7d2f2f43b5a652e21d1b1f8fb764452b。
- 分支：codex/fix-action-replay-history；复用本聊天干净审核工作树。

## 背景与目标

用户授权修复简单审核确认的 P1：Java 已提交 APPROVED，Python Resume 已提交 RESUMED，但恢复响应丢失或随后 Java 业务事务失败；同一会话下一轮 Chat 覆盖最新 checkpoint 后，旧审批以原 key 重试返回 409，不能继续业务执行。前轮已通过真实 PostgreSQL 和内部 HTTP 复现，但本次必须重新运行红绿与回归。

## 范围与目标文档

先更新 agent-runtime 功能、Agent Service API、ADR-0037 并新增 ADR-0043，明确历史恢复事实只读重放。实现只在 Python Resume 找不到当前匹配 workflow 时，在同一完整 Namespace 的 checkpoint 历史中查找该 workflow 已完成的 RESUMED 状态，校验 schema、Namespace、action、decision、key 后返回原事实；不调用历史状态的 invoke，不修改当前 WAITING/ABORTED/RESUMED，也不由 Python 执行业务。

## 测试与授权边界

用户已批准修复上轮通过运行时公开接口和内部 HTTP 复现的缺陷。沿用这些已确认公共 seam，增加真实 PostgreSQL 的重启/多轮重放和 Java HTTP client 跨进程契约，使用项目 TDD 技能逐个纵向切片实际红灯后实现。风险规划要求 L3 Milestone、Python 模块全量、Java clean verify 与跨进程 smoke。本次为既有缺陷修复，不开启新的 V2/V3 Node。

## 非目标与限制

不新增端点、字段、迁移、依赖、业务权限或部署行为；不修复此前建议，不实施下一路线图 Node。v1 无 workflow ID 继续只支持当前轮恢复。历史查找按 checkpoint 时间倒序分页读取（每页最多 64 条），旧轮重放成本随会话历史增长；不增加未经验证的索引/保留清理方案。Java 继续重新授权并保证业务写入幂等。

## Git、审核与交付计划

隔离工作树初始 staged/unstaged/untracked 均为空；根工作树用户已有改动完全保留。先文档、后失败测试和最小实现，再运行相称门禁、清理和 Gitleaks，调用指定 deepseek/deepseek-flash 一次只读 Milestone。只处理证实阻塞项。回填证据后提交并非 force 推送 origin/codex/fix-action-replay-history、核验 SHA；不直接改写 main。

## 验证回填

### 当前源码与红绿

- 2026-10-06 初次执行被自动审批服务额度中断；文档和红灯测试保留。2026-10-07 用户明确继续，恢复命令成功，HEAD/main 仍为原基线，未重置已有工作。
- 工作目录为本聊天 `main-quick-review/AgentForge` 独立 worktree；使用原工作树 venv 的依赖，但所有 pytest 和子进程显式以本 worktree `services/agent-service/src` 为 PYTHONPATH，避免 editable 源码串到旧分支。
- 根目录红灯命令：`python -m pytest -q -p no:cacheprovider services/agent-service/tests/test_action_runtime.py -k old_resume_replays`（python 为原仓库 `.venv/Scripts/python.exe`，PYTHONDONTWRITEBYTECODE=1）。exit 1；APPROVE/REJECT 两例均在旧 workflow 校验抛出 identity mismatch，2 failed / 13 deselected。真实 PostgreSQL 隔离容器自动清理。
- 最小实现后根目录命令：`python -m pytest -q -p no:cacheprovider services/agent-service/tests/test_action_runtime.py`；exit 0，15 passed。补齐身份/决定/key/未知/已补偿轮次与长历史分页后，同一命令 exit 0，26 passed。
- 生产改动仅 34 行：当前轮 mismatch 才查询同 Namespace 的持久历史，只重放已终结 RESUMED，继续执行既有 schema/Namespace/workflow/action/decision/key 校验；不调用旧 checkpoint，不修改最新状态。
- 当前 LangGraph `get_state_history` 实现会 eagerly load 查询结果；采用 `limit=64`、`before` 游标逐页读取，将内存占用限制到单页，兼容已有 v2 历史。长历史测试通过 25 轮已完成操作再重放第一轮验证分页。

### Python 模块全量

- 工作目录 `services/agent-service`；命令：`python -m pytest -q -p no:cacheprovider --basetemp ../../.data/action-replay/pytest`，设置 PYTHONPATH 为当前 src、PYTHONDONTWRITEBYTECODE=1、NO_PROXY=127.0.0.1,localhost。
- exit 0，246 passed，0 failed/error/skipped，5 warnings，67.90 秒。warnings 为 AnyIO BlockingPortal 别名、Starlette 422 常量（3 例）以及 Pydantic TypedDict ReadOnly 提示。

### Java 全模块

- 工作目录 `services/core-api`；命令：`.\mvnw.cmd -B clean verify`。exit 0，BUILD SUCCESS；242 tests，0 failures/errors，12 条件式 skips，2 分 56 秒。未使用旧 target。
- 12 skips 为 9 项 Agent HTTP contract、1 项 Repository citation、1 项 Graph live Python、1 项 Resolution advisor live Python；其中 Agent/Graph/Resolution 的 11 项由下一步 Java/Python runner 显式开启，Repository citation 不属于本次修改范围。
- 保留环境与测试日志限制：JDK CDS/ByteBuddy 动态 agent warnings；MCP 无效参数测试产生预期校验 warnings。`GraphDependencyIsolationTest` 2 项通过后，其缓存 Spring 上下文中的调度器仍访问已退出的 PostgreSQL 测试容器，出现 Hikari closed-connection warnings 和一条 scheduled-task connection timeout error。已通过该测试的 JDBC 端口及 HikariPool-3 时间线定位；不是断言失败，本次未修改该测试或图调度器，也不将其隐藏为“无告警”。

### 跨进程、版本与清理

- 仓库根目录命令：`.\scripts\validation\v3-release-regression.ps1 -Only java-python-contracts`。显式 PYTHONPATH=当前 worktree/services/agent-service/src、PYTHONDONTWRITEBYTECODE=1、NO_PROXY=127.0.0.1,localhost；临时 Junction 仅复用原 venv 依赖。
- exit 0，runner 为 PASS_PARTIAL（本次只选择该影响域，并非全发布回归）。当前 Surefire XML：AgentServiceHttpContractIntegrationTest 9、GraphResolutionAdvisorContractTest 3、GraphApiIntegrationTest live Python 1，合计 13/13，0 failures/errors/skips。Java 实际调用真实 Python/持久 PostgreSQL，验证旧轮响应丢失后的同 key 重放返回原结果、错误 key 409，以及新轮仍能独立拒绝。
- 版本：Git 2.23.0.windows.1、Python 3.14.3、OpenJDK 21.0.12.1、Maven 3.9.11、Docker 29.5.3、Gitleaks v8.30.1。根目录调用模块 Maven wrapper 查询版本曾因相对路径失败；在 core-api 目录重新查询成功，不影响已完成的测试。
- runner 已停止专用 Python 子进程并删除 Compose project `agentforge-v309-contract-952330a7` 的容器/网络/卷；Docker 标签查询无残留。本轮 Testcontainers 容器均退出。
- 模块目录 `.\mvnw.cmd -B clean` exit 0；核验目标后删除仅本轮创建的 venv Junction（保留原 venv）、`.data/action-replay` 临时日志/pytest 数据。没有改动根工作树用户文件。

### 风险、扫描与审核

- 最终影响域 Docs/AgentService/CoreApi；L3 Milestone。根目录 `.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef INDEX -Milestone -Json` 输出 fingerprint `b0f46d5926a692ee4ef755fa81b9f37af8acb45cc7f27046ab768be2f7b93182`。正式审核前 INDEX 指纹为 `32c0531ace5482b283e461f58531936c8392b92a3957f8ce000b539b0451325f`；之后仅回填审核报告与文档完成状态，不把含自引用证据的指纹伪称为最终提交哈希。
- `git diff --cached --check` 初次发现变更记录 EOF 多余空行，修正后 exit 0。Git CRLF→LF 提示属仓库行尾规范化。
- `git diff --cached --no-ext-diff HEAD | docker run --rm -i --network none zricethezav/gitleaks:v8.30.1 stdin --redact --no-banner` 首轮扫描 29.07 KB，exit 0；必要上下文（Java WorkflowService、Python Runtime、功能契约）另通过同一 stdin 扫描 27 KB，exit 0。最终补录文档后再扫。
- Pi 预检精确 provider/model `deepseek/deepseek-flash` 可用。2026-10-07 尝试以 `run-review.ps1 -StageName action-resume-history-replay -BaseRef HEAD -TargetRef INDEX -ReviewMode Milestone -ContextFiles <已扫描的 WorkflowService/Runtime/功能契约> -Attempt 1 -TimeoutSeconds 600` 启动审核，自动审批在进程执行前拒绝，理由为向外部 DeepSeek Pi 发送仓库源码需用户针对目的地及内容明确授权。没有向 Pi 发送本次代码，没有生成报告；未改用入口、未重试绕过。
- 随后用户明确回复“同意”，授权本次向指定目的地发送上述已扫描材料。再次扫描 33.40 KB，exit 0；正式 Attempt 1 执行成功，exit 0，Pi 返回 PASS。此前拒绝发生在执行前，不计作已执行的审核轮次。
- 未改 Web/部署/依赖/schema，不重复无关 Web、TLS 和完整发布门禁；Repository citation 独立跨进程用例保持条件跳过，Python repository 测试已随全量执行。旧轮查询时间随历史增长；v1 继续只允许当前轮兼容恢复，均已写入 ADR。


### Pi 判断与交付

- 命令：`run-review.ps1 -StageName action-resume-history-replay -BaseRef HEAD -TargetRef INDEX -ReviewMode Milestone -ContextFiles <WorkflowService/Runtime/agent-runtime> -Attempt 1 -TimeoutSeconds 600 -OutputFile docs/08-reviews/2026-10-07-review-action-resume-history-replay-attempt-1.md`，使用固定 `deepseek/deepseek-flash`。发送 10 文件 diff、已扫描必要上下文与结构化测试摘要，未发送凭据或完整日志。
- PASS，无必须修改项。S1 为 ADR 索引排布建议：链接有效、文档可达，按只处理阻塞项约束仅记录，不扩张修复。S2 为跨日日期建议：文件日期是 10-06 立项日，10-07 续作与验证已明确记录，保留可追踪时间线。N1 历史线性成本、N2 原 requestId、N3 旧测试改为错误 action ID 均与契约和现行回归一致，无需改实现。无 Attempt 2。
- 审核后无生产代码、测试、配置、依赖或 base 变化，本次已通过机器证据仍有效。完成状态、审查评估及最终敏感扫描属于交付回填。
- 修复提交前再次核验 main 仍为原基线，远端尚无同名修复分支。仅向 origin/codex/fix-action-replay-history 非 force 推送并核验 SHA，不合并 main、不部署生产；精确提交与远端结果由不可变提交完成后的交付汇报给出。

- 最终审核报告的行尾空白已清除；对包含报告和评估的完整 staged diff 再执行 diff check 与 Gitleaks，扫描无敏感命中。Pi 本轮运行日志已清理，审查报告保留入库。
