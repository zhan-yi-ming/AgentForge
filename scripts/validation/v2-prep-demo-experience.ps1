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
$seed = Get-Content -Raw -Encoding UTF8 (Join-Path $root "scripts/deploy/seed-demo-v12.sh")
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
    'ensure_wiki_page',
    'ensure_task',
    'AgentForge V2 Architecture',
    'Interview demo walkthrough'
)) {
    if (-not $seed.Contains($entry)) {
        throw "seed-demo-v12.sh does not idempotently include the V2 interview dataset: $entry"
    }
}

foreach ($value in @($expectedEmail, $expectedPassword)) {
    if ($webApp.Contains($value)) {
        throw "App.tsx must not contain a Demo credential literal."
    }
}

if (-not $webApp.Contains('账号是简历上的邮箱，密码是微信号')) {
    throw "App.tsx does not contain the approved credential hint."
}

if (-not $webApp.Contains('请联系我 向我索要体验账号')) {
    throw "App.tsx does not contain the approved login failure message."
}

foreach ($entry in @(
    'synchronize_fixed_account_password',
    "WHERE email=lower(:'demo_email') AND role='USER'",
    "'{bcrypt}' || crypt(:'demo_password', gen_salt('bf', 10))"
)) {
    if (-not $seed.Contains($entry)) {
        throw "seed-demo-v12.sh does not safely synchronize an existing fixed Demo account: $entry"
    }
}

if (-not $validator.Contains('PUBLIC_DEMO_PASSWORD="Z1060168"')) {
    throw "validate-env.sh does not explicitly scope the eight-character exception to the approved public Demo password."
}

Write-Host "V2-prep Demo contract passed: server account is stable, browser literals are absent, and the short-password exception is explicitly scoped."
