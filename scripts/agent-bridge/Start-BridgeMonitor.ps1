<#
.SYNOPSIS
    在当前开发工作树中隐藏启动 bridge-monitor，并避免重复启动。
#>

[CmdletBinding()]
param(
    [ValidateRange(5, 3600)]
    [int]$PollIntervalSeconds = 15,
    [ValidateRange(60, 1800)]
    [int]$ReviewTimeoutSeconds = 300
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$MonitorScript = Join-Path $PSScriptRoot "bridge-monitor.ps1"
$PidFile = Join-Path $PSScriptRoot ".bridge-monitor.pid"
$LogFile = Join-Path $PSScriptRoot ".bridge-monitor.log"
$ErrorLogFile = Join-Path $PSScriptRoot ".bridge-monitor.error.log"
$existingProcess = $null
if (Test-Path -LiteralPath $PidFile) {
    $existingPid = Get-Content -Raw -LiteralPath $PidFile
    if ($existingPid -match '^\d+$') {
        $existingProcess = Get-Process -Id ([int]$existingPid) -ErrorAction SilentlyContinue
    }
}

if ($null -ne $existingProcess) {
    [PSCustomObject]@{ Started = $false; ProcessId = $existingProcess.Id; LogFile = $LogFile; ErrorLogFile = $ErrorLogFile }
    return
}

$argumentLine = "-NoProfile -ExecutionPolicy Bypass -File `"$MonitorScript`" -PollIntervalSeconds $PollIntervalSeconds -ReviewTimeoutSeconds $ReviewTimeoutSeconds"
$process = Start-Process -FilePath "powershell.exe" -ArgumentList $argumentLine `
    -WorkingDirectory (Resolve-Path (Join-Path $PSScriptRoot "..\..")) `
    -RedirectStandardOutput $LogFile `
    -RedirectStandardError $ErrorLogFile `
    -WindowStyle Hidden `
    -PassThru
[System.IO.File]::WriteAllText($PidFile, $process.Id.ToString(), [System.Text.UTF8Encoding]::new($false))
[PSCustomObject]@{ Started = $true; ProcessId = $process.Id; LogFile = $LogFile; ErrorLogFile = $ErrorLogFile }
