# Pi 代码审查报告：autonomous-review-delivery / Attempt 1

- 日期：2026-09-04
- 审查阶段：autonomous-review-delivery
- 审查对象：df4793b476d184cd6a6896fd2220e0144b676c49（基线：7635d9bca8736cb94470cf1b8de4a38c50543748）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: NEEDS_FIX
- Pi 进程超时上限：300 秒

---

REVIEW_RESULT: NEEDS_FIX

# AgentForge 独立代码审查报告

- 审查阶段：autonomous-review-delivery
- 审查轮次：1 / 3
- 审查对象：Commit `df4793b476d184cd6a6896fd2220e0144b676c49`
- 审查模型：DeepSeek V4-pro（只读审查）
- 审查范围说明：本次提供的 diff 为截断证据（首/中/尾采样）。结论基于可见部分、文件清单、文档交叉引用；未断言本次未看到部分存在确定性故障，仅标记无法从当前证据确认的契约与安全面。

## 概述与总体结论

- 结论：**需修复后交付**。本提交方向正确，采纳了上一轮 Attempt 1 的多数修复建议（参数范围校验、monitor 契约校验、密钥扫描、恢复信号 here-string 修复、状态原子写改造）；未发现已确证的 Java 业务逻辑回归。
- 主要风险集中在：
  1. `Save-State` 的“原子替换”使用 `Move-Item -Force`，与 ADR-0007 声称的“原子状态写入”不是同一等级，存在状态文件短暂缺失/写坏窗口。
  2. 本提交未携带与改动相称的自动化回归测试（锁、原子写、密钥拒绝、三次上限、monitor 契约）。
  3. `PASS → 自动排队下一路线图阶段` 在可见脚本中无显式状态提升证据，需要测试或实现补证。
  4. 密钥扫描规则覆盖面窄，且恢复信号仍按 mtime 而非权威状态选择报告。

## 详细发现清单

| ID | Severity | File & Line | 核心问题 |
|---|---|---|---|
| R-01 | Medium | `scripts/agent-bridge/review-loop.ps1: Save-State` | `Move-Item -Force` 非严格原子替换，威胁权威状态文件一致性 |
| R-02 | High | 全部改动/PowerShell 脚本 | 新增控制流（锁、原子写、密钥拒绝、monitor 契约、三次上限）无新增自动化测试 |
| R-03 | Medium | `review-loop.ps1` / `review-orchestration.md` | `PASS` 自动推进下一路线图阶段在可见代码中无显式实现证据 |
| R-04 | Low | `review-loop.ps1: Save-State` | 临时文件仅按 `$PID` 命名且失败时无清理，可能残留或被误提交 |
| R-05 | Low | `bridge-monitor.ps1: Handle-QuotaWait` | 恢复信号仍按 mtime 选最新报告，而非权威状态，可指向非当前阶段/attempt |
| R-06 | Low | `run-review.ps1: $SensitivePatterns` | 密钥扫描仅覆盖 4 类高信噪比模式，JWT/Bearer 等常见敏感串仍会外发 |

---

### R-01 — `Move-Item -Force` 非严格原子替换

- **Severity**：Medium
- **File & Line**：`scripts/agent-bridge/review-loop.ps1: Save-State`
- **Evidence**：
  ```powershell
  $temporaryStatePath = "$StatePath.$PID.tmp"
  [System.IO.File]::WriteAllText(
      $temporaryStatePath,
      ($State | ConvertTo-Json -Depth 8),
      [System.Text.UTF8Encoding]::new($false))
  Move-Item -LiteralPath $temporaryStatePath -Destination $StatePath -Force
  ```
- **Description**：ADR-0007 明确规定“互斥锁和原子状态写入防止用户唤醒与 monitor 并发消耗轮次”。`Move-Item -Force` 在 Windows 上依赖 `MoveFileEx`，它没有提供与 `ReplaceFile` 等价的同卷原子替换保证。若进程在移动瞬间崩溃或被强杀，状态文件可能短暂缺失或处于不可预期状态；后续读者（即使持有锁）读取不到旧状态时可能按默认空状态重建，导致 attempt 计数、HUMAN_REQUIRED 状态等关键信息丢失。锁只防止同时写入，不能弥补替换非原子性。
- **Suggested Fix**：
  ```powershell
  # 同卷原子替换：Replace 要么完成，要么保留原文件与备份
  [System.IO.File]::Replace($temporaryStatePath, $StatePath, $null)
  ```
  并在 `catch` 中清理临时文件（见 R-04），必要时在替换前 `[System.IO.File]::Copy` 一份备份。

---

### R-02 — 无与改动相称的自动化回归测试

