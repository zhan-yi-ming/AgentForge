<#
.SYNOPSIS
    编排 Pi 阶段审查、Codex 修复复审和三次人工接管循环。
#>

[CmdletBinding()]
param(
    [switch]$OnCodexWake,
    [switch]$OnMonitorWake,
    [switch]$DryRun,
    [ValidateRange(60, 1800)]
    [int]$TimeoutSeconds = 300,
    [string]$StatePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $ProjectRoot
$StagesFile = Join-Path $PSScriptRoot "review-stages.json"
$RunReviewScript = Join-Path $PSScriptRoot "run-review.ps1"
if ([string]::IsNullOrWhiteSpace($StatePath)) {
    $StatePath = Join-Path $PSScriptRoot ".review-loop-state.json"
}
$script:StateLock = $null
if (-not $DryRun) {
    try {
        $script:StateLock = [System.IO.File]::Open("$StatePath.lock", [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
    } catch [System.IO.IOException] {
        [PSCustomObject]@{ OverallStatus = "BUSY"; StatePath = $StatePath; Actions = @() }
        return
    }
}

try {

function ConvertTo-Hashtable($Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $result = @{}
        foreach ($key in $Value.Keys) { $result[$key] = ConvertTo-Hashtable $Value[$key] }
        return $result
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        $result = @()
        foreach ($item in $Value) { $result += ,(ConvertTo-Hashtable $item) }
        return $result
    }
    if ($Value -is [pscustomobject]) {
        $result = @{}
        foreach ($property in $Value.PSObject.Properties) {
            $result[$property.Name] = ConvertTo-Hashtable $property.Value
        }
        return $result
    }
    return $Value
}

function Get-State {
    if (-not (Test-Path -LiteralPath $StatePath)) {
        return @{ schemaVersion = 1; stages = @{}; nextStageReady = $null }
    }
    try {
        $state = ConvertTo-Hashtable (Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json)
        if ($null -eq $state.stages) { $state.stages = @{} }
        if (-not $state.ContainsKey("nextStageReady")) { $state.nextStageReady = $null }
        return $state
    } catch {
        throw "无法读取审查循环状态 $StatePath：$($_.Exception.Message)"
    }
}

function Save-State($State) {
    $parent = Split-Path -Parent $StatePath
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $temporaryStatePath = "$StatePath.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    $backupStatePath = "$StatePath.backup"
    try {
        [System.IO.File]::WriteAllText(
            $temporaryStatePath,
            ($State | ConvertTo-Json -Depth 8),
            [System.Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $StatePath) {
            [System.IO.File]::Replace($temporaryStatePath, $StatePath, $backupStatePath)
            Remove-Item -LiteralPath $backupStatePath -Force -ErrorAction SilentlyContinue
        } else {
            Move-Item -LiteralPath $temporaryStatePath -Destination $StatePath
        }
    } catch {
        if ($null -ne $script:StateLock) { $script:StateLock.Dispose(); $script:StateLock = $null }
        throw
    } finally {
        if (Test-Path -LiteralPath $temporaryStatePath) {
            Remove-Item -LiteralPath $temporaryStatePath -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $backupStatePath) {
            Remove-Item -LiteralPath $backupStatePath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Ensure-Stage($State, $Definition) {
    $id = [string]$Definition.id
    if (-not $State.stages.ContainsKey($id)) {
        $State.stages[$id] = @{
            id = $id
            displayName = [string]$Definition.displayName
            initialBaseRef = $Definition.baseRef
            deliveryCommit = [string]$Definition.deliveryCommit
            attempts = 0
            status = "PENDING_REVIEW"
            lastReviewedCommit = $null
            lastReportPath = $null
            lastFailure = $null
            updatedAt = $null
        }
    }
    return $State.stages[$id]
}

function Restore-StageFromReports($Stage, [int]$MaximumAttempts) {
    if ([int]$Stage.attempts -gt 0) { return }
    $reviewsDirectory = Join-Path $ProjectRoot "docs\\08-reviews"
    $pattern = "*-review-$($Stage.id)-attempt-*.md"
    $candidate = Get-ChildItem -LiteralPath $reviewsDirectory -Filter $pattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '-attempt-(\d+)\.md$' } |
        Sort-Object { [int]([regex]::Match($_.Name, '-attempt-(\d+)\.md$').Groups[1].Value) } -Descending |
        Select-Object -First 1
    if ($null -eq $candidate) { return }

    $content = Get-Content -Raw -LiteralPath $candidate.FullName
    $attemptMatch = [regex]::Match($candidate.Name, '-attempt-(\d+)\.md$')
    $commitMatch = [regex]::Match($content, '\b[0-9a-f]{40}\b')
    $resultMatch = [regex]::Match($content, '(?im)^-\s*REVIEW_RESULT:\s*(PASS|NEEDS_FIX)\s*$')
    if (-not $attemptMatch.Success -or -not $commitMatch.Success -or -not $resultMatch.Success) { return }

    $Stage.attempts = [int]$attemptMatch.Groups[1].Value
    $Stage.lastReviewedCommit = $commitMatch.Value
    $Stage.lastReportPath = $candidate.FullName
    $Stage.updatedAt = (Get-Date).ToString("o")
    if ($resultMatch.Groups[1].Value -eq "PASS") {
        $Stage.status = "RESOLVED"
    } elseif ($Stage.attempts -ge $MaximumAttempts) {
        $Stage.status = "HUMAN_REQUIRED"
    } else {
        $Stage.status = "WAITING_FOR_CODEX_FIX"
    }
}

function Is-Ancestor([string]$Ancestor, [string]$Descendant) {
    if ([string]::IsNullOrWhiteSpace($Ancestor)) { return $false }
    & git merge-base --is-ancestor $Ancestor $Descendant
    return $LASTEXITCODE -eq 0
}

function Write-HumanInterventionRecord($Stage, [int]$MaximumAttempts) {
    $today = Get-Date -Format "yyyy-MM-dd"
    $file = Join-Path $ProjectRoot "docs\08-reviews\$today-review-$($Stage.id)-human-intervention.md"
    $content = @"
# 审查循环人工介入：$($Stage.displayName)

- 日期：$today
- 阶段：$($Stage.displayName)
- 状态：HUMAN_REQUIRED
- 已完成 Pi 审查次数：$MaximumAttempts / $MaximumAttempts
- 最新审查报告：$($Stage.lastReportPath)

## 为什么自动循环已停止

第 $MaximumAttempts 次 Pi V4-pro 审查仍返回 `NEEDS_FIX`。根据项目治理规则，Codex 不会开始第 4 次自动审查或自动实施更多修改，必须由用户确认后续取舍、范围或风险接受方式。

## 用户需要决定

1. 继续在当前阶段进行定向修复并允许新的人工授权审查；
2. 接受并记录明确的豁免；
3. 调整阶段范围或拆分后续工作。
"@
    [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
    return $file
}

function Get-CurrentCommit {
    $commit = (& git rev-parse --verify "HEAD^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw "无法解析当前 HEAD。" }
    return $commit
}

function Get-ReviewFixStageIds([string]$Commit) {
    $message = (& git show -s --format=%B $Commit) -join "`n"
    return @([regex]::Matches($message, '(?im)^Review-Fixes:\s*([a-z0-9][a-z0-9._-]*)\s*$') |
        ForEach-Object { $_.Groups[1].Value } |
        Select-Object -Unique)
}

function Find-AutomaticStageDefinition($State, $KnownDefinitions, [string]$CurrentCommit) {
    foreach ($definition in $KnownDefinitions) {
        if ([string]$definition.deliveryCommit -eq $CurrentCommit) {
            return $null
        }
    }
    foreach ($entry in $State.stages.Values) {
        if ($entry.deliveryCommit -eq $CurrentCommit -or $entry.lastReviewedCommit -eq $CurrentCommit) {
            return $null
        }
    }
    $changedFiles = @(& git diff-tree --no-commit-id --name-only -r $CurrentCommit)
    $changeRecord = $changedFiles |
        Where-Object { $_ -match '^docs/07-changes/\d{4}-\d{2}-\d{2}-.+\.md$' } |
        Select-Object -First 1
    if ($null -eq $changeRecord) { return $null }
    $stageId = [System.IO.Path]::GetFileNameWithoutExtension($changeRecord) -replace '^\d{4}-\d{2}-\d{2}-', ''
    $parent = (& git rev-parse --verify "$CurrentCommit^" 2>$null).Trim()
    if ($LASTEXITCODE -ne 0) { $parent = $null }
    return @{
        id = $stageId
        displayName = $stageId
        baseRef = $parent
        deliveryCommit = $CurrentCommit
    }
}

$stageRegistry = Get-Content -Raw -LiteralPath $StagesFile | ConvertFrom-Json
$maximumAttempts = [int]$stageRegistry.maximumAttempts
$state = Get-State
$currentCommit = Get-CurrentCommit
$definitions = @($stageRegistry.historicalStages)
$reviewFixStageIds = @(Get-ReviewFixStageIds $currentCommit)
$automatic = if ($reviewFixStageIds.Count -eq 0) { Find-AutomaticStageDefinition $state $definitions $currentCommit } else { $null }
if ($null -ne $automatic) { $definitions += [pscustomobject]$automatic }
foreach ($fixStageId in $reviewFixStageIds) {
    $knownStage = @($definitions | Where-Object { [string]$_.id -eq $fixStageId }).Count -gt 0 -or $state.stages.ContainsKey($fixStageId)
    if (-not $knownStage) { throw "Review-Fixes 引用了未知阶段：$fixStageId" }
}

$actions = @()
foreach ($definition in $definitions) {
    $stage = Ensure-Stage $state $definition
    Restore-StageFromReports $stage $maximumAttempts
    if ($stage.status -eq "HUMAN_REQUIRED") {
        $actions += [pscustomobject]@{ Stage = $stage.id; Action = "BLOCKED_HUMAN_REQUIRED"; Attempt = $stage.attempts }
        break
    }

    $targetRef = $null
    $baseRef = $null
    $isExplicitFix = $reviewFixStageIds -contains [string]$stage.id
    $isOtherRegisteredDelivery = @($definitions | Where-Object {
            ([string]$_.id -ne [string]$stage.id) -and ([string]$_.deliveryCommit -eq $currentCommit)
        }).Count -gt 0 -or @($state.stages.Values | Where-Object {
            ([string]$_.id -ne [string]$stage.id) -and ([string]$_.deliveryCommit -eq $currentCommit)
        }).Count -gt 0
    if ($stage.status -eq "PENDING_REVIEW") {
        $targetRef = $stage.deliveryCommit
        $baseRef = $stage.initialBaseRef
    } elseif ($stage.status -eq "WAITING_FOR_CODEX_FIX" -and
              ($reviewFixStageIds.Count -eq 0 -or $isExplicitFix) -and
              ($isExplicitFix -or -not $isOtherRegisteredDelivery) -and
              $stage.lastReviewedCommit -ne $currentCommit -and
              (Is-Ancestor $stage.lastReviewedCommit $currentCommit)) {
        $targetRef = $currentCommit
        $baseRef = $stage.lastReviewedCommit
    } elseif ($stage.status -eq "WAITING_FOR_CODEX_FIX") {
        $actions += [pscustomobject]@{ Stage = $stage.id; Action = "WAITING_FOR_CODEX_FIX"; Attempt = $stage.attempts }
        continue
    } elseif ($stage.status -eq "RESOLVED") {
        continue
    }

    if ($null -eq $targetRef) { continue }
    $nextAttempt = [int]$stage.attempts + 1
    $actions += [pscustomobject]@{ Stage = $stage.id; Action = "REVIEW"; Attempt = $nextAttempt; Base = $baseRef; Target = $targetRef }
    if ($DryRun) { continue }

    try {
        $review = & $RunReviewScript -StageName $stage.id -BaseRef $baseRef -TargetRef $targetRef `
            -Attempt $nextAttempt -PriorReportPath $stage.lastReportPath -TimeoutSeconds $TimeoutSeconds
        if ($null -eq $review -or [string]::IsNullOrWhiteSpace($review.ReportPath)) {
            throw "run-review 没有返回报告元数据。"
        }
        $stage.attempts = $nextAttempt
        $stage.lastReviewedCommit = $review.TargetCommit
        $stage.lastReportPath = $review.ReportPath
        $stage.lastFailure = $null
        $stage.updatedAt = (Get-Date).ToString("o")
        if ($review.Result -eq "PASS") {
            $stage.status = "RESOLVED"
            $state.nextStageReady = $stage.id
        } elseif ($nextAttempt -ge $maximumAttempts) {
            $stage.status = "HUMAN_REQUIRED"
            $stage.humanInterventionPath = Write-HumanInterventionRecord $stage $maximumAttempts
        } else {
            $stage.status = "WAITING_FOR_CODEX_FIX"
        }
        Save-State $state
    } catch {
        $stage.lastFailure = $_.Exception.Message
        $stage.updatedAt = (Get-Date).ToString("o")
        Save-State $state
        $actions += [pscustomobject]@{ Stage = $stage.id; Action = "REVIEW_FAILED"; Detail = $_.Exception.Message }
    }
}

$overallStatus = "IDLE"
if ($DryRun) {
    $overallStatus = "DRY_RUN"
} elseif ($state.stages.Values | Where-Object { $_.status -eq "HUMAN_REQUIRED" }) {
    $overallStatus = "HUMAN_REQUIRED"
} elseif ($state.stages.Values | Where-Object { $_.status -eq "WAITING_FOR_CODEX_FIX" }) {
    $overallStatus = "WAITING_FOR_CODEX_FIX"
} elseif ($actions | Where-Object { $_.Action -eq "REVIEW_FAILED" }) {
    $overallStatus = "REVIEW_FAILED"
} elseif ($actions | Where-Object { $_.Action -eq "REVIEW" }) {
    $overallStatus = "REVIEW_COMPLETED"
}
if (-not [string]::IsNullOrWhiteSpace([string]$state.nextStageReady)) {
    $overallStatus = "NEXT_STAGE_READY"
}

$finalResult = [PSCustomObject]@{
    OverallStatus = $overallStatus
    StatePath = $StatePath
    Actions = $actions
    NextStageReady = $state.nextStageReady
}
$finalResult
} finally {
    if ($null -ne $script:StateLock) { $script:StateLock.Dispose(); $script:StateLock = $null }
}
