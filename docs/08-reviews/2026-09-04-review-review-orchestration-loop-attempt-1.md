# Pi 代码审查报告：review-orchestration-loop / Attempt 1

- 日期：2026-09-04
- 审查阶段：review-orchestration-loop
- 审查对象：7635d9bca8736cb94470cf1b8de4a38c50543748（基线：8ad81498470bf265cc58516068bc095e2ebe5fa0）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- 审查阶段：review-orchestration-loop
- 审查轮次：1 / 3
- 审查对象：Commit `7635d9bca8736cb94470cf1b8de4a38c50543748`
- 审查模型：DeepSeek V4-pro（只读审查）
- 审查范围说明：本次 diff 被截断，`scripts/agent-bridge/review-loop.ps1`、`run-review.ps1`、`Start-BridgeMonitor.ps1`、`review-stages.json` 的完整正文未在证据中出现。以下发现基于已提供 diff 的可见部分、文档交叉引用及文件清单，优先覆盖不可证伪的高风险面。

## 概述与总体结论

- 结论：**需修复后交付**（存在高优先级流程/安全风险，未发现已确证的 Java 业务逻辑回归，但核心 PowerShell 状态机的契约与并发安全无法从当前证据确认）。
- Java 测试侧改动总体正确：将 `service.delete(..., 0)` 修正为 `service.delete(..., updated.version())`，符合乐观锁语义；`ResourceApiTest` 新增 404/403 映射测试方向正确。
- 主要风险集中在：
  1. Codex 唤醒路径与 monitor 唤醒路径可能并发写 `.review-loop-state.json`，但证据中未见锁或原子写机制。
  2. 发送 diff 给外部模型前，文档要求“先做本地密钥扫描”，但截断证据中无法确认 `run-review.ps1` 实际执行了该步骤。
  3. monitor 对 `review-loop.ps1` 的调用缺少退出码/返回对象校验。
  4. 额度恢复信号只指向“最近修改的报告”，未携带阶段/attempt/当前状态，可能误导 Codex。

## 详细发现清单

| ID | Severity | File & Line | 核心问题 |
|---|---|---|---|
| R-01 | High | `scripts/agent-bridge/review-loop.ps1` / `bridge-monitor.ps1` | Codex wake 与 monitor wake 并发触发状态机，未见锁/原子写，可能重复 attempt 或损坏状态 |
| R-02 | High | `scripts/agent-bridge/run-review.ps1` / `docs/03-features/review-orchestration.md` | 无法确认调用外部模型前执行了本地密钥扫描，存在敏感信息外发风险 |
| R-03 | Medium | `bridge-monitor.ps1: Invoke-ReviewLoop` | 未校验 `review-loop.ps1` 退出码/异常/返回类型，失败时可能静默继续 |
| R-04 | Medium | `bridge-monitor.ps1: Handle-QuotaWait` | 额度恢复信号只引用按 mtime 排序的最新报告，缺少阶段/attempt/状态上下文 |
| R-05 | Low | `bridge-monitor.ps1: param` | `$QuotaHours`/`$SimulationMinutes` 未加范围校验，异常值可引发异常或无限循环 |
| R-06 | Low | `TaskServiceTest.java` / `WikiPageServiceTest.java` | 乐观锁删除测试未断言 version 递增，也未覆盖 stale version 的拒绝分支 |

## 逐项发现

### R-01 — Codex 唤醒与 monitor 唤醒并发写状态文件，未证明有锁/原子写

- **Severity**：High
- **File & Line**：`scripts/agent-bridge/review-loop.ps1`、`bridge-monitor.ps1`（Invoke-ReviewLoop），配合 `AGENTS.md` 中的强制规则
- **Evidence**：
  - `AGENTS.md`：Codex 每次收到用户消息必须运行 `review-loop.ps1 -OnCodexWake`；同时要求 `bridge monitor` 在提交后独立启动 Pi 审查。
  - `.gitignore` 新增 `scripts/agent-bridge/.review-loop-state.json`，说明存在共享运行时状态文件。
  - `bridge-monitor.ps1` 中：`$result = & $ReviewLoopScript -OnMonitorWake -TimeoutSeconds $ReviewTimeoutSeconds`。
- **Description**：用户消息触发的 Codex 唤醒与提交触发的 monitor 唤醒可能在时间上重叠。若 `review-loop.ps1` 直接读改写 `.review-loop-state.json` 而没有跨进程锁或“临时文件 + 原子替换”策略，两个进程可能重复增加 attempt、生成两份同 attempt 报告，或因交错写损坏 JSON。一旦状态损坏，可能错误进入 `HUMAN_REQUIRED` 或跳过修复复审，直接影响三次接管边界的可靠性。
- **Suggested Fix**：
  1. 在 `review-loop.ps1` 内使用互斥锁（PowerShell `Mutex`）或 `.lock` 文件 + 重试，保证同一工作树的 loop 串行执行。
  2. 状态持久化改为写临时文件后 `Move-Item -Force` 原子替换。
  3. 状态转换做成幂等（带前置条件校验，如只有 `WAITING_FOR_CODEX_FIX -> 下一 attempt` 才推进）。
  4. 增加并发调用测试：两个进程同时传入 `-OnMonitorWake`/`-OnCodexWake`，断言不会双写 attempt。

