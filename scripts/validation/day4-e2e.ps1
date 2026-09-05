[CmdletBinding()]
param(
    [int]$PostgresPort = 55432,
    [int]$CorePort = 18080,
    [int]$AgentPort = 18000,
    [string]$ComposeProject = "agentforge-day4-e2e"
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
$coreOut = Join-Path $env:TEMP "agentforge-day4-$runId-core.out.log"
$coreErr = Join-Path $env:TEMP "agentforge-day4-$runId-core.err.log"
$agentOut = Join-Path $env:TEMP "agentforge-day4-$runId-agent.out.log"
$agentErr = Join-Path $env:TEMP "agentforge-day4-$runId-agent.err.log"
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

function Invoke-ApiPost([string]$Uri, [hashtable]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType "application/json" `
        -Body ($Body | ConvertTo-Json -Depth 8)
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
    if (-not (Test-Path -LiteralPath $coreJar)) {
        throw "Core API jar is missing; run mvnw.cmd clean verify first."
    }
    if (-not (Test-Path -LiteralPath $python)) {
        throw "Agent Service virtual environment is missing; install .[test] first."
    }

    $env:POSTGRES_PORT = [string]$PostgresPort
    $env:POSTGRES_DB = "agentforge"
    $env:POSTGRES_USER = "agentforge"
    $env:POSTGRES_PASSWORD = "agentforge_e2e_only"
    Invoke-Compose @("up", "-d", "postgres")

    $postgresReady = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        & docker compose -p $ComposeProject -f $composeFile exec -T postgres `
            pg_isready -U agentforge -d agentforge *> $null
        if ($LASTEXITCODE -eq 0) { $postgresReady = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $postgresReady) { throw "PostgreSQL did not become healthy" }

    $agentToken = "day4-e2e-agent-token-123456"
    $coreToken = "day4-e2e-core-token-1234567"
    $databasePassword = "agentforge_e2e_only"
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
        email = "day4-one-$runId@example.test"; displayName = "Day 4 One"; password = "test-password-123"
    }
    $headersOne = @{ Authorization = "Bearer $($authOne.accessToken)" }
    $projectOne = Invoke-ApiPost "$coreUrl/api/v1/projects" @{
        name = "Day 4 Project One $runId"; description = "isolated RAG validation"
    } $headersOne
    $wiki = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/wiki-pages" @{
        title = "Orion Architecture"; content = "# Orion`n`nJava owns authentication authorization and deterministic writes."
    } $headersOne
    $task = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/tasks" @{
        title = "Nebula deployment checklist"; description = "Verify the hybrid retrieval release"; status = "TODO"; priority = "HIGH"
    } $headersOne

    $authTwo = Invoke-ApiPost "$coreUrl/api/v1/auth/register" @{
        email = "day4-two-$runId@example.test"; displayName = "Day 4 Two"; password = "test-password-456"
    }
    $headersTwo = @{ Authorization = "Bearer $($authTwo.accessToken)" }
    $projectTwo = Invoke-ApiPost "$coreUrl/api/v1/projects" @{
        name = "Day 4 Project Two $runId"; description = "cross-project isolation"
    } $headersTwo
    $otherWiki = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectTwo.id)/wiki-pages" @{
        title = "Zebra Secret"; content = "zebra-secret-only second-project material"
    } $headersTwo

    $wikiChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "Which service owns authentication and authorization?"
    } $headersOne
    Assert-True ([bool]$wikiChat.conversationId -and [bool]$wikiChat.answer -and [bool]$wikiChat.requestId) `
        "Wiki chat response is missing required fields"
    Assert-True (@($wikiChat.sources | Where-Object { $_.sourceType -eq "WIKI" -and $_.sourceId -eq $wiki.id }).Count -gt 0) `
        "Wiki source was not retrieved"

    $taskChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "What does the Nebula deployment checklist verify?"
    } $headersOne
    Assert-True (@($taskChat.sources | Where-Object { $_.sourceType -eq "TASK" -and $_.sourceId -eq $task.id }).Count -gt 0) `
        "Task source was not retrieved"

    $projectOneChunkCount = [int](Invoke-DatabaseScalar "select count(*) from rag_chunk where project_id = '$($projectOne.id)'")
    Assert-True ($projectOneChunkCount -ge 2) "Expected Wiki and Task chunks for project one"

    $otherChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectTwo.id)/agent/chat" @{
        message = "Where is zebra-secret-only documented?"
    } $headersTwo
    Assert-True (@($otherChat.sources | Where-Object { $_.sourceId -eq $otherWiki.id }).Count -gt 0) `
        "Second project source was not retrieved in its own project"
    Assert-True (@($wikiChat.sources | Where-Object { $_.sourceId -eq $otherWiki.id }).Count -eq 0) `
        "Second project source leaked into project one"

    $updatedWiki = Invoke-RestMethod -Method Put `
        -Uri "$coreUrl/api/v1/projects/$($projectOne.id)/wiki-pages/$($wiki.id)" `
        -Headers $headersOne -ContentType "application/json" `
        -Body (@{ title = "Orion Architecture"; content = "# Orion`n`nQuasar security tokens are owned by Java Core."; version = 0 } | ConvertTo-Json)
    $updatedChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "Who owns Quasar security tokens?"
    } $headersOne
    Assert-True (@($updatedChat.sources | Where-Object { $_.sourceId -eq $wiki.id }).Count -gt 0) `
        "Updated Wiki source was not retrieved"
    $oldVersionCount = [int](Invoke-DatabaseScalar "select count(*) from rag_chunk where source_id = '$($wiki.id)' and source_version <> $($updatedWiki.version)")
    Assert-True ($oldVersionCount -eq 0) "Old Wiki source version remains indexed"

    Invoke-WebRequest -Method Delete `
        -Uri "$coreUrl/api/v1/projects/$($projectOne.id)/tasks/$($task.id)?version=$($task.version)" `
        -Headers $headersOne | Out-Null
    $syncChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "Summarize Quasar security ownership"
    } $headersOne
    $deletedTaskCount = [int](Invoke-DatabaseScalar "select count(*) from rag_chunk where source_id = '$($task.id)'")
    Assert-True ($deletedTaskCount -eq 0) "Deleted Task chunks remain indexed"

    $unmatchedChat = Invoke-ApiPost "$coreUrl/api/v1/projects/$($projectOne.id)/agent/chat" @{
        message = "unmatchedalpha unmatchedbeta unmatchedgamma unmatcheddelta unmatchedepsilon unmatchedzeta zebra-secret-only"
    } $headersOne
    Assert-True (@($unmatchedChat.sources).Count -eq 0) "Unmatched query fabricated a source"
    $crossProjectRows = [int](Invoke-DatabaseScalar "select count(*) from rag_chunk where project_id = '$($projectOne.id)' and source_id = '$($otherWiki.id)'")
    Assert-True ($crossProjectRows -eq 0) "Second project chunk exists under project one"

    [PSCustomObject]@{
        status = "PASS"
        wikiSources = @($wikiChat.sources).Count
        taskSources = @($taskChat.sources).Count
        projectOneChunksBeforeDelete = $projectOneChunkCount
        updatedWikiVersion = [int]$updatedWiki.version
        deletedTaskChunks = $deletedTaskCount
        unmatchedSources = @($unmatchedChat.sources).Count
        crossProjectRows = $crossProjectRows
    }
    $completed = $true
} catch {
    Write-Host "Day 4 E2E failure: $($_.Exception.Message)"
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
        Write-Host "Day 4 E2E cleanup completed after failure."
    }
}
