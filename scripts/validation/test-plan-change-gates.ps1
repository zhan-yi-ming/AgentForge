[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$planner = Join-Path $PSScriptRoot "plan-change-gates.ps1"
if (-not (Test-Path -LiteralPath $planner)) {
    throw "Gate planner does not exist: $planner"
}

function Invoke-Plan {
    param(
        [string[]]$Paths,
        [switch]$Milestone,
        [switch]$ReleaseGate
    )

    $parameters = @{ Paths = $Paths; Json = $true }
    if ($Milestone) { $parameters.Milestone = $true }
    if ($ReleaseGate) { $parameters.ReleaseGate = $true }
    $raw = & $planner @parameters
    return ($raw -join "`n") | ConvertFrom-Json
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message. Expected '$Expected', got '$Actual'."
    }
}

function Assert-Contains {
    param([object[]]$Actual, $Expected, [string]$Message)
    if ($Expected -notin @($Actual)) {
        throw "$Message. Missing '$Expected' in '$(@($Actual) -join ', ')'."
    }
}

function Assert-NotContains {
    param([object[]]$Actual, $Expected, [string]$Message)
    if ($Expected -in @($Actual)) {
        throw "$Message. Unexpected '$Expected' in '$(@($Actual) -join ', ')'."
    }
}

function Assert-ThrowsLike {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) {
            return
        }
        throw "$Message. Expected error like '$Pattern', got '$($_.Exception.Message)'."
    }
    throw "$Message. Expected an error, but the command succeeded."
}

$docs = Invoke-Plan -Paths @("docs/03-features/observability.md")
Assert-Equal $docs.riskLevel "L0" "Docs-only risk"
Assert-Equal $docs.reviewMode "None" "Docs-only review"
Assert-Contains $docs.gates "docs-consistency" "Docs-only gates"
Assert-NotContains $docs.gates "full-repo-regression" "Docs-only gates"

$python = Invoke-Plan -Paths @(
    "services/agent-service/src/agentforge_agent/retrieval.py"
)
Assert-Equal $python.riskLevel "L2" "Agent-only risk"
Assert-Equal $python.reviewMode "Diff" "Agent-only review"
Assert-Contains $python.areas "AgentService" "Agent-only areas"
Assert-Contains $python.gates "python-test" "Agent-only gates"
Assert-NotContains $python.gates "java-clean-verify" "Agent-only gates"
Assert-NotContains $python.gates "web-test" "Agent-only gates"

$tls = Invoke-Plan -Paths @(
    "scripts/deploy/tls-issue.sh",
    "infra/nginx/production.conf.template"
)
Assert-Equal $tls.riskLevel "L2" "TLS risk"
Assert-Equal $tls.reviewMode "Diff" "TLS review"
Assert-Contains $tls.areas "TlsDeployment" "TLS areas"
foreach ($gate in @("shell-parser", "tls-contract", "compose-config", "nginx-config")) {
    Assert-Contains $tls.gates $gate "TLS gates"
}
Assert-NotContains $tls.gates "java-clean-verify" "TLS gates"
Assert-NotContains $tls.gates "python-test" "TLS gates"
Assert-NotContains $tls.gates "web-test" "TLS gates"

$crossService = Invoke-Plan -Paths @(
    "services/core-api/src/main/java/com/agentforge/core/agent/api/AgentChatController.java",
    "services/agent-service/src/agentforge_agent/api.py"
)
Assert-Equal $crossService.riskLevel "L3" "Cross-service API risk"
Assert-Equal $crossService.reviewMode "Milestone" "Cross-service API review"
Assert-Contains $crossService.gates "java-clean-verify" "Cross-service gates"
Assert-Contains $crossService.gates "python-test" "Cross-service gates"
Assert-Contains $crossService.gates "cross-process-smoke" "Cross-service gates"
Assert-NotContains $crossService.gates "web-test" "Cross-service gates"

$schema = Invoke-Plan -Paths @(
    "services/core-api/src/main/resources/db/migration/V6__example.sql"
)
Assert-Equal $schema.riskLevel "L3" "Schema risk"
Assert-Equal $schema.reviewMode "Milestone" "Schema review"
Assert-Contains $schema.gates "database-integration" "Schema gates"
Assert-Contains $schema.gates "java-clean-verify" "Schema gates"

$unknown = Invoke-Plan -Paths @("tools/new-format.bin")
Assert-Equal $unknown.riskLevel "L2" "Unknown path risk"
Assert-Equal $unknown.reviewMode "Diff" "Unknown path review"
Assert-Contains $unknown.areas "Unknown" "Unknown path areas"
Assert-Contains $unknown.gates "manual-impact-review" "Unknown path gates"

$sharedBuild = Invoke-Plan -Paths @("pom.xml")
Assert-Equal $sharedBuild.riskLevel "L3" "Shared build risk"
Assert-Equal $sharedBuild.reviewMode "Milestone" "Shared build review"
Assert-Contains $sharedBuild.areas "SharedBuild" "Shared build areas"
Assert-Contains $sharedBuild.gates "full-repo-regression" "Shared build gates"

