[CmdletBinding()]
param(
    [ValidateRange(1, 200)][int]$Tail = 20,
    [switch]$Json,
    [switch]$Watch,
    [ValidateRange(1, 60)][int]$RefreshSeconds = 2,
    [ValidateRange(30, 3600)][int]$StallThresholdSeconds = 120,
    [string]$StateDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { return Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json }
    catch { return [PSCustomObject]@{ status = "INVALID"; error = $_.Exception.Message; processId = $null; model = $null; stage = $null; attempt = $null } }
}

function Get-FileInfo([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $item = Get-Item -LiteralPath $Path
    [PSCustomObject]@{ path = $Path; lastWriteAt = $item.LastWriteTime.ToString("o"); ageSeconds = [int]((Get-Date) - $item.LastWriteTime).TotalSeconds }
}

function Get-Snapshot {
    $runtimeDirectory = if ([string]::IsNullOrWhiteSpace($StateDirectory)) { $PSScriptRoot } else { $StateDirectory }
    $pidFile = Join-Path $runtimeDirectory ".bridge-monitor.pid"
    $stateFile = Join-Path $runtimeDirectory ".review-loop-state.json"
    $lockFile = "$stateFile.lock"
    $statusFile = Join-Path $runtimeDirectory ".pi-review-status.json"
    $liveLog = Join-Path $runtimeDirectory ".pi-live-output.log"
    $errorLog = Join-Path $runtimeDirectory ".pi-live-error.log"
    $monitorLog = Join-Path $runtimeDirectory ".bridge-monitor.log"
    $monitorErrorLog = Join-Path $runtimeDirectory ".bridge-monitor.error.log"

    $monitorPid = $null
    if (Test-Path -LiteralPath $pidFile) {
        $pidText = (Get-Content -Raw -LiteralPath $pidFile).Trim()
        if ($pidText -match '^\d+$') { $monitorPid = [int]$pidText }
    }
    $monitorProcess = if ($null -eq $monitorPid) { $null } else { Get-Process -Id $monitorPid -ErrorAction SilentlyContinue }
    $piStatus = Read-JsonFile $statusFile
    $piProcess = if ($null -ne $piStatus -and $null -ne $piStatus.processId) { Get-Process -Id ([int]$piStatus.processId) -ErrorAction SilentlyContinue } else { $null }
    $lockHeld = $false
    if (Test-Path -LiteralPath $lockFile) {
        try {
            $probe = [System.IO.File]::Open($lockFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $probe.Dispose()
        } catch [System.IO.IOException] { $lockHeld = $true }
    }
    $state = Read-JsonFile $stateFile
    $stages = @()
    if ($null -ne $state -and $null -ne $state.stages) {
        $stages = @($state.stages.PSObject.Properties | ForEach-Object {
            [PSCustomObject]@{ stage = $_.Name; status = $_.Value.status; attempts = $_.Value.attempts; updatedAt = $_.Value.updatedAt; report = $_.Value.lastReportPath; failure = $_.Value.lastFailure }
        })
    }
    $lockInfo = Get-FileInfo $lockFile
    $piRunning = $null -ne $piProcess -and $null -ne $piStatus -and $piStatus.status -eq "RUNNING"
    $overall = if ($piRunning) { "RUNNING" }
        elseif ($null -eq $monitorProcess) { "STOPPED" }
        elseif ($lockHeld -and $null -ne $lockInfo -and $lockInfo.ageSeconds -ge $StallThresholdSeconds) { "STALLED" }
        elseif ($stages.status -contains "HUMAN_REQUIRED") { "HUMAN_REQUIRED" }
        elseif ($stages.status -contains "WAITING_FOR_CODEX_FIX") { "WAITING_FOR_CODEX_FIX" }
        elseif ($stages.status -contains "PENDING_REVIEW") { "PENDING_REVIEW" }
        else { "IDLE" }

    [PSCustomObject]@{
        generatedAt = (Get-Date).ToString("o")
        overallStatus = $overall
        monitor = [PSCustomObject]@{ running = ($null -ne $monitorProcess); processId = $monitorPid }
        lock = [PSCustomObject]@{ held = $lockHeld; details = $lockInfo }
        pi = [PSCustomObject]@{ running = $piRunning; status = $piStatus }
        stages = $stages
        recentPiOutput = @(if (Test-Path -LiteralPath $liveLog) { Get-Content -LiteralPath $liveLog -Tail $Tail })
        recentPiErrors = @(if (Test-Path -LiteralPath $errorLog) { Get-Content -LiteralPath $errorLog -Tail $Tail })
        recentMonitorOutput = @(if (Test-Path -LiteralPath $monitorLog) { Get-Content -LiteralPath $monitorLog -Tail $Tail })
        recentMonitorErrors = @(if (Test-Path -LiteralPath $monitorErrorLog) { Get-Content -LiteralPath $monitorErrorLog -Tail $Tail })
    }
}

function Write-HumanStatus($Snapshot) {
    $color = if ($Snapshot.overallStatus -in @("STALLED", "STOPPED", "HUMAN_REQUIRED")) { "Yellow" } else { "Cyan" }
    Write-Host "AgentForge Pi Review Status: $($Snapshot.overallStatus)" -ForegroundColor $color
    Write-Host "Monitor: $(if ($Snapshot.monitor.running) { "RUNNING (PID $($Snapshot.monitor.processId))" } else { "STOPPED" })"
    Write-Host "Lock: $(if ($Snapshot.lock.held) { "HELD ($($Snapshot.lock.details.ageSeconds)s)" } else { "FREE" })"
    if ($null -ne $Snapshot.pi.status) {
        Write-Host "Pi: $($Snapshot.pi.status.status) | model=$($Snapshot.pi.status.model) | stage=$($Snapshot.pi.status.stage) | attempt=$($Snapshot.pi.status.attempt) | PID=$($Snapshot.pi.status.processId)"
    } else { Write-Host "Pi: NOT STARTED" }
    if ($Snapshot.stages.Count -gt 0) { $Snapshot.stages | Format-Table stage, status, attempts, updatedAt -AutoSize }
    if ($Snapshot.recentPiOutput.Count -gt 0) {
        Write-Host "Recent Pi output:" -ForegroundColor DarkCyan
        $Snapshot.recentPiOutput | ForEach-Object { Write-Host "  $_" }
    }
    if ($Snapshot.recentMonitorErrors.Count -gt 0) {
        Write-Host "Recent monitor errors:" -ForegroundColor Yellow
        $Snapshot.recentMonitorErrors | ForEach-Object { Write-Host "  $_" }
    }
}

do {
    if ($Watch -and -not $Json) { Clear-Host }
    $snapshot = Get-Snapshot
    if ($Json) { $snapshot | ConvertTo-Json -Depth 8 } else { Write-HumanStatus $snapshot }
    if ($Watch) { Start-Sleep -Seconds $RefreshSeconds }
} while ($Watch)
