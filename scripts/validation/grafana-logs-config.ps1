[CmdletBinding()]
param(
    [string]$EnvironmentFile = ".env.production.example"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$composeFile = Join-Path $root "infra/compose.prod.yaml"
$envPath = Join-Path $root $EnvironmentFile
$nginxTemplate = Join-Path $root "infra/nginx/production.conf.template"
$alloyConfig = Join-Path $root "infra/observability/alloy/config.alloy"
$lokiConfig = Join-Path $root "infra/observability/loki/config.yaml"
$dataSourceConfig = Join-Path $root "infra/observability/grafana/provisioning/datasources/loki.yaml"
$dashboardProvider = Join-Path $root "infra/observability/grafana/provisioning/dashboards/provider.yaml"
$dashboardFile = Join-Path $root "infra/observability/grafana/dashboards/agentforge-logs.json"

$json = docker compose --env-file $envPath -f $composeFile config --format json
if ($LASTEXITCODE -ne 0) { throw "Production Compose did not render." }
$config = ($json -join "`n") | ConvertFrom-Json

$expectedServices = @('agent-service','alloy','core-api','gateway','grafana','loki','postgres','web')
$actualServices = @($config.services.PSObject.Properties.Name | Sort-Object)
if (($actualServices -join ',') -ne ($expectedServices -join ',')) {
    throw "Expected the application plus Grafana log stack services: $($actualServices -join ',')."
}

$publishedServices = @(
    foreach ($serviceProperty in $config.services.PSObject.Properties) {
        if ($null -ne $serviceProperty.Value.PSObject.Properties['ports']) {
            foreach ($port in @($serviceProperty.Value.ports)) {
                if ($null -ne $port) { $serviceProperty.Name }
            }
        }
    }
)
if (@($publishedServices | Where-Object { $_ -ne 'gateway' }).Count -ne 0) {
    throw "Only gateway may publish host ports: $($publishedServices -join ',')."
}

$grafana = $config.services.grafana
if ($grafana.environment.GF_AUTH_ANONYMOUS_ENABLED -ne 'false' -or
    $grafana.environment.GF_USERS_ALLOW_SIGN_UP -ne 'false' -or
    $grafana.environment.GF_SERVER_SERVE_FROM_SUB_PATH -ne 'true' -or
    $grafana.environment.GF_SERVER_ROOT_URL -notmatch '^https://.+/grafana/$' -or
    [string]::IsNullOrWhiteSpace($grafana.environment.GF_SECURITY_ADMIN_PASSWORD)) {
    throw "Grafana authentication or /grafana/ sub-path configuration is incomplete."
}

$alloyMounts = @($config.services.alloy.volumes)
$dockerSocket = @($alloyMounts | Where-Object { $_.target -eq '/var/run/docker.sock' })
if ($dockerSocket.Count -ne 1 -or $dockerSocket[0].read_only -ne $true -or
    $config.services.alloy.read_only -ne $true -or
    @($config.services.alloy.cap_add).Count -ne 1 -or
    $config.services.alloy.cap_add -notcontains 'DAC_OVERRIDE' -or
    $config.services.alloy.security_opt -notcontains 'no-new-privileges:true') {
    throw "Alloy must use the read-only Docker socket and only the DAC_OVERRIDE capability needed for its data volume."
}

foreach ($applicationService in 'postgres','core-api','agent-service','web','gateway') {
    $dependsOnProperty = $config.services.$applicationService.PSObject.Properties['depends_on']
    $dependencies = if ($null -eq $dependsOnProperty) {
        @()
    } else {
        @($dependsOnProperty.Value.PSObject.Properties.Name)
    }
    if (@($dependencies | Where-Object { $_ -in @('grafana','loki','alloy') }).Count -ne 0) {
        throw "$applicationService must not depend on the observability stack."
    }
}

$nginx = Get-Content -Raw -LiteralPath $nginxTemplate
if ($nginx -notmatch 'location\s+\^~\s+/grafana/' -or
    $nginx -notmatch 'proxy_pass\s+http://grafana:3000') {
    throw "Nginx does not expose the Grafana sub-path."
}

$alloy = Get-Content -Raw -LiteralPath $alloyConfig
if ($alloy -notmatch 'com_docker_compose_project' -or
    $alloy -notmatch 'regex\s*=\s*"agentforge"' -or
    $alloy -notmatch 'target_label\s*=\s*"service"' -or
    $alloy -notmatch 'http://loki:3100/loki/api/v1/push') {
    throw "Alloy must filter the agentforge Compose project, label services, and write to Loki."
}

$loki = Get-Content -Raw -LiteralPath $lokiConfig
if ($loki -notmatch '(?m)^\s*retention_enabled:\s*true\s*$' -or
    $loki -notmatch '(?m)^\s*retention_period:\s*168h\s*$' -or
    $loki -notmatch '(?m)^\s*delete_request_store:\s*filesystem\s*$') {
    throw "Loki must enable filesystem-backed seven-day retention."
}

$dataSource = Get-Content -Raw -LiteralPath $dataSourceConfig
if ($dataSource -notmatch 'url:\s*http://loki:3100' -or $dataSource -notmatch 'isDefault:\s*true') {
    throw "Grafana must provision Loki as its default data source."
}
$provider = Get-Content -Raw -LiteralPath $dashboardProvider
if ($provider -notmatch '/var/lib/grafana/dashboards') {
    throw "Grafana dashboard provider path is missing."
}
$dashboard = Get-Content -Raw -LiteralPath $dashboardFile | ConvertFrom-Json
if ($dashboard.title -ne 'AgentForge Logs' -or
    @($dashboard.templating.list | Where-Object name -eq 'service').Count -ne 1 -or
    @($dashboard.templating.list | Where-Object name -eq 'search').Count -ne 1 -or
    @($dashboard.panels).Count -lt 1) {
    throw "The AgentForge Logs dashboard must expose service and search controls."
}

Write-Host "Grafana logs config passed: authenticated sub-path, internal-only services, project-scoped collection, seven-day retention, and provisioned dashboard."