### R-02 — 无法确认对外发送 diff 前执行了本地密钥扫描

- **Severity**：High
- **File & Line**：`scripts/agent-bridge/run-review.ps1`（正文未在 diff 中提供），与 `docs/03-features/review-orchestration.md` 的“权限与安全”段落
- **Evidence**：
  - 功能文档明确要求：`传给 Pi 的内容来自公开仓库的提交 diff；仍必须先通过本地密钥扫描，避免将意外敏感信息发送给外部模型。`
  - 本次 diff 提供的 `run-review.ps1` 证据不足，未看到任何针对 diff 的 secret pattern 扫描、SCAN 结果断言或扫描失败即中止逻辑。
- **Description**：该项目公开展示且仓库安全制度严格要求不出具真实密钥/令牌。审查包装器会把 diff 内容发送给外部模型；若缺少本地密钥扫描，任何被误提交过的临时密钥都可能外发。即使 `.gitignore` 覆盖了常见文件，过去已进入 git 历史或本次 diff 的偶然敏感字符串仍可能进入模型上下文。
- **Suggested Fix**：
  1. 在组装模型输入前，对 `git diff` 输出和变更文件内容执行确定性 secret 扫描（如 `gitleaks detect --no-git` 或内建正则；V1 可先用覆盖 AWS/GitHub/JWT/私钥的高信噪比模式）。
  2. 若命中任何规则，脚本以非零退出并拒绝调用模型，输出命中位置但不输出原始密钥。
  3. 在 `run-review.ps1` 日志中记录“扫描命令 + 结果摘要”，保证每次审查可追溯。
  4. 增加测试：用包含伪密钥的 diff 验证会中止调用。

### R-03 — Invoke-ReviewLoop 未校验 review-loop 退出码/返回类型

- **Severity**：Medium
- **File & Line**：`bridge-monitor.ps1: Invoke-ReviewLoop`
- **Evidence**：
  ```powershell
  $result = & $ReviewLoopScript -OnMonitorWake -TimeoutSeconds $ReviewTimeoutSeconds
  Write-Host "[review-loop] $($result.OverallStatus)" -ForegroundColor Cyan
  foreach ($action in $result.Actions) {
      Write-Host "  - $($action.Stage): $($action.Action)" -ForegroundColor Gray
  }
  return $result
  ```
- **Description**：该函数假定 `review-loop.ps1` 始终成功、始终返回单一对象且必含 `OverallStatus` 与 `Actions` 属性。未检查 `$LASTEXITCODE`，也未验证 `$result` 是否为 `$null` 或字符串输出。PowerShell 中若被调脚本 `exit 1`、直接抛出异常，或只输出普通字符串，外层读写可能误判为空操作/成功，违反“Pi 失败不消耗 attempt、诊断记录清晰”的要求。
- **Suggested Fix**：
  1. 在调用后立即检查 `$LASTEXITCODE`，非零则记录失败并进入诊断分支。
  2. `review-loop.ps1` 约定返回 `[PSCustomObject]@{ Success=bool; OverallStatus=string; Actions=array; Stage=...; Attempt=...; ReportPath=... }`，并用 `assert` 或类型检查保证。
  3. `Invoke-ReviewLoop` 在 `$null -eq $result -or -not $result.Success` 时抛出可读异常，由外层统一捕获。

### R-04 — 额度恢复信号只有“最新报告”，缺少阶段/状态上下文

- **Severity**：Medium
- **File & Line**：`bridge-monitor.ps1: Handle-QuotaWait`
- **Evidence**：
  ```powershell
  $latestReport = Get-ChildItem ... -Filter "*.md" | Where-Object { $_.Name -ne "README.md" } |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1
  ```
  随后生成的 `$ResumeSignalFile` 只写：“最新审查报告：$reportPath” 和通用指令。
- **Description**：当存在多个待审阶段或多次 attempt 时，“按 LastWriteTime 排序取最新”不一定对应当前需要 Codex 处理的阶段。例如 attempt 1 报告比 attempt 2 晚被 mtime 触摸，信号会指向错误文件。同时信号未包含当前状态（`WAITING_FOR_CODEX_FIX` 或 `HUMAN_REQUIRED`）、阶段 ID 和 attempt 号。Codex 恢复后需要再次运行 loop 才能获知真相，增加了误读概率。
- **Suggested Fix**：
  1. 在写 resume 信号前调用一次 `review-loop.ps1 -OnMonitorWake` 获取当前权威状态。
  2. 信号内容携带：stage、attempt、`OverallStatus`、报告相对路径、待处理文件清单。
  3. 报告路径直接取自状态机输出，而不是按 mtime 推断。

