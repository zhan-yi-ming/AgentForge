# Pi 代码审查报告：v2-09-regression-release / Attempt 1

- 日期：2026-09-09
- 审查阶段：v2-09-regression-release
- 审查对象：INDEX@03b9117（基线：03b9117842d1f40ddfb600bdd6ceed5c80a25a88）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: PASS
- Pi 进程超时上限：900 秒

---

REVIEW_RESULT: PASS

# AgentForge V2-09 Regression Release — Milestone 审查报告（独立只读 Reviewer）

**审查模式**：Milestone Review（第 1/3 轮）
**审查对象**：Commit `03b9117842d1f40ddfb600bdd6ceed5c80a25a88 .. INDEX@03b9117`
**审查结论**：**通过（无阻塞性问题）**。本提交严格落在 V2-09「回归收口 + Release Gate」范围内，无新增业务功能、无 V3 越界、无权限/幂等/数据一致性缺陷引入。文件为文档与编排脚本的增量，脚本修改方向与 V2-05/V2-06 已确认契约一致。存在若干鲁棒性与证据可追溯性改进点（均为建议级）。

---

## 一、概述与总体结论

1. **边界符合性**：符合。改动全部为 README 叙事重构、路线图/特性/测试策略/变更记录，以及 `scripts/validation` 回归编排与既有 e2e 脚本的契约适配。未发现 MCP、GraphRAG、LiteLLM、Neo4j 等 V3 组件提前混入，也未改变「Java 确定性执行 / Python Agent 决策」信任边界。
2. **契约一致性**：`day5-e2e.ps1` 与 `v1-acceptance.ps1` 为 confirm/reject 补齐 `Idempotency-Key`（同一 action 的 replay 复用同一 key），与 V2-06 幂等契约一致；`day4-e2e.ps1` 经测试专用 ADMIN 提升适配 V2-05 HIGH-risk delete，未放宽生产策略。
3. **状态真实性**：V2-09 变更记录标注 `In Progress` 且明确「待完成」项（Gitleaks、文档一致性、Close Gate、提交/推送/标签与远端核验），与「不提前创建 `v2-stable`」门槛一致；README 将 V2-08 基线与 V3 规划如实标注，未发现 Planned→Implemented 的虚假 Claims。
4. **测试证据**：变更记录提供了可复现的红绿记录与最终 11/11 阶段通过结果，证据链与脚本实现一致。
5. **总体**：Pass。本提交可继续推进，但建议在 Close Gate 前处理下述建议项以强化 fail-closed 可证明性与覆盖矩阵的可审计性。

> 说明：本次为只读审查，未运行任何命令、未修改文件与 Git 状态；所有结论仅依据提供的 diff、变更记录与显式上下文。

---

## 二、详细发现清单

| ID | 严重级别 | 文件 | 行号（按 diff 估算） | 核心问题 | 分组 |
| --- | --- | --- | --- | --- | --- |
| F1 | 建议 | `scripts/validation/v2-release-regression.ps1` | 约 L125–L128（gate-planner-contract 分支） | contract 阶段未校验子脚本退出码，fail-closed 依赖 throw 传播假设 | 建议修改 |
| F2 | 建议 | `scripts/validation/v2-release-regression.ps1` | L27–L36（`$coverage`）vs L13–L25（`$stages`） | 「Trace / Evaluation」「Long Conversation / Token Budget」无独立 stage 映射，完整验收证据不易审计 | 建议修改 |
| F3 | 建议 | `scripts/validation/v2-release-regression.ps1` | 约 L97–L105（`Get-FreeTcpPort`） | 端口获取-释放-使用存在 TOCTOU 竞态，CI 高并发下可能偶发端口冲突 | 建议修改 |
| F4 | 建议 | `scripts/validation/v2-release-regression.ps1` | 约 L239–L257（full-stack-acceptance finally） | 清理失败会覆盖原始阶段异常，导致排障信息丢失 | 建议修改 |
| F5 | 建议 | `scripts/validation/day4-e2e.ps1` | 约 L158–L163 | 直接 SQL 提升 ADMIN 绕过 Service 层（测试专用可接受，但建议改为 ADMIN 种子账号） | 建议修改 |
| F6 | 建议 | `README.md` | 「仓库导航」「审核记录」段落 | README 新增多文档链接（如 `docs/08-reviews/README.md`）不在本 diff，需在 Close Gate 做链接校验 | 建议修改 |
| — | 无需修改 | `scripts/validation/day5-e2e.ps1`、`v1-acceptance.ps1` | — | `Idempotency-Key` 补齐与 V2-06 契约一致，replay 复用 key 正确 | 无需修改 |
| — | 无需修改 | `docs/01-product/v2-v3-node-roadmap.md`、`docs/03-features/README.md` | — | 状态更新准确且遵守节点边界 | 无需修改 |

---

## 三、逐项 Issue 展开

### F1（建议修改）— gate-planner-contract 阶段未校验 `$LASTEXITCODE`

