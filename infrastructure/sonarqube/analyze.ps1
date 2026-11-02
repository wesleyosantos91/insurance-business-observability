[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:9000',
    [ValidateSet('all','ms-propostas','ms-cadastro','ms-analise','ms-contratos','ms-endossos','ms-pagamentos')]
    [string]$Service = 'all',
    [switch]$SkipContractInstall
)
$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path $envPath)) { throw 'Run initialize.ps1 first.' }
$settings = @{}
foreach ($line in Get-Content $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]]=$Matches[2] }
}
$services = if ($Service -eq 'all') {
    @('ms-propostas','ms-cadastro','ms-analise','ms-contratos','ms-endossos','ms-pagamentos')
} else { @($Service) }
foreach ($name in $services) {
    $tokenKey='SONAR_TOKEN_' + $name.ToUpperInvariant().Replace('-','_')
    if (-not $settings[$tokenKey]) { throw "Missing $tokenKey. Run initialize.ps1 first." }
}
$previousToken=$env:SONAR_TOKEN
$previousUrl=$env:SONAR_HOST_URL
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $repo
try {
    # The service POMs retain Spring Boot as parent. Install only their generated contract dependency first.
    if (-not $SkipContractInstall -and ($services | Where-Object { $_ -in @('ms-propostas','ms-cadastro') })) {
        & mvn -B -ntp -pl libraries/event-contracts -am install
        if ($LASTEXITCODE -ne 0) { throw 'Contract module installation failed.' }
    }
    foreach ($name in $services) {
        $tokenKey='SONAR_TOKEN_' + $name.ToUpperInvariant().Replace('-','_')
        $env:SONAR_TOKEN=$settings[$tokenKey]
        $env:SONAR_HOST_URL=$BaseUrl
        Write-Host "Analyzing independent SonarQube project: insurance-$name"
        $arguments=@('-B','-ntp','-f',"services/$name/pom.xml",'clean','verify',
            'org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar',
            '-Dsonar.qualitygate.wait=true','-Dsonar.qualitygate.timeout=180','-Dsonar.scanner.skipJreProvisioning=true')
        if ($name -in @('ms-propostas','ms-cadastro')) { $arguments += '-Pmutation' }
        & mvn @arguments
        if ($LASTEXITCODE -ne 0) { throw "Build, mutation, analysis or Quality Gate failed for $name." }
    }
} finally {
    $env:SONAR_TOKEN=$previousToken
    $env:SONAR_HOST_URL=$previousUrl
    Pop-Location
}