$milestone = Invoke-Plan -Paths @(
    "services/agent-service/src/agentforge_agent/retrieval.py"
) -Milestone
Assert-Equal $milestone.reviewMode "Milestone" "Node milestone review"
Assert-NotContains $milestone.gates "full-repo-regression" "Node milestone gates"

$release = Invoke-Plan -Paths @("docs/README.md") -ReleaseGate
Assert-Equal $release.riskLevel "L3" "Release risk"
Assert-Equal $release.reviewMode "Milestone" "Release review"
Assert-Contains $release.gates "full-repo-regression" "Release gates"

$absolutePath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\pom.xml"))
Assert-ThrowsLike -Action { Invoke-Plan -Paths @($absolutePath) } -Pattern "*repository-relative*" -Message "Absolute path validation"

$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$temporaryRepository = Join-Path $temporaryRoot ("agentforge-gate-planner-" + [guid]::NewGuid().ToString("N"))
$originalLocation = Get-Location
try {
    $temporaryScripts = Join-Path $temporaryRepository "scripts\validation"
    $temporaryDocs = Join-Path $temporaryRepository "docs"
    New-Item -ItemType Directory -Path $temporaryScripts, $temporaryDocs -Force | Out-Null
    $temporaryPlanner = Join-Path $temporaryScripts "plan-change-gates.ps1"
    Copy-Item -LiteralPath $planner -Destination $temporaryPlanner
    [System.IO.File]::WriteAllText(
        (Join-Path $temporaryDocs "sample.md"),
        "version 1`n",
        [System.Text.UTF8Encoding]::new($false)
    )

    Set-Location $temporaryRepository
    & git init --quiet
    if ($LASTEXITCODE -ne 0) { throw "git init failed in planner contract test." }
    & git config core.autocrlf false
    & git config user.name "AgentForge Gate Test"
    & git config user.email "gate-test@example.invalid"
    & git add .
    & git commit --quiet -m "base"
    if ($LASTEXITCODE -ne 0) { throw "base git commit failed in planner contract test." }

    Assert-ThrowsLike -Action {
        & $temporaryPlanner -BaseRef HEAD -TargetRef WORKTREE -Json | Out-Null
    } -Pattern "*No changed paths*" -Message "No-change WORKTREE validation"

    [System.IO.File]::WriteAllText(
        (Join-Path $temporaryDocs "sample.md"),
        "version 2`n",
        [System.Text.UTF8Encoding]::new($false)
    )
    $worktreeRaw = & $temporaryPlanner -BaseRef HEAD -TargetRef WORKTREE -Json
    $worktree = ($worktreeRaw -join "`n") | ConvertFrom-Json
    Assert-Equal $worktree.source "HEAD..WORKTREE" "WORKTREE source"
    Assert-Contains $worktree.changedPaths "docs/sample.md" "WORKTREE paths"

    $humanOutput = (& $temporaryPlanner -Paths @("docs/sample.md") 6>&1 | Out-String)
    if ($humanOutput -notmatch 'Risk: L0' -or $humanOutput -notmatch 'Gates:') {
        throw "Human-readable output did not contain the risk and gates summary."
    }

    & git add docs/sample.md
    $indexRaw = & $temporaryPlanner -BaseRef HEAD -TargetRef INDEX -Json
    $index = ($indexRaw -join "`n") | ConvertFrom-Json
    Assert-Equal $index.source "HEAD..INDEX" "INDEX source"
    Assert-Contains $index.changedPaths "docs/sample.md" "INDEX paths"
    if ([string]::IsNullOrWhiteSpace($index.changeFingerprint)) {
        throw "INDEX fingerprint must not be empty."
    }

    & git commit --quiet -m "docs update"
    if ($LASTEXITCODE -ne 0) { throw "second git commit failed in planner contract test." }
    $refRaw = & $temporaryPlanner -BaseRef 'HEAD~1' -TargetRef HEAD -Json
    $refPlan = ($refRaw -join "`n") | ConvertFrom-Json
    Assert-Equal $refPlan.source "HEAD~1..HEAD" "Ref source"
    Assert-Contains $refPlan.changedPaths "docs/sample.md" "Ref paths"
} finally {
    Set-Location $originalLocation
    $resolvedTemporaryRepository = [System.IO.Path]::GetFullPath($temporaryRepository)
    if (
        $resolvedTemporaryRepository.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTemporaryRepository) -like 'agentforge-gate-planner-*' -and
        (Test-Path -LiteralPath $resolvedTemporaryRepository)
    ) {
        Remove-Item -LiteralPath $resolvedTemporaryRepository -Recurse -Force
    }
}

Write-Host "Gate planner contract passed: 15 representative checks."
