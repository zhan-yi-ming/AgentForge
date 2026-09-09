# V2-09 Regression Test + V2 Release Gate

- 日期：2026-09-09
- 状态：Implemented
- 阶段：V2-09
- 交付目标：`codex/v2-09-regression-release` / `origin`
- 基线：`03b9117842d1f40ddfb600bdd6ceed5c80a25a88`
- Start Gate：用户已于 2026-09-09 确认节点 Scope、公共测试 seam，并追加 README 面试项目叙事重构。

## 背景

V2-01 至 V2-08 已分别实现 Context Engineering、集中式 Tool Policy、HITL、幂等与审计、可恢复 LangGraph Runtime、Trace 和 Evaluation，但现有证据按节点分散，尚未形成一次可重复执行的 V2 Release Regression。公开 README 也以版本清单和知识问答为主，不能让首次访问者快速理解这是一个解决 Agent 落地工程问题的工作流项目。

## 目标

- 建立统一、失败关闭、可重复运行的 V2 Release Regression 入口。
- 覆盖普通问答、RAG、Create/Update Task、High-risk Approval、Reject、Duplicate Request、Agent Retry、Service Restart/Resume、Cross Project、Unauthorized、Long Conversation、Trace 和 Evaluation。
- 只在已确认的公共 seam 补测试；每个缺口按 TDD red → green 的纵向切片推进。
- 重构 README 第一屏和主体叙事，优先展示 `研发输入 → Context/Retrieval → Tool Intent → Risk/HITL → Java 确定性执行 → Audit/Trace/Evaluation`，不把项目包装成普通知识库或技术名词集合。
- 完成全仓回归、敏感扫描与 Pi V2 Release Milestone Review；所有门禁真实通过后才允许创建 `v2-stable` 标签。

## 非目标

- 不新增业务功能，不提前实现 V3-01 MCP、GraphRAG、Model Gateway 或 Multi-model Routing。
- 不改变 Java 业务执行 / Python Agent 的确定性边界。
- 不为 README 编造 GIF、Trace 截图、性能指标或线上质量结论；没有真实媒体资产时使用可核验流程图和已有测试证据。
- 不进行生产部署，不主动修改数据库 Schema 或公共 HTTP 契约。

## 受影响文档

- `README.md`：面向 HR / AI 应用研发面试官重构首屏、核心链路、工程问题、架构、证据和运行入口。
- `docs/01-product/v2-v3-node-roadmap.md`：在开发期间把 Current Node 更新为 V2-09，Close Gate 后标记 V2-01 至 V2-09 completed，并明确 V3-01 等待新授权。
- `docs/03-features/README.md`：记录 V2-09 Release Gate 已完成。
- `docs/05-development/testing-strategy.md`：定义 V2 Release Regression 的公共 seam、覆盖矩阵和完整门禁。
- `docs/07-changes/README.md`：加入本记录索引。

## 设计决定

- Release runner 只编排仓库现有公共测试/构建/Smoke/Evaluation 入口，并对每一步的退出码失败关闭；不复制各模块内部断言。
- 默认测试凭据运行时随机生成，日志只输出阶段和脱敏摘要；不得读取或打印仓库 `.env` 中的真实值。
- README 首屏先解释问题和完整 Agent 工作流，再展示技术实现；RAG 是 Context 来源之一，不是产品定义。
- 当前仓库没有可公开 GIF 或截图，因此本节点不放占位媒体或伪造效果图。后续只有在真实录制并完成敏感信息检查后才加入首屏 GIF。

## 已确认公共测试 seam

- Core `/api/v1` REST/SSE 与 Java application service。
- Agent `/internal/v1` HTTP/NDJSON、Context/Memory/Action Runtime 公共接口。
- Java → Python 跨进程审批、恢复和最终业务结果。
- Web DOM 与 typed API client。
- Evaluation CLI、版本化 dataset/report。
- Compose 与 `scripts/validation` 公共入口。

## 实现