- **Severity**：High
- **File & Line**：本次变更整体（`review-loop.ps1`、`bridge-monitor.ps1`、`run-review.ps1`）
- **Evidence**：本次 diff 统计仅含 `.gitignore`、2 个 ADR/feature/change 文档、1 份审查报告、3 个 PowerShell 脚本，**无任何测试文件变更**。变更记录称“AST 与 DryRun 通过”“Maven 测试通过”，但未提交可复现这些断言的自动化测试脚本。
- **Description**：本提交是自主循环的核心控制面：锁、原子写、跨进程互斥、密钥外发拒绝、monitor 契约校验、三次上限与 HUMAN_REQUIRED。任何一处回归都可能导致 Pi 轮次被错误消耗、状态被清空或敏感 diff 外发。AGENTS.md 要求“每次交付前运行与改动相称的测试，并把命令和结果写入变更记录”，且本次审查触发条件是“真实问题、测试缺口均使用 NEEDS_FIX”。目前这些关键分支没有任何可重复验证的自动化覆盖，仅凭手工 AST/DryRun 不足以支撑无人值守自主推进。
- **Suggested Fix**：
  1. 为 `review-loop.ps1` 添加 Pester 测试：并发获取锁、`Save-State` 原子替换后可读、第三次 NEEDS_FIX 进入 HUMAN_REQUIRED、`WAITING_FOR_CODEX_FIX` 不消耗轮次。
  2. 为 `run-review.ps1` 添加伪密钥拒绝测试：构造包含 `-----BEGIN PRIVATE KEY-----` 的 diff，断言非零退出且不调用 Pi。
  3. 为 `Invoke-ReviewLoop` 契约校验添加单元测试：`$result` 为 `$null`、纯字符串、缺少 `Actions` 时抛出并进入诊断分支。
  4. 把测试文件纳入本仓库，并在变更记录中回填命令与输出路径。

---

### R-03 — `PASS` 自动推进下一路线图阶段缺少实现/测试证据

- **Severity**：Medium
- **File & Line**：`review-loop.ps1`（Find-AutomaticStageDefinition 及状态机）、`docs/03-features/review-orchestration.md`
- **Evidence**：文档改为“Pi 结论为 `PASS` 时阶段审查完成**并自动排队下一路线图阶段**”；变更记录称“Pi 通过后将下一路线图阶段标为可执行”。但当前可见的 `review-loop.ps1` 仅将当前阶段置为 `PENDING_REVIEW`/`WAITING_FOR_CODEX_FIX`/`RESOLVED` 等，未出现任何对路线图阶段注册表、活跃阶段标记或下一阶段 `executable` 标志的写入。
- **Description**：如果“自动排队下一阶段”仅依赖 `Find-AutomaticStageDefinition` 隐式发现（例如按当前 commit 匹配），则可能成立；但本次 diff 未显示该函数如何消费 PASS 状态，也没有测试验证 PASS 后 heartbeat 下一轮会实际选中下一阶段。若缺少显式标记，heartbeat 可能反复选中同一阶段或停在 IDLE，使文档承诺的自动推进失效。
- **Suggested Fix**：
  1. 在 `review-loop.ps1` 对 PASS 明确写出下一阶段的可执行标记（或说明 `Find-AutomaticStageDefinition` 如何依据 PASS 发现下一阶段）。
  2. 增加一个 Pester 测试：当前阶段 PASS 后，下一轮 `review-loop` 返回的 Actions 指向下一阶段的 REVIEW。
  3. 在变更记录中记录该端到端 DryRun 输出。

---

### R-04 — 临时文件仅按 `$PID` 命名且失败无清理

- **Severity**：Low
- **File & Line**：`review-loop.ps1: Save-State`
- **Evidence**：
  ```powershell
  $temporaryStatePath = "$StatePath.$PID.tmp"
  [System.IO.File]::WriteAllText($temporaryStatePath, ...)
  Move-Item -LiteralPath $temporaryStatePath -Destination $StatePath -Force
  ```
- **Description**：`$PID` 只能保证同一进程内唯一；跨不同 PowerShell 进程时若锁失败或旧 temp 残留，可能互相覆盖。Write/Move 过程中抛异常时，`.tmp` 文件不会被清理。这些文件不被 `.gitignore` 当前规则覆盖（仅 `.lock` 被忽略），可能在后续提交中被误纳入仓库。长时间运行且多次异常时，`scripts/agent-bridge/` 会堆积诊断垃圾文件。
- **Suggested Fix**：
  ```powershell
  $temporaryStatePath = "$StatePath.$PID.$([guid]::NewGuid().ToString('N')).tmp"
  try {
      [System.IO.File]::WriteAllText($temporaryStatePath, ($State | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
      [System.IO.File]::Replace($temporaryStatePath, $StatePath, $null)
  } finally {
      if (Test-Path -LiteralPath $temporaryStatePath) { Remove-Item -LiteralPath $temporaryStatePath -Force }
  }
  ```
  并在 `.gitignore` 增加 `scripts/agent-bridge/*.tmp`。

