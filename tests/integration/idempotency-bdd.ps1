[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $repo
try {
    if (-not $SkipBuild) {
        foreach ($service in @('ms-propostas', 'ms-cadastro')) {
            & docker build -f "services/$service/Dockerfile" -t ("insurance/{0}:idempotency-aot" -f $service) .
            if ($LASTEXITCODE -ne 0) { throw "Docker build failed: $service" }
        }
    }
    & mvn -B -ntp -Pintegration clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven/BDD validation failed; inspect target/cucumber and failsafe-reports' }
    Write-Host 'BDD report: services/ms-propostas/target/cucumber/idempotency.html'
} finally {
    Pop-Location
}
