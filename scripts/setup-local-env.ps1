[CmdletBinding()]
param(
    [string]$OutputPath = ".env",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$templatePath = Join-Path $projectRoot ".env.example"
$targetPath = if ([IO.Path]::IsPathRooted($OutputPath)) { $OutputPath } else { Join-Path $projectRoot $OutputPath }

if ((Test-Path -LiteralPath $targetPath) -and -not $Force) {
    throw "$targetPath already exists. Keep it, or rerun with -Force to replace it intentionally."
}

$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtBytes = New-Object byte[] 32
    $agentBytes = New-Object byte[] 32
    $coreBytes = New-Object byte[] 32
    $databaseBytes = New-Object byte[] 24
    $random.GetBytes($jwtBytes)
    $random.GetBytes($agentBytes)
    $random.GetBytes($coreBytes)
    $random.GetBytes($databaseBytes)
} finally {
    $random.Dispose()
}

$jwtSecret = [Convert]::ToBase64String($jwtBytes)
$agentToken = -join ($agentBytes | ForEach-Object { $_.ToString("x2") })
$coreToken = -join ($coreBytes | ForEach-Object { $_.ToString("x2") })
$databasePassword = -join ($databaseBytes | ForEach-Object { $_.ToString("x2") })

$content = [IO.File]::ReadAllText($templatePath)
$content = $content.Replace("REPLACE_WITH_BASE64_32_BYTE_RANDOM_VALUE", $jwtSecret)
$content = $content.Replace("REPLACE_WITH_RANDOM_INTERNAL_TOKEN", $agentToken)
$content = $content.Replace("REPLACE_WITH_RANDOM_CORE_INTERNAL_TOKEN", $coreToken)
$content = $content.Replace("agentforge_local_only", $databasePassword)
[IO.File]::WriteAllText($targetPath, $content, [Text.UTF8Encoding]::new($false))

Write-Host "Created local configuration: $targetPath"
Write-Host "Default disabled LLM mode needs no external API key."
Write-Host "To enable AI, edit this ignored file and set LLM_PROVIDER plus LLM_API_KEY."
