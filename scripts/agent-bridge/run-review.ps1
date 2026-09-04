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
    [ValidateSet("deepseek/deepseek-v4-pro")]
    [string]$Model = "deepseek/deepseek-v4-pro"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $ProjectRoot
$PiCmd = "C:\Users\86134\Documents\Codex\2026-09-03\bang\outputs\pi.cmd"
if (-not (Test-Path -LiteralPath $PiCmd)) {
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

$TargetCommit = (& git rev-parse --verify "$TargetRef^{commit}").Trim()
if ($LASTEXITCODE -ne 0) {
    throw "无法解析审查目标提交：$TargetRef"
}

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

if ($ChangedFiles.Count -eq 0) {
    throw "审查范围没有文件变化：$BaseCommit .. $TargetCommit"
}

$SensitivePatterns = @{
    "private-key" = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    "github-token" = '\bgh[pousr]_[A-Za-z0-9_]{20,}\b'
    "aws-access-key" = '\bAKIA[0-9A-Z]{16}\b'
    "openai-key" = '\bsk-[A-Za-z0-9]{20,}\b'
}
foreach ($patternName in $SensitivePatterns.Keys) {
    if ([regex]::IsMatch($DiffText, $SensitivePatterns[$patternName])) {
        throw "本地敏感信息扫描命中规则 $patternName；已拒绝将差异发送给 Pi。"
    }
}

# 大型阶段提交会包含大量文档和脚手架。无界 diff 会让审查本身超时；保留首、中、尾
# 三段证据，可覆盖不同目录的变更，同时完整文件清单与统计仍保留在提示词中。
$MaximumDiffCharacters = 24000
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
$TemplateRaw = Get-Content -Raw -LiteralPath $TemplateFile -Encoding UTF8
$PromptContent = $TemplateRaw.Replace("{{STAGE_NAME}}", $StageName)
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
$TempOutputFile = [System.IO.Path]::GetTempFileName() + ".out"
$TempErrorFile = [System.IO.Path]::GetTempFileName() + ".err"

try {
    [System.IO.File]::WriteAllText($TempPromptFile, $PromptContent, [System.Text.UTF8Encoding]::new($false))
    $FileArg = "@$TempPromptFile"
    $process = Start-Process -FilePath $PiCmd `
        -ArgumentList @("--no-session", "--no-context-files", "--no-tools", "--thinking", "minimal", "--model", $Model, "-p", $FileArg) `
        -WorkingDirectory $ProjectRoot `
        -RedirectStandardOutput $TempOutputFile `
        -RedirectStandardError $TempErrorFile `
        -NoNewWindow `
        -PassThru

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Pi 审查在 $TimeoutSeconds 秒内未完成，已停止该次进程。"
    }

    $PiOutput = Get-Content -Raw -LiteralPath $TempOutputFile -ErrorAction SilentlyContinue
    $PiError = Get-Content -Raw -LiteralPath $TempErrorFile -ErrorAction SilentlyContinue
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
    [PSCustomObject]@{
        StageName = $StageName
        Attempt = $Attempt
        BaseCommit = $BaseCommit
        TargetCommit = $TargetCommit
        Result = $Result
        ReportPath = $OutputFile
    }
} finally {
    foreach ($temporaryFile in @($TempPromptFile, $TempOutputFile, $TempErrorFile)) {
        if (Test-Path -LiteralPath $temporaryFile) {
            Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
        }
    }
}