- **Severity**：建议修改（不阻塞）
- **File & Line**：`scripts/validation/v2-release-regression.ps1`，新增文件 `Invoke-ReleaseStage` 的 `"gate-planner-contract"` 分支（约 L125–L128）
- **Evidence**：
  ```powershell
  "gate-planner-contract" {
      $output = @(& (Join-Path $PSScriptRoot "test-plan-change-gates.ps1") 6>&1)
      Write-StageOutput $output
  }
  ```
  对比同文件对原生命令的失败关闭统一封装：
  ```powershell
  function Invoke-NativeChecked {
      ...
      $output = @(& $Action 2>&1)
      $exitCode = $LASTEXITCODE
      Write-StageOutput $output
      if ($exitCode -ne 0) { throw "$Description failed with exit code $exitCode." }
  }
  ```
- **Description**：本节点的核心承诺是「失败关闭」（`test-v2-release-regression.ps1` 亦断言 `failClosed`）。原生命令经 `Invoke-NativeChecked` 显式校验退出码，但 `gate-planner-contract` 与各 e2e `.ps1` 子脚本仅依赖子脚本 `throw` 的传播假设。若 `test-plan-change-gates.ps1` 中以 `exit 1`（而非 `throw`）报告失败，该阶段会被静默记为 PASS，破坏 lead gate 的失败关闭语义。变更记录显示其余 e2e 子脚本的失败在本轮均被正确捕获（普遍采用 throw），故仅作防御性改进。
- **Suggested Fix**：
  ```powershell
  "gate-planner-contract" {
      $output = @(& (Join-Path $PSScriptRoot "test-plan-change-gates.ps1") 6>&1)
      $exitCode = $LASTEXITCODE
      Write-StageOutput $output
      if ($exitCode -ne 0) { throw "Validation planner contract failed with exit code $exitCode." }
  }
  ```
  并建议在 `test-v2-release-regression.ps1` 增补「人为注入失败 stage 必须整体失败关闭」的行为测试，使 `failClosed` 从元数据声明升级为行为验证。

---

### F2（建议修改）— coverage 声明与 stage 映射不透明

- **Severity**：建议修改
- **File & Line**：`scripts/validation/v2-release-regression.ps1`，`$coverage`（约 L27–L36）与 `$stages`（约 L13–L25）
- **Evidence**：
  ```powershell
  $coverage = @(
      "Ordinary Chat / RAG",
      ...
      "Long Conversation / Token Budget",
      "Trace / Evaluation"
  )
  ```
  而 11 个 stage 中仅 `python-test`（Agent unit + pgvector/checkpoint）、`java-clean-verify`、`evaluation` 等通用入口，无名为 `trace` 或 `long-conversation` 的独立跨进程阶段。
- **Description**：路线图 V2-09「完整验收」明确要求 Trace、Long Conversation 纳入 Release Regression。当前它们大概率由 `python-test`/`java-clean-verify` 内部单元/集成测试覆盖（与变更记录「runner 只编排已有公共测试、不复制各模块断言」的设计一致），但覆盖矩阵未标注「哪一项由哪个 stage/测试类承载」，Milestone 审查与后续审计难以确认无遗漏。
- **Suggested Fix**：在变更记录或 runner 注释中补充覆盖矩阵映射表（例如：Trace → `python-test` 的 observability 用例；Long Conversation/Token Budget → `python-test` 的 context 用例；Cross Project/Unauthorized → `rag-cross-process`/`tool-hitl-cross-process`），并在回归产物的 `$summary` 或报告中输出该映射，保持「完整验收」可审计。

---

### F3（建议修改）— `Get-FreeTcpPort` TOCTOU 竞态

- **Severity**：建议修改
- **File & Line**：`scripts/validation/v2-release-regression.ps1`，`Get-FreeTcpPort` 函数（约 L97–L105）
- **Evidence**：
  ```powershell
  $listener.Start()
  return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
  } finally { $listener.Stop() }
  ```
- **Description**：先获取空闲端口、随即释放、稍后再由 Compose/uvicorn 绑定，存在「获取到释放之间端口被其他进程占用」的时间窗。本机单跑风险低，但在 CI 并行或端口密集环境下可能偶发端口冲突，且这类 flake 难以复现定位。
- **Suggested Fix**：对获取端口增加重试与占用探测，或对关键跨进程阶段捕获「端口占用」失败并自动重选端口重试一次；保持最终失败关闭。

---

### F4（建议修改）— full-stack 阶段清理异常覆盖原始异常

- **Severity**：建议修改
- **File & Line**：`scripts/validation/v2-release-regression.ps1`，`"full-stack-acceptance"` 分支 `finally`（约 L239–L257）
- **Evidence**：
  ```powershell
  } finally {
      $cleanupOutput = @(& docker compose -p $composeProject -f $composeFile down -v --remove-orphans 2>&1)
      $cleanupExit = $LASTEXITCODE
      Write-StageOutput $cleanupOutput
      if ($cleanupExit -ne 0) { throw "Full-stack cleanup failed with exit code $cleanupExit." }
  }
  ```
