[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runner = Join-Path $PSScriptRoot "v3-release-regression.ps1"
if (-not (Test-Path -LiteralPath $runner)) {
    throw "V3 release regression runner does not exist: $runner"
}

$raw = & $runner -Plan -Json
if ($LASTEXITCODE -ne 0) {
    throw "V3 release regression plan failed with exit code $LASTEXITCODE."
}
$plan = ($raw -join "`n") | ConvertFrom-Json

$expectedStages = @(
    "runner-contract",
    "v2-release-regression",
    "java-python-contracts",
    "repository-context-contract"
)
$expectedCapabilities = @(
    "RAG / GraphRAG",
    "Entity Resolution",
    "HITL / Risk / Approval / Audit",
    "Retry / Resume",
    "Langfuse / Evaluation",
    "MCP",
    "LiteLLM / Fallback / Multi-model Routing",
    "Git Repository Context"
)

if ($plan.release -ne "V3") {
    throw "Expected V3 release plan, got '$($plan.release)'."
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
foreach ($capability in $expectedCapabilities) {
    if (@($plan.coverage) -notcontains $capability) {
        throw "V3 release plan does not declare '$capability' coverage."
    }
}
if (-not $plan.conditions.javaPythonContract -or -not $plan.conditions.graphContract -or
    -not $plan.conditions.repositoryContract) {
    throw "Conditional Java/Python, GraphRAG and Repository contracts must be explicitly enabled."
}
if ($plan.conditions.javaPythonContractTests -ne 11 -or $plan.conditions.graphContractTests -ne 1) {
    throw "Expected 11 Java/Python tests and exactly 1 default-skipped GraphRAG contract test."
}
if (-not $plan.conditions.stageEnvironmentIsolation -or -not $plan.conditions.childExitCodesChecked -or
    -not $plan.conditions.runtimeRandomCredentials) {
    throw "Release stages must restore their environment, check child exit codes and use runtime-random credentials."
}

$executionRaw = & $runner -Only "runner-contract" -Json
if ($LASTEXITCODE -ne 0) {
    throw "Single-stage V3 release execution failed with exit code $LASTEXITCODE."
}
$execution = ($executionRaw -join "`n") | ConvertFrom-Json
if ($execution.status -ne "PASS_PARTIAL" -or -not $execution.partial -or
    @($execution.requestedStages).Count -ne 1 -or $execution.requestedStages[0] -ne "runner-contract" -or
    @($execution.completedStages) -notcontains "runner-contract") {
    throw "Single-stage V3 release execution did not report its completed gate."
}

$invalidFailed = $false
try {
    & $runner -Only "not-a-release-stage" -Json | Out-Null
} catch {
    $invalidFailed = $_.Exception.Message -like "*Unknown release stage*"
}
if (-not $invalidFailed) {
    throw "Unknown V3 release stages must fail closed."
}

Write-Host "V3 release regression contract passed: $($expectedStages.Count) ordered stages, complete capability declaration and fail-closed selection."
