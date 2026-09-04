function ConvertTo-ReviewOutcome {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory = $true)][hashtable]$Stage,
        [Parameter(Mandatory = $true)][ValidateSet("PASS", "NEEDS_FIX")][string]$Result,
        [Parameter(Mandatory = $true)][int]$Attempt,
        [Parameter(Mandatory = $true)][int]$MaximumAttempts,
        [Parameter(Mandatory = $true)][string]$TargetCommit,
        [Parameter(Mandatory = $true)][string]$ReportPath
    )

    $status = "WAITING_FOR_CODEX_FIX"
    $nextStageReady = $null
    $humanIntervention = $false
    if ($Result -eq "PASS") {
        $status = "RESOLVED"
        $nextStageReady = [string]$Stage.id
    } elseif ($Attempt -ge $MaximumAttempts) {
        $status = "HUMAN_REQUIRED"
        $humanIntervention = $true
    }

    [PSCustomObject]@{
        StageId = [string]$Stage.id
        Status = $status
        Attempts = $Attempt
        LastReviewedCommit = $TargetCommit
        LastReportPath = $ReportPath
        LastFailure = $null
        NextStageReady = $nextStageReady
        HumanIntervention = $humanIntervention
    }
}
