<#
.SYNOPSIS
    使用 DeepSeek V4-pro 对一个 Git 交付范围执行有超时保护的只读审查。
.DESCRIPTION
    将明确的 Git diff、文件清单与上一轮报告摘要写入临时提示词，再调用 Pi。
    成功时写入一份独立报告，并把机器可读的 REVIEW_RESULT 返回给 review-loop。
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$StageName,
    [string]$BaseRef,
    [Parameter(Mandatory = $true)]
    [string]$TargetRef,
    [ValidateRange(1, 3)]
    [int]$Attempt = 1,
    [string]$PriorReportPath,
    [string]$OutputFile,
    [ValidateRange(60, 1800)]
    [int]$TimeoutSeconds = 300,
    [ValidateSet("Diff", "Milestone")]
    [string]$ReviewMode = "Diff",
    [string[]]$ContextFiles = @(),
    [ValidateSet("deepseek/deepseek-v4-pro")]
    [string]$Model = "deepseek/deepseek-v4-pro"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $ProjectRoot
$PiCmd = $env:AGENTFORGE_PI_CMD
if ([string]::IsNullOrWhiteSpace($PiCmd) -or -not (Test-Path -LiteralPath $PiCmd)) {
    $PiInPath = Get-Command pi.cmd -ErrorAction SilentlyContinue
    if ($null -eq $PiInPath) {
        throw "未找到 Pi Agent 启动器 pi.cmd。"
    }
    $PiCmd = $PiInPath.Source
}

$ModelCatalog = & $PiCmd --list-models "v4-pro" 2>&1
if ($LASTEXITCODE -ne 0 -or (($ModelCatalog -join "`n") -notmatch '(?m)\bdeepseek\b.*\bdeepseek-v4-pro\b')) {
    throw "Pi 模型目录中未找到 deepseek/deepseek-v4-pro；审查已停止，禁止降级到 Flash。"
}

