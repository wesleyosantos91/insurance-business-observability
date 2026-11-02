[CmdletBinding()]
param([string]$BaseUrl = 'http://localhost:9000')
$ErrorActionPreference = 'Stop'
$settings = @{}
foreach ($line in Get-Content (Join-Path $PSScriptRoot '.env')) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
if (-not $settings.SONAR_ADMIN_PASSWORD) { throw 'Initialize the local Sonar administrator first.' }
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes('admin:' + $settings.SONAR_ADMIN_PASSWORD)) }
function Invoke-Sonar([string]$Path, [hashtable]$Body, [string]$Method = 'Post') {
    Invoke-RestMethod ($BaseUrl.TrimEnd('/') + $Path) -Headers $headers -Body $Body -Method $Method
}
$plugins = Invoke-Sonar '/api/plugins/installed' @{} 'Get'
$plugin = $plugins.plugins | Where-Object key -eq 'checkstyle'
if ($plugin.version -ne '14.3.0') {
    throw "Expected Checkstyle 14.3.0, found '$($plugin.version)'. Run start.ps1 and review pending Marketplace installs."
}
$profileName = 'Insurance Java + Checkstyle'
$profiles = Invoke-Sonar '/api/qualityprofiles/search' @{language='java'} 'Get'
$profile = $profiles.profiles | Where-Object name -eq $profileName
if (-not $profile) {
    $profile = (Invoke-Sonar '/api/qualityprofiles/create' @{language='java';name=$profileName}).profile
}
$parent = $profiles.profiles | Where-Object { $_.isDefault -and $_.name -ne $profileName } | Select-Object -First 1
if (-not $parent) { throw 'No default Java parent profile found.' }
Invoke-Sonar '/api/qualityprofiles/change_parent' @{
    language='java';qualityProfile=$profileName;parentQualityProfile=$parent.name
} | Out-Null
# Read the exact same declarative configuration as Maven; do not maintain a second ruleset.
[xml]$configuration = Get-Content (Join-Path $PSScriptRoot '../../config/checkstyle/checkstyle.xml') -Raw
$catalog = Invoke-Sonar '/api/rules/search' @{repositories='checkstyle';ps='500'} 'Get'
if ($catalog.total -gt 500) { throw 'Checkstyle template catalog exceeds one page.' }
$existing = Invoke-Sonar '/api/rules/search' @{repositories='checkstyle';is_template='false';ps='500'} 'Get'
if ($existing.total -gt 500) { throw 'Checkstyle custom rule catalog exceeds one page.' }
$modules = $configuration.SelectNodes('/module/module[not(@name="TreeWalker")] | /module/module[@name="TreeWalker"]/module')
$expected = @()
foreach ($module in $modules) {
    $name = $module.GetAttribute('name')
    $template = @($catalog.rules | Where-Object { $_.key -notlike 'checkstyle:insurance_*' -and $_.internalKey -match ("/" + [regex]::Escape($name) + '$') })
    if ($template.Count -ne 1) { throw "Expected exactly one Checkstyle template for $name." }
    $parameters = @()
    foreach ($property in $module.SelectNodes('property')) {
        $key = $property.GetAttribute('name')
        if ($key -notin $template[0].params.key) { throw "Unsupported Checkstyle parameter $name.$key." }
        $parameters += "$key=$($property.GetAttribute('value'))"
    }
    if (-not $template[0].isTemplate) {
        if ($parameters.Count -ne 0) { throw "Rule $name is not configurable in this plugin version." }
        $ruleKey = $template[0].key
        Invoke-Sonar '/api/qualityprofiles/activate_rule' @{
            key=$profile.key;rule=$ruleKey;severity='MAJOR'
        } | Out-Null
        $expected += $ruleKey
        continue
    }
    $customKey = "insurance_$name"
    $ruleKey = "checkstyle:$customKey"
    $body = @{
        name="Insurance / $name";severity='MAJOR'
        markdownDescription="Project convention defined in config/checkstyle/checkstyle.xml: $name. Maven rejects every violation."
        params=($parameters -join ';')
    }
    if ($ruleKey -notin $existing.rules.key) {
        $body.customKey = $customKey
        $body.templateKey = $template[0].key
        $body.type = 'CODE_SMELL'
        Invoke-Sonar '/api/rules/create' $body | Out-Null
    } else {
        $body.key = $ruleKey
        Invoke-Sonar '/api/rules/update' $body | Out-Null
    }
    Invoke-Sonar '/api/qualityprofiles/activate_rule' @{
        key=$profile.key;rule=$ruleKey;severity='MAJOR';params=($parameters -join ';')
    } | Out-Null
    $expected += $ruleKey
}
# Remove only stale rules owned by this automation, never inherited or operator-managed rules.
$active = Invoke-Sonar '/api/rules/search' @{
    repositories='checkstyle';activation='true';qprofile=$profile.key;ps='500'
} 'Get'
foreach ($rule in $active.rules) {
    if ($rule.key -like 'checkstyle:insurance_*' -and $rule.key -notin $expected) {
        Invoke-Sonar '/api/qualityprofiles/deactivate_rule' @{key=$profile.key;rule=$rule.key} | Out-Null
    }
}
foreach ($service in @('ms-propostas','ms-cadastro','ms-analise','ms-contratos','ms-endossos','ms-pagamentos')) {
    Invoke-Sonar '/api/qualityprofiles/add_project' @{
        language='java';project="insurance-$service";qualityProfile=$profileName
    } | Out-Null
    Invoke-Sonar '/api/settings/set' @{
        component="insurance-$service";key='sonar.checkstyle.tabWidth';value='4'
    } | Out-Null
}
Write-Host "Checkstyle 14.3.0 verified; $($expected.Count) shared project rules synchronized for six services."
