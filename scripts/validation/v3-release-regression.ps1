[CmdletBinding()]
param(
    [switch]$Plan,
    [string[]]$Only = @(),
    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$stages = @(
    [ordered]@{ id = "runner-contract"; description = "Validation planner and V2/V3 runner CLI contracts" },
    [ordered]@{ id = "v2-release-regression"; description = "Complete current-source V1/V2 release baseline" },
    [ordered]@{ id = "java-python-contracts"; description = "Real Java to Python Chat, Resume, Entity Resolution and GraphRAG contracts" },
    [ordered]@{ id = "repository-context-contract"; description = "Real Python Repository citation through Java JSON and stream clients" }
)

$coverage = @(
    "RAG / GraphRAG",
    "Entity Resolution",
    "HITL / Risk / Approval / Audit",
    "Retry / Resume",
    "Langfuse / Evaluation",
    "MCP",
    "LiteLLM / Fallback / Multi-model Routing",
    "Git Repository Context"
)

if ($Plan) {
    $result = [ordered]@{
        release = "V3"
        riskLevel = "L3"
        failClosed = $true
        stages = $stages
        coverage = $coverage
        conditions = [ordered]@{
            javaPythonContract = $true
            javaPythonContractTests = 11
            graphContract = $true
            graphContractTests = 1
            repositoryContract = $true
            stageEnvironmentIsolation = $true
            childExitCodesChecked = $true
            runtimeRandomCredentials = $true
        }
    }
    if ($Json) { $result | ConvertTo-Json -Depth 6 }
    else {
        Write-Host "V3 Release Regression (L3, fail closed)"
        foreach ($stage in $stages) { Write-Host "- $($stage.id): $($stage.description)" }
    }
    exit 0
}

$stageById = @{}
foreach ($stage in $stages) { $stageById[$stage.id] = $stage }
$selectedStages = if ($Only.Count -eq 0) {
    @($stages)
} else {
    foreach ($stageId in $Only) {
        if (-not $stageById.ContainsKey($stageId)) {
            throw "Unknown release stage '$stageId'. Use -Plan to list valid stages."
        }
        $stageById[$stageId]
    }
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$coreRoot = Join-Path $projectRoot "services\core-api"
$agentRoot = Join-Path $projectRoot "services\agent-service"
$composeFile = Join-Path $projectRoot "infra\compose.yaml"
$python = Join-Path $agentRoot ".venv\Scripts\python.exe"
$runId = [guid]::NewGuid().ToString("N")
$completedStages = [System.Collections.Generic.List[string]]::new()
$startedAt = [DateTimeOffset]::UtcNow

function Write-StageOutput([object[]]$Output) {
    if (-not $Json -and $Output.Count -gt 0) { $Output | Out-Host }
}

function Invoke-NativeChecked {
    param([scriptblock]$Action, [string]$Description)
    $output = @(& $Action 2>&1)
    $exitCode = $LASTEXITCODE
    Write-StageOutput $output
    if ($exitCode -ne 0) { throw "$Description failed with exit code $exitCode." }
}

function Invoke-ChildChecked {
    param([scriptblock]$Action, [string]$Description, [switch]$Stream)
    $hadPreviousExitCode = Test-Path Variable:global:LASTEXITCODE
    $previousExitCode = if ($hadPreviousExitCode) { $global:LASTEXITCODE } else { $null }
    $exitCode = 0
    try {
        $global:LASTEXITCODE = 0
        if ($Stream) {
            & $Action
        } else {
            $output = @(& $Action 2>&1)
            Write-StageOutput $output
        }
        $exitCode = $LASTEXITCODE
    } finally {
        if ($hadPreviousExitCode) {
            $global:LASTEXITCODE = $previousExitCode
        } else {
            Remove-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue
        }
    }
    if ($exitCode -ne 0) { throw "$Description failed with exit code $exitCode." }
}

function Add-LoopbackNoProxy {
    $loopbackNoProxy = "127.0.0.1,localhost,::1"
    if ([string]::IsNullOrWhiteSpace($env:NO_PROXY)) {
        $env:NO_PROXY = $loopbackNoProxy
    } elseif ($env:NO_PROXY -notlike "*127.0.0.1*") {
        $env:NO_PROXY = "$($env:NO_PROXY),$loopbackNoProxy"
    }
}

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Wait-AgentHealth([int]$Port, [System.Diagnostics.Process]$Process) {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($Process.HasExited) { throw "Python Agent process exited before readiness." }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 1
            if ([string]$health.status -eq "UP") { return }
        } catch {
            Start-Sleep -Milliseconds 200
        }
    }
    throw "Timed out waiting for Python Agent health."
}

