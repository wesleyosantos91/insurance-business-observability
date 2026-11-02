[CmdletBinding()]
param([string]$BaseUrl = 'http://localhost:9000')
$ErrorActionPreference = 'Stop'
$services = @('ms-propostas','ms-cadastro','ms-analise','ms-contratos','ms-endossos','ms-pagamentos')
$gateName = 'Insurance - 90 percent line coverage'
$envPath = Join-Path $PSScriptRoot '.env'
$settings = [ordered]@{ SONAR_PORT='9000'; SONAR_DB_PASSWORD='sonarqube-local'; SONAR_ADMIN_PASSWORD=''; SONAR_TOKEN='' }
if (Test-Path $envPath) {
    foreach ($line in Get-Content $envPath) {
        if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
    }
}
function Save-LocalSettings {
    $lines = foreach ($entry in $settings.GetEnumerator()) { '{0}={1}' -f $entry.Key,$entry.Value }
    [IO.File]::WriteAllLines($envPath, [string[]]$lines, [Text.UTF8Encoding]::new($false))
}
function Basic-Headers([string]$Password) {
    @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:' + $Password)) }
}
function Invoke-Sonar([string]$Path, [hashtable]$Body, [string]$Method = 'Post') {
    Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/') + $Path) -Method $Method -Headers $script:headers -Body $Body
}
$script:headers = Basic-Headers $(if($settings.SONAR_ADMIN_PASSWORD){$settings.SONAR_ADMIN_PASSWORD}else{'admin'})
$authentication = Invoke-Sonar '/api/authentication/validate' @{} 'Get'
if (-not $authentication.valid) {
    throw 'Admin authentication failed. Set SONAR_ADMIN_PASSWORD in the ignored local .env; existing credentials will not be reset.'
}
if (-not $settings.SONAR_ADMIN_PASSWORD) {
    $password = [Guid]::NewGuid().ToString('N') + '-Aa1!'
    Invoke-Sonar '/api/users/change_password' @{login='admin';previousPassword='admin';password=$password} | Out-Null
    $settings.SONAR_ADMIN_PASSWORD = $password
    Save-LocalSettings
    $script:headers = Basic-Headers $password
}
$gates = Invoke-Sonar '/api/qualitygates/list' @{} 'Get'
if (-not ($gates.qualitygates | Where-Object name -eq $gateName)) {
    Invoke-Sonar '/api/qualitygates/create' @{name=$gateName} | Out-Null
}
$gate = Invoke-Sonar '/api/qualitygates/show' @{name=$gateName} 'Get'
if (-not ($gate.conditions | Where-Object metric -eq 'line_coverage')) {
    Invoke-Sonar '/api/qualitygates/create_condition' @{gateName=$gateName;metric='line_coverage';op='LT';error='90'} | Out-Null
}
foreach ($service in $services) {
    $projectKey = "insurance-$service"
    $projects = Invoke-Sonar '/api/projects/search' @{projects=$projectKey} 'Get'
    if (-not $projects.components) {
        Invoke-Sonar '/api/projects/create' @{project=$projectKey;name=$service;mainBranch='develop';visibility='private'} | Out-Null
    }
    Invoke-Sonar '/api/qualitygates/select' @{gateName=$gateName;projectKey=$projectKey} | Out-Null
    $tokenKey = 'SONAR_TOKEN_' + $service.ToUpperInvariant().Replace('-','_')
    if (-not $settings[$tokenKey]) {
        $token = Invoke-Sonar '/api/user_tokens/generate' @{
            name="$projectKey-maven";type='PROJECT_ANALYSIS_TOKEN';projectKey=$projectKey
            expirationDate=(Get-Date).AddDays(90).ToString('yyyy-MM-dd')
        }
        $settings[$tokenKey] = $token.token
        Save-LocalSettings
    }
    Write-Host "Project and 90% line-coverage gate configured: $BaseUrl/dashboard?id=$projectKey"
}
Write-Host 'Passwords and project-scoped tokens are saved only in the ignored infrastructure/sonarqube/.env.'
