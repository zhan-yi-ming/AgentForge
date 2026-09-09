[CmdletBinding()]
param(
    [switch]$Plan,
    [string[]]$Only = @(),
    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$stages = @(
    [ordered]@{ id = "gate-planner-contract"; description = "Validation planner contract" },
    [ordered]@{ id = "compose-config"; description = "Local and production Compose configuration" },
    [ordered]@{ id = "java-clean-verify"; description = "Java clean build, unit, API and PostgreSQL integration tests" },
    [ordered]@{ id = "python-test"; description = "Agent unit and real PostgreSQL/pgvector/checkpoint tests" },
    [ordered]@{ id = "web-test"; description = "Web DOM and typed API client tests" },
    [ordered]@{ id = "web-build"; description = "TypeScript and production Vite build" },
    [ordered]@{ id = "rag-cross-process"; description = "Core to Agent RAG and project isolation smoke" },
    [ordered]@{ id = "tool-hitl-cross-process"; description = "Create/update, approval, reject and replay smoke" },
    [ordered]@{ id = "restart-resume-cross-process"; description = "Persistent interrupt, restart, resume and replay smoke" },
    [ordered]@{ id = "evaluation"; description = "Versioned V2 dataset and evaluation report" },
    [ordered]@{ id = "full-stack-acceptance"; description = "Clean Compose build and public API acceptance" }
)

$coverage = @(
    "Ordinary Chat / RAG",
    "Create / Update Task",
    "High-risk Approval / Reject",
    "Duplicate Request / Agent Retry",
    "Service Restart / Resume",
    "Cross Project / Unauthorized",
    "Long Conversation / Token Budget",
    "Trace / Evaluation"
)

if ($Plan) {
    $result = [ordered]@{
        release = "V2"
        riskLevel = "L3"
        failClosed = $true
        stages = $stages
        coverage = $coverage
    }
    if ($Json) {
        $result | ConvertTo-Json -Depth 5
    } else {
        Write-Host "V2 Release Regression (L3, fail closed)"
        foreach ($stage in $stages) {
            Write-Host "- $($stage.id): $($stage.description)"
        }
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
$webRoot = Join-Path $projectRoot "apps\web"
$composeFile = Join-Path $projectRoot "infra\compose.yaml"
$productionComposeFile = Join-Path $projectRoot "infra\compose.prod.yaml"
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

function Get-FreeTcpPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Set-ReleaseEnvironment([int]$PostgresPort, [int]$CorePort, [int]$AgentPort, [int]$WebPort) {
    $env:POSTGRES_PORT = [string]$PostgresPort
    $env:CORE_PORT = [string]$CorePort
    $env:AGENT_PORT = [string]$AgentPort
    $env:WEB_PORT = [string]$WebPort
    $env:POSTGRES_DB = "agentforge"
    $env:POSTGRES_USER = "agentforge"
    $env:POSTGRES_PASSWORD = "v209_$([guid]::NewGuid().ToString('N'))"
    $env:AGENTFORGE_JWT_SECRET = [Convert]::ToBase64String(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    )
    $env:AGENTFORGE_AGENT_INTERNAL_TOKEN = "v209-$([guid]::NewGuid().ToString('N'))"
    $env:AGENTFORGE_CORE_INTERNAL_TOKEN = "v209-$([guid]::NewGuid().ToString('N'))"
    $env:AGENTFORGE_AGENT_LLM_PROVIDER = "disabled"
    $env:AGENTFORGE_AGENT_LLM_API_KEY = ""
}

function Invoke-ReleaseStage([string]$StageId) {
    switch ($StageId) {
        "gate-planner-contract" {
            $output = @(& (Join-Path $PSScriptRoot "test-plan-change-gates.ps1") 6>&1)
            Write-StageOutput $output
        }
        "compose-config" {
            Set-ReleaseEnvironment -PostgresPort (Get-FreeTcpPort) -CorePort (Get-FreeTcpPort) `
                -AgentPort (Get-FreeTcpPort) -WebPort (Get-FreeTcpPort)
            Invoke-NativeChecked -Description "Local Compose config" -Action {
                & docker compose -f $composeFile config --quiet
            }
            Invoke-NativeChecked -Description "Production Compose config" -Action {
                & docker compose --env-file (Join-Path $projectRoot ".env.production.example") `
                    -f $productionComposeFile config --quiet
            }
        }
        "java-clean-verify" {
            Push-Location $coreRoot
            try { Invoke-NativeChecked -Description "Java clean verify" -Action { & .\mvnw.cmd clean verify } }
            finally { Pop-Location }
        }
        "python-test" {
            if (-not (Test-Path -LiteralPath $python)) { throw "Agent Service virtual environment is missing: $python" }
            $baseTemp = Join-Path $projectRoot ".data\pytest-v2-09-$runId"
            try {
                Push-Location $agentRoot
                try {
                    Invoke-NativeChecked -Description "Agent Service pytest" -Action {
                        & $python -m pytest -q --basetemp $baseTemp
                    }
                } finally { Pop-Location }
            } finally {
                $resolvedDataRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot ".data"))
                $resolvedBaseTemp = [System.IO.Path]::GetFullPath($baseTemp)
                if ($resolvedBaseTemp.StartsWith($resolvedDataRoot, [StringComparison]::OrdinalIgnoreCase) -and
                    (Split-Path -Leaf $resolvedBaseTemp) -like "pytest-v2-09-*" -and
                    (Test-Path -LiteralPath $resolvedBaseTemp)) {
                    Remove-Item -LiteralPath $resolvedBaseTemp -Recurse -Force
                }
            }
        }
        "web-test" {
            Push-Location $webRoot
            try { Invoke-NativeChecked -Description "Web tests" -Action { & npm test -- --run } }
            finally { Pop-Location }
        }
        "web-build" {
            Push-Location $webRoot
            try { Invoke-NativeChecked -Description "Web production build" -Action { & npm run build } }
            finally { Pop-Location }
        }
        "rag-cross-process" {
            $postgresPort = Get-FreeTcpPort
            $corePort = Get-FreeTcpPort
            $agentPort = Get-FreeTcpPort
            Set-ReleaseEnvironment -PostgresPort $postgresPort -CorePort $corePort `
                -AgentPort $agentPort -WebPort (Get-FreeTcpPort)
            $output = @(& (Join-Path $PSScriptRoot "day4-e2e.ps1") `
                -PostgresPort $postgresPort -CorePort $corePort -AgentPort $agentPort `
                -ComposeProject "agentforge-v209-rag-$($runId.Substring(0, 8))" 6>&1)
            Write-StageOutput $output
        }
        "tool-hitl-cross-process" {
            $postgresPort = Get-FreeTcpPort
            $corePort = Get-FreeTcpPort
            $agentPort = Get-FreeTcpPort
            Set-ReleaseEnvironment -PostgresPort $postgresPort -CorePort $corePort `
                -AgentPort $agentPort -WebPort (Get-FreeTcpPort)
            $output = @(& (Join-Path $PSScriptRoot "day5-e2e.ps1") `
                -PostgresPort $postgresPort -CorePort $corePort -AgentPort $agentPort `
                -ComposeProject "agentforge-v209-tool-$($runId.Substring(0, 8))" 6>&1)
            Write-StageOutput $output
        }
        "restart-resume-cross-process" {
            $postgresPort = Get-FreeTcpPort
            $corePort = Get-FreeTcpPort
            $agentPort = Get-FreeTcpPort
            Set-ReleaseEnvironment -PostgresPort $postgresPort -CorePort $corePort `
                -AgentPort $agentPort -WebPort (Get-FreeTcpPort)
            $output = @(& (Join-Path $PSScriptRoot "v2-07-resume-e2e.ps1") `
                -PostgresPort $postgresPort -CorePort $corePort -AgentPort $agentPort `
                -ComposeProject "agentforge-v209-resume-$($runId.Substring(0, 8))" 6>&1)
            Write-StageOutput $output
        }
        "evaluation" {
            if (-not (Test-Path -LiteralPath $python)) { throw "Agent Service virtual environment is missing: $python" }
            $report = Join-Path $projectRoot ".data\v2-09-evaluation-$runId.json"
            $baselinePath = Join-Path $agentRoot "evaluation\reports\v2-baseline.json"
            try {
                Push-Location $agentRoot
                try {
                    Invoke-NativeChecked -Description "V2 evaluation" -Action {
                        & $python -m agentforge_agent.evaluation.runner `
                            --dataset evaluation/datasets/v2-small.json --output $report
                    }
                } finally { Pop-Location }
                $actual = Get-Content -Raw -Encoding UTF8 $report | ConvertFrom-Json
                $baseline = Get-Content -Raw -Encoding UTF8 $baselinePath | ConvertFrom-Json
                if ($actual.dataset.sha256 -ne $baseline.dataset.sha256) {
                    throw "Evaluation dataset hash differs from the committed V2 baseline."
                }
                foreach ($property in $baseline.metrics.PSObject.Properties.Name) {
                    if ($actual.metrics.$property -ne $baseline.metrics.$property) {
                        throw "Evaluation metric '$property' regressed from '$($baseline.metrics.$property)' to '$($actual.metrics.$property)'."
                    }
                }
            } finally {
                if (Test-Path -LiteralPath $report) { Remove-Item -LiteralPath $report -Force }
            }
        }
        "full-stack-acceptance" {
            $composeProject = "agentforge-v209-stack-$($runId.Substring(0, 8))"
            $postgresPort = Get-FreeTcpPort
            $corePort = Get-FreeTcpPort
            $agentPort = Get-FreeTcpPort
            $webPort = Get-FreeTcpPort
            Set-ReleaseEnvironment -PostgresPort $postgresPort -CorePort $corePort -AgentPort $agentPort -WebPort $webPort
            try {
                Invoke-NativeChecked -Description "Full-stack Compose build" -Action {
                    & docker compose -p $composeProject -f $composeFile up --build -d --wait
                }
                $output = @(& (Join-Path $PSScriptRoot "v1-acceptance.ps1") `
                    -WebUrl "http://127.0.0.1:$webPort" -CoreUrl "http://127.0.0.1:$corePort" `
                    -AgentUrl "http://127.0.0.1:$agentPort" 6>&1)
                Write-StageOutput $output
            } finally {
                $cleanupOutput = @(& docker compose -p $composeProject -f $composeFile down -v --remove-orphans 2>&1)
                $cleanupExit = $LASTEXITCODE
                Write-StageOutput $cleanupOutput
                if ($cleanupExit -ne 0) { throw "Full-stack cleanup failed with exit code $cleanupExit." }
            }
        }
        default { throw "Unknown release stage '$StageId'." }
    }
}

foreach ($stage in $selectedStages) {
    if (-not $Json) { Write-Host "`n[$($stage.id)] $($stage.description)" }
    Invoke-ReleaseStage $stage.id
    $completedStages.Add([string]$stage.id)
}

$summary = [ordered]@{
    release = "V2"
    status = "PASS"
    completedStages = @($completedStages)
    startedAt = $startedAt.ToString("O")
    finishedAt = [DateTimeOffset]::UtcNow.ToString("O")
}
if ($Json) { $summary | ConvertTo-Json -Depth 4 }
else { Write-Host "`nV2 Release Regression PASS: $($completedStages.Count) stage(s) completed." }
