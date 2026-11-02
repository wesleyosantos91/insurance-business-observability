[CmdletBinding()]
param([string]$BaseUrl = 'http://localhost:9000')
$ErrorActionPreference = 'Stop'
$compose = Join-Path $PSScriptRoot 'compose.yaml'
& docker compose -f $compose up -d --build --wait --wait-timeout 480
if ($LASTEXITCODE -ne 0) { throw 'SonarQube infrastructure startup failed.' }
# This administrative bootstrap explicitly records the consent requested for this local environment.
& (Join-Path $PSScriptRoot 'initialize.ps1') -BaseUrl $BaseUrl -AcceptThirdPartyPluginRisk
Write-Host "SonarQube ready with pinned Mutation Analysis and Checkstyle plugins and per-service configuration: $BaseUrl"
