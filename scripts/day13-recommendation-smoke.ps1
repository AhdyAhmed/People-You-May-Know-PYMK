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
    $response = Invoke-RestMethod -Method Get -Uri $uri
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

    Write-Host "Member ${id}: $($recommendations.Count) recommendations (visible invariants passed)"
    $recommendations |
        Select-Object candidateId, score, mutualConnectionCount, @{Name = "reasons"; Expression = { $_.reasons -join "; " }} |
        Format-Table -AutoSize
}

Write-Host "Day 13 smoke check passed for $($MemberId.Count) member(s)."