### R-05 — 额度/模拟等待参数无范围校验

- **Severity**：Low
- **File & Line**：`bridge-monitor.ps1: param`
- **Evidence**：
  ```powershell
  [double]$QuotaHours = 5.0
  [double]$SimulationMinutes = 0
  ```
  且 `Handle-QuotaWait` 使用 `[TimeSpan]::FromHours($QuotaHours)`（当 `$SimulationMinutes <= 0` 时）。
- **Description**：`$QuotaHours` 为负数、为零或 `NaN` 时，`FromHours` 会抛异常；若值非常小，`Start-Sleep` 的最小 clamp 虽避免了多数问题，但异常退出仍会中断 monitor。`$SimulationMinutes` 同理可传入负数导致 `FromMinutes` 失败。
- **Suggested Fix**：
  1. 为两个参数加 `[ValidateRange(...)]` 并校验有限值：
     `[ValidateRange(0.01, 720)] [double]$QuotaHours = 5.0`
     `[ValidateRange(0, 1440)] [double]$SimulationMinutes = 0`
  2. 在 `Handle-QuotaWait` 内显式拒绝 `$QuotaHours -le 0`。

### R-06 — 乐观锁删除测试未断言 version 递增或 stale 拒绝分支

- **Severity**：Low
- **File & Line**：`services/core-api/src/test/java/com/agentforge/core/task/application/TaskServiceTest.java`、`.../wiki/application/WikiPageServiceTest.java`
- **Evidence**：
  ```java
  service.delete(projectId, taskId, actor, updated.version());
  // ...
  service.delete(projectId, pageId, actor, updated.version());
  ```
- **Description**：本次改动修复了“更新后使用硬编码 0 调用 delete”的测试错误，方向正确。但没有断言更新返回的 `version` 确实递增；也没有新增针对旧版本（`updated.version() - 1`）调用 delete/update 时应抛冲突的负向测试。如果实现将来忘记递增 version，这两个测试可能仍通过，V1 的并发/幂等保障不会被测试兜住。
- **Suggested Fix**：
  1. 增加断言：`assertThat(updated.version()).isEqualTo(1);`（或初始版本 + 1）。
  2. 增加负向用例：用 `updated.version() - 1` 调用 delete/update，断言抛 `ConflictException`/stale version 错误，并 `verify(..., never()).delete(...)`。
  3. 保持 `ResourceApiTest` 中已覆盖的 409 HTTP 契约测试。

## 主开发 (Codex) 评估回填区

> Codex 需逐项回填处理结论，并仅在文档先行后实施修复。

| Issue ID | 处理结论（采纳 / 豁免 / 待定） | 说明与依据 | 关联提交/文档 |
|---|---|---|---|
| R-01 | 采纳 | review-loop 对状态文件持有跨进程独占锁，并通过临时文件原子替换保存状态。 | AST 与 DryRun 通过 |
| R-02 | 采纳 | run-review 在构造 Pi 提示词前扫描私钥、GitHub、AWS 与 OpenAI 高信噪比模式；命中即拒绝外发。 | 代码检查通过 |
| R-03 | 采纳 | monitor 校验 review-loop 的退出状态和结构化结果，异常进入诊断分支。 | 单次 monitor 通过 |
| R-04 | 部分采纳 | 恢复信号语法修复；权威状态仍由 heartbeat 首次运行 review-loop 获取。 | heartbeat 已启用 |
| R-05 | 采纳 | QuotaHours 与 SimulationMinutes 加入范围校验。 | AST 通过 |
| R-06 | 部分采纳 | 已使用更新响应版本并覆盖 API 404/403；JPA 实际版本递增由持久化集成测试验证。 | Maven 测试通过 |

> 备注：审查结论为 NEEDS_FIX；R-01、R-02 建议在进入下一阶段前优先处理或书面豁免，其余可在本阶段修复或明确记录豁免理由。

## 限制声明

当前未看到 `review-loop.ps1`、`run-review.ps1`、`Start-BridgeMonitor.ps1`、`review-stages.json` 的完整 diff，因此关于这些核心 PowerShell 逻辑的结论基于可见证据与交叉文档推断。Codex 复位后应在真机上验证：
- monitor 与 Codex 同时唤醒的并发行为；
- 伪造 secret 时 `run-review.ps1` 是否拒绝调用模型；
- 第三轮 NEEDS_FIX 是否准确进入 `HUMAN_REQUIRED`。