- 新增 `scripts/validation/v2-release-regression.ps1`：提供 `-Plan`、`-Only` 和完整默认执行三种公共入口，按固定顺序编排 11 个失败关闭阶段；每个跨进程阶段独立生成端口、Compose project 和随机测试凭据，可从任意失败点安全重跑。
- 新增 `scripts/validation/test-v2-release-regression.ps1`：从 CLI seam 验证 L3/fail-closed 计划、11 阶段顺序、隔离/恢复覆盖声明、单阶段执行和未知阶段拒绝。
- 修正 `day4-e2e.ps1`：RAG 删除生命周期 fixture 在删除前把本轮测试用户提升为 ADMIN 并重新登录，适配 V2-05 的 HIGH-risk delete 权限，不放宽生产策略。
- 修正 `day5-e2e.ps1` 与 `v1-acceptance.ps1`：所有 confirm/reject 带合法 `Idempotency-Key`，同一动作 replay 复用同一个 key，适配 V2-06 公共契约。
- Evaluation 阶段将临时真实报告的 dataset SHA-256 和全部 metrics 与已提交 V2 基线逐项比较；临时报告无论成功失败均删除。
- README 第一屏改为真实 Agent workflow，增加工程问题/实现/证据映射、Java/Python 信任边界、Implemented/In Progress/Planned 状态和克制的生产边界；没有媒体资产时不伪造 GIF。

Release coverage 不复制模块内部断言，而是映射到可追溯的 stage：Ordinary Chat/RAG 与 Cross Project 由 `rag-cross-process` 承载；Create/Update、HITL、Reject、Unauthorized 和 replay 由 `tool-hitl-cross-process` 承载；restart/resume 由 `restart-resume-cross-process` 承载；Long Conversation/Token Budget 与 Trace 分别由 `python-test` 中的 `test_context.py`/`test_llm.py` 和 `test_observability.py` 承载；Evaluation 由 `evaluation` 承载；公开 API 主链路由 `full-stack-acceptance` 再验证。

## 验证结果

本节点固定为 L3 Release Gate，不裁剪全仓验证。工具版本：Java Temurin 21.0.12.1、Python 3.14.3、pytest 8.4.2、Node 24.14.0、npm 11.9.0、Docker Client/Server 29.5.3。