function Assert-SurefireResult([string[]]$Names, [int]$ExpectedTests) {
    $tests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    foreach ($name in $Names) {
        $report = Join-Path $coreRoot "target\surefire-reports\TEST-$name.xml"
        if (-not (Test-Path -LiteralPath $report)) { throw "Missing Surefire report: $report" }
        [xml]$suite = Get-Content -Raw -Encoding UTF8 $report
        $tests += [int]$suite.testsuite.tests
        $failures += [int]$suite.testsuite.failures
        $errors += [int]$suite.testsuite.errors
        $skipped += [int]$suite.testsuite.skipped
    }
    if ($tests -ne $ExpectedTests -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
        throw "Expected $ExpectedTests tests with zero failures/errors/skips; got tests=$tests failures=$failures errors=$errors skipped=$skipped."
    }
}

function Invoke-JavaPythonContracts {
    if (-not (Test-Path -LiteralPath $python)) {
        throw "Agent Service virtual environment is missing: $python"
    }
    $postgresPort = Get-FreeTcpPort
    $agentPort = Get-FreeTcpPort
    $composeProject = "agentforge-v309-contract-$($runId.Substring(0, 8))"
    $databasePassword = "v309_$([guid]::NewGuid().ToString('N'))"
    $agentToken = "v309-$([guid]::NewGuid().ToString('N'))"
    $coreToken = "v309-$([guid]::NewGuid().ToString('N'))"
    $agentOut = Join-Path $env:TEMP "agentforge-v309-$runId-agent.out.log"
    $agentErr = Join-Path $env:TEMP "agentforge-v309-$runId-agent.err.log"
    $agentProcess = $null
    $cleanupFailure = $null
    $stageSucceeded = $false
    $environmentNames = @(
        "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD",
        "AGENTFORGE_CORE_DB_PASSWORD", "AGENTFORGE_AGENT_DB_PASSWORD",
        "AGENTFORGE_AGENT_INTERNAL_TOKEN", "AGENTFORGE_CORE_INTERNAL_TOKEN", "AGENTFORGE_JWT_SECRET",
        "AGENTFORGE_AGENT_RAG_DB_DSN", "AGENTFORGE_AGENT_CHECKPOINT_DB_DSN",
        "AGENTFORGE_AGENT_RAG_ENABLED", "AGENTFORGE_AGENT_LLM_PROVIDER",
        "AGENTFORGE_AGENT_CONTRACT_TEST", "AGENTFORGE_AGENT_SERVICE_URL",
        "AGENTFORGE_RESOLUTION_SMOKE_URL", "NO_PROXY"
    )
    $environmentSnapshot = @{}
    foreach ($name in $environmentNames) {
        $environmentSnapshot[$name] = [Environment]::GetEnvironmentVariable(
            $name, [EnvironmentVariableTarget]::Process
        )
    }
    try {
        $env:POSTGRES_PORT = [string]$postgresPort
        $env:POSTGRES_DB = "agentforge"
        $env:POSTGRES_USER = "agentforge"
        $env:POSTGRES_PASSWORD = $databasePassword
        $env:AGENTFORGE_CORE_DB_PASSWORD = "v309_core_$runId"
        $env:AGENTFORGE_AGENT_DB_PASSWORD = "v309_agent_$runId"
        $env:AGENTFORGE_AGENT_INTERNAL_TOKEN = $agentToken
        $env:AGENTFORGE_CORE_INTERNAL_TOKEN = $coreToken
        $env:AGENTFORGE_JWT_SECRET = [Convert]::ToBase64String(
            [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
        )
        Invoke-NativeChecked -Description "V3 contract PostgreSQL startup" -Action {
            & docker compose -p $composeProject -f $composeFile up -d postgres
        }
        $postgresReady = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            & docker compose -p $composeProject -f $composeFile exec -T postgres `
                pg_isready -U agentforge -d agentforge *> $null
            if ($LASTEXITCODE -eq 0) { $postgresReady = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $postgresReady) { throw "V3 contract PostgreSQL did not become healthy." }
        Invoke-NativeChecked -Description "V3 checkpoint schema setup" -Action {
            & docker compose -p $composeProject -f $composeFile exec -T postgres `
                psql -U agentforge -d agentforge -v ON_ERROR_STOP=1 -c "CREATE SCHEMA agent_checkpoint"
        }

        $env:AGENTFORGE_AGENT_RAG_DB_DSN = "postgresql://agentforge:$databasePassword@127.0.0.1:$postgresPort/agentforge"
        $env:AGENTFORGE_AGENT_CHECKPOINT_DB_DSN = $env:AGENTFORGE_AGENT_RAG_DB_DSN
        $env:AGENTFORGE_AGENT_RAG_ENABLED = "false"
        $env:AGENTFORGE_AGENT_LLM_PROVIDER = "disabled"
        Add-LoopbackNoProxy
        $agentProcess = Start-Process -FilePath $python -ArgumentList @(
            "-m", "uvicorn", "agentforge_agent.main:app", "--host", "127.0.0.1", "--port", [string]$agentPort
        ) -WorkingDirectory $agentRoot -RedirectStandardOutput $agentOut -RedirectStandardError $agentErr `
            -WindowStyle Hidden -PassThru
        Wait-AgentHealth -Port $agentPort -Process $agentProcess

        $env:AGENTFORGE_AGENT_CONTRACT_TEST = "true"
        $env:AGENTFORGE_AGENT_SERVICE_URL = "http://127.0.0.1:$agentPort"
        $env:AGENTFORGE_RESOLUTION_SMOKE_URL = $env:AGENTFORGE_AGENT_SERVICE_URL
        Push-Location $coreRoot
        try {
            Invoke-NativeChecked -Description "Java/Python V3 contracts" -Action {
                & .\mvnw.cmd -q "-Dagentforge.graph.crossprocess=true" `
                    "-Dtest=AgentServiceHttpContractIntegrationTest,GraphResolutionAdvisorContractTest,GraphApiIntegrationTest#livePythonProcessConsumesGraphRetrievalContract" test
            }
        } finally {
            Pop-Location
        }
        Assert-SurefireResult -Names @(
            "com.agentforge.core.agent.infrastructure.AgentServiceHttpContractIntegrationTest",
            "com.agentforge.core.graph.GraphResolutionAdvisorContractTest",
            "com.agentforge.core.graph.GraphApiIntegrationTest"
        ) -ExpectedTests 13
        $stageSucceeded = $true
    } catch {
        if (Test-Path -LiteralPath $agentErr) { Get-Content -Tail 80 -LiteralPath $agentErr | Out-Host }
        throw
    } finally {
        if ($null -ne $agentProcess -and -not $agentProcess.HasExited) {
            Stop-Process -Id $agentProcess.Id -Force -ErrorAction SilentlyContinue
            $agentProcess.WaitForExit()
        }
        $cleanupOutput = @(& docker compose -p $composeProject -f $composeFile down -v --remove-orphans 2>&1)
        if ($LASTEXITCODE -ne 0) { $cleanupFailure = "V3 contract cleanup failed with exit code $LASTEXITCODE." }
        Write-StageOutput $cleanupOutput
        foreach ($log in @($agentOut, $agentErr)) {
            Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
        }
        foreach ($name in $environmentNames) {
            if ($null -eq $environmentSnapshot[$name]) {
                Remove-Item "Env:$name" -ErrorAction SilentlyContinue
            } else {
                Set-Item "Env:$name" $environmentSnapshot[$name]
            }
        }
        if ($null -ne $cleanupFailure) {
            if ($stageSucceeded) { throw $cleanupFailure }
            Write-Warning $cleanupFailure
        }
    }
}

