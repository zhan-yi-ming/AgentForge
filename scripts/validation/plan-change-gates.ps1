[CmdletBinding()]
param(
    [string]$BaseRef = "HEAD",
    [string]$TargetRef = "WORKTREE",
    [string[]]$Paths = @(),
    [switch]$Milestone,
    [switch]$ReleaseGate,
    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $projectRoot

function Invoke-GitLines {
    param([string[]]$Arguments)
    $lines = @(& git @Arguments)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
    return $lines
}

if ($Paths.Count -gt 0) {
    foreach ($requestedPath in $Paths) {
        if ([System.IO.Path]::IsPathRooted($requestedPath)) {
            throw "-Paths accepts repository-relative paths only: $requestedPath"
        }
    }
    $changedPaths = @($Paths)
    $source = "explicit-paths"
} elseif ($TargetRef -eq "INDEX") {
    $changedPaths = @(Invoke-GitLines @("diff", "--cached", "--name-only", "--no-ext-diff", $BaseRef))
    $source = "$BaseRef..INDEX"
} elseif ($TargetRef -eq "WORKTREE") {
    $changedPaths = @(
        Invoke-GitLines @("diff", "--name-only", "--no-ext-diff", $BaseRef)
        Invoke-GitLines @("ls-files", "--others", "--exclude-standard")
    )
    $source = "$BaseRef..WORKTREE"
} else {
    $changedPaths = @(Invoke-GitLines @("diff", "--name-only", "--no-ext-diff", "$BaseRef..$TargetRef"))
    $source = "$BaseRef..$TargetRef"
}

$changedPaths = @(
    $changedPaths |
        ForEach-Object { $_.Trim().Replace('\', '/') } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique
)
if ($changedPaths.Count -eq 0) {
    throw "No changed paths were found for $source."
}

$riskRank = 0
$areas = @()
$gates = @("diff-check", "gitleaks-final")
$reasons = @()

function Raise-Risk {
    param([int]$Rank, [string]$Reason)
    if ($Rank -gt $script:riskRank) {
        $script:riskRank = $Rank
    }
    if (-not [string]::IsNullOrWhiteSpace($Reason)) {
        $script:reasons += $Reason
    }
}

foreach ($path in $changedPaths) {
    $isDocument = $path -match '(?i)(^docs/|\.md$)'
    if ($isDocument) {
        $areas += "Docs"
        $gates += "docs-consistency"
    }

    $isGovernance = (
        $path -eq "AGENTS.md" -or
        $path -match '^docs/00-governance/' -or
        $path -eq 'docs/05-development/testing-strategy.md' -or
        $path -eq 'docs/06-operations/pi-review-connection.md' -or
        $path -eq 'docs/08-reviews/README.md' -or
        $path -match '^scripts/validation/(test-)?plan-change-gates\.ps1$' -or
        $path -match '^skills/'
    )
    if ($isGovernance) {
        $areas += "Governance"
        $gates += @("powershell-parser", "gate-planner-contract")
        Raise-Risk 2 "Governance or validation policy changes future delivery behavior."
        continue
    }

    $isSharedBuild = $path -match '(?i)(^pom\.xml$|^(build|settings)\.gradle(?:\.kts)?$|^package\.json$|^(package-lock\.json|yarn\.lock|pnpm-lock\.yaml)$|^(docker-compose|compose)(?:\.[^/]+)?\.ya?ml$|^Makefile$|^\.github/workflows/)'
    if ($isSharedBuild) {
        $areas += "SharedBuild"
        $gates += "full-repo-regression"
        Raise-Risk 3 "A root shared build, dependency, Compose, or CI path can affect the complete repository."
        continue
    }

    if ($path -match '^apps/web/') {
        $areas += "Web"
        $gates += "web-test"
        Raise-Risk 1 "Web behavior is isolated to the frontend."
        if ($path -match '(?i)(package(-lock)?\.json$|vite\.config|tsconfig|nginx\.conf$|Dockerfile$)') {
            $gates += "web-build"
            Raise-Risk 2 "Web build or production configuration changed."
        }
        continue
    }

    if ($path -match '^services/core-api/') {
        $areas += "CoreApi"
        $gates += "java-clean-verify"
        Raise-Risk 2 "Core API implementation or configuration changed."
        if ($path -match '(?i)(/db/migration/|/security/|auth|jwt|permission|quota|approval|idempot)') {
            $gates += "database-integration"
            Raise-Risk 3 "Schema or security-sensitive Core API behavior changed."
        }
        if ($path -match '(?i)(/api/|Controller\.java$|Request\.java$|Response\.java$)') {
            Raise-Risk 3 "A public or cross-service API contract may have changed."
        }
        continue
    }

    if ($path -match '^services/agent-service/') {
        $areas += "AgentService"
        $gates += "python-test"
        Raise-Risk 2 "Agent Service implementation or configuration changed."
        if ($path -match '(?i)(/api\.py$|/schemas\.py$|/graph\.py$|/main\.py$|state|checkpoint|permission|quota|approval|idempot)') {
            Raise-Risk 3 "Agent Runtime, state, or HTTP contract may have changed."
        }
        continue
    }

    $isTls = $path -match '(?i)(tls|letsencrypt|certbot|infra/nginx/production\.conf)'
    $isDeployment = $path -match '^(scripts/deploy/|infra/|\.env(?:\.production)?\.example$)'
    if ($isDeployment) {
        $areas += "Deployment"
        $gates += "compose-config"
        Raise-Risk 2 "Deployment or infrastructure behavior changed."
        if ($path -match '(?i)\.(sh|bash)$') {
            $gates += "shell-parser"
        }
        if ($isTls) {
            $areas += "TlsDeployment"
            $gates += @("tls-contract", "nginx-config")
        }
        continue
    }

    if ($isDocument) {
        continue
    }

    $areas += "Unknown"
    $gates += "manual-impact-review"
    Raise-Risk 2 "At least one path is unknown and requires manual impact review: $path"
}

$areas = @($areas | Sort-Object -Unique)
if ("CoreApi" -in $areas -and "AgentService" -in $areas) {
    $gates += "cross-process-smoke"
    Raise-Risk 3 "Core API and Agent Service changed together."
}
if ("Web" -in $areas -and "CoreApi" -in $areas) {
    $gates += "web-core-contract"
    Raise-Risk 2 "Web and Core API changed together."
}

if ($ReleaseGate) {
    $areas += "Release"
    $gates += @(
        "java-clean-verify",
        "python-test",
        "web-test",
        "web-build",
        "compose-config",
        "cross-process-smoke",
        "full-repo-regression"
    )
    Raise-Risk 3 "Release Gate always requires the complete repository regression."
}

$areas = @($areas | Sort-Object -Unique)
$gates = @($gates | Sort-Object -Unique)
$reasons = @($reasons | Sort-Object -Unique)
$riskLevel = "L$riskRank"
$reviewMode = if ($ReleaseGate -or $Milestone -or $riskRank -ge 3) {
    "Milestone"
} elseif ($riskRank -eq 2) {
    "Diff"
} else {
    "None"
}

$fingerprintParts = @(
    "source=$source",
    "milestone=$([bool]$Milestone)",
    "release=$([bool]$ReleaseGate)"
)
foreach ($path in $changedPaths) {
    $absolutePath = Join-Path $projectRoot $path
    $contentHash = if ($Paths.Count -gt 0 -or $TargetRef -eq "WORKTREE") {
        if (Test-Path -LiteralPath $absolutePath -PathType Leaf) {
            (Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash.ToLowerInvariant()
        } else {
            "missing"
        }
    } elseif ($TargetRef -eq "INDEX") {
        $indexEntry = @(& git ls-files --stage -- $path)
        if ($LASTEXITCODE -ne 0) { throw "git ls-files failed for $path" }
        if ($indexEntry.Count -gt 0) {
            ($indexEntry[0] -split '\s+')[1]
        } else {
            "missing"
        }
    } else {
        $objectId = @(& git rev-parse --verify "$TargetRef`:$path" 2>$null)
        if ($LASTEXITCODE -eq 0 -and $objectId.Count -gt 0) {
            $objectId[0].Trim()
        } else {
            "missing"
        }
    }
    $fingerprintParts += "$path=$contentHash"
}
$fingerprintInput = [System.Text.Encoding]::UTF8.GetBytes($fingerprintParts -join "`n")
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $changeFingerprint = ([BitConverter]::ToString($sha.ComputeHash($fingerprintInput))).Replace("-", "").ToLowerInvariant()
} finally {
    $sha.Dispose()
}

$result = [PSCustomObject]@{
    source = $source
    riskLevel = $riskLevel
    areas = $areas
    gates = $gates
    reviewMode = $reviewMode
    reasons = $reasons
    changedPaths = $changedPaths
    changeFingerprint = $changeFingerprint
}

if ($Json) {
    $result | ConvertTo-Json -Depth 5
} else {
    Write-Host "Risk: $riskLevel | Review: $reviewMode | Areas: $($areas -join ', ')"
    Write-Host "Gates: $($gates -join ', ')"
    Write-Host "Fingerprint: $changeFingerprint"
    foreach ($reason in $reasons) {
        Write-Host "- $reason"
    }
}