---

### R-05 — 恢复信号仍按 mtime 而非权威状态选择报告

- **Severity**：Low
- **File & Line**：`bridge-monitor.ps1: Handle-QuotaWait`
- **Evidence**：
  ```powershell
  $latestReport = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "docs\08-reviews") -File -Filter "*.md" |
      Where-Object { $_.Name -ne "README.md" } |
      Sort-Object LastWriteTime -Descending |
      Select-Object -First 1
  $reportPath = if ($null -eq $latestReport) { "无" } else { "docs/08-reviews/$($latestReport.Name)" }
  ```
- **Description**：上一轮建议是“先调用 review-loop 获取权威状态”，本提交仅用 here-string 修复了写入语法，仍未接入状态机输出。当存在多个待审阶段或多次 attempt 时，按 `LastWriteTime` 排序取最新可能指向非当前需要处理的报告（例如 attempt 1 报告因读取被触摸而晚于 attempt 2）。这不会直接破坏功能（heartbeat 仍会运行 review-loop），但会误导恢复后的 Codex 优先阅读错误报告，降低自动续跑可靠性。
- **Suggested Fix**：
  1. 写信号前调用 `review-loop.ps1 -OnMonitorWake -DryRun`，从返回结果中取当前阶段、attempt、`OverallStatus` 和 `ReportPath`。
  2. 信号内容显式携带 stage、attempt、状态；不再依赖 mtime 猜测。

---

### R-06 — 密钥扫描规则覆盖面窄，JWT/Bearer 可能外发

- **Severity**：Low
- **File & Line**：`run-review.ps1: $SensitivePatterns`
- **Evidence**：
  ```powershell
  $SensitivePatterns = @{
      "private-key" = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
      "github-token" = '\bgh[pousr]_[A-Za-z0-9_]{20,}\b'
      "aws-access-key" = '\bAKIA[0-9A-Z]{16}\b'
      "openai-key" = '\bsk-[A-Za-z0-9]{20,}\b'
  }
  foreach ($patternName in $SensitivePatterns.Keys) {
      if ([regex]::IsMatch($DiffText, $SensitivePatterns[$patternName])) { throw ... }
  }
  ```
- **Description**：扫描在 diff 截断之前执行，这一点正确；但规则仅覆盖 PEM 私钥、GitHub token、AWS Access Key ID、OpenAI key。常见的 JWT（`eyJ...`）、`Authorization: Bearer ...`、Azure 存储连接串、Slack token 等仍会随 diff 发送给外部模型。该项目公开展示且安全制度要求极严格，任何漏网敏感串都可能成为事故。按“高信噪比”定位可降低误报，但 JWT/Bearer 属于高信噪比且极常见，值得纳入。
- **Suggested Fix**：
  ```powershell
  $SensitivePatterns += @{
      "jwt" = '\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b'
      "bearer" = '\bBearer\s+[A-Za-z0-9._~+/-]+={0,2}\b'
  }
  ```
  并增加伪 JWT/Bearer 的拒绝测试（见 R-02）。

---

## 主开发 (Codex) 评估回填区

> Codex 需逐项回填处理结论，并仅在文档先行后实施修复。

| Issue ID | 处理结论（采纳 / 豁免 / 待定） | 说明与依据 | 关联提交/文档 |
|---|---|---|---|
| R-01 |  |  |  |
| R-02 |  |  |  |
| R-03 |  |  |  |
| R-04 |  |  |  |
| R-05 |  |  |  |
| R-06 |  |  |  |

> 备注：审查结论为 NEEDS_FIX。R-02（自动化测试缺失）与 R-03（自动推进链路的可验证性）建议在进入下一阶段前优先处理或提供书面豁免；其余可在本阶段修复并回填上述表格。

## 限制声明

本次 diff 为截断证据，`review-loop.ps1` 顶部（锁获取与 `Find-AutomaticStageDefinition` 全文）、`bridge-monitor.ps1` 主循环、`run-review.ps1` 提示词组装与 Pi 调用段未完整可见。Codex 在复位后应在真机上验证：并发唤醒下的锁行为、伪密钥拒绝、第三次 NEEDS_FIX 是否准确进入 `HUMAN_REQUIRED`、PASS 后 heartbeat 是否实际推进到下一阶段。
