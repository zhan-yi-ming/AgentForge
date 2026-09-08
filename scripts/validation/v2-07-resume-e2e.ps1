[CmdletBinding()]
param(
    [int]$PostgresPort = 55437,
    [int]$CorePort = 18087,
    [int]$AgentPort = 18007,
    [string]$ComposeProject = "agentforge-v207-e2e"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeFile = Join-Path $projectRoot "infra\compose.yaml"
$coreRoot = Join-Path $projectRoot "services\core-api"
$agentRoot = Join-Path $projectRoot "services\agent-service"
$coreJar = Join-Path $coreRoot "target\core-api-0.1.0-SNAPSHOT.jar"
$python = Join-Path $agentRoot ".venv\Scripts\python.exe"
$runId = [guid]::NewGuid().ToString("N")
$coreOut = Join-Path $env:TEMP "agentforge-v207-$runId-core.out.log"
$coreErr = Join-Path $env:TEMP "agentforge-v207-$runId-core.err.log"
$agentOut = Join-Path $env:TEMP "agentforge-v207-$runId-agent.out.log"
$agentErr = Join-Path $env:TEMP "agentforge-v207-$runId-agent.err.log"
$coreProcess = $null
$agentProcess = $null
$completed = $false

function Invoke-Compose([string[]]$Arguments) {
    & docker compose -p $ComposeProject -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose failed with exit code $LASTEXITCODE"
    }
}

function Wait-Http([string]$Uri, [string]$ExpectedStatus) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-RestMethod -Uri $Uri -TimeoutSec 2
            if ([string]$response.status -eq $ExpectedStatus) { return }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Timed out waiting for $Uri"
}

