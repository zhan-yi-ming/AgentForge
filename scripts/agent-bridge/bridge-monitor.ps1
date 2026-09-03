<#
.SYNOPSIS
    AgentForge 自动化桥接守护脚本：阶段完成感知、Pi 自动审查触发与 Codex 5 小时额度自动续跑。
.DESCRIPTION
    1. 监听 Git 仓库阶段提交：当 Codex 交付阶段并产生新 Commit 时，自动拉起 Pi Agent 执行代码审查；
    2. 额度管理：当捕获到 Codex 5 小时限额（或检测到 .codex-quota-exhausted 信号文件）时，
       进入 5 小时休眠倒计时，在额度恢复后自动生成续跑指令与审查报告链接，实现自动续跑衔接。
.PARAMETER PollIntervalSeconds
    轮询检查间隔（秒），默认 15 秒。
.PARAMETER QuotaHours
    Codex 额度重置等待时长（小时），默认 5 小时。
.PARAMETER TriggerQuotaWait
    立即触发 5 小时额度等待模式（用于调试或手动标记额度耗尽）。
.PARAMETER SimulationMinutes
    额度等待模拟测试（分钟）。若大于 0，将以指定分钟数代替 5 小时进行快速测试。
#>

[CmdletBinding()]
param(
    [int]$PollIntervalSeconds = 15,
    [double]$QuotaHours = 5.0,
    [switch]$TriggerQuotaWait,
    [double]$SimulationMinutes = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $ProjectRoot

$RunReviewScript = Join-Path $PSScriptRoot "run-review.ps1"
$QuotaSignalFile = Join-Path $ProjectRoot ".codex-quota-exhausted"
$ResumeSignalFile = Join-Path $ProjectRoot ".codex-resume-signal.md"
$StateCacheFile = Join-Path $PSScriptRoot ".bridge-state.json"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  AgentForge 双 Agent 桥接调度器 (Codex <-> Pi Agent)" -ForegroundColor Green
Write-Host "  项目目录: $ProjectRoot" -ForegroundColor Gray
Write-Host "  轮询间隔: $PollIntervalSeconds 秒 | 额度重置周期: $QuotaHours 小时" -ForegroundColor Gray
Write-Host "==========================================================" -ForegroundColor Cyan

# 读取持久化状态
$LastReviewedCommit = ""
if (Test-Path $StateCacheFile) {
    try {
        $State = Get-Content $StateCacheFile -Raw | ConvertFrom-Json
        $LastReviewedCommit = $State.LastReviewedCommit
    } catch {
        $LastReviewedCommit = ""
    }
}

function Save-State([string]$CommitHash, [string]$LatestReport) {
    $StateObj = [PSCustomObject]@{
        LastReviewedCommit = $CommitHash
        LastReviewReport   = $LatestReport
        UpdatedAt          = (Get-Date).ToString("o")
    }
    $StateObj | ConvertTo-Json | Set-Content $StateCacheFile -Encoding UTF8
}

function Handle-QuotaWait([double]$Hours, [double]$SimMins) {
    $WaitDuration = if ($SimMins -gt 0) {
        [TimeSpan]::FromMinutes($SimMins)
    } else {
        [TimeSpan]::FromHours($Hours)
    }

    $StartTime = Get-Date
    $ResumeTime = $StartTime.Add($WaitDuration)

    Write-Host "`n[!IMPORTANT] 触发 Codex 额度耗尽等待机制！" -ForegroundColor Red
    Write-Host "中断时间: $($StartTime.ToString('yyyy-MM-dd HH:mm:ss'))" -ForegroundColor Yellow
    Write-Host "预计恢复时间: $($ResumeTime.ToString('yyyy-MM-dd HH:mm:ss'))" -ForegroundColor Yellow
    Write-Host "等待时长: $($WaitDuration.TotalMinutes) 分钟 ($($WaitDuration.TotalHours) 小时)" -ForegroundColor Yellow
    Write-Host "在等待期间，后台将确保 Pi Agent 审查报告就绪，待额度恢复后自动生成唤醒信号。`n" -ForegroundColor Gray

    # 移除触发文件
    if (Test-Path $QuotaSignalFile) {
        Remove-Item $QuotaSignalFile -Force -ErrorAction SilentlyContinue
    }

    while ((Get-Date) -lt $ResumeTime) {
        $Remaining = $ResumeTime - (Get-Date)
        $HoursLeft = [int]$Remaining.TotalHours
        $MinsLeft = $Remaining.Minutes
        $SecsLeft = $Remaining.Seconds

        Write-Host -NoNewline "`r[额度等待倒计时] 剩余: $HoursLeft 小时 $MinsLeft 分 $SecsLeft 秒 ...   " -ForegroundColor DarkYellow
        Start-Sleep -Seconds ([Math]::Min(30, [Math]::Max(1, [int]$Remaining.TotalSeconds)))
    }

    Write-Host "`n`n==========================================================" -ForegroundColor Green
    Write-Host "[SUCCESS] 5 小时额度等待已结束！Codex 额度已恢复！" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green

    # 查找最新审查报告
    $LatestReport = Get-ChildItem (Join-Path $ProjectRoot "docs\08-reviews\*.md") -Exclude "README.md" |
                    Sort-Object LastWriteTime -Descending | Select-Object -First 1

    $ReportPathRelative = if ($LatestReport) { "docs/08-reviews/$($LatestReport.Name)" } else { "无新增报告" }

    # 生成自动续跑信号文档
    $ResumeContent = @"
# Codex 额度恢复与自动续跑指令

- 恢复时间: $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
- 关联代码审查报告: [$ReportPathRelative]($ReportPathRelative)
- 调度状态: READY_TO_RESUME

## 续跑指令

请主开发 Agent (Codex) 立即执行以下续跑动作：
1. 打开并阅读审查报告：`$ReportPathRelative`；
2. 针对报告中提出的各项 Issue（Bug、权限安全、API契约、测试覆盖）进行技术研判：
   - 采纳的问题：在代码中完成修复，并补充自动化单元/集成测试；
   - 误报或豁免的问题：在审查报告第 3 节填写技术理由；
3. 运行完整测试验证（如 `mvn verify`）；
4. 修复与验证完成后，回填审查报告，并向用户汇报阶段完成情况，准备进入下一开发阶段。
"@

    [System.IO.File]::WriteAllText($ResumeSignalFile, $ResumeContent, [System.Text.Encoding]::UTF8)

    Write-Host "已生成续跑信号文档: $ResumeSignalFile" -ForegroundColor Cyan
    Write-Host "Codex 可随时读取此文档恢复任务！`n" -ForegroundColor Green
    [Console]::Beep(1000, 500)
}

if ($TriggerQuotaWait) {
    Handle-QuotaWait -Hours $QuotaHours -SimMins $SimulationMinutes
}

Write-Host "开始监听阶段提交与额度事件 (按 Ctrl+C 停止)...`n" -ForegroundColor Gray

while ($true) {
    try {
        # 1. 检测是否收到额度耗尽信号文件
        if (Test-Path $QuotaSignalFile) {
            Handle-QuotaWait -Hours $QuotaHours -SimMins $SimulationMinutes
        }

        # 2. 获取当前 Git HEAD Commit
        $CurrentCommit = git rev-parse HEAD 2>$null
        if ($CurrentCommit -and ($CurrentCommit -ne $LastReviewedCommit)) {
            $CommitMsg = git log -1 --pretty=format:"%s" $CurrentCommit 2>$null

            # 判断是否为阶段性重要提交（如 feat, fix, chore(stage), day-X 等，或直接对任何新提交审查）
            Write-Host "[新提交感知] 检测到新 Commit: $CurrentCommit ($CommitMsg)" -ForegroundColor Green
            Write-Host "正在自动拉起 Pi Agent 进行只读代码审查..." -ForegroundColor Cyan

            # 调用 run-review.ps1
            & $RunReviewScript -BaseRef "$CurrentCommit~1" -TargetRef $CurrentCommit

            $LastReviewedCommit = $CurrentCommit

            $LatestReport = Get-ChildItem (Join-Path $ProjectRoot "docs\08-reviews\*.md") -Exclude "README.md" |
                            Sort-Object LastWriteTime -Descending | Select-Object -First 1
            $ReportPath = if ($LatestReport) { $LatestReport.FullName } else { "" }

            Save-State -CommitHash $CurrentCommit -LatestReport $ReportPath

            Write-Host "[审查完成] 已生成审查报告，保存在: $ReportPath" -ForegroundColor Green
            Write-Host "Codex 可在阶段检查点阅读该报告进行修复。`n" -ForegroundColor Yellow
        }
    } catch {
        Write-Warning "监控循环中发生异常: $_"
    }

    Start-Sleep -Seconds $PollIntervalSeconds
}
