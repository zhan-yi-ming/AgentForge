[CmdletBinding()]
param(
    [string]$WebUrl = "http://127.0.0.1:5173",
    [string]$CoreUrl = "http://127.0.0.1:8080",
    [string]$AgentUrl = "http://127.0.0.1:8000"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-JsonPost([string]$Uri, [hashtable]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType "application/json" `
        -Body ($Body | ConvertTo-Json -Depth 8)
}

$web = Invoke-WebRequest -Uri $WebUrl -UseBasicParsing
$coreHealth = Invoke-RestMethod "$CoreUrl/actuator/health"
$agentHealth = Invoke-RestMethod "$AgentUrl/health"
Assert-True ($web.StatusCode -eq 200 -and $web.Content -match "AgentForge") "Web entry is not available"
Assert-True ($coreHealth.status -eq "UP") "Core API is not healthy"
Assert-True ($agentHealth.status -eq "UP") "Agent Service is not healthy"

$runId = [guid]::NewGuid().ToString("N")
$auth = Invoke-JsonPost "$WebUrl/api/v1/auth/register" @{
    email = "v1-$runId@example.test"; displayName = "V1 Acceptance"; password = "acceptance-password-123"
}
$headers = @{ Authorization = "Bearer $($auth.accessToken)" }
$project = Invoke-JsonPost "$WebUrl/api/v1/projects" @{
    name = "V1 Acceptance $runId"; description = "Disposable Day 7 acceptance project"
} $headers
$wiki = Invoke-JsonPost "$WebUrl/api/v1/projects/$($project.id)/wiki-pages" @{
    title = "Architecture"; content = "# Ownership`n`nJava owns authentication and deterministic writes. Python owns Agent reasoning and RAG."
} $headers
$initialTask = Invoke-JsonPost "$WebUrl/api/v1/projects/$($project.id)/tasks" @{
    title = "Initial acceptance task"; description = "V1 baseline"; status = "TODO"; priority = "MEDIUM"
} $headers

$rag = Invoke-JsonPost "$WebUrl/api/v1/projects/$($project.id)/agent/chat" @{
    message = "Which service owns authentication and deterministic writes?"
} $headers
Assert-True ($rag.sources.Count -gt 0) "RAG chat returned no source"
Assert-True ($rag.sources[0].sourceId -eq $wiki.id) "RAG chat did not cite the project Wiki"

$createProposal = Invoke-JsonPost "$WebUrl/api/v1/projects/$($project.id)/agent/chat" @{
    message = "create task: Verify V1 confirmation; priority=HIGH"
} $headers
Assert-True ($createProposal.pendingAction.status -eq "PENDING") "Create proposal is not pending"
$confirmed = Invoke-RestMethod -Method Post -Uri "$WebUrl/api/v1/projects/$($project.id)/agent/actions/$($createProposal.pendingAction.id)/confirm" -Headers $headers
Assert-True ($confirmed.status -eq "EXECUTED" -and $confirmed.resultTask.title -eq "Verify V1 confirmation") "Confirmed task mismatch"

$rejectProposal = Invoke-JsonPost "$WebUrl/api/v1/projects/$($project.id)/agent/chat" @{
    message = "create task: Must remain rejected; priority=LOW"
} $headers
$rejected = Invoke-RestMethod -Method Post -Uri "$WebUrl/api/v1/projects/$($project.id)/agent/actions/$($rejectProposal.pendingAction.id)/reject" -Headers $headers
$tasks = Invoke-RestMethod -Uri "$WebUrl/api/v1/projects/$($project.id)/tasks" -Headers $headers
Assert-True ($rejected.status -eq "REJECTED") "Reject response mismatch"
Assert-True ($tasks.Count -eq 2) "Rejected action changed the task count"

[PSCustomObject]@{
    Status = "PASS"
    WebStatus = $web.StatusCode
    CoreStatus = $coreHealth.status
    AgentStatus = $agentHealth.status
    ProjectId = $project.id
    InitialTaskId = $initialTask.id
    RagSourceCount = $rag.sources.Count
    ConfirmedTaskId = $confirmed.resultTask.id
    RejectedStatus = $rejected.status
    FinalTaskCount = $tasks.Count
}