function Invoke-ReleaseStage([string]$StageId) {
    switch ($StageId) {
        "runner-contract" {
            Invoke-ChildChecked -Description "Validation planner contract" -Action {
                & (Join-Path $PSScriptRoot "test-plan-change-gates.ps1") 6>&1
            }
            Invoke-ChildChecked -Description "V2 release runner contract" -Action {
                & (Join-Path $PSScriptRoot "test-v2-release-regression.ps1") 6>&1
            }
        }
        "v2-release-regression" {
            $previousNoProxy = $env:NO_PROXY
            $loopbackNoProxy = "127.0.0.1,localhost,::1"
            if ([string]::IsNullOrWhiteSpace($previousNoProxy)) {
                $env:NO_PROXY = $loopbackNoProxy
            } elseif ($previousNoProxy -notlike "*127.0.0.1*") {
                $env:NO_PROXY = "$previousNoProxy,$loopbackNoProxy"
            }
            try {
                $v2Runner = Join-Path $PSScriptRoot "v2-release-regression.ps1"
                if ($Json) {
                    Invoke-ChildChecked -Description "V2 release regression" -Action {
                        & $v2Runner -Json 6>&1
                    }
                } else {
                    Invoke-ChildChecked -Description "V2 release regression" -Stream -Action {
                        & $v2Runner
                    }
                }
            } finally {
                if ($null -eq $previousNoProxy) {
                    Remove-Item Env:NO_PROXY -ErrorAction SilentlyContinue
                } else {
                    $env:NO_PROXY = $previousNoProxy
                }
            }
        }
        "java-python-contracts" {
            Invoke-JavaPythonContracts
        }
        "repository-context-contract" {
            $previousNoProxy = $env:NO_PROXY
            Add-LoopbackNoProxy
            Push-Location $coreRoot
            try {
                Invoke-NativeChecked -Description "Repository context cross-process contract" -Action {
                    & .\mvnw.cmd -q "-Dagentforge.repository.crossprocess=true" `
                        "-Dtest=RepositorySourceCrossProcessTest" test
                }
            } finally {
                Pop-Location
                if ($null -eq $previousNoProxy) {
                    Remove-Item Env:NO_PROXY -ErrorAction SilentlyContinue
                } else {
                    $env:NO_PROXY = $previousNoProxy
                }
            }
            Assert-SurefireResult -Names @(
                "com.agentforge.core.agent.infrastructure.RepositorySourceCrossProcessTest"
            ) -ExpectedTests 1
        }
        default { throw "Unknown release stage '$StageId'." }
    }
}

foreach ($stage in $selectedStages) {
    if (-not $Json) { Write-Host "`n[$($stage.id)] $($stage.description)" }
    Invoke-ReleaseStage $stage.id
    $completedStages.Add([string]$stage.id)
}

$partial = @($selectedStages).Count -ne @($stages).Count
$summary = [ordered]@{
    release = "V3"
    status = if ($partial) { "PASS_PARTIAL" } else { "PASS" }
    partial = $partial
    requestedStages = @($selectedStages | ForEach-Object { $_.id })
    completedStages = @($completedStages)
    startedAt = $startedAt.ToString("O")
    finishedAt = [DateTimeOffset]::UtcNow.ToString("O")
}
if ($Json) { $summary | ConvertTo-Json -Depth 4 }
else {
    $label = if ($partial) { "PASS_PARTIAL" } else { "PASS" }
    Write-Host "`nV3 Release Regression ${label}: $($completedStages.Count) stage(s) completed."
}
exit 0