$IsWorktreeReview = $TargetRef -eq "WORKTREE"
$IsIndexReview = $TargetRef -eq "INDEX"
if ($IsIndexReview) {
    if ([string]::IsNullOrWhiteSpace($BaseRef)) { $BaseRef = "HEAD" }
    $BaseCommit = (& git rev-parse --verify "$BaseRef^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw "无法解析暂存区审查基线：$BaseRef" }
    $TargetCommit = "INDEX@$((& git rev-parse --short HEAD).Trim())"
    $ChangedFiles = @(& git diff --cached --no-ext-diff --name-only $BaseCommit)
    $DiffStat = @(& git diff --cached --no-ext-diff --stat $BaseCommit)
    $DiffText = (& git diff --cached --no-ext-diff --unified=20 $BaseCommit) -join "`n"
} elseif ($IsWorktreeReview) {
    if ([string]::IsNullOrWhiteSpace($BaseRef)) { $BaseRef = "HEAD" }
    $BaseCommit = (& git rev-parse --verify "$BaseRef^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw "无法解析工作树审查基线：$BaseRef" }
    $TargetCommit = "WORKTREE@$((& git rev-parse --short HEAD).Trim())"
    $ChangedFiles = @(& git diff --no-ext-diff --name-only $BaseCommit)
    $DiffStat = @(& git diff --no-ext-diff --stat $BaseCommit)
    $DiffText = (& git diff --no-ext-diff --unified=20 $BaseCommit) -join "`n"
} else {
    $TargetCommit = (& git rev-parse --verify "$TargetRef^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw "无法解析审查目标提交：$TargetRef" }
    $IsRootReview = [string]::IsNullOrWhiteSpace($BaseRef)
    if (-not $IsRootReview) {
    $BaseCommit = (& git rev-parse --verify "$BaseRef^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "无法解析审查基线提交：$BaseRef"
    }
    $ChangedFiles = @(& git diff --no-ext-diff --name-only "$BaseCommit..$TargetCommit")
    $DiffStat = @(& git diff --no-ext-diff --stat "$BaseCommit..$TargetCommit")
    $DiffText = (& git diff --no-ext-diff --unified=20 "$BaseCommit..$TargetCommit") -join "`n"
    } else {
        $BaseCommit = "<root>"
        $ChangedFiles = @(& git diff --root --no-ext-diff --name-only $TargetCommit)
        $DiffStat = @(& git diff --root --no-ext-diff --stat $TargetCommit)
        $DiffText = (& git diff --root --no-ext-diff --unified=20 $TargetCommit) -join "`n"
    }
}

if ($ChangedFiles.Count -eq 0) {
    throw "审查范围没有文件变化：$BaseCommit .. $TargetCommit"
}

$ContextParts = @()
$ContextCharacterLimit = 40000
$ContextCharacterCount = 0
foreach ($contextFile in $ContextFiles) {
    if ([string]::IsNullOrWhiteSpace($contextFile)) { continue }
    $resolvedContext = (Resolve-Path -LiteralPath $contextFile -ErrorAction Stop).Path
    $rootPrefix = $ProjectRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolvedContext.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "审查上下文必须位于项目目录内：$contextFile"
    }
    & git ls-files --error-unmatch -- $resolvedContext | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "审查上下文必须是 Git 已跟踪文件：$contextFile" }
    if ([System.IO.Path]::GetFileName($resolvedContext) -match '(?i)(^\.env|private|credential|secret|token|password)') {
        throw "审查上下文文件名疑似敏感，已拒绝：$contextFile"
    }
    $contextContent = Get-Content -Raw -LiteralPath $resolvedContext -Encoding UTF8
    $ContextCharacterCount += $contextContent.Length
    if ($ContextCharacterCount -gt $ContextCharacterLimit) {
        throw "显式审查上下文超过 $ContextCharacterLimit 字符上限。"
    }
    $relativeContext = [System.IO.Path]::GetRelativePath($ProjectRoot, $resolvedContext).Replace('\', '/')
    $ContextParts += "### $relativeContext`n`n$contextContent"
}
$ReviewContext = if ($ContextParts.Count -eq 0) { "无；仅审查本次 diff。" } else { $ContextParts -join "`n`n" }

$SensitivePatterns = @{
    "private-key" = '(?m)^\+?\s*-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s*$'
    "github-token" = '\bgh[pousr]_[A-Za-z0-9_]{20,}\b'
    "aws-access-key" = '\bAKIA[0-9A-Z]{16}\b'
    "openai-key" = '\bsk-[A-Za-z0-9]{20,}\b'
    "jwt" = '\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b'
    "bearer-token" = '\bBearer\s+[A-Za-z0-9._~+/-]{16,}={0,2}\b'
}
foreach ($patternName in $SensitivePatterns.Keys) {
    if ([regex]::IsMatch(($DiffText + "`n" + $ReviewContext), $SensitivePatterns[$patternName])) {
        throw "本地敏感信息扫描命中规则 $patternName；已拒绝将差异发送给 Pi。"
    }
}

# 让正常阶段交付携带完整 diff；只有异常超大差异才保留首、中、尾证据并明确标记截断。
# Day 5 当前约 14.2 万字符，低于本上限，因此 Pi 能看到全部实现、测试和文档变化。
$MaximumDiffCharacters = 180000
$DiffWasTruncated = $DiffText.Length -gt $MaximumDiffCharacters
if ($DiffWasTruncated) {
    $FragmentLength = [int]($MaximumDiffCharacters / 3)
    $MiddleStart = [Math]::Max(0, [int](($DiffText.Length - $FragmentLength) / 2))
    $TailStart = $DiffText.Length - $FragmentLength
    $DiffText = @(
        "--- diff evidence: beginning ---"
        $DiffText.Substring(0, $FragmentLength)
        "--- diff evidence: middle ---"
        $DiffText.Substring($MiddleStart, $FragmentLength)
        "--- diff evidence: end ---"
        $DiffText.Substring($TailStart, $FragmentLength)
    ) -join "`n"
}

$PriorReportExcerpt = "无"
if (-not [string]::IsNullOrWhiteSpace($PriorReportPath) -and (Test-Path -LiteralPath $PriorReportPath)) {
    $PriorReportExcerpt = Get-Content -Raw -LiteralPath $PriorReportPath
    if ($PriorReportExcerpt.Length -gt 30000) {
        $PriorReportExcerpt = $PriorReportExcerpt.Substring(0, 30000)
    }
}

$Today = Get-Date -Format "yyyy-MM-dd"
$ReviewsDir = Join-Path $ProjectRoot "docs\08-reviews"
if (-not (Test-Path -LiteralPath $ReviewsDir)) {
    New-Item -ItemType Directory -Path $ReviewsDir -Force | Out-Null
}
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $ReviewsDir "$Today-review-$StageName-attempt-$Attempt.md"
}

$TemplateFile = Join-Path $PSScriptRoot "prompts\stage-review-system.md"
$PiInstructionsFile = Join-Path $PSScriptRoot "pi\AGENTS.md"
$PiInstructions = Get-Content -Raw -LiteralPath $PiInstructionsFile -Encoding UTF8
$TemplateRaw = Get-Content -Raw -LiteralPath $TemplateFile -Encoding UTF8
$PromptContent = ($PiInstructions + "`n`n当前模式：REVIEW`n`n" + $TemplateRaw).Replace("{{STAGE_NAME}}", $StageName)
$PromptContent = $PromptContent.Replace("{{REVIEW_MODE}}", $ReviewMode)
$PromptContent = $PromptContent.Replace("{{REVIEW_CONTEXT}}", $ReviewContext)
$PromptContent = $PromptContent.Replace("{{ATTEMPT}}", $Attempt.ToString())
$PromptContent = $PromptContent.Replace("{{HEAD_COMMIT}}", $TargetCommit)
$PromptContent = $PromptContent.Replace("{{BASE_REF}}", $BaseCommit)
$PromptContent = $PromptContent.Replace("{{TARGET_REF}}", $TargetCommit)
$PromptContent = $PromptContent.Replace("{{CHANGED_FILES}}", ($ChangedFiles -join "`n"))
$PromptContent = $PromptContent.Replace("{{DIFF_STAT}}", ($DiffStat -join "`n"))
$PromptContent = $PromptContent.Replace("{{DIFF_TRUNCATED}}", $(if ($DiffWasTruncated) { "是；证据为 diff 的首、中、尾采样，完整文件清单与统计仍在上方。" } else { "否。" }))
$PromptContent = $PromptContent.Replace("{{PRIOR_REPORT}}", $PriorReportExcerpt)
$PromptContent = $PromptContent.Replace("{{GIT_DIFF}}", $DiffText)

$TempPromptFile = [System.IO.Path]::GetTempFileName() + ".md"
$LiveOutputFile = Join-Path $PSScriptRoot ".pi-live-output.log"
$LiveErrorFile = Join-Path $PSScriptRoot ".pi-live-error.log"
$StatusFile = Join-Path $PSScriptRoot ".pi-review-status.json"
$process = $null
$finalStatus = "FAILED"

try {
    [System.IO.File]::WriteAllText($TempPromptFile, $PromptContent, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($LiveOutputFile, "", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($LiveErrorFile, "", [System.Text.UTF8Encoding]::new($false))
    $FileArg = "@$TempPromptFile"
    $process = Start-Process -FilePath $PiCmd `
        -ArgumentList @("--no-session", "--no-context-files", "--no-tools", "--thinking", "minimal", "--model", $Model, "-p", $FileArg) `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $LiveOutputFile `
        -RedirectStandardError $LiveErrorFile `
        -NoNewWindow `
        -PassThru
    [System.IO.File]::WriteAllText($StatusFile, (([PSCustomObject]@{ status="RUNNING"; stage=$StageName; attempt=$Attempt; model=$Model; processId=$process.Id; startedAt=(Get-Date).ToString("o") }) | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Pi 审查在 $TimeoutSeconds 秒内未完成，已停止该次进程。"
    }

    $PiOutput = Get-Content -Raw -LiteralPath $LiveOutputFile -ErrorAction SilentlyContinue
    $PiError = Get-Content -Raw -LiteralPath $LiveErrorFile -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) {
        throw "Pi Agent 以退出码 $($process.ExitCode) 结束：$PiError"
    }
    if ([string]::IsNullOrWhiteSpace($PiOutput)) {
        throw "Pi Agent 未返回审查报告内容：$PiError"
    }

    $Result = "NEEDS_FIX"
    if ($PiOutput -match '(?im)^\s*REVIEW_RESULT:\s*(PASS|NEEDS_FIX)\s*$') {
        $Result = $matches[1].ToUpperInvariant()
    }

    $Report = @"
# Pi 代码审查报告：$StageName / Attempt $Attempt

- 日期：$Today
- 审查阶段：$StageName
- 审查对象：$TargetCommit（基线：$BaseCommit）
- 审查工具：Pi Agent（DeepSeek V4-pro，只读）
- REVIEW_RESULT: $Result
- Pi 进程超时上限：$TimeoutSeconds 秒

---

$PiOutput
"@
    [System.IO.File]::WriteAllText($OutputFile, $Report, [System.Text.UTF8Encoding]::new($false))
    $finalStatus = "COMPLETED"
    [PSCustomObject]@{
        StageName = $StageName
        Attempt = $Attempt
        BaseCommit = $BaseCommit
        TargetCommit = $TargetCommit
        Result = $Result
        ReportPath = $OutputFile
    }
} finally {
    [System.IO.File]::WriteAllText($StatusFile, (([PSCustomObject]@{ status=$finalStatus; stage=$StageName; attempt=$Attempt; model=$Model; processId=$(if($null -eq $process){$null}else{$process.Id}); finishedAt=(Get-Date).ToString("o") }) | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
    foreach ($temporaryFile in @($TempPromptFile)) {
        if (Test-Path -LiteralPath $temporaryFile) {
            Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
        }
    }
}
