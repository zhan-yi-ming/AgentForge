[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$reviewLoop = Join-Path $PSScriptRoot "review-loop.ps1"
$showStatus = Join-Path $PSScriptRoot "Show-ReviewStatus.ps1"
. (Join-Path $PSScriptRoot "review-state.ps1")
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("agentforge-review-test-" + [guid]::NewGuid().ToString("N"))
$originalLocation = Get-Location

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter "*.ps1") {
        $bytes = [System.IO.File]::ReadAllBytes($script.FullName)
        Assert-True ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) "缺少 UTF-8 BOM：$($script.Name)"
        $tokens = $null
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$tokens, [ref]$errors) | Out-Null
        Assert-True ($errors.Count -eq 0) "PowerShell AST 失败：$($script.Name)"
    }

    $invalidState = Join-Path $testRoot "invalid-state.json"
    [System.IO.File]::WriteAllText($invalidState, "{broken", [System.Text.UTF8Encoding]::new($false))
    try { & $reviewLoop -OnCodexWake -StatePath $invalidState | Out-Null } catch { }
    $releasedLock = [System.IO.File]::Open("$invalidState.lock", [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    $releasedLock.Dispose()

    $stage = @{ id = "v1-day-1" }
    $pass = ConvertTo-ReviewOutcome -Stage $stage -Result PASS -Attempt 3 -MaximumAttempts 3 -TargetCommit "abc123" -ReportPath "r.md"
    Assert-True ($pass.Status -eq "RESOLVED" -and $pass.NextStageReady -eq "v1-day-1" -and -not $pass.HumanIntervention) "PASS 迁移失败。"
    $third = ConvertTo-ReviewOutcome -Stage $stage -Result NEEDS_FIX -Attempt 3 -MaximumAttempts 3 -TargetCommit "abc123" -ReportPath "r.md"
    Assert-True ($third.Status -eq "HUMAN_REQUIRED" -and $third.HumanIntervention) "HUMAN_REQUIRED 迁移失败。"
    $first = ConvertTo-ReviewOutcome -Stage $stage -Result NEEDS_FIX -Attempt 1 -MaximumAttempts 3 -TargetCommit "abc123" -ReportPath "r.md"
    Assert-True ($first.Status -eq "WAITING_FOR_CODEX_FIX" -and -not $first.HumanIntervention) "WAITING_FOR_CODEX_FIX 迁移失败。"
    $passAgain = ConvertTo-ReviewOutcome -Stage $stage -Result PASS -Attempt 3 -MaximumAttempts 3 -TargetCommit "abc123" -ReportPath "r.md"
    Assert-True (($pass | ConvertTo-Json -Compress) -eq ($passAgain | ConvertTo-Json -Compress)) "PASS 迁移不是确定性的。"

    $stateOnlyStages = @{ "auto-stage" = @{ id="auto-stage"; displayName="Auto Stage"; initialBaseRef="base"; deliveryCommit="delivery" } }
    $withExplicitStateStage = @(Add-ExplicitStateStageDefinitions -Definitions @() -StateStages $stateOnlyStages -ExplicitStageIds @("auto-stage"))
    Assert-True ($withExplicitStateStage.Count -eq 1 -and $withExplicitStateStage[0].id -eq "auto-stage") "显式 state-only 阶段未加入调度定义。"
    $withoutExplicitStateStage = @(Add-ExplicitStateStageDefinitions -Definitions @() -StateStages $stateOnlyStages -ExplicitStageIds @())
    Assert-True ($withoutExplicitStateStage.Count -eq 0) "未引用的 state-only 阶段不应加入调度定义。"

    $busyState = Join-Path $testRoot "busy-state.json"
    [System.IO.File]::WriteAllText($busyState, '{"schemaVersion":1,"stages":{"v1-day-1":{"id":"v1-day-1","attempts":1,"status":"WAITING_FOR_CODEX_FIX","deliveryCommit":"0000"}},"nextStageReady":null}', [System.Text.UTF8Encoding]::new($false))
    $heldLock = [System.IO.File]::Open("$busyState.lock", [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
        $busy = & $reviewLoop -OnCodexWake -StatePath $busyState
        Assert-True ($busy.OverallStatus -eq "BUSY") "锁占用时未返回 BUSY。"
    } finally { $heldLock.Dispose() }
    $busyAfter = Get-Content -Raw -LiteralPath $busyState | ConvertFrom-Json
    Assert-True ([int]$busyAfter.stages.'v1-day-1'.attempts -eq 1) "锁竞争消耗了 attempt。"

    $dryState = Join-Path $testRoot "dry-state.json"
    $dryRun = & $reviewLoop -DryRun -StatePath $dryState
    Assert-True ($dryRun.OverallStatus -eq "DRY_RUN") "DryRun 状态不正确。"
    Assert-True (-not (Test-Path -LiteralPath $dryState)) "DryRun 不应写状态文件。"

    [System.IO.File]::WriteAllText((Join-Path $testRoot ".pi-review-status.json"), "{broken", [System.Text.UTF8Encoding]::new($false))
    $status = (& $showStatus -StateDirectory $testRoot -Json) | ConvertFrom-Json
    Assert-True (-not [string]::IsNullOrWhiteSpace($status.overallStatus)) "状态 JSON 缺少 overallStatus。"
    Assert-True ($status.pi.status.status -eq "INVALID") "损坏的 Pi 状态未报告 INVALID。"

    [System.IO.File]::WriteAllText((Join-Path $testRoot ".bridge-monitor.pid"), "$PID", [System.Text.UTF8Encoding]::new($false))
    $statusLock = Join-Path $testRoot ".review-loop-state.json.lock"
    [System.IO.File]::WriteAllText($statusLock, "", [System.Text.UTF8Encoding]::new($false))
    (Get-Item -LiteralPath $statusLock).LastWriteTime = (Get-Date).AddSeconds(-60)
    $heldStatusLock = [System.IO.File]::Open($statusLock, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
        $stalled = (& $showStatus -StateDirectory $testRoot -Json -StallThresholdSeconds 30) | ConvertFrom-Json
        Assert-True ($stalled.overallStatus -eq "STALLED" -and $stalled.lock.held) "STALLED 判断失败。"
    } finally { $heldStatusLock.Dispose() }

    [PSCustomObject]@{ Passed = $true; ProjectRoot = $projectRoot; Checks = 14 }
} finally {
    Set-Location $originalLocation
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
