param(
    [string]$BaseUrl = "http://localhost:8080",
    [Parameter(Mandatory = $true)]
    [long[]]$MemberId,
    [ValidateRange(1, 100)]
    [int]$Limit = 20
)

$ErrorActionPreference = "Stop"

foreach ($id in $MemberId) {
    if ($id -le 0) {
        throw "Member IDs must be positive; received $id."
    }

    $uri = "$($BaseUrl.TrimEnd('/'))/api/v1/pymk/$id`?limit=$Limit"
    $wire = Invoke-WebRequest -Method Get -Uri $uri
    $mode = [string]$wire.Headers['X-PYMK-Scorer']
    $response = $wire.Content | ConvertFrom-Json
    $recommendations = @($response.recommendations)
    $candidateIds = @($recommendations | ForEach-Object { [long]$_.candidateId })

    if ([long]$response.memberId -ne $id) {
        throw "Response memberId $($response.memberId) does not match requested member $id."
    }
    if ($recommendations.Count -gt $Limit) {
        throw "Member $id returned $($recommendations.Count) results for limit $Limit."
    }
    if ($candidateIds -contains $id) {
        throw "Member $id was recommended to itself."
    }
    if (@($candidateIds | Select-Object -Unique).Count -ne $candidateIds.Count) {
        throw "Member $id received duplicate candidates."
    }

    $previousScore = [double]::PositiveInfinity
    $previousId = 0L
    foreach ($recommendation in $recommendations) {
        $score = [double]$recommendation.score
        if ($null -eq $recommendation.score -or [double]::IsNaN($score) -or
            [double]::IsInfinity($score) -or $score -lt 0 -or $score -gt 1 -or
            $score -gt $previousScore) {
            throw "Member $id received invalid or out-of-order $mode scores."
        }
        if ($mode -in @('L2','RERANK') -and $score -eq $previousScore -and [long]$recommendation.candidateId -lt $previousId) {
            throw "Member $id received unstable candidate-ID ties."
        }
        $previousScore = $score
        $previousId = [long]$recommendation.candidateId
    }

    # Day 29 selects a score-ordered subsequence, so descending score remains valid.
    if ($mode -eq 'RERANK') {
        $exposure = $response.exposure
        if ($null -eq $exposure -or $exposure.requestedLimit -ne $Limit -or
            $exposure.returnedSize -ne $recommendations.Count -or $exposure.knownGroupCap -lt 1) {
            throw 'Missing or inconsistent exposure report'
        }
        $known = 0
        $maximum = 0
        foreach ($group in $exposure.knownGroupCounts.PSObject.Properties) {
            $count = [int]$group.Value
            if ($count -lt 1 -or $count -gt $exposure.knownGroupCap) { throw 'Employer cap violated' }
            $known += $count
            $maximum = [Math]::Max($maximum,$count)
        }
        if ($known + $exposure.unknownGroupCount -ne $recommendations.Count) { throw 'Exposure counts do not sum to returned size' }
        $fraction = if ($recommendations.Count -eq 0) { 0 } else { $maximum / $recommendations.Count }
        if ([Math]::Abs($fraction - $exposure.maxKnownGroupFraction) -gt 1e-10) { throw 'Incorrect realized exposure fraction' }
    }

    Write-Host "Member ${id}: $($recommendations.Count) recommendations (visible invariants passed)"
    $recommendations |
        Select-Object candidateId, score, mutualConnectionCount, @{Name = "reasons"; Expression = { $_.reasons -join "; " }} |
        Format-Table -AutoSize
}

Write-Host "Recommendation smoke check (Day 29 mode-aware contract) passed for $($MemberId.Count) member(s)."
