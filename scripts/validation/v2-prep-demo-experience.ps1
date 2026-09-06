[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$expectedEmail = "210168y@gmail.com"
$expectedPassword = "Z1060168"
$example = Get-Content -Raw -Encoding UTF8 (Join-Path $root ".env.production.example")
$generator = Get-Content -Raw -Encoding UTF8 (Join-Path $root "scripts/deploy/generate-production-env.sh")
$validator = Get-Content -Raw -Encoding UTF8 (Join-Path $root "scripts/deploy/validate-env.sh")
$webApp = Get-Content -Raw -Encoding UTF8 (Join-Path $root "apps/web/src/App.tsx")

foreach ($entry in @(
    "AGENTFORGE_DEMO_FIXED_EMAIL=$expectedEmail",
    "AGENTFORGE_DEMO_FIXED_PASSWORD=$expectedPassword"
)) {
    if (-not $example.Contains($entry)) {
        throw ".env.production.example does not contain the approved public Demo value: $entry"
    }
    if (-not $generator.Contains($entry)) {
        throw "generate-production-env.sh does not emit the approved public Demo value: $entry"
    }
}

foreach ($entry in @(
    "const PUBLIC_DEMO_EMAIL = `"$expectedEmail`";",
    "const PUBLIC_DEMO_PASSWORD = `"$expectedPassword`";"
)) {
    if (-not $webApp.Contains($entry)) {
        throw "App.tsx does not expose the approved public Demo value: $entry"
    }
}

if (-not $validator.Contains('PUBLIC_DEMO_PASSWORD="Z1060168"')) {
    throw "validate-env.sh does not explicitly scope the eight-character exception to the approved public Demo password."
}

Write-Host "V2-prep Demo contract passed: public account is stable and the short-password exception is explicitly scoped."
