[CmdletBinding()]
param(
    [string]$EnvironmentFile = ".env.production.example"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$composeFile = Join-Path $root "infra/compose.prod.yaml"
$envPath = Join-Path $root $EnvironmentFile
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 10)
$project = "agentforge-grafana-smoke-$suffix"
$logSource = "$project-log-source"
$lokiVolume = "$project-loki"
$alloyVolume = "$project-alloy"
$grafanaVolume = "$project-grafana"
$adminUser = "agentforge-smoke"
$adminPassword = "grafana-smoke-password-1234"
$network = "${project}_app"

$previousLokiVolume = $env:AGENTFORGE_LOKI_VOLUME
$previousAlloyVolume = $env:AGENTFORGE_ALLOY_VOLUME
$previousGrafanaVolume = $env:AGENTFORGE_GRAFANA_VOLUME
$previousAdminUser = $env:GRAFANA_ADMIN_USER
$previousAdminPassword = $env:GRAFANA_ADMIN_PASSWORD

function Invoke-Compose {
    & docker compose -p $project --env-file $envPath -f $composeFile @args
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($args -join ' ')" }
}

try {
    $env:AGENTFORGE_LOKI_VOLUME = $lokiVolume
    $env:AGENTFORGE_ALLOY_VOLUME = $alloyVolume
    $env:AGENTFORGE_GRAFANA_VOLUME = $grafanaVolume
    $env:GRAFANA_ADMIN_USER = $adminUser
    $env:GRAFANA_ADMIN_PASSWORD = $adminPassword

    Invoke-Compose up -d loki alloy grafana
    & docker run -d --name $logSource `
        --label com.docker.compose.project=agentforge `
        --label com.docker.compose.service=observability-smoke `
        nginx:1.29-alpine sh -c "while true; do echo AGENTFORGE_OBSERVABILITY_SMOKE; sleep 1; done" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Temporary log source failed to start." }

    Start-Sleep -Seconds 12
    $serviceIds = @{}
    foreach ($service in 'loki','alloy','grafana') {
        $serviceId = (& docker compose -p $project --env-file $envPath -f $composeFile ps -q $service).Trim()
        if ([string]::IsNullOrWhiteSpace($serviceId)) { throw "$service container is missing." }
        $serviceIds[$service] = $serviceId
        $state = (& docker inspect -f '{{.State.Status}}' $serviceId).Trim()
        if ($state -ne 'running') {
            & docker logs $serviceId
            throw "$service state is $state."
        }
    }

    $alloyLogs = (& docker logs $serviceIds.alloy 2>&1 | Out-String)
    if ($alloyLogs -match 'Error:|failed to|level=error') {
        throw "Alloy reported an error:`n$alloyLogs"
    }

    & docker exec $serviceIds.grafana curl --fail --silent `
        http://localhost:3000/grafana/api/health | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Grafana health endpoint failed." }

    $unauthenticatedStatus = (& docker exec $serviceIds.grafana curl --silent `
        --output /dev/null --write-out '%{http_code}' `
        http://localhost:3000/grafana/api/search).Trim()
    if ($unauthenticatedStatus -ne '401') {
        throw "Expected unauthenticated Grafana search status 401, received $unauthenticatedStatus."
    }

    $dashboardSearch = & docker exec $serviceIds.grafana curl --fail --silent `
        --user "${adminUser}:${adminPassword}" `
        'http://localhost:3000/grafana/api/search?query=AgentForge%20Logs'
    if ($LASTEXITCODE -ne 0 -or ($dashboardSearch -join "`n") -notmatch 'agentforge-logs') {
        throw "Provisioned AgentForge Logs dashboard was not returned."
    }

    $dataSourceHealth = & docker exec $serviceIds.grafana curl --fail --silent `
        --user "${adminUser}:${adminPassword}" `
        http://localhost:3000/grafana/api/datasources/uid/agentforge-loki/health
    if ($LASTEXITCODE -ne 0 -or ($dataSourceHealth -join "`n") -notmatch '"status"\s*:\s*"OK"') {
        throw "Provisioned Loki data source is not healthy."
    }

    $serviceLabels = ""
    foreach ($attempt in 1..12) {
        $serviceLabels = (& docker exec $serviceIds.grafana curl --fail --silent `
            http://loki:3100/loki/api/v1/label/service/values 2>$null | Out-String)
        if ($LASTEXITCODE -eq 0 -and $serviceLabels -match 'observability-smoke') { break }
        Start-Sleep -Seconds 1
    }
    if ($serviceLabels -notmatch 'observability-smoke') {
        throw "Alloy did not ingest the project-scoped smoke log into Loki."
    }

    Write-Host "Grafana logs smoke passed: authentication, dashboard, Loki data source, and project-scoped log ingestion."
} finally {
    & docker rm -f $logSource 2>$null | Out-Null
    & docker compose -p $project --env-file $envPath -f $composeFile down -v --remove-orphans 2>$null | Out-Null

    $env:AGENTFORGE_LOKI_VOLUME = $previousLokiVolume
    $env:AGENTFORGE_ALLOY_VOLUME = $previousAlloyVolume
    $env:AGENTFORGE_GRAFANA_VOLUME = $previousGrafanaVolume
    $env:GRAFANA_ADMIN_USER = $previousAdminUser
    $env:GRAFANA_ADMIN_PASSWORD = $previousAdminPassword

    $remainingContainers = @(& docker ps -a --filter "label=com.docker.compose.project=$project" -q)
    $remainingVolumes = @(
        foreach ($volume in $lokiVolume,$alloyVolume,$grafanaVolume) {
            & docker volume inspect $volume 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $volume }
        }
    )
    if ($remainingContainers.Count -ne 0 -or $remainingVolumes.Count -ne 0) {
        throw "Smoke cleanup failed for project $project."
    }
}
