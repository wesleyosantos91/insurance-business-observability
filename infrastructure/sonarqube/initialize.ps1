[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:9000',
    [switch]$AcceptThirdPartyPluginRisk
)
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
if ($AcceptThirdPartyPluginRisk) {
    & (Join-Path $PSScriptRoot 'accept-plugin-risk.ps1') -BaseUrl $BaseUrl -AcceptThirdPartyPluginRisk
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
& (Join-Path $PSScriptRoot 'configure-checkstyle.ps1') -BaseUrl $BaseUrl
$installed = Invoke-Sonar '/api/plugins/installed' @{} 'Get'
$mutation = $installed.plugins | Where-Object key -eq 'mutationanalysis'
if ($mutation) {
    if ($mutation.version -ne '1.8') { throw "Unexpected Mutation Analysis version $($mutation.version). Rebuild the pinned image." }
    $profileName = 'Insurance Java + Mutation Analysis'
    $profiles = Invoke-Sonar '/api/qualityprofiles/search' @{language='java'} 'Get'
    $profile = $profiles.profiles | Where-Object name -eq $profileName
    if (-not $profile) {
        $created = Invoke-Sonar '/api/qualityprofiles/create' @{language='java';name=$profileName}
        $profile = $created.profile
    }
    $parent = $profiles.profiles | Where-Object name -eq 'Insurance Java + Checkstyle'
    if (-not $parent) { throw 'Checkstyle parent profile missing; run the shared rules bootstrap first.' }
    Invoke-Sonar '/api/qualityprofiles/change_parent' @{
        language='java';qualityProfile=$profileName;parentQualityProfile=$parent.name
    } | Out-Null
    # Operator rules have BUG and CODE_SMELL alternatives; the generic survived rule
    # overlaps both. Keep one CODE_SMELL per operator instead of reporting each mutant three times.
    $rules = Invoke-Sonar '/api/rules/search' @{repositories='mutation.analysis.java';ps='100'} 'Get'
    if ($rules.total -gt 100) { throw 'Mutation rule catalog exceeds one page; review the pinned plugin configuration.' }
    $active = Invoke-Sonar '/api/rules/search' @{
        repositories='mutation.analysis.java';activation='true';qprofile=$profile.key;ps='100'
    } 'Get'
    foreach ($rule in $rules.rules) {
        $enabled = $rule.type -eq 'CODE_SMELL' -or $rule.key -in @(
            'mutation.analysis.java:mutant.lurking', 'mutation.analysis.java:mutant.unknownStatus'
        )
        $alreadyActive = $rule.key -in $active.rules.key
        if ($enabled -eq $alreadyActive) { continue }
        $action = if ($enabled) { 'activate_rule' } else { 'deactivate_rule' }
        Invoke-Sonar "/api/qualityprofiles/$action" @{key=$profile.key;rule=$rule.key} | Out-Null
    }
    $mutationGateName = 'Insurance - 90 percent line coverage and 100 percent mutation'
    $gates = Invoke-Sonar '/api/qualitygates/list' @{} 'Get'
    if (-not ($gates.qualitygates | Where-Object name -eq $mutationGateName)) {
        Invoke-Sonar '/api/qualitygates/create' @{name=$mutationGateName} | Out-Null
    }
    $gate = Invoke-Sonar '/api/qualitygates/show' @{name=$mutationGateName} 'Get'
    $thresholds = @{line_coverage='90';dc5_mutationAnalysis_mutations_coverage='100'}
    foreach ($metric in $thresholds.Keys) {
        if (-not ($gate.conditions | Where-Object metric -eq $metric)) {
            Invoke-Sonar '/api/qualitygates/create_condition' @{
                gateName=$mutationGateName;metric=$metric;op='LT';error=$thresholds[$metric]
            } | Out-Null
        }
    }
    foreach ($service in @('ms-propostas','ms-cadastro')) {
        Invoke-Sonar '/api/qualityprofiles/add_project' @{
            language='java';project="insurance-$service";qualityProfile=$profileName
        } | Out-Null
        Invoke-Sonar '/api/qualitygates/select' @{
            gateName=$mutationGateName;projectKey="insurance-$service"
        } | Out-Null
    }
    Write-Host 'Mutation Analysis 1.8 verified; Java rules and 100% mutation gates configured for proposals/cadastro.'
} else {
    throw 'Mutation Analysis is not installed; use start.ps1 to build and start the pinned image.'
}
Write-Host 'Passwords and project-scoped tokens are saved only in the ignored infrastructure/sonarqube/.env.'
