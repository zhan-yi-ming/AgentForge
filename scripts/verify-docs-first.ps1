param(
    [Parameter(Mandatory = $true)]
    [string]$BaseRef
)

$ErrorActionPreference = 'Stop'

$changedFiles = @(git diff --name-only "$BaseRef...HEAD")
if ($LASTEXITCODE -ne 0) {
    throw "Unable to compare HEAD with $BaseRef."
}

$implementationChanges = @(
    $changedFiles | Where-Object {
        $_ -notmatch '^(docs/|AGENTS\.md$|README\.md$)'
    }
)

$changeRecordChanges = @(
    $changedFiles | Where-Object { $_ -match '^docs/07-changes/.+\.md$' }
)

if ($implementationChanges.Count -gt 0 -and $changeRecordChanges.Count -eq 0) {
    Write-Error "Implementation changed without a docs/07-changes record. Update documentation before implementation."
    $implementationChanges | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host "Documentation-first check passed. Changed files: $($changedFiles.Count)."
