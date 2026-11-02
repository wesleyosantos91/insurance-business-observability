[CmdletBinding()]
param(
    [switch]$Otlp,
    [string]$EvidenceDirectory = (Join-Path $PSScriptRoot 'target/datadog-agent')
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$network = "insurance-dd-isolated-$runId"
$collector = "$network-collector"
$agent = "$network-agent"
$logSource = "insurance-observability-ms-propostas-otlp-test-$runId"
$sourceCreated = $false

function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $($Arguments -join ' ')" }
    return $output
}

if ($Otlp) {
    foreach ($payload in @('traces.pb','metrics.pb')) {
        if (-not (Test-Path (Join-Path $PSScriptRoot "target/observability/$payload"))) {
            throw 'Run tests/integration/idempotency-bdd.ps1 first to generate real OTLP payloads'
        }
    }
    $sampleLog = @{message='insurance-observability-local-log-validation';service='ms-propostas'}
    $logDirectory = Join-Path $repo 'services/ms-propostas/target/cucumber/container-logs'
    if (-not (Test-Path $logDirectory)) {
        $logDirectory = Join-Path $PSScriptRoot 'target/observability/container-logs'
    }
    $logs = Get-ChildItem $logDirectory -Filter '*.log' -File
    foreach ($file in $logs) {
        $line = Get-Content $file.FullName | Where-Object { $_ -like '{*' -and $_ -like '*Proposal intent durably registered*' -and $_ -like '*trace_id*' } | Select-Object -First 1
        if ($line) {
            $sampleLog = $line | ConvertFrom-Json -AsHashtable
            $sampleLog.message = 'insurance-observability-local-log-validation'
            break
        }
    }
    if (-not $sampleLog.trace_id) { throw 'No real correlated business log found in BDD evidence' }
    $sampleLog | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $PSScriptRoot 'target/observability/sample-log.json') -Encoding utf8NoBOM
}
try {
    Invoke-Docker @('network','create','--internal',$network) | Out-Host
    Invoke-Docker @('run','-d','--name',$collector,'--network',$network,'--network-alias','collector',
        '--mount',("type=bind,source={0},target=/tests,readonly" -f $PSScriptRoot),
        'python:3.13.9-slim-bookworm','python','/tests/datadog-receiver.py') | Out-Host
    $arguments = @('run','-d','--name',$agent,'--network',$network,'--network-alias','datadog-agent','--memory','768m',
        '-v','/var/run/docker.sock:/var/run/docker.sock:ro','-v','/proc:/host/proc:ro','-v','/sys/fs/cgroup:/host/sys/fs/cgroup:ro',
        '-e','DD_API_KEY=00000000000000000000000000000000','-e','DD_HOSTNAME=insurance-local-agent-validation',
        '-e','DD_DD_URL=http://collector:8126','-e','DD_APM_DD_URL=http://collector:8126',
        '-e','DD_APM_ENABLED=true','-e','DD_APM_NON_LOCAL_TRAFFIC=true','-e','DD_DOGSTATSD_NON_LOCAL_TRAFFIC=true',
        '-e','DD_DOGSTATSD_STATS_ENABLE=true','-e','DD_PROCESS_AGENT_ENABLED=false',
        '-e','DD_REMOTE_CONFIGURATION_ENABLED=false','-e','DD_CONTAINER_EXCLUDE_METRICS=name:.*')
    if ($Otlp) {
        $arguments += @('-e','HOST_PROC=/proc','-e','DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318',
            '-e','DD_OTLP_CONFIG_TRACES_ENABLED=true','-e','DD_OTLP_CONFIG_METRICS_ENABLED=true',
            '-e','DD_LOGS_ENABLED=true','-e','DD_LOGS_CONFIG_CONTAINER_COLLECT_ALL=false',
            '-e','DD_CONTAINER_EXCLUDE_LOGS=name:.*','-e',("DD_CONTAINER_INCLUDE_LOGS=name:" + $logSource),
            '-e','DD_LOGS_CONFIG_USE_HTTP=true','-e','DD_LOGS_CONFIG_LOGS_DD_URL=collector:8126',
            '-e','DD_LOGS_CONFIG_LOGS_NO_SSL=true','-e','DD_LOGS_CONFIG_USE_COMPRESSION=false')
    } else {
        $arguments += @('-e','DD_LOGS_ENABLED=false')
    }
    Invoke-Docker ($arguments + 'registry.datadoghq.com/agent:7.85.0') | Out-Host
    $healthy = $false
    foreach ($attempt in 1..45) {
        $health = if ($Otlp) {
            & docker exec $agent bash -ec 'agent health && exec 3<>/dev/tcp/127.0.0.1/4318' 2>&1
        } else {
            & docker exec $agent agent health 2>&1
        }
        if ($LASTEXITCODE -eq 0) { $healthy = $true; break }
        Start-Sleep -Seconds 2
    }
    $health | Set-Content (Join-Path $EvidenceDirectory 'agent-health.txt') -Encoding utf8
    if (-not $healthy) { throw ($health | Out-String) }
    Invoke-Docker @('exec',$collector,'python','-c',
        "import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.sendto(b'insurance.validation:1|c|#service:agent-validation,env:test,version:test',('datadog-agent',8125))") | Out-Host
    if ($Otlp) {
        foreach ($signal in @('traces','metrics')) {
            $code = "import urllib.request,time; data=open('/tests/target/observability/$signal.pb','rb').read(); r=urllib.request.Request('http://datadog-agent:4318/v1/$signal',data,{'Content-Type':'application/x-protobuf'}); print(urllib.request.urlopen(r,timeout=15).status)"
            $statusCode = Invoke-Docker @('exec',$collector,'python','-c',$code)
            if ($statusCode -notcontains '200') { throw "Agent rejected OTLP $signal : $statusCode" }
        }
        $code = "import json,time; data=json.load(open('/tests/target/observability/sample-log.json')); exec('for i in range(60):\n print(json.dumps(data),flush=True)\n time.sleep(2)')"
        Invoke-Docker @('run','-d','--name',$logSource,'--network',$network,'--memory','64m',
            '--label','com.datadoghq.ad.logs=[{"source":"java","service":"ms-propostas"}]',
            '--mount',("type=bind,source={0},target=/tests,readonly" -f $PSScriptRoot),
            'python:3.13.9-slim-bookworm','python','-u','-c',$code) | Out-Host
        $sourceCreated = $true
    }
    $received = $null
    foreach ($attempt in 1..45) {
        Start-Sleep -Seconds 2
        $received = (Invoke-Docker @('exec',$collector,'python','-c',
            "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8126/results').read().decode())")) | ConvertFrom-Json
        $paths = $received.http_requests.PSObject.Properties.Name
        if ($paths -contains '/api/v2/series' -and (-not $Otlp -or (
                ($paths -like '*traces*') -and $received.logMessages.Count -gt 0))) { break }
    }
    $received | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $EvidenceDirectory 'local-intake.json') -Encoding utf8
    $status = Invoke-Docker @('exec',$agent,'agent','status')
    $status | Set-Content (Join-Path $EvidenceDirectory 'agent-status.txt') -Encoding utf8
    if (($status | Out-String) -notmatch 'Metric Packets: [1-9]') { throw 'Agent did not report DogStatsD metric packets' }
    if ($received.http_requests.PSObject.Properties.Name -notcontains '/api/v2/series') {
        throw 'No metric batch forwarded to isolated local intake'
    }
    if ($Otlp) {
        if (-not ($received.http_requests.PSObject.Properties.Name -like '*traces*')) { throw 'No OTLP traces forwarded by Agent' }
        if ($received.logMessages.Count -eq 0) { throw 'No structured Docker log forwarded by Agent' }
        if ($received.logMessages[0].trace_id -ne $sampleLog.trace_id) { throw 'Log trace context changed' }
    }
    Write-Host "PASS Agent 7.85.0: healthy, DogStatsD, OTLP=$Otlp, selective logs=$Otlp, local forwarding. No SaaS traffic. Evidence: $EvidenceDirectory"
} finally {
    & docker logs $agent 2>&1 | Set-Content (Join-Path $EvidenceDirectory 'agent.log') -Encoding utf8
    if ($sourceCreated) { & docker rm -f $logSource 2>$null | Out-Null }
    & docker rm -f $agent $collector 2>$null | Out-Null
    & docker network rm $network 2>$null | Out-Null
}
