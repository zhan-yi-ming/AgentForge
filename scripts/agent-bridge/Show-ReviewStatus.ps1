[CmdletBinding()]
param([ValidateRange(1, 200)][int]$Tail = 30)

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$pidFile = Join-Path $PSScriptRoot ".bridge-monitor.pid"
$statusFile = Join-Path $PSScriptRoot ".pi-review-status.json"
$liveLog = Join-Path $PSScriptRoot ".pi-live-output.log"
$monitorPidText = if(Test-Path $pidFile){(Get-Content -Raw $pidFile).Trim()}else{$null}
[PSCustomObject]@{
    monitorPid=$monitorPidText
    monitorRunning=($null -ne $monitorPidText -and $null -ne (Get-Process -Id $monitorPidText -ErrorAction SilentlyContinue))
    piStatus=$(if(Test-Path $statusFile){Get-Content -Raw $statusFile|ConvertFrom-Json}else{$null})
    recentPiOutput=$(if(Test-Path $liveLog){Get-Content $liveLog -Tail $Tail}else{@()})
} | ConvertTo-Json -Depth 6
