[CmdletBinding()]
param(
    [switch]$Build,
    [string]$EvidenceDirectory = (Join-Path $PSScriptRoot ('target/datadog/' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "insurance-dd-test-$runId"
$receiver = "$network-receiver"
$containers = [Collections.Generic.List[string]]::new()
$services = @('ms-analise','ms-contratos','ms-endossos','ms-pagamentos')
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null

function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($Arguments -join ' ')" }
    return $output
}
function Read-Http([string]$Uri) {
    $response = Invoke-WebRequest $Uri -TimeoutSec 5
    $body = $response.Content
    if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
    return $body | ConvertFrom-Json
}
function Get-Port([string]$Container, [int]$InternalPort) {
    $mapping = (Invoke-Docker @('port', $Container, "$InternalPort/tcp") | Select-Object -First 1)
    return [int]($mapping -split ':')[-1]
}

Push-Location $repo
try {
    if ($Build) {
        foreach ($service in $services) {
            $context = if ($service -in @('ms-propostas','ms-cadastro')) { '.' } else { "services/$service" }
            Invoke-Docker @('build','-f',"services/$service/Dockerfile",'-t',("insurance/{0}:datadog-aot" -f $service),$context) | Out-Host
        }
    }
    Invoke-Docker @('network','create',$network) | Out-Host
    $mount = (Join-Path $repo 'tests/integration') + ':/tests:ro'
    Invoke-Docker @('create','--name',$receiver,'--network',$network,'--network-alias','datadog-agent',
        '-p','127.0.0.1::8126','--mount',("type=bind,source={0},target=/tests,readonly" -f (Join-Path $repo 'tests/integration')),
        'python:3.13.9-slim-bookworm','python','/tests/datadog-receiver.py') | Out-Host
    $containers.Add($receiver)
    Invoke-Docker @('start',$receiver) | Out-Host
    $receiverPort = Get-Port $receiver 8126
    foreach ($index in 0..3) {
        $service = $services[$index]
        $port = 8083 + $index
        $name = "$network-$service"
        $arguments = @('create','--name',$name,'--network',$network,'--memory','512m',
            '-p',("127.0.0.1::{0}" -f $port),'-e','DD_ENV=validation','-e','DD_VERSION=validation',
            '-e','DD_AGENT_HOST=datadog-agent','-e','JAVA_TOOL_OPTIONS=-Xmx256m -Xlog:aot=info')
        $arguments += ("insurance/{0}:datadog-aot" -f $service)
        Invoke-Docker $arguments | Out-Host
        $containers.Add($name)
        Invoke-Docker @('start',$name) | Out-Host
        $mappedPort = Get-Port $name $port
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        $healthy = $false
        do {
            try {
                $health = Read-Http "http://127.0.0.1:$mappedPort/actuator/health"
                if ($health.status -eq 'UP') { $healthy = $true; break }
            } catch { $lastHealthError = $_.Exception.Message }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $healthy) { throw "$service did not become healthy: $lastHealthError" }
        1..5 | ForEach-Object { Read-Http "http://127.0.0.1:$mappedPort/actuator/info" | Out-Null }
        Write-Host "$service healthy; HTTP requests generated"
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $result = Read-Http "http://127.0.0.1:$receiverPort/results"
        $missing = @($services | Where-Object {
            $received = $result.services.$_
            $names = @($received.metrics.PSObject.Properties.Name)
            ('jvm.heap_memory' -notin $names) -or ('jvm.memory.used' -notin $names) -or (-not ($names -like 'http.server.requests*')) -or
                ('env:validation' -notin $received.tags) -or ('version:validation' -notin $received.tags)
        })
        if ($missing.Count -eq 0) { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    $result | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $EvidenceDirectory 'dogstatsd.json') -Encoding utf8
    if ($missing.Count -gt 0) { throw "Missing tagged HTTP/JVM metrics: $($missing -join ', ')" }
    if (-not ($result.http_requests.PSObject.Properties.Name -like '*traces*')) { throw 'No trace HTTP traffic received' }
    foreach ($service in $services) {
        $logs = (Invoke-Docker @('logs',"$network-$service") 2>&1 | Out-String)
        if ($logs -notmatch 'Using AOT-linked classes: true') { throw "JVM AOT cache not active: $service" }
        if ($logs -notmatch 'AOT-processed') { throw "Spring AOT not active: $service" }
        Write-Host "PASS $service : Java Agent runtime + Micrometer JVM/HTTP DogStatsD, env/version tags, Spring AOT + JVM cache"
    }
    Write-Host "PASS LOCAL TELEMETRY. Foundation services only; run idempotency-bdd.ps1 for the OTLP journey. This receiver does not validate Datadog SaaS delivery. Evidence: $EvidenceDirectory"
} finally {
    foreach ($name in $containers) {
        & docker logs $name 2>&1 | Set-Content (Join-Path $EvidenceDirectory "$name.log") -Encoding utf8
        & docker rm -f $name | Out-Null
    }
    & docker network rm $network 2>$null | Out-Null
    Pop-Location
}
