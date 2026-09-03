<#
.SYNOPSIS
    调用 Pi Agent (DeepSeek) 执行当前阶段代码的只读审查并生成标准审查报告。
.DESCRIPTION
    读取指定 Git 提交区间或当前工作区的 diff，结合系统提示词调用 Pi Agent
    生成符合 AgentForge 规范的 Markdown 审查文档并写入 docs/08-reviews/。
.PARAMETER StageName
    审查阶段名称，如 "day-2-security-wiki-task"。未指定时自动从变更记录或最新提交信息推断。
.PARAMETER BaseRef
    对比基准（默认 HEAD~1）。可指定特定 commit sha、tag 或分支。
.PARAMETER TargetRef
    审查目标（默认 HEAD）。
.PARAMETER OutputFile
    指定审查文档输出路径。未指定时默认存储在 docs/08-reviews/YYYY-MM-DD-review-<StageName>.md。
.PARAMETER Model
    Pi 运行使用的模型，固定为 deepseek/deepseek-v4-pro；禁止使用 Flash。
#>

[CmdletBinding()]
param(
    [string]$StageName,
    [string]$BaseRef = "HEAD~1",
    [string]$TargetRef = "HEAD",
    [string]$OutputFile,
    [ValidateSet("deepseek/deepseek-v4-pro")]
    [string]$Model = "deepseek/deepseek-v4-pro"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $ProjectRoot

# 1. 寻找 Pi 启动脚本
$PiCmd = "C:\Users\86134\Documents\Codex\2026-09-03\bang\outputs\pi.cmd"
if (-not (Test-Path $PiCmd)) {
    $PiInPath = Get-Command pi.cmd -ErrorAction SilentlyContinue
    if ($PiInPath) {
        $PiCmd = $PiInPath.Source
    } else {
        Write-Error "未找到 Pi Agent 启动脚本 pi.cmd，请确认安装路径。"
        exit 1
    }
}

$ModelCatalog = & $PiCmd --list-models "v4-pro" 2>&1
$ModelCatalogExitCode = $LASTEXITCODE
$ModelCatalogText = $ModelCatalog -join "`n"
if ($ModelCatalogExitCode -ne 0 -or $ModelCatalogText -notmatch '(?m)\bdeepseek\b.*\bdeepseek-v4-pro\b') {
    throw "Pi 模型目录中未找到 deepseek/deepseek-v4-pro；审查已停止，禁止降级到 Flash。"
}

# 2. 检查 Git 提交与 Diff
try {
    $HeadCommit = git rev-parse --short $TargetRef 2>$null
} catch {
    $HeadCommit = "WORKING_TREE"
}

if (-not $StageName) {
    # 尝试从最近的 07-changes 文件名或最新提交提取
    $LatestChange = Get-ChildItem (Join-Path $ProjectRoot "docs\07-changes\*.md") -Exclude "README.md" |
                    Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($LatestChange) {
        $StageName = [System.IO.Path]::GetFileNameWithoutExtension($LatestChange.Name)
    } else {
        $StageName = "stage-$HeadCommit"
    }
}

$Today = Get-Date -Format "yyyy-MM-dd"
$ReviewsDir = Join-Path $ProjectRoot "docs\08-reviews"
if (-not (Test-Path $ReviewsDir)) {
    New-Item -ItemType Directory -Path $ReviewsDir -Force | Out-Null
}

if (-not $OutputFile) {
    $OutputFile = Join-Path $ReviewsDir "$Today-review-$StageName.md"
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "[AgentForge Review Bridge] 启动 Pi Agent 代码审查" -ForegroundColor Green
Write-Host "对比范围: $BaseRef .. $TargetRef ($HeadCommit)" -ForegroundColor Yellow
Write-Host "审查阶段: $StageName" -ForegroundColor Yellow
Write-Host "输出文件: $OutputFile" -ForegroundColor Yellow
Write-Host "审查模型: $Model" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# 验证 BaseRef 是否存在
$BaseExists = $false
try {
    $null = git rev-parse --verify "$BaseRef" 2>&1
    if ($LASTEXITCODE -eq 0) { $BaseExists = $true }
} catch {
    $BaseExists = $false
}

$ChangedFiles = @()
$DiffStat = @()

if ($BaseExists) {
    $ChangedFiles = git diff --name-only "$BaseRef..$TargetRef" 2>&1
    $DiffStat = git diff --stat "$BaseRef..$TargetRef" 2>&1
}

if ((-not $ChangedFiles) -or (-not $BaseExists)) {
    Write-Host "提示: 正在对比当前工作区改动 (git diff / git status)..." -ForegroundColor Yellow
    $ChangedFiles = git status --short 2>&1
    $DiffStat = git diff --stat 2>&1
}

# 3. 构造审查专用 Prompt
$TemplateFile = Join-Path $PSScriptRoot "prompts\stage-review-system.md"
if (Test-Path $TemplateFile) {
    $TemplateRaw = Get-Content $TemplateFile -Raw -Encoding UTF8
} else {
    $TemplateRaw = "请作为 AgentForge 的独立代码审查员（Reviewer，依托 DeepSeek），执行对当前阶段代码的深度只读审查。"
}

$PromptContent = $TemplateRaw.Replace("{{STAGE_NAME}}", $StageName)
$PromptContent = $PromptContent.Replace("{{HEAD_COMMIT}}", $HeadCommit)
$PromptContent = $PromptContent.Replace("{{BASE_REF}}", $BaseRef)
$PromptContent = $PromptContent.Replace("{{TARGET_REF}}", $TargetRef)
$PromptContent = $PromptContent.Replace("{{DIFF_STAT}}", ($DiffStat -join "`n"))

$TempPromptFile = [System.IO.Path]::GetTempFileName() + ".md"
[System.IO.File]::WriteAllText($TempPromptFile, $PromptContent, [System.Text.Encoding]::UTF8)

# 4. 执行 Pi Agent 审查
Write-Host "正在调用 Pi Agent (DeepSeek) 分析代码中，请稍候..." -ForegroundColor Cyan

$TempOutputFile = [System.IO.Path]::GetTempFileName() + ".txt"

try {
    # 启用只读工具：read,grep,find,ls，管道传递空字符以关闭 stdin 避免非 TTY 挂起
    $FileArg = "@" + $TempPromptFile
    "" | & $PiCmd --tools read,grep,find,ls --model $Model -p $FileArg 2>&1 |
            Out-File -FilePath $TempOutputFile -Encoding utf8
    $PiExitCode = $LASTEXITCODE

    if ($PiExitCode -ne 0) {
        throw "Pi Agent 以退出码 $PiExitCode 结束。"
    }

    $PiOutput = Get-Content $TempOutputFile -Raw -Encoding utf8
    if ([string]::IsNullOrWhiteSpace($PiOutput)) {
        throw "Pi Agent 未返回审查报告内容。"
    }

    # 提取 Markdown 报告主体
    if ($PiOutput -match '(?s)(# 代码审查报告.*)') {
        $ReportMarkdown = $matches[1]
    } else {
        $ReportMarkdown = $PiOutput
    }

    [System.IO.File]::WriteAllText($OutputFile, $ReportMarkdown, [System.Text.Encoding]::UTF8)

    Write-Host "审查完成！报告已保存至: $OutputFile" -ForegroundColor Green
} catch {
    throw "Pi Agent 执行审查失败: $($_.Exception.Message)"
} finally {
    if (Test-Path $TempPromptFile) { Remove-Item $TempPromptFile -Force -ErrorAction SilentlyContinue }
    if (Test-Path $TempOutputFile) { Remove-Item $TempOutputFile -Force -ErrorAction SilentlyContinue }
}
