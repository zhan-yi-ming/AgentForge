[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$reviewLoop = Join-Path $PSScriptRoot "review-loop.ps1"
$showStatus = Join-Path $PSScriptRoot "Show-ReviewStatus.ps1"
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

    $busyState = Join-Path $testRoot "busy-state.json"
    $heldLock = [System.IO.File]::Open("$busyState.lock", [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    try {
        $busy = & $reviewLoop -OnCodexWake -StatePath $busyState
        Assert-True ($busy.OverallStatus -eq "BUSY") "锁占用时未返回 BUSY。"
    } finally { $heldLock.Dispose() }

    $dryState = Join-Path $testRoot "dry-state.json"
    $dryRun = & $reviewLoop -DryRun -StatePath $dryState
    Assert-True ($dryRun.OverallStatus -eq "DRY_RUN") "DryRun 状态不正确。"
    Assert-True (-not (Test-Path -LiteralPath $dryState)) "DryRun 不应写状态文件。"

    $status = (& $showStatus -Json) | ConvertFrom-Json
    Assert-True (-not [string]::IsNullOrWhiteSpace($status.overallStatus)) "状态 JSON 缺少 overallStatus。"
    [PSCustomObject]@{ Passed = $true; ProjectRoot = $projectRoot; Checks = 6 }
} finally {
    Set-Location $originalLocation
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
