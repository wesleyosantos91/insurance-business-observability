[CmdletBinding()]
param([string]$Image = 'insurance/sonarqube:26.10.0.133078-mutation-1.8-checkstyle-14.3.0')
$ErrorActionPreference = 'Stop'
$volume = 'insurance-sonar-plugin-test-' + [Guid]::NewGuid().ToString('N')
$mount = "type=volume,source=$volume,target=/opt/sonarqube/extensions"
$created = $false
$plugins = @(
    @{Name='Mutation Analysis';Jar='mutation-analysis-plugin-1.8.jar';Prefix='mutation-analysis-plugin';Hash='077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f'},
    @{Name='Checkstyle';Jar='checkstyle-sonar-plugin-14.3.0.jar';Prefix='checkstyle-sonar-plugin';Hash='79ec5c888111dccd180c8919b218a54b5390d54a91b36ad067b6b7610e2da5c9'}
)
function Run-Docker([string[]]$Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker validation failed: $($Arguments -join ' ')" }
    return $output
}
function Assert-Rejected([string]$Pattern) {
    $output = & docker run --rm --network none --mount $mount $Image sh -c 'exit 0' 2>&1
    if ($LASTEXITCODE -eq 0 -or "$output" -notmatch $Pattern) { throw "Expected plugin rejection: $Pattern. Output: $output" }
}
try {
    Run-Docker @('volume','create',$volume) | Out-Null
    $created = $true
    $assertions = foreach ($plugin in $plugins) {
        'echo "' + $plugin.Hash + '  /opt/sonarqube/extensions/plugins/' + $plugin.Jar + '" | sha256sum --check -'
    }
    $assertion = ($assertions -join '; ') + '; test "$(id -u)" = 1000'
    Run-Docker @('run','--rm','--network','none','--mount',$mount,$Image,'sh','-ec',$assertion) | Out-Host
    Run-Docker @('run','--rm','--network','none','--mount',$mount,$Image,'sh','-ec',$assertion) | Out-Host
    foreach ($plugin in $plugins) {
        $target = '/opt/sonarqube/extensions/plugins/' + $plugin.Jar
        $source = '/opt/insurance/plugins/' + $plugin.Jar
        Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
            '-ec',("chmod 0644 $target; printf tampered > $target")) | Out-Null
        Assert-Rejected ($plugin.Name + ' checksum differs')
        Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
            '-ec',("cp $source $target; chmod 0444 $target; touch /opt/sonarqube/extensions/plugins/" + $plugin.Prefix + '-conflict.jar')) | Out-Null
        Assert-Rejected ('Conflicting ' + $plugin.Name + ' plugin')
        Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
            '-ec',('mv /opt/sonarqube/extensions/plugins/' + $plugin.Prefix + '-conflict.jar /opt/sonarqube/extensions/')) | Out-Null
        Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
            '-ec',('mkdir -p /opt/sonarqube/extensions/downloads; touch /opt/sonarqube/extensions/downloads/' + $plugin.Prefix + '-pending.jar')) | Out-Null
        Assert-Rejected ('Pending ' + $plugin.Name + ' Marketplace download')
        Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
            '-ec',('mv /opt/sonarqube/extensions/downloads/' + $plugin.Prefix + '-pending.jar /opt/sonarqube/extensions/')) | Out-Null
    }
    Run-Docker @('run','--rm','--network','none','--mount',$mount,$Image,'sh','-ec',$assertion) | Out-Host
    Write-Host 'PASS: both plugins; checksums, UID 1000, empty/reused volume, offline startup, corruption/version/pending-download rejection.'
} finally {
    if ($created) {
        # Only the uniquely named temporary volume created by this test is removed.
        & docker volume rm $volume | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove test volume $volume" }
    }
}
