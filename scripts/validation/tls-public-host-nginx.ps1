[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$composeFile = Join-Path $root "infra\compose.prod.yaml"
$environmentFile = Join-Path $root ".env.production.example"
$template = Join-Path $root "infra\nginx\production.conf.template"
$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$testRoot = Join-Path $temporaryRoot ("agentforge-tls-nginx-" + [guid]::NewGuid().ToString("N"))
$tlsDirectory = Join-Path $testRoot "tls"

$previousPublicHost = $env:PUBLIC_HOST
$previousWwwHost = $env:PUBLIC_WWW_HOST

function Test-SingleServerNameScenario {
    param(
        [string]$PublicHost,
        [string]$ForbiddenText,
        [string]$TlsMount
    )

    $env:PUBLIC_HOST = $PublicHost
    $env:PUBLIC_WWW_HOST = ""
    $json = & docker compose --env-file $environmentFile -f $composeFile config --format json
    if ($LASTEXITCODE -ne 0) { throw "Production Compose did not render for $PublicHost." }
    $config = ($json -join "`n") | ConvertFrom-Json
    if ($config.services.gateway.environment.PUBLIC_HOST -ne $PublicHost -or
        $config.services.gateway.environment.PUBLIC_WWW_HOST -ne "") {
        throw "Gateway did not receive the single-name environment for $PublicHost."
    }

    $templateMount = "type=bind,source=$template,target=/etc/nginx/templates/default.conf.template,readonly"
    $output = (& docker run --rm `
        --env "PUBLIC_HOST=$PublicHost" `
        --env "PUBLIC_WWW_HOST=" `
        --add-host "core-api:127.0.0.1" `
        --add-host "web:127.0.0.1" `
        --mount $templateMount `
        --mount $TlsMount `
        nginx:1.29-alpine nginx -T 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Nginx rejected the rendered configuration for $PublicHost.`n$output" }
    $pattern = "server_name\s+" + [regex]::Escape($PublicHost) + "\s*;"
    if ([regex]::Matches($output, $pattern).Count -ne 2) {
        throw "Expected both Nginx server blocks to contain only $PublicHost."
    }
    if ($output.Contains($ForbiddenText)) { throw "Rendered Nginx configuration contained $ForbiddenText." }
}

try {
    New-Item -ItemType Directory -Path $tlsDirectory -Force | Out-Null
    & "C:\Program Files\Git\usr\bin\openssl.exe" req -x509 -newkey rsa:2048 -nodes -days 1 `
        -keyout (Join-Path $tlsDirectory "privkey.pem") `
        -out (Join-Path $tlsDirectory "fullchain.pem") `
        -subj "/CN=example.com" `
        -addext "subjectAltName=DNS:example.com,DNS:www.example.com" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Failed to create the temporary Nginx test certificate." }

    $env:PUBLIC_HOST = "example.com"
    $env:PUBLIC_WWW_HOST = "www.example.com"

    $json = & docker compose --env-file $environmentFile -f $composeFile config --format json
    if ($LASTEXITCODE -ne 0) { throw "Production Compose did not render for the root-domain scenario." }
    $config = ($json -join "`n") | ConvertFrom-Json
    $gatewayEnvironment = $config.services.gateway.environment
    if ($gatewayEnvironment.PUBLIC_HOST -ne "example.com" -or
        $gatewayEnvironment.PUBLIC_WWW_HOST -ne "www.example.com") {
        throw "Gateway did not receive the root-domain server names."
    }

    $mount = "type=bind,source=$template,target=/etc/nginx/templates/default.conf.template,readonly"
    $tlsMount = "type=bind,source=$tlsDirectory,target=/etc/agentforge-tls,readonly"
    $nginxOutput = (& docker run --rm `
        --env "PUBLIC_HOST=example.com" `
        --env "PUBLIC_WWW_HOST=www.example.com" `
        --add-host "core-api:127.0.0.1" `
        --add-host "web:127.0.0.1" `
        --mount $mount `
        --mount $tlsMount `
        nginx:1.29-alpine nginx -T 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Nginx rejected the rendered root-domain configuration.`n$nginxOutput" }

    $expected = "server_name example.com www.example.com;"
    if ([regex]::Matches($nginxOutput, [regex]::Escape($expected)).Count -ne 2) {
        throw "Expected both Nginx server blocks to contain '$expected'."
    }

    Test-SingleServerNameScenario -PublicHost "47.76.95.86" -ForbiddenText "www.47.76.95.86" -TlsMount $tlsMount
    Test-SingleServerNameScenario -PublicHost "www.example.com" -ForbiddenText "www.www.example.com" -TlsMount $tlsMount
} finally {
    $env:PUBLIC_HOST = $previousPublicHost
    $env:PUBLIC_WWW_HOST = $previousWwwHost
    $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
    if (
        $resolvedTestRoot.StartsWith($temporaryRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedTestRoot) -like 'agentforge-tls-nginx-*' -and
        (Test-Path -LiteralPath $resolvedTestRoot)
    ) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}

Write-Host "TLS Nginx contract passed: IPv4, root-domain, and existing-www server names render and validate."
