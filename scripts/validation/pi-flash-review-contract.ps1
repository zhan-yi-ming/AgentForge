[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$reviewScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'agent-bridge/run-review.ps1'

function Get-ReviewFailure {
    param([string]$Model, [switch]$UseDefault)
    try {
        $reviewArgs = @{ StageName='pi-flash-contract'; BaseRef='HEAD'; TargetRef='HEAD'; TimeoutSeconds=60 }
        if (-not $UseDefault) { $reviewArgs.Model = $Model }
        & $reviewScript @reviewArgs 2>&1 | Out-Null
        throw 'A review with no diff unexpectedly succeeded.'
    } catch {
        return $_.Exception.Message
    }
}

$flashFailure = Get-ReviewFailure -Model 'deepseek/deepseek-flash'
if ($flashFailure -notmatch '审查范围没有文件变化') {
    throw "The configured Flash model did not pass preflight before the empty-diff guard: $flashFailure"
}

$defaultFailure = Get-ReviewFailure -UseDefault
if ($defaultFailure -notmatch '审查范围没有文件变化') {
    throw "The default review model did not pass preflight before the empty-diff guard: $defaultFailure"
}

$oldModelFailure = Get-ReviewFailure -Model 'deepseek/deepseek-v4-pro'
if ($oldModelFailure -notmatch 'ValidateSet|验证集|validation set') {
    throw "The former V4-pro model was not rejected by the public review entrypoint: $oldModelFailure"
}

$legacyFlashFailure = Get-ReviewFailure -Model 'deepseek/deepseek-v4-flash'
if ($legacyFlashFailure -notmatch 'ValidateSet|验证集|validation set') {
    throw "The retired V4 Flash compatibility model was not rejected: $legacyFlashFailure"
}

Write-Host 'Pi V4.1 Flash review contract passed: explicit/default deepseek-flash selected; legacy models rejected; no diff sent.'
