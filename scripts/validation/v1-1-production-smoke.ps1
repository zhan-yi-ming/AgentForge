[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$testRoot = Join-Path $root ".data\v1-1-production-smoke"
$tlsDir = Join-Path $testRoot "tls"
$acmeDir = Join-Path $testRoot "acme"
$envFile = Join-Path $testRoot ".env"
$project = "agentforge-v11-smoke"

function Invoke-Compose {
    & docker compose -p $project --env-file $envFile -f (Join-Path $root "infra\compose.prod.yaml") @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $args" }
}

try {
    New-Item -ItemType Directory -Force $tlsDir, $acmeDir | Out-Null
    & "C:\Program Files\Git\usr\bin\openssl.exe" req -x509 -newkey rsa:2048 -nodes -days 1 `
        -keyout (Join-Path $tlsDir "privkey.pem") -out (Join-Path $tlsDir "fullchain.pem") `
        -subj "/CN=127.0.0.1" -addext "subjectAltName=IP:127.0.0.1" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Failed to generate the smoke-test certificate." }

    $bytes = [byte[]]::new(48)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $jwt = [Convert]::ToBase64String($bytes)
    $envContent = @"
PUBLIC_HOST=127.0.0.1
POSTGRES_DB=agentforge
POSTGRES_USER=agentforge
POSTGRES_PASSWORD=smoketestpassword0123456789
AGENTFORGE_POSTGRES_VOLUME=agentforge-v11-smoke-postgres-data
AGENTFORGE_JWT_SECRET=$jwt
AGENTFORGE_JWT_ISSUER=https://127.0.0.1/core-api
AGENTFORGE_JWT_TTL=PT30M
AGENTFORGE_AGENT_INTERNAL_TOKEN=smoke-agent-token-0123456789abcdef
AGENTFORGE_CORE_INTERNAL_TOKEN=smoke-core-token-0123456789abcdefg
AGENTFORGE_REGISTRATION_ENABLED=false
AGENTFORGE_AI_DAILY_LIMIT=2
AGENTFORGE_AGENT_LLM_PROVIDER=disabled
AGENTFORGE_AGENT_LLM_API_KEY=
AGENTFORGE_AGENT_LLM_BASE_URL=
AGENTFORGE_AGENT_LLM_MODEL=
AGENTFORGE_AGENT_LLM_MAX_TOKENS=800
AGENTFORGE_TLS_CURRENT_DIR=$($tlsDir -replace '\\','/')
AGENTFORGE_ACME_WEBROOT_DIR=$($acmeDir -replace '\\','/')
"@
    [IO.File]::WriteAllText($envFile, $envContent, [Text.UTF8Encoding]::new($false))

    docker tag agentforge-core-api:latest "${project}-core-api:latest"
    docker tag agentforge-agent-service:latest "${project}-agent-service:latest"
    docker tag agentforge-web:latest "${project}-web:latest"
    Invoke-Compose up -d --no-build

    $healthy = $false
    foreach ($attempt in 1..60) {
        $running = @(Invoke-Compose ps --status running -q)
        if ($running.Count -eq 5) {
            $states = Invoke-Compose ps --format json | ConvertFrom-Json
            if (@($states | Where-Object { $_.Health -and $_.Health -ne "healthy" }).Count -eq 0) {
                $healthy = $true
                break
            }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) { Invoke-Compose ps; throw "Production smoke stack did not become healthy." }

    $homeStatus = & curl.exe --insecure --silent --output NUL --write-out "%{http_code}" https://127.0.0.1/
    if ($homeStatus -ne "200") { throw "Expected HTTPS home 200, received $homeStatus." }
    $registerStatus = & curl.exe --insecure --silent --output NUL --write-out "%{http_code}" `
        -H "Content-Type: application/json" `
        -d '{"email":"blocked@example.com","displayName":"Blocked","password":"correct-horse-battery"}' `
        https://127.0.0.1/api/v1/auth/register
    if ($registerStatus -ne "403") { throw "Expected disabled registration 403, received $registerStatus." }

    Write-Host "Production smoke passed: 5 healthy containers, HTTPS 200, registration disabled 403."
}
finally {
    try {
        $configuredVolume = docker compose -p $project --env-file $envFile `
            -f (Join-Path $root "infra\compose.prod.yaml") config --format json | ConvertFrom-Json
        if ($configuredVolume.volumes.'agentforge-postgres-data'.name -ne 'agentforge-v11-smoke-postgres-data') {
            throw "Refusing smoke cleanup because the PostgreSQL volume is not the isolated test volume."
        }
        Invoke-Compose down --volumes --remove-orphans
    } catch { Write-Warning $_ }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
