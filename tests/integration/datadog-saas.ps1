[CmdletBinding()]
param(
    [int]$ProposalPort = 18081,
    [int]$CadastroPort = 8082,
    [switch]$Redeliver,
    [string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$EvidenceDirectory) { $EvidenceDirectory = Join-Path $PSScriptRoot 'target/stage10-saas' }
New-Item -ItemType Directory -Force -Path $EvidenceDirectory | Out-Null
function Invoke-Docker([string[]]$Arguments) {
    $result = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return $result
}
function Read-Database([string]$Service, [string]$Database, [string]$Sql) {
    $rows = Invoke-Docker @('compose','-f','infrastructure/persistence/compose.yaml','exec','-T',
        $Service,'psql','-U','insurance','-d',$Database,'-t','-A','-c',$Sql)
    return ($rows -join '').Trim()
}
Push-Location $repo
try {
    foreach ($port in @($ProposalPort,$CadastroPort)) {
        $health = Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 5
        if ($health.status -ne 'UP') { throw "Service on port $port is not UP" }
    }
    $startedAt = [DateTime]::UtcNow.ToString('o')
    $key = 'stage10-' + [guid]::NewGuid().ToString()
    $trace = [guid]::NewGuid().ToString('N')
    $parent = [guid]::NewGuid().ToString('N').Substring(0,16)
    $parameters = @{ Uri = "http://127.0.0.1:$ProposalPort/proposals"; Method = 'Post';
        ContentType = 'application/json'; Body = '{"productKey":"AUTO"}'; TimeoutSec = 15;
        Headers = @{ 'Idempotency-Key' = $key; traceparent = "00-$trace-$parent-01" } }
    $http = Invoke-WebRequest @parameters
    if ($http.StatusCode -ne 202) { throw "Expected 202, received $($http.StatusCode)" }
    $proposal = $http.Content | ConvertFrom-Json
    foreach ($field in @('proposalId','journeyId','correlationId')) {
        $null = [guid]::Parse($proposal.$field)
    }
    $replay = Invoke-WebRequest @parameters
    if ($replay.StatusCode -ne 202 -or ($replay.Content | ConvertFrom-Json).proposalId -ne $proposal.proposalId) {
        throw 'Idempotent replay changed the proposal'
    }
    $mount = (Join-Path $repo 'tests/integration') + ':/tests:ro'
    $arguments = @('compose','-f','infrastructure/kafka/compose.yaml','--profile','tools','run',
        '--rm','--no-deps','--entrypoint','python','-v',$mount,'tools','/tests/verify-proposal.py',
        '--proposal-id',$proposal.proposalId,'--journey-id',$proposal.journeyId,
        '--correlation-id',$proposal.correlationId,'--trace-id',$trace)
    if ($Redeliver) { $arguments += '--redeliver' }
    $inspection = Invoke-Docker $arguments
    $wire = $inspection | Where-Object { $_ -like '{"result":*' } | Select-Object -Last 1 | ConvertFrom-Json
    if (!$wire -or $wire.result -ne 'AVRO_CONFIRMED') { throw 'Binary Avro inspection failed' }
    $id = $proposal.proposalId
    $eventId = $wire.eventId
    $null = [guid]::Parse($eventId)
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $published = Read-Database 'propostas-db' 'propostas' "SELECT count(*) FROM proposal_outbox WHERE proposal_id='$id' AND published_at IS NOT NULL AND claim_token IS NULL;"
        $inbox = Read-Database 'cadastro-db' 'cadastro' "SELECT count(*) FROM proposal_inbox i JOIN received_proposals p USING(event_id) WHERE i.event_id='$eventId' AND p.proposal_id='$id';"
        $logWindow = ([DateTime]::Parse($startedAt)).AddSeconds(-5).ToString('o')
        $logs = Invoke-Docker @('logs','--since',$logWindow,'insurance-observability-ms-cadastro')
        $matched = @($logs | Where-Object { $_ -like '{*' } | ForEach-Object {
            try { $_ | ConvertFrom-Json } catch { }
        } | Where-Object { $_.business_id -eq $id })
        $duplicate = @($matched | Where-Object { $_.outcome -eq 'duplicate' }).Count -gt 0
        if ($published -eq '1' -and $inbox -eq '1' -and (!$Redeliver -or $duplicate)) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($published -ne '1' -or $inbox -ne '1') { throw 'Persistent Outbox/Inbox confirmation missing' }
    if ($Redeliver -and !$duplicate) { throw 'Duplicate delivery was not observed' }
    $evidence = [ordered]@{ startedAt = $startedAt; completedAt = [DateTime]::UtcNow.ToString('o');
        httpStatus = $http.StatusCode; replayStatus = $replay.StatusCode; proposal = $proposal;
        traceId = $trace; outboxPublished = [int]$published; inboxRecords = [int]$inbox;
        duplicateObserved = $duplicate; kafka = $wire;
        logsUrl = "https://us5.datadoghq.com/logs?query=env%3Alocal%20%40business_id%3A$id";
        apmUrl = "https://us5.datadoghq.com/apm/traces?query=env%3Alocal%20%40businessId%3A$id" }
    $evidence | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $EvidenceDirectory 'journey.json') -Encoding utf8NoBOM
    $matched | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $EvidenceDirectory 'consumer-logs.json') -Encoding utf8NoBOM
    $evidence | ConvertTo-Json -Depth 12
} finally { Pop-Location }