- 初始规划：仓库根目录执行 `.\scripts\validation\plan-change-gates.ps1 -BaseRef HEAD -TargetRef WORKTREE -ReleaseGate -Paths @('README.md','docs/01-product/v2-v3-node-roadmap.md','docs/03-features/README.md','docs/05-development/testing-strategy.md','docs/07-changes/README.md','docs/07-changes/2026-09-09-v2-09-regression-release.md','scripts/validation/v2-release-regression.ps1') -Json`，退出码 0；结果 L3、Release/Docs/Governance/Unknown、Milestone Review、完整全仓门禁，初始 fingerprint `180e685cbba9ed2809278bb4829fd9fb7631c4869139c51abfe7a1a66cc5c2d8`。
- TDD red 1：仓库根目录执行 `.\scripts\validation\test-v2-release-regression.ps1`，退出码 1，准确报告 `v2-release-regression.ps1` 不存在；加入最小 `-Plan -Json` 后同命令退出 0，11 个有序阶段计划契约通过。
- TDD red 2：为单阶段执行和未知阶段失败关闭增加断言后，再执行 `.\scripts\validation\test-v2-release-regression.ps1`，退出码 1，准确报告缺少 `-Only`；加入最小执行入口后同命令退出 0。最终脚本变化后再次执行，退出码 0，11 阶段与 fail-closed selection 均通过。
- PowerShell parser：仓库根目录对 `v2-release-regression.ps1`、`test-v2-release-regression.ps1`、`v1-acceptance.ps1`、`day4-e2e.ps1`、`day5-e2e.ps1` 调用 `System.Management.Automation.Language.Parser.ParseFile`，退出码 0，5 个脚本无 parser error。
- 首次完整 runner：仓库根目录执行 `.\scripts\validation\v2-release-regression.ps1`，退出码 1；gate planner 15 项与 local/production Compose config 已通过，但沙箱禁止 Maven Wrapper 网络连接，Java 在测试前以 `SocketException: Permission denied: connect` 停止，未记作产品 PASS。
- 首次受控回归：仓库根目录执行 `.\scripts\validation\v2-release-regression.ps1 -Only java-clean-verify,python-test,web-test,web-build,rag-cross-process,tool-hitl-cross-process,restart-resume-cross-process,evaluation,full-stack-acceptance`，退出码 1；Java 108 tests、0 failures/errors、8 个需专用 Python 进程的条件跳过，真实 PostgreSQL 7 tests 通过；Python 99 passed、0 failed/error/skipped、4 warnings；Web 37 passed；Vite production build 通过。随后 RAG smoke 因旧 USER fixture 删除 HIGH-risk Task 返回 403 而停止。
- RAG 修复红绿：第一次执行 `.\scripts\validation\v2-release-regression.ps1 -Only rag-cross-process`，退出码 1，证明 `UPDATE ... RETURNING` 的多行 psql 输出不能直接作为 ADMIN 断言；拆分 UPDATE/SELECT 后重跑退出码 0，Wiki source=1、Task source=1、旧 Wiki version=0、删除后 Task chunk=0、unmatched=0、cross-project=0，隔离容器/网络/volume 清理成功。
- Tool/HITL 红绿：执行 `.\scripts\validation\v2-release-regression.ps1 -Only tool-hitl-cross-process,restart-resume-cross-process,evaluation,full-stack-acceptance`，退出码 1，Day 5 首个跨用户 reject 因缺少 V2 `Idempotency-Key` 返回 400；修复后执行 `.\scripts\validation\v2-release-regression.ps1 -Only tool-hitl-cross-process`，退出码 0，确认前 Task=0、重复确认后 Task=1、Update version=1、Reject 后最终 Task=1、跨用户状态=403，清理成功。
- Resume/Evaluation 红绿：执行 `.\scripts\validation\v2-release-regression.ps1 -Only restart-resume-cross-process,evaluation,full-stack-acceptance`，restart/resume 已通过（PENDING→EXECUTED、same-key replay EXECUTED、Task=1、checkpoint=3），Evaluation 六项指标与基线一致，但 runner 错读顶层 `datasetSha256` 后退出 1；按报告真实 `dataset.sha256` 修正后，Evaluation 可进入下一阶段。
- Full-stack 红绿：同一续跑中三个镜像从源码构建、PostgreSQL/Core/Agent/Web 全部 healthy，但旧 V1 acceptance confirm 缺 `Idempotency-Key` 返回 400，runner 退出 1 且容器、网络、volume 清理成功。修复后执行 `.\scripts\validation\v2-release-regression.ps1 -Only full-stack-acceptance`，退出码 0；Web=200、Core/Agent=UP、RAG source=1、confirm=EXECUTED、reject=REJECTED、最终 Task=2，隔离栈完整清理。
- 最终单命令 Release Regression：仓库根目录在最终脚本版本执行 `.\scripts\validation\v2-release-regression.ps1`，退出码 0，11/11 阶段全部完成：planner 15 checks、双 Compose config、Java clean verify 108 tests（0 failures/errors、8 条条件跳过由专用跨进程 stages 覆盖）、Python 99 passed（4 条既有 Starlette/HTTP 422/pytest cache ACL warnings）、Web 37 passed、Vite build、RAG、Tool/HITL、restart/resume、Evaluation 与完整 Compose acceptance 全部通过。
- Diff 与清理：仓库根目录执行 `git diff --check`，退出码 0，仅报告三个既有 LF 文件将由 Git 转换 CRLF 的 warning；Docker 按 `agentforge-v209` 精确过滤无 container/network/volume 残留。首次清理命令因 PowerShell 类型名多一个 `]` 在解析期失败、未删除任何内容；修正并验证绝对路径位于 workspace 后，精确删除 `services/core-api/target`、`apps/web/dist` 和 9 个本轮 `agentforge-v209-stack-*` 镜像，退出码 0。
- README link check: PASS (20 local Markdown targets resolved).
- Pi Milestone Review: PASS (DeepSeek V4-pro, attempt 1, no blockers).
- Final INDEX planner: exit 0, L3 Milestone Review, all Release gates selected; the final fingerprint is retained in the Close Gate command evidence.
- Final Gitleaks: two final staged-content scans exited 0 with no leaks found.
- Pending: Close Gate, commit, push, tag and remote verification.

## 风险与回滚

主要风险是测试编排误报成功、环境失败被错误当作通过、跨项目或重复请求产生副作用，以及 README 叙事超过真实能力。防护是失败关闭、隔离 Compose project/端口/卷、运行时随机测试凭据、公共 seam 断言和公开 Claim 对应实现/测试/文档证据。回滚可删除独立 runner 与新增回归用例并恢复文档，不涉及生产数据迁移。
