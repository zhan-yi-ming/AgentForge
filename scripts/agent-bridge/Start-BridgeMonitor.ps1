<#
.SYNOPSIS
    在当前开发工作树中隐藏启动 bridge-monitor，并避免重复启动。
#>

[CmdletBinding()]
param(
    [ValidateRange(5, 3600)]
    [int]$PollIntervalSeconds = 15,
    [ValidateRange(60, 1800)]
    [int]$ReviewTimeoutSeconds = 300,
    [switch]$Visible
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
$powerShellHost = Get-Command pwsh.exe -ErrorAction SilentlyContinue
if ($null -eq $powerShellHost) {
    throw "未找到 pwsh.exe；为避免 Windows PowerShell 对 UTF-8 bridge 脚本的编码损坏，monitor 未启动。"
}
$startArguments = @{ FilePath=$powerShellHost.Source; ArgumentList=$argumentLine; WorkingDirectory=(Resolve-Path (Join-Path $PSScriptRoot "..\..")); PassThru=$true }
if ($Visible) {
    $startArguments.WindowStyle = "Normal"
} else {
    $startArguments.RedirectStandardOutput = $LogFile
    $startArguments.RedirectStandardError = $ErrorLogFile
    $startArguments.WindowStyle = "Hidden"
}
$process = Start-Process @startArguments
[System.IO.File]::WriteAllText($PidFile, $process.Id.ToString(), [System.Text.UTF8Encoding]::new($false))
[PSCustomObject]@{ Started = $true; ProcessId = $process.Id; LogFile = $LogFile; ErrorLogFile = $ErrorLogFile }
