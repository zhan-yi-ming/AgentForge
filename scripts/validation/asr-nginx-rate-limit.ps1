[CmdletBinding()]
param([int]$DurationSeconds = 60)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if ($DurationSeconds -lt 60) { throw "ASR gateway smoke must run for at least 60 seconds." }

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$templatePath = Join-Path $root "infra/nginx/production.conf.template"
$template = Get-Content -Raw -Encoding UTF8 $templatePath
$zones = [regex]::Matches($template, '(?m)^limit_req_zone[^;]+;$').Value -join "`n"
$asr = [regex]::Match($template, '(?ms)^\s{4}location ~ \^/api/v1/projects/\[\^/\]\+/agent/asr/sessions.*?^\s{4}\}').Value
$api = [regex]::Match($template, '(?ms)^\s{4}location /api/ \{.*?^\s{4}\}').Value
if ([string]::IsNullOrWhiteSpace($asr) -or [string]::IsNullOrWhiteSpace($api) -or
    $zones -notmatch 'asr_per_ip') {
    throw "Production Nginx template does not contain the expected ASR and generic API limits."
}
$asr = $asr.Replace('proxy_pass http://core-api:8080;', 'proxy_pass http://127.0.0.1:8081;')
$api = $api.Replace('proxy_pass http://core-api:8080;', 'proxy_pass http://127.0.0.1:8081;')

$tempRoot = Join-Path $root ".validation-tmp"
$temp = Join-Path $tempRoot ("asr-nginx-" + [Guid]::NewGuid().ToString("N"))
$resolvedRoot = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
$resolvedTemp = [IO.Path]::GetFullPath($temp)
if (-not $resolvedTemp.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary path escaped the repository: $resolvedTemp"
}
$container = "agentforge-r11-nginx-" + [Guid]::NewGuid().ToString("N").Substring(0, 10)
$created = $false
try {
    New-Item -ItemType Directory -Force $temp | Out-Null
    $config = @"
events {}
http {
$zones
    access_log /var/log/nginx/access.log combined;
    server { listen 8081; location / { return 204; } }
    server {
        listen 8080;
$asr
$api
    }
}
"@
    [IO.File]::WriteAllText((Join-Path $temp "nginx.conf"), $config, [Text.UTF8Encoding]::new($false))
    & docker run --detach --name $container --mount "type=bind,source=$(Join-Path $temp 'nginx.conf'),target=/etc/nginx/nginx.conf,readonly" nginx:1.29-alpine
    if ($LASTEXITCODE -ne 0) { throw "Failed to start temporary Nginx." }
    $created = $true
    & docker exec $container nginx -t
    if ($LASTEXITCODE -ne 0) { throw "Rendered ASR Nginx configuration is invalid." }

    $loop = @"
set -eu
end=`$((`$(date +%s) + $DurationSeconds))
while [ `$(date +%s) -lt `$end ]; do
  wget -q -O /dev/null --post-data=00 http://127.0.0.1:8080/api/v1/projects/p/agent/asr/sessions/u1/audio
  wget -q -O /dev/null http://127.0.0.1:8080/api/v1/projects/p/agent/asr/sessions/u1
  wget -q -O /dev/null --post-data=00 http://127.0.0.1:8080/api/v1/projects/p/agent/asr/sessions/u2/audio
  wget -q -O /dev/null http://127.0.0.1:8080/api/v1/projects/p/agent/asr/sessions/u2
  wget -q -O /dev/null http://127.0.0.1:8080/api/v1/projects/p/wiki/pages
  sleep 1.5
done
rejected=0
i=0
while [ `$i -lt 40 ]; do
  if ! wget -q -O /dev/null http://127.0.0.1:8080/api/v1/projects/p/wiki/pages 2>/dev/null; then rejected=1; fi
  i=`$((i + 1))
done
test `$rejected -eq 1
    wget -q -O /dev/null http://127.0.0.1:8080/api/v1/projects/p/agent/asr/sessions/u1
"@
    $loop = $loop.Replace("`r`n", "`n")
    & docker exec $container sh -c $loop
    if ($LASTEXITCODE -ne 0) { throw "ASR Nginx sustained-rate smoke failed." }
    Write-Host "ASR Nginx rate-limit smoke passed: two same-IP sessions plus ordinary API sustained for $DurationSeconds seconds; generic API burst was rejected; ASR remained available."
} finally {
    if ($created) {
        & docker rm --force $container *> $null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Failed to remove temporary container $container" }
    }
    if (Test-Path -LiteralPath $temp) {
        $verified = [IO.Path]::GetFullPath($temp)
        if ($verified.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $verified).StartsWith('asr-nginx-')) {
            Remove-Item -LiteralPath $verified -Recurse -Force
        } else {
            Write-Warning "Refusing to remove unverified temporary path $verified"
        }
    }
}
