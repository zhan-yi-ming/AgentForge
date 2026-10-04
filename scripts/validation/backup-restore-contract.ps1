[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$backupPath = Join-Path $root "scripts/deploy/backup.sh"
$restorePath = Join-Path $root "scripts/deploy/restore-backup.sh"

function Assert-Contains {
    param([string]$Text, [string]$Pattern, [string]$Message)
    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

function Assert-NotContains {
    param([string]$Text, [string]$Pattern, [string]$Message)
    if ($Text -match $Pattern) {
        throw $Message
    }
}

if (-not (Test-Path -LiteralPath $restorePath -PathType Leaf)) {
    throw "Missing production restore entry point: scripts/deploy/restore-backup.sh"
}

$backup = Get-Content -Raw -Encoding UTF8 $backupPath
$restore = Get-Content -Raw -Encoding UTF8 $restorePath

Assert-Contains $backup 'compose stop gateway core-api agent-service' "Backup must quiesce every application write path."
Assert-Contains $backup 'pg_dump[^\r\n]+-Fc' "Backup must create a PostgreSQL custom dump."
Assert-Contains $backup 'compose stop neo4j' "Backup must stop Neo4j before a Community dump."
Assert-Contains $backup 'neo4j-admin database dump neo4j' "Backup must include the Neo4j database."
Assert-Contains $backup 'sha256sum' "Backup must publish a cryptographic integrity manifest."
Assert-Contains $backup '\.partial' "Backup must build outside the published destination."
Assert-Contains $backup 'mv[^\r\n]+PARTIAL[^\r\n]+TARGET' "Backup must atomically publish a complete directory."

Assert-Contains $restore 'sha256sum --check' "Restore must verify all backup artifacts before mutation."
Assert-Contains $restore 'compose stop gateway core-api agent-service' "Restore must quiesce every application write path."
Assert-Contains $restore '(?s)pg_restore.*--clean.*--if-exists' "Restore must replace the PostgreSQL snapshot."
Assert-NotContains $restore '--no-privileges' "Restore must preserve the dump ACLs for Core and Agent service roles."
Assert-Contains $restore 'neo4j-admin database load neo4j' "Restore must load the offline Neo4j database."
Assert-Contains $restore '--overwrite-destination=true' "Restore must explicitly replace the Neo4j database."

$bash = Get-Command bash -ErrorAction SilentlyContinue
if ($null -ne $bash) {
    $backupUnix = $backupPath.Replace('\', '/')
    $restoreUnix = $restorePath.Replace('\', '/')
    $backupBashPath = "/mnt/$($backupUnix.Substring(0,1).ToLowerInvariant())/$($backupUnix.Substring(3))"
    $restoreBashPath = "/mnt/$($restoreUnix.Substring(0,1).ToLowerInvariant())/$($restoreUnix.Substring(3))"
    & $bash.Source -n $backupBashPath
    if ($LASTEXITCODE -ne 0) { throw "backup.sh failed bash -n." }
    & $bash.Source -n $restoreBashPath
    if ($LASTEXITCODE -ne 0) { throw "restore-backup.sh failed bash -n." }
}

Write-Host "Backup/restore CLI contract: PASS"
