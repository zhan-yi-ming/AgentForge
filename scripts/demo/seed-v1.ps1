[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$WebUrl = "http://127.0.0.1:5173",
    [string]$Email = "demo@agentforge.local",
    [string]$Password = "demo-password-123",
    [string]$DisplayName = "AgentForge Demo"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-JsonPost([string]$Uri, [hashtable]$Body, [hashtable]$Headers = @{}) {
    Invoke-RestMethod -Method Post -Uri $Uri -Headers $Headers -ContentType "application/json" `
        -Body ($Body | ConvertTo-Json -Depth 6)
}

try {
    $auth = Invoke-JsonPost "$BaseUrl/api/v1/auth/register" @{
        email = $Email; displayName = $DisplayName; password = $Password
    }
} catch {
    if ($null -eq $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409) { throw }
    $auth = Invoke-JsonPost "$BaseUrl/api/v1/auth/login" @{ email = $Email; password = $Password }
}

$headers = @{ Authorization = "Bearer $($auth.accessToken)" }
$suffix = Get-Date -Format "yyyyMMdd-HHmmss"
$project = Invoke-JsonPost "$BaseUrl/api/v1/projects" @{
    name = "AgentForge V1 Demo $suffix"; description = "Day 7 local V1 acceptance workspace"
} $headers

$wiki = Invoke-JsonPost "$BaseUrl/api/v1/projects/$($project.id)/wiki-pages" @{
    title = "V1 Architecture"; content = "# AgentForge V1`n`nJava owns authentication and deterministic writes. Python owns Agent reasoning and RAG."
} $headers

$task = Invoke-JsonPost "$BaseUrl/api/v1/projects/$($project.id)/tasks" @{
    title = "Try the V1 workspace"; description = "Ask Agent about architecture, then confirm a proposed task."; status = "TODO"; priority = "HIGH"
} $headers

[PSCustomObject]@{
    WebUrl = $WebUrl
    Email = $Email
    Password = $Password
    ProjectId = $project.id
    WikiId = $wiki.id
    TaskId = $task.id
}
