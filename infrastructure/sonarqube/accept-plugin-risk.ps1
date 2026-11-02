[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$BaseUrl = 'http://localhost:9000',
    [switch]$AcceptThirdPartyPluginRisk
)
$ErrorActionPreference = 'Stop'
if (-not $AcceptThirdPartyPluginRisk) {
    throw 'No consent changed. Pass -AcceptThirdPartyPluginRisk to explicitly acknowledge third-party plugin risk.'
}
$envPath = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path $envPath)) { throw 'Run initialize.ps1 first to configure the local administrator.' }
$settings = @{}
foreach ($line in Get-Content $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]]=$Matches[2] }
}
if (-not $settings.SONAR_ADMIN_PASSWORD) { throw 'SONAR_ADMIN_PASSWORD is missing in the ignored local .env.' }
$headers = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes('admin:' + $settings.SONAR_ADMIN_PASSWORD))
}
$base = $BaseUrl.TrimEnd('/')
$key = 'sonar.plugins.risk.consent'
function Read-Consent {
    $response = Invoke-RestMethod "$base/api/settings/values?keys=$key" -Headers $headers
    return ($response.settings | Where-Object key -eq $key).value
}
$current = Read-Consent
if ($current -eq 'ACCEPTED') {
    Write-Host 'Third-party plugin risk consent is already ACCEPTED; no change required.'
    return
}
if ($current -ne 'NOT_ACCEPTED') {
    throw "Unexpected consent state '$current'. Review this SonarQube version before changing it."
}
if ($PSCmdlet.ShouldProcess($BaseUrl, 'Record explicit administrator consent for third-party plugins')) {
    Invoke-RestMethod "$base/api/settings/set" -Method Post -Headers $headers -Body @{
        key=$key; value='ACCEPTED'
    } | Out-Null
    if ((Read-Consent) -ne 'ACCEPTED') { throw 'SonarQube did not persist the requested consent.' }
    Write-Host 'Third-party plugin risk consent ACCEPTED and verified through the SonarQube API.'
    Write-Host 'Consent is stored by SonarQube; passwords and analysis tokens are not printed.'
}
