<#
.SYNOPSIS
    后台触发 review-loop，并在额度耗尽时生成可恢复的续跑信号。
#>

[CmdletBinding()]
param(
    [ValidateRange(5, 3600)]
    [int]$PollIntervalSeconds = 15,
    [ValidateRange(60, 1800)]
    [int]$ReviewTimeoutSeconds = 300,
    [ValidateRange(0.01, 720)]
    [double]$QuotaHours = 5.0,
    [switch]$TriggerQuotaWait,
    [ValidateRange(0, 1440)]
    [double]$SimulationMinutes = 0,
    [switch]$Once
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ReviewLoopScript = Join-Path $PSScriptRoot "review-loop.ps1"
$QuotaSignalFile = Join-Path $ProjectRoot ".codex-quota-exhausted"
$ResumeSignalFile = Join-Path $ProjectRoot ".codex-resume-signal.md"

function Handle-QuotaWait {
    $waitDuration = if ($SimulationMinutes -gt 0) {
        [TimeSpan]::FromMinutes($SimulationMinutes)
    } else {
        [TimeSpan]::FromHours($QuotaHours)
    }
    $resumeTime = (Get-Date).Add($waitDuration)
    if (Test-Path -LiteralPath $QuotaSignalFile) {
        Remove-Item -LiteralPath $QuotaSignalFile -Force
    }
    while ((Get-Date) -lt $resumeTime) {
        $remaining = $resumeTime - (Get-Date)
        Write-Host "[额度等待] 剩余 $([int]$remaining.TotalHours) 小时 $($remaining.Minutes) 分钟" -ForegroundColor Yellow
        Start-Sleep -Seconds ([Math]::Min(30, [Math]::Max(1, [int]$remaining.TotalSeconds)))
    }
    $latestReport = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "docs\08-reviews") -File -Filter "*.md" |
        Where-Object { $_.Name -ne "README.md" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $reportPath = if ($null -eq $latestReport) { "无" } else { "docs/08-reviews/$($latestReport.Name)" }
    $content = @(
        "# Codex 额度恢复与审查续跑指令"
        ""
        "- 恢复时间：$((Get-Date).ToString('o'))"
        "- 最新审查报告：$reportPath"
        ""
        "1. 运行 review-loop.ps1 -OnCodexWake。"
        "2. 若状态为 WAITING_FOR_CODEX_FIX，读取报告、文档先行修复并测试。"
        "3. 若状态为 HUMAN_REQUIRED，停止并请求用户决定。"
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($ResumeSignalFile, $content, [System.Text.UTF8Encoding]::new($false))
}

function Invoke-ReviewLoop {
    $result = & $ReviewLoopScript -OnMonitorWake -TimeoutSeconds $ReviewTimeoutSeconds
    if ($LASTEXITCODE -ne 0 -or $null -eq $result -or
            [string]::IsNullOrWhiteSpace([string]$result.OverallStatus) -or $null -eq $result.Actions) {
        throw "review-loop 未返回有效结果。"
    }
    Write-Host "[review-loop] $($result.OverallStatus)" -ForegroundColor Cyan
    foreach ($action in $result.Actions) {
        Write-Host "  - $($action.Stage): $($action.Action)" -ForegroundColor Gray
    }
    return $result
}

if ($TriggerQuotaWait) { Handle-QuotaWait }

do {
    try {
        if (Test-Path -LiteralPath $QuotaSignalFile) { Handle-QuotaWait }
        $result = Invoke-ReviewLoop
        if ($result.OverallStatus -eq "HUMAN_REQUIRED") {
            Write-Warning "审查循环已进入 HUMAN_REQUIRED，monitor 停止自动审查。"
            break
        }
    } catch {
        Write-Warning "bridge-monitor 本轮失败：$($_.Exception.Message)"
    }
    if (-not $Once) { Start-Sleep -Seconds $PollIntervalSeconds }
} while (-not $Once)
