[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runner = Join-Path $PSScriptRoot "v2-release-regression.ps1"
if (-not (Test-Path -LiteralPath $runner)) {
    throw "V2 release regression runner does not exist: $runner"
}

$raw = & $runner -Plan -Json
if ($LASTEXITCODE -ne 0) {
    throw "V2 release regression plan failed with exit code $LASTEXITCODE."
}
$plan = ($raw -join "`n") | ConvertFrom-Json

$expectedStages = @(
    "gate-planner-contract",
    "compose-config",
    "java-clean-verify",
    "python-test",
    "web-test",
    "web-build",
    "rag-cross-process",
    "tool-hitl-cross-process",
    "restart-resume-cross-process",
    "evaluation",
    "full-stack-acceptance"
)

if ($plan.release -ne "V2") {
    throw "Expected V2 release plan, got '$($plan.release)'."
}
if ($plan.riskLevel -ne "L3" -or -not $plan.failClosed) {
    throw "Release plan must be L3 and fail closed."
}
if (@($plan.stages).Count -ne $expectedStages.Count) {
    throw "Expected $($expectedStages.Count) stages, got $(@($plan.stages).Count)."
}
for ($index = 0; $index -lt $expectedStages.Count; $index++) {
    if ($plan.stages[$index].id -ne $expectedStages[$index]) {
        throw "Stage $index should be '$($expectedStages[$index])', got '$($plan.stages[$index].id)'."
    }
}
if (@($plan.coverage) -notcontains "Cross Project / Unauthorized") {
    throw "Release plan does not declare the isolation and authorization coverage."
}
if (@($plan.coverage) -notcontains "Service Restart / Resume") {
    throw "Release plan does not declare restart/resume coverage."
}

$executionRaw = & $runner -Only "gate-planner-contract" -Json
if ($LASTEXITCODE -ne 0) {
    throw "Single-stage release execution failed with exit code $LASTEXITCODE."
}
$execution = ($executionRaw -join "`n") | ConvertFrom-Json
if ($execution.status -ne "PASS" -or @($execution.completedStages) -notcontains "gate-planner-contract") {
    throw "Single-stage release execution did not report its completed gate."
}

$invalidFailed = $false
try {
    & $runner -Only "not-a-release-stage" -Json | Out-Null
} catch {
    $invalidFailed = $_.Exception.Message -like "*Unknown release stage*"
}
if (-not $invalidFailed) {
    throw "Unknown release stages must fail closed."
}

Write-Host "V2 release regression contract passed: $($expectedStages.Count) ordered stages and fail-closed selection."