- **Description**：若 `v1-acceptance.ps1` 先抛出失败，而 `finally` 中的清理又失败并再次 `throw`，清理异常会替换原始业务断言异常，排障者将看到「cleanup failed」而非真正的验收失败点。
- **Suggested Fix**：用 `try/catch` 聚合：捕获 `finally` 清理失败时，将原始异常作为主错误输出，清理失败仅追加为附加信息；不改变失败关闭的最终退出码语义。

---

### F5（建议修改）— day4 直接用 SQL 提升 ADMIN 绕过 Service 层

- **Severity**：建议修改（测试专用，不阻塞）
- **File & Line**：`scripts/validation/day4-e2e.ps1`（约 L158–L163）
- **Evidence**：
  ```powershell
  Invoke-DatabaseScalar "update app_user set role = 'ADMIN' where id = '$($authOne.user.id)'" | Out-Null
  $promotedRole = Invoke-DatabaseScalar "select role from app_user where id = '$($authOne.user.id)'"
  Assert-True ($promotedRole -eq "ADMIN") "RAG deletion fixture was not promoted to ADMIN"
  $adminAuth = Invoke-ApiPost "$coreUrl/api/v1/auth/login" ...
  ```
- **Description**：用 SQL 直接改写 `app_user.role` 以适配 V2-05 的 HIGH-risk delete 权限。因为是隔离 Compose project 内的测试 seeding，不构成生产越权绕过；且变更记录已明确说明「不放宽生产策略」，验证目标（RAG 删除生命周期）与权限策略正交，故可接受。
- **Suggested Fix**：若希望测试路径更贴近生产语义，可在 seed 阶段直接创建 `role=ADMIN` 的专用账号（走同一认证与 RBAC 读取路径），避免在 e2e 中引入「数据库优先于 Service 层」的特殊分支。

---

### F6（建议修改）— README 新增链接需在 Close Gate 校验

- **Severity**：建议修改
- **File & Line**：`README.md`「当前状态与真实证据」「仓库导航」段落
- **Evidence**：
  ```markdown
  每个节点的实现、失败测试、最终验证和独立 Review 都记录在[变更记录](docs/07-changes/README.md)与[审核记录](docs/08-reviews/README.md)。
  ```
- **Description**：`docs/08-reviews/README.md`、`docs/03-features/context-management.md` 等引用均不在本 diff 范围内，无法在此只读审查中确认目标文件存在。V2-09 变更记录已将「文档一致性」列入待完成项，与本建议一致。
- **Suggested Fix**：Close Gate 前执行一次性链接/文档存在性校验（如扫描 README 相对链接），并确认「审核记录」目录已建或修正叙事。

---

## 四、无需修改项（已核验正确）

1. **`day5-e2e.ps1` / `v1-acceptance.ps1` 的 `Idempotency-Key` 补齐**：confirm/reject 均携带合法 key，同一 action 的 replay 复用同一 key（`createDecisionHeaders` 用于首次 confirm 与重复 confirm），符合 V2-06 幂等契约；跨用户 reject 403、reject 后 confirm 409 的断言边界正确。
2. **`docs/01-product/v2-v3-node-roadmap.md` / `docs/03-features/README.md` 状态更新**：Current Node 标为 V2-09 in progress、功能 README 明确「V3-01 MCP 尚未授权，禁止提前实现」，与节点边界及 V1 冻结原则一致，无越界。

---

## 五、主开发（Codex）评估回填区

> 由 Codex 逐条回填事实判断与处置结论（不得无脑照单执行）。

| ID | 是否成立 | 原因/证据 | 是否修复 | 修复/补测范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| F1 | 否 | 当前 contract 断言使用 `throw`，内部 native Git 调用也显式检查退出码 | 否 | 无 | Closed |
| F2 | 是 | 原记录未显示 coverage 到 stage/test 的映射 | 是 | 已在 V2-09 变更记录补充完整映射 | Closed |
| F3 | 理论成立 | 端口释放后存在 TOCTOU 时窗，本轮未复现 | 否 | 留作后续 CI 并发鲁棒性改进 | Accepted |
| F4 | 是 | 主失败与 cleanup 同时失败时可能覆盖诊断信息，但仍会失败关闭 | 否 | 不扩展 V2-09 范围 | Accepted |
| F5 | 可接受 | 只在隔离 Compose 测试数据中设置 ADMIN，且仍经过真实认证/RBAC 路径 | 否 | 生产策略未放宽 | Closed |
| F6 | 是 | Close Gate 应验证 README 相对链接 | 是 | 20 个 Markdown link 目标全部存在 | Closed |

---

**最终判定**：`REVIEW_RESULT: PASS`（无必须修改项；6 项建议修改不阻塞提交，但建议在 Close Gate 前处理或明确回填处置理由）。
