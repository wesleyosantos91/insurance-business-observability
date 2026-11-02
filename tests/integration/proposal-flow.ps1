[CmdletBinding()]
param(
    [switch]$Build,
    [int]$ProposalPort = 18081,
    [int]$CadastroPort = 18082,
    [string]$EvidenceDirectory = (Join-Path $env:TEMP ("insurance-proposal-flow-" + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$compose = Join-Path $repo 'infrastructure/kafka/compose.yaml'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$producer = "insurance-propostas-test-$runId"
$consumer = "insurance-cadastro-test-$runId"
$started = [Collections.Generic.List[string]]::new()
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null

function Invoke-Docker {
    param([string[]]$Arguments)
    $result = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker failed ($LASTEXITCODE): $($Arguments -join ' ')" }
    return $result
}
function Read-JsonResponse($Response) {
    $body = $Response.Content
    if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
    return $body | ConvertFrom-Json
}
function Wait-Application([int]$Port) {
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        try {
            $health = Read-JsonResponse (Invoke-WebRequest "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5)
            if ($health.status -eq 'UP') { return }
        } catch { $lastHealthError = $_.Exception.Message }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Application on port $Port did not become healthy. Last error: $lastHealthError"
}

Push-Location $repo
try {
    if ($Build) {
        foreach ($module in @('ms-propostas','ms-cadastro')) {
            Invoke-Docker -Arguments @('build', '-f', "services/$module/Dockerfile", '-t', ("insurance/{0}:idempotency-aot" -f $module), '.') | Out-Host
        }
    }
    Invoke-Docker -Arguments @('compose', '-f', (Join-Path $repo 'infrastructure/persistence/compose.yaml'), 'up', '-d', '--wait') | Out-Host
    Invoke-Docker -Arguments @('compose', '-f', $compose, 'up', '-d', '--wait', 'kafka', 'schema-registry', 'kafka-ui') | Out-Host
    $subject = [Uri]::EscapeDataString('insurance.proposals.events.v1-io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated')
    $versionsBefore = @(Read-JsonResponse (Invoke-WebRequest "http://127.0.0.1:8087/subjects/$subject/versions"))
    foreach ($entry in @(
        @{ Name = $consumer; Image = 'insurance/ms-cadastro:idempotency-aot'; Port = $CadastroPort; InternalPort = 8082 },
        @{ Name = $producer; Image = 'insurance/ms-propostas:idempotency-aot'; Port = $ProposalPort; InternalPort = 8081 }
    )) {
        $databaseHost = if ($entry.InternalPort -eq 8081) { "propostas-db:5432/propostas" } else { "cadastro-db:5432/cadastro" }
        Invoke-Docker -Arguments @('create', '--name', $entry.Name, '--network', 'insurance-kafka-local',
            '-p', ("127.0.0.1:{0}:{1}" -f $entry.Port, $entry.InternalPort),
            '-e', 'KAFKA_BOOTSTRAP_SERVERS=kafka:29092',
            '-e', 'SCHEMA_REGISTRY_URL=http://schema-registry:8081',
            '-e', 'JAVA_TOOL_OPTIONS=-Xlog:aot=info',
            '-e', ("DATABASE_URL=jdbc:postgresql://" + $databaseHost), $entry.Image) | Out-Host
        $started.Add($entry.Name)
        Invoke-Docker -Arguments @('network', 'connect', 'insurance-data-local', $entry.Name) | Out-Host
        Invoke-Docker -Arguments @('start', $entry.Name) | Out-Host
        Wait-Application $entry.Port
    }

    $invalidParams = @{ Uri = "http://127.0.0.1:$ProposalPort/proposals"; Method = 'Post';
        ContentType = 'application/json'; Body = '{"productKey":" "}'; SkipHttpErrorCheck = $true }
    $invalid = Invoke-WebRequest @invalidParams
    if ($invalid.StatusCode -ne 400 -or $invalid.Headers.'Content-Type' -notlike '*application/problem+json*') {
        throw 'Invalid request did not return HTTP 400 ProblemDetail'
    }
    $httpParams = @{ Uri = "http://127.0.0.1:$ProposalPort/proposals"; Method = 'Post';
        ContentType = 'application/json'; Body = '{"productKey":"AUTO"}'; Headers = @{ 'Idempotency-Key' = $runId }; TimeoutSec = 15 }
    $http = Invoke-WebRequest @httpParams
    if ($http.StatusCode -ne 202) { throw "Expected HTTP 202, got $($http.StatusCode)" }
    $response = Read-JsonResponse $http
    if ($response.status -ne 'ACCEPTED') { throw 'Unexpected proposal status' }
    $response | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'http-response.json') -Encoding utf8

    $testMount = (Join-Path $repo 'tests/integration') + ':/tests:ro'
    $inspection = Invoke-Docker -Arguments @('compose', '-f', $compose, '--profile', 'tools', 'run',
        '--rm', '--no-deps', '--entrypoint', 'python', '-v', $testMount, 'tools',
        '/tests/verify-proposal.py', '--proposal-id', $response.proposalId,
        '--journey-id', $response.journeyId, '--correlation-id', $response.correlationId)
    $inspection | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'avro-inspection.json') -Encoding utf8
    $wire = $inspection | Where-Object { $_ -like '{"result":*' } | Select-Object -Last 1 | ConvertFrom-Json
    if ($null -eq $wire -or $wire.result -ne 'AVRO_CONFIRMED') { throw 'Avro verification failed' }

    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $logs = Invoke-Docker -Arguments @('logs', $consumer)
        $processed = @($logs | Where-Object { $_ -like '{*' } | ForEach-Object {
            try { $_ | ConvertFrom-Json -ErrorAction Stop } catch { $lastHealthError = $_.Exception.Message }
        } | Where-Object { $_.proposalId -eq $response.proposalId -and $_.message -eq 'ProposalCreated processed and acknowledged' })
        if ($processed.Count -gt 0) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($processed.Count -eq 0) { throw 'ms-cadastro did not acknowledge the proposal' }
    $received = $processed[-1]
    foreach ($field in @('eventId','journeyId','rootJourneyId','correlationId','topic','partition','offset')) {
        if ([string]$received.$field -ne [string]$wire.$field) { throw "Consumer mismatch: $field" }
    }
    $offsetRows = Invoke-Docker -Arguments @('compose', '-f', $compose, 'exec', '-T', 'kafka',
        'kafka-consumer-groups', '--bootstrap-server', 'kafka:29092',
        '--group', 'insurance-cadastro-v1', '--describe')
    $offsetRows | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'consumer-group-offsets.txt') -Encoding utf8
    $committed = @($offsetRows | ForEach-Object {
        if ($_ -match '^\s*insurance-cadastro-v1\s+insurance\.proposals\.events\.v1\s+(\d+)\s+(\d+)\s+') {
            if ([int]$Matches[1] -eq [int]$wire.partition) { [long]$Matches[2] }
        }
    })
    if ($committed.Count -ne 1 -or $committed[0] -le [long]$wire.offset) {
        throw 'The consumer group did not commit the processed offset'
    }
    $versionsAfter = @(Read-JsonResponse (Invoke-WebRequest "http://127.0.0.1:8087/subjects/$subject/versions"))
    if (($versionsBefore -join ',') -ne ($versionsAfter -join ',')) { throw 'Runtime registered a new schema version' }
    $received | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 'consumer-confirmation.json') -Encoding utf8
    Write-Host "PASS: HTTP 202, binary Avro, key=proposalId, correlation, consumer acknowledgment and committed offset; schema ID=$($wire.schemaId), version=$($wire.registryVersion)."
    Write-Host "Evidence: $EvidenceDirectory"
} finally {
    foreach ($name in $started) {
        & docker logs $name 2>&1 | Set-Content -LiteralPath (Join-Path $EvidenceDirectory "$name.log") -Encoding utf8
        & docker rm -f $name | Out-Null
    }
    Pop-Location
}
