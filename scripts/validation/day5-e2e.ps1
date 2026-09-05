[CmdletBinding()]
param(
    [int]$PostgresPort = 55433,
    [int]$CorePort = 18081,
    [int]$AgentPort = 18001,
    [string]$ComposeProject = "agentforge-day5-e2e"
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
$coreOut = Join-Path $env:TEMP "agentforge-day5-$runId-core.out.log"
$coreErr = Join-Path $env:TEMP "agentforge-day5-$runId-core.err.log"
$agentOut = Join-Path $env:TEMP "agentforge-day5-$runId-agent.out.log"
$agentErr = Join-Path $env:TEMP "agentforge-day5-$runId-agent.err.log"
$coreProcess = $null
$agentProcess = $null
$completed = $false

function Invoke-Compose([string[]]$Arguments) {
    & docker compose -p $ComposeProject -f $composeFile @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed with exit code $LASTEXITCODE" }
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

function Invoke-ApiPost([string]$Uri, [hashtable]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType "application/json" `
        -Body ($Body | ConvertTo-Json -Depth 8)
}

function Invoke-EmptyPost([string]$Uri, [hashtable]$Headers) {
    Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers
}

function Assert-HttpError([string]$Uri, [hashtable]$Headers, [int]$ExpectedStatus) {
    try {
        Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers | Out-Null
        throw "Expected HTTP $ExpectedStatus from $Uri"
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response -or [int]$response.StatusCode -ne $ExpectedStatus) { throw }
    }
}

function Invoke-DatabaseScalar([string]$Sql) {
    $output = & docker compose -p $ComposeProject -f $composeFile exec -T postgres `
        psql -U agentforge -d agentforge -tAc $Sql
    if ($LASTEXITCODE -ne 0) { throw "Database assertion query failed" }
    return ($output -join "`n").Trim()
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

try {
    if (-not (Test-Path -LiteralPath $coreJar)) { throw "Core API jar is missing; run clean verify first." }
    if (-not (Test-Path -LiteralPath $python)) { throw "Agent Service virtual environment is missing." }

    $env:POSTGRES_PORT = [string]$PostgresPort
    $env:POSTGRES_DB = "agentforge"
    $env:POSTGRES_USER = "agentforge"
    $env:POSTGRES_PASSWORD = "agentforge_day5_e2e_only"
    Invoke-Compose @("up", "-d", "postgres")

    $postgresReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker compose -p $ComposeProject -f $composeFile exec -T postgres `
            pg_isready -U agentforge -d agentforge *> $null
        if ($LASTEXITCODE -eq 0) { $postgresReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $postgresReady) { throw "PostgreSQL did not become healthy" }

    $agentToken = "day5-e2e-agent-token-123456"
    $coreToken = "day5-e2e-core-token-1234567"
    $databasePassword = "agentforge_day5_e2e_only"
    $env:AGENTFORGE_DB_URL = "jdbc:postgresql://127.0.0.1:$PostgresPort/agentforge"
    $env:AGENTFORGE_DB_USERNAME = "agentforge"
    $env:AGENTFORGE_DB_PASSWORD = $databasePassword
    $env:AGENTFORGE_SERVER_PORT = [string]$CorePort
    $env:AGENTFORGE_JWT_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    $env:AGENTFORGE_AGENT_SERVICE_URL = "http://127.0.0.1:$AgentPort"
    $env:AGENTFORGE_AGENT_INTERNAL_TOKEN = $agentToken
    $env:AGENTFORGE_CORE_INTERNAL_TOKEN = $coreToken
    $coreProcess = Start-Process -FilePath "java" -ArgumentList @("-jar", $coreJar) `
        -WorkingDirectory $coreRoot -RedirectStandardOutput $coreOut -RedirectStandardError $coreErr `
        -WindowStyle Hidden -PassThru
    Wait-Http "http://127.0.0.1:$CorePort/actuator/health" "UP"

    $env:AGENTFORGE_AGENT_CORE_API_URL = "http://127.0.0.1:$CorePort"
    $env:AGENTFORGE_AGENT_RAG_DB_DSN = "postgresql://agentforge:$databasePassword@127.0.0.1:$PostgresPort/agentforge"
    $env:AGENTFORGE_AGENT_RAG_ENABLED = "true"
    $env:AGENTFORGE_AGENT_EMBEDDING_PROVIDER = "hash"
    $env:AGENTFORGE_AGENT_EMBEDDING_DIMENSIONS = "384"
    $agentProcess = Start-Process -FilePath $python -ArgumentList @(
        "-m", "uvicorn", "agentforge_agent.main:app", "--host", "127.0.0.1", "--port", [string]$AgentPort
    ) -WorkingDirectory $agentRoot -RedirectStandardOutput $agentOut -RedirectStandardError $agentErr `
        -WindowStyle Hidden -PassThru
    Wait-Http "http://127.0.0.1:$AgentPort/health" "UP"

    $coreUrl = "http://127.0.0.1:$CorePort"
    $authOne = Invoke-ApiPost "$coreUrl/api/v1/auth/register" @{
        email = "day5-one-$runId@example.test"; displayName = "Day 5 One"; password = "test-password-123"
    }
    $headersOne = @{ Authorization = "Bearer $($authOne.accessToken)" }
    $projectOne = Invoke-ApiPost "$coreUrl/api/v1/projects" @{
        name = "Day 5 Project One $runId"; description = "tool confirmation validation"
    } $headersOne

    $authTwo = Invoke-ApiPost "$coreUrl/api/v1/auth/register" @{
        email = "day5-two-$runId@example.test"; displayName = "Day 5 Two"; password = "test-password-456"
    }
    $headersTwo = @{ Authorization = "Bearer $($authTwo.accessToken)" }

    $createChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "把登录模块的改造需求整理成任务，优先级设为高。"
    } $headersOne
    Assert-True ($createChat.pendingAction.status -eq "PENDING") "Create action is not pending"
    Assert-True ($createChat.pendingAction.actionType -eq "CREATE_TASK") "Create action type mismatch"
    $beforeConfirm = [int](Invoke-DatabaseScalar "select count(*) from task_item where project_id = '$($projectOne.id)'")
    Assert-True ($beforeConfirm -eq 0) "Task was written before confirmation"

    Assert-HttpError `
        "$coreUrl/api/v1/projects/$($projectOne.id)/agent/actions/$($createChat.pendingAction.id)/reject" `
        $headersTwo 403

    $created = Invoke-EmptyPost `
        "$coreUrl/api/v1/projects/$($projectOne.id)/agent/actions/$($createChat.pendingAction.id)/confirm" `
        $headersOne
    Assert-True ($created.status -eq "EXECUTED") "Create action was not executed"
    Assert-True ($created.resultTask.title -eq "登录模块的改造需求") "Created Task title mismatch"
    $repeated = Invoke-EmptyPost `
        "$coreUrl/api/v1/projects/$($projectOne.id)/agent/actions/$($createChat.pendingAction.id)/confirm" `
        $headersOne
    Assert-True ($repeated.resultTask.id -eq $created.resultTask.id) "Repeated confirm returned another Task"
    $afterRepeatedConfirm = [int](Invoke-DatabaseScalar "select count(*) from task_item where project_id = '$($projectOne.id)'")
    Assert-True ($afterRepeatedConfirm -eq 1) "Repeated confirm duplicated Task"

    $updateChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "update task $($created.resultTask.id) version $($created.resultTask.version): status=DONE; priority=LOW"
    } $headersOne
    Assert-True ($updateChat.pendingAction.status -eq "PENDING") "Update action is not pending"
    $beforeUpdate = Invoke-RestMethod -Uri "$coreUrl/api/v1/projects/$($projectOne.id)/tasks/$($created.resultTask.id)" -Headers $headersOne
    Assert-True ($beforeUpdate.status -eq "TODO") "Task changed before update confirmation"
    $updated = Invoke-EmptyPost `
        "$coreUrl/api/v1/projects/$($projectOne.id)/agent/actions/$($updateChat.pendingAction.id)/confirm" `
        $headersOne
    Assert-True ($updated.resultTask.status -eq "DONE" -and $updated.resultTask.priority -eq "LOW") `
        "Confirmed update did not apply proposed patch"

    $rejectChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "create task: Rejected task; priority=LOW"
    } $headersOne
    $rejected = Invoke-EmptyPost `
        "$coreUrl/api/v1/projects/$($projectOne.id)/agent/actions/$($rejectChat.pendingAction.id)/reject" `
        $headersOne
    $hasResultTask = $rejected.PSObject.Properties.Name -contains "resultTask"
    Assert-True ($rejected.status -eq "REJECTED" -and -not $hasResultTask) "Reject response mismatch"
    Assert-HttpError `
        "$coreUrl/api/v1/projects/$($projectOne.id)/agent/actions/$($rejectChat.pendingAction.id)/confirm" `
        $headersOne 409
    $finalTaskCount = [int](Invoke-DatabaseScalar "select count(*) from task_item where project_id = '$($projectOne.id)'")
    Assert-True ($finalTaskCount -eq 1) "Rejected action wrote a Task"

    [PSCustomObject]@{
        status = "PASS"
        tasksBeforeConfirm = $beforeConfirm
        tasksAfterRepeatedConfirm = $afterRepeatedConfirm
        createdTaskId = $created.resultTask.id
        updatedTaskVersion = [int]$updated.resultTask.version
        rejectedStatus = $rejected.status
        finalTaskCount = $finalTaskCount
        crossUserStatus = 403
    }
    $completed = $true
} catch {
    Write-Host "Day 5 E2E failure: $($_.Exception.Message)"
    foreach ($log in @($coreErr, $agentErr)) {
        if (Test-Path -LiteralPath $log) { Get-Content -Tail 80 -LiteralPath $log }
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
    if (-not $completed) { Write-Host "Day 5 E2E cleanup completed after failure." }
}
