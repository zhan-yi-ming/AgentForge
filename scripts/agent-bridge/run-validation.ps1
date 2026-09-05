<#
.SYNOPSIS
    使用 DeepSeek V4-pro 执行受限测试设计、测试命令和测试数据清理。
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$StageName,
    [Parameter(Mandatory = $true)][string]$PromptFile,
    [string]$OutputFile,
    [ValidateRange(60, 3600)][int]$TimeoutSeconds = 900,
    [ValidateSet("deepseek/deepseek-v4-pro")][string]$Model = "deepseek/deepseek-v4-pro"
)

# Disabled by user request on 2026-09-05; retain historical implementation for audit only.
[PSCustomObject]@{ OverallStatus = 'DISABLED'; Actions = @(); Reason = 'Pi review and validation disabled by user' }
return

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$promptPath = (Resolve-Path -LiteralPath $PromptFile).Path
$piInstructionsPath = Join-Path $PSScriptRoot "pi\AGENTS.md"
$piCommand = $env:AGENTFORGE_PI_CMD
if ([string]::IsNullOrWhiteSpace($piCommand) -or -not (Test-Path -LiteralPath $piCommand)) {
    $piInPath = Get-Command pi.cmd -ErrorAction SilentlyContinue
    if ($null -eq $piInPath) { throw "未找到 Pi Agent 启动器 pi.cmd。" }
    $piCommand = $piInPath.Source
}

$catalog = & $piCommand --list-models "v4-pro" 2>&1
if ($LASTEXITCODE -ne 0 -or (($catalog -join "`n") -notmatch '(?m)^deepseek\s+deepseek-v4-pro\s')) {
    throw "Pi 模型目录中未找到 deepseek/deepseek-v4-pro；禁止降级到 Flash。"
}

$guard = @"
你是 AgentForge 的独立验证员。模型身份必须是 DeepSeek V4-pro。只允许：读取仓库文件；设计测试并在最终输出中给出建议代码；执行 Prompt 明确列出的测试命令；删除本次测试在系统临时目录、构建目录或 Testcontainers 中明确创建的数据。禁止使用 edit/write，禁止通过 PowerShell 写源码或文档，禁止 Git 写操作，禁止访问生产环境、用户数据或输出密钥。发现实现问题时只报告，由 Codex 修改。必须记录执行的每条命令、退出码、测试数量、跳过项和清理结果；无法执行时必须真实说明，不得伪造通过。

"@
$sourcePrompt = Get-Content -Raw -LiteralPath $promptPath -Encoding UTF8
$piInstructions = Get-Content -Raw -LiteralPath $piInstructionsPath -Encoding UTF8
$temporaryPrompt = [System.IO.Path]::GetTempFileName() + ".md"
$liveOutput = Join-Path $PSScriptRoot ".pi-live-output.log"
$liveError = Join-Path $PSScriptRoot ".pi-live-error.log"
$statusFile = Join-Path $PSScriptRoot ".pi-review-status.json"
$process = $null
$finalStatus = "FAILED"

try {
    [System.IO.File]::WriteAllText($temporaryPrompt, $piInstructions + "`n`n当前模式：VALIDATION`n`n" + $guard + $sourcePrompt, [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($liveOutput, "", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($liveError, "", [System.Text.UTF8Encoding]::new($false))
    $process = Start-Process -FilePath $piCommand `
        -ArgumentList @("--no-session", "--no-context-files", "--tools", "read,grep,find,ls,powershell", "--thinking", "minimal", "--model", $Model, "-p", "@$temporaryPrompt") `
        -WorkingDirectory $projectRoot `
        -RedirectStandardOutput $liveOutput `
        -RedirectStandardError $liveError `
        -NoNewWindow `
        -PassThru
    [System.IO.File]::WriteAllText($statusFile, (([PSCustomObject]@{ status="RUNNING"; mode="VALIDATION"; stage=$StageName; model=$Model; processId=$process.Id; startedAt=(Get-Date).ToString("o") }) | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "Pi 验证在 $TimeoutSeconds 秒内未完成。"
    }
    $output = Get-Content -Raw -LiteralPath $liveOutput -ErrorAction SilentlyContinue
    $errorOutput = Get-Content -Raw -LiteralPath $liveError -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) { throw "Pi 验证退出码 $($process.ExitCode)：$errorOutput" }
    if ([string]::IsNullOrWhiteSpace($output)) { throw "Pi 验证没有输出。" }
    if (-not [string]::IsNullOrWhiteSpace($OutputFile)) {
        [System.IO.File]::WriteAllText($OutputFile, $output, [System.Text.UTF8Encoding]::new($false))
    }
    $finalStatus = "COMPLETED"
    [PSCustomObject]@{ StageName=$StageName; Model=$Model; OutputFile=$OutputFile; Output=$output }
} finally {
    [System.IO.File]::WriteAllText($statusFile, (([PSCustomObject]@{ status=$finalStatus; mode="VALIDATION"; stage=$StageName; model=$Model; processId=$(if($null -eq $process){$null}else{$process.Id}); finishedAt=(Get-Date).ToString("o") }) | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $temporaryPrompt) { Remove-Item -LiteralPath $temporaryPrompt -Force -ErrorAction SilentlyContinue }
}
