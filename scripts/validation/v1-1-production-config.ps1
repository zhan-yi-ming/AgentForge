[CmdletBinding()]
param(
    [string]$EnvironmentFile = ".env.production.example"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$composeFile = Join-Path $root "infra/compose.prod.yaml"
$envPath = Join-Path $root $EnvironmentFile
$json = docker compose --env-file $envPath -f $composeFile config --format json
if ($LASTEXITCODE -ne 0) { throw "Production Compose did not render." }
$config = $json | ConvertFrom-Json

$published = @()
foreach ($serviceProperty in $config.services.PSObject.Properties) {
    $portsProperty = $serviceProperty.Value.PSObject.Properties['ports']
    $servicePorts = if ($null -eq $portsProperty) { @() } else { @($portsProperty.Value) }
    foreach ($port in $servicePorts) {
        if ($null -ne $port) {
            $published += [PSCustomObject]@{
                Service = $serviceProperty.Name
                Published = [string]$port.published
                Target = [string]$port.target
            }
        }
    }
    if ($serviceProperty.Value.logging.options.'max-size' -ne '10m' -or
        $serviceProperty.Value.logging.options.'max-file' -ne '3') {
        throw "Service $($serviceProperty.Name) does not use bounded Docker logs."
    }
}

if ($published.Count -ne 2 -or
    @($published | Where-Object Service -ne 'gateway').Count -ne 0 -or
    @($published.Published | Sort-Object) -join ',' -ne '443,80') {
    throw "Only gateway ports 80 and 443 may be published: $($published | ConvertTo-Json -Compress)"
}

foreach ($internalService in 'postgres','core-api','agent-service','web') {
    if ($null -ne $config.services.$internalService.PSObject.Properties['ports']) {
        throw "$internalService must not publish host ports."
    }
}

$deployScripts = Get-ChildItem -Path (Join-Path $root "scripts/deploy") -Filter "*.sh" -File
foreach ($deployScript in $deployScripts) {
    $scriptContent = Get-Content -Raw -LiteralPath $deployScript.FullName
    if ($scriptContent -match '(?m)^\s*(?:compose|docker\s+compose)\s+build\b[^\r\n]*--no-deps\b') {
        throw "$($deployScript.Name) uses build --no-deps, which Docker Compose v5 does not support."
    }
}

Write-Host "Production Compose boundary passed: only gateway publishes 80/443; all 5 services use 10m x 3 logs; deploy scripts avoid unsupported Compose v5 build flags."