function Invoke-JsonPost([string]$Uri, [hashtable]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType "application/json" `
        -Body ($Body | ConvertTo-Json -Depth 8)
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

try {
    if (-not (Test-Path -LiteralPath $coreJar)) {
        throw "Core API jar is missing; run mvnw.cmd clean verify first."
    }
    if (-not (Test-Path -LiteralPath $python)) {
        throw "Agent Service virtual environment is missing; install .[test] first."
    }

    $databasePassword = "v207_$([guid]::NewGuid().ToString('N'))"
    $agentToken = "v207-$([guid]::NewGuid().ToString('N'))"
    $coreToken = "v207-$([guid]::NewGuid().ToString('N'))"
    $userPassword = "V2!$([guid]::NewGuid().ToString('N'))"
    $env:AGENTFORGE_AGENT_INTERNAL_TOKEN = $agentToken
    $env:AGENTFORGE_CORE_INTERNAL_TOKEN = $coreToken
    $env:AGENTFORGE_JWT_SECRET = [Convert]::ToBase64String(
        [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    )
    $env:POSTGRES_PORT = [string]$PostgresPort
    $env:POSTGRES_DB = "agentforge"
    $env:POSTGRES_USER = "agentforge"
    $env:POSTGRES_PASSWORD = $databasePassword
    Invoke-Compose @("up", "-d", "postgres")

    $postgresReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker compose -p $ComposeProject -f $composeFile exec -T postgres `
            pg_isready -U agentforge -d agentforge *> $null
        if ($LASTEXITCODE -eq 0) { $postgresReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $postgresReady) { throw "PostgreSQL did not become healthy" }

    $env:AGENTFORGE_DB_URL = "jdbc:postgresql://127.0.0.1:$PostgresPort/agentforge"
    $env:AGENTFORGE_DB_USERNAME = "agentforge"
    $env:AGENTFORGE_DB_PASSWORD = $databasePassword
    $env:AGENTFORGE_SERVER_PORT = [string]$CorePort
    $env:AGENTFORGE_AGENT_SERVICE_URL = "http://127.0.0.1:$AgentPort"
    $coreProcess = Start-Process -FilePath "java" -ArgumentList @("-jar", $coreJar) `
        -WorkingDirectory $coreRoot -RedirectStandardOutput $coreOut -RedirectStandardError $coreErr `
        -WindowStyle Hidden -PassThru
    Wait-Http "http://127.0.0.1:$CorePort/actuator/health" "UP"

    $env:AGENTFORGE_AGENT_CORE_API_URL = "http://127.0.0.1:$CorePort"
    $env:AGENTFORGE_AGENT_RAG_DB_DSN = "postgresql://agentforge:$databasePassword@127.0.0.1:$PostgresPort/agentforge"
    $env:AGENTFORGE_AGENT_CHECKPOINT_DB_DSN = $env:AGENTFORGE_AGENT_RAG_DB_DSN
    $env:AGENTFORGE_AGENT_RAG_ENABLED = "false"
    $agentProcess = Start-Process -FilePath $python -ArgumentList @(
        "-m", "uvicorn", "agentforge_agent.main:app", "--host", "127.0.0.1", "--port", [string]$AgentPort
    ) -WorkingDirectory $agentRoot -RedirectStandardOutput $agentOut -RedirectStandardError $agentErr `
        -WindowStyle Hidden -PassThru
    Wait-Http "http://127.0.0.1:$AgentPort/health" "UP"

    $coreUrl = "http://127.0.0.1:$CorePort"
    $auth = Invoke-JsonPost "$coreUrl/api/v1/auth/register" @{
        email = "v207-$runId@example.test"; displayName = "V2-07 E2E"; password = $userPassword
    }
    $headers = @{ Authorization = "Bearer $($auth.accessToken)" }
    $project = Invoke-JsonPost "$coreUrl/api/v1/projects" @{
        name = "V2-07 $runId"; description = "Disposable checkpoint restart validation"
    } $headers
    $proposal = Invoke-JsonPost "$coreUrl/api/v1/projects/$($project.id)/agent/chat" @{
        message = "create task: Resume after Python restart; priority=HIGH"
    } $headers
    Assert-True ($proposal.pendingAction.status -eq "PENDING") "Agent action did not interrupt as PENDING"

    Stop-Process -Id $agentProcess.Id -Force
    $agentProcess.WaitForExit()
    $agentProcess = Start-Process -FilePath $python -ArgumentList @(
        "-m", "uvicorn", "agentforge_agent.main:app", "--host", "127.0.0.1", "--port", [string]$AgentPort
    ) -WorkingDirectory $agentRoot -RedirectStandardOutput $agentOut -RedirectStandardError $agentErr `
        -WindowStyle Hidden -PassThru
    Wait-Http "http://127.0.0.1:$AgentPort/health" "UP"

    $decisionHeaders = @{
        Authorization = $headers.Authorization
        "Idempotency-Key" = "v207-restart-key"
    }
    $confirmUri = "$coreUrl/api/v1/projects/$($project.id)/agent/actions/$($proposal.pendingAction.id)/confirm"
    $confirmed = Invoke-RestMethod -Method Post -Uri $confirmUri -Headers $decisionHeaders
    $replayed = Invoke-RestMethod -Method Post -Uri $confirmUri -Headers $decisionHeaders
    $tasks = Invoke-RestMethod -Uri "$coreUrl/api/v1/projects/$($project.id)/tasks" -Headers $headers
    $matchingTasks = @($tasks | Where-Object { $_.title -eq "Resume after Python restart" })
    $checkpointCount = & docker compose -p $ComposeProject -f $composeFile exec -T postgres `
        psql -U agentforge -d agentforge -tAc "select count(*) from agent_checkpoint.checkpoints"

    Assert-True ($confirmed.status -eq "EXECUTED") "Restarted confirmation did not execute"
    Assert-True ($replayed.status -eq "EXECUTED") "Confirmation replay was not stable"
    Assert-True ($confirmed.resultTask.id -eq $replayed.resultTask.id) "Replay returned another Task"
    Assert-True ($matchingTasks.Count -eq 1) "Resume/replay wrote the Task more than once"
    Assert-True ([int](($checkpointCount -join "").Trim()) -gt 0) "No persistent checkpoint was written"

    [PSCustomObject]@{
        Status = "PASS"
        InterruptedStatus = $proposal.pendingAction.status
        ConfirmedStatus = $confirmed.status
        ReplayStatus = $replayed.status
        MatchingTaskCount = $matchingTasks.Count
        CheckpointCount = [int](($checkpointCount -join "").Trim())
    }
    $completed = $true
} catch {
    Write-Host "V2-07 E2E failure: $($_.Exception.Message)"
    foreach ($log in @($coreErr, $agentErr)) {
        if (Test-Path -LiteralPath $log) {
            Get-Content -Tail 80 -LiteralPath $log
        }
    }
    throw
} finally {
    foreach ($process in @($agentProcess, $coreProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $process.WaitForExit()
        }
    }
    & docker compose -p $ComposeProject -f $composeFile down -v --remove-orphans | Out-Host
    foreach ($log in @($coreOut, $coreErr, $agentOut, $agentErr)) {
        Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    }
    if (-not $completed) {
        Write-Host "V2-07 E2E cleanup completed after failure."
    }
}
