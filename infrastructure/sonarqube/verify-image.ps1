[CmdletBinding()]
param([string]$Image = 'insurance/sonarqube:26.10.0.133078-mutation-1.8')
$ErrorActionPreference = 'Stop'
$volume = 'insurance-sonar-plugin-test-' + [Guid]::NewGuid().ToString('N')
$mount = "type=volume,source=$volume,target=/opt/sonarqube/extensions"
$target = '/opt/sonarqube/extensions/plugins/mutation-analysis-plugin-1.8.jar'
$created = $false
function Run-Docker([string[]]$Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker validation failed: $($Arguments -join ' ')" }
    return $output
}
try {
    Run-Docker @('volume','create',$volume) | Out-Null
    $created = $true
    $assertion = 'echo "077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f  ' + $target + '" | sha256sum --check -'
    Run-Docker @('run','--rm','--network','none','--mount',$mount,$Image,'sh','-ec',
        ($assertion + '; test "$(id -u)" = 1000; chmod 0400 ' + $target)) | Out-Host
    Run-Docker @('run','--rm','--network','none','--mount',$mount,$Image,'sh','-ec',
        ($assertion + '; test "$(stat -c %a ' + $target + ')" = 400')) | Out-Host

    Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
        '-ec',("chmod 0644 $target; printf tampered > $target")) | Out-Null
    $output = & docker run --rm --network none --mount $mount $Image sh -c 'exit 0' 2>&1
    if ($LASTEXITCODE -eq 0 -or "$output" -notmatch 'checksum differs') {
        throw 'Tampered managed plugin was not rejected'
    }

    Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
        '-ec',("cp /opt/insurance/plugins/mutation-analysis-plugin-1.8.jar $target; touch /opt/sonarqube/extensions/plugins/mutation-analysis-plugin-conflict.jar")) | Out-Null
    $output = & docker run --rm --network none --mount $mount $Image sh -c 'exit 0' 2>&1
    if ($LASTEXITCODE -eq 0 -or "$output" -notmatch 'Conflicting Mutation Analysis plugin') {
        throw 'Multiple plugin versions were not rejected'
    }
    Run-Docker @('run','--rm','--network','none','--mount',$mount,'--entrypoint','sh',$Image,
        '-ec','test -f /opt/sonarqube/extensions/plugins/mutation-analysis-plugin-conflict.jar') | Out-Null
    Write-Host 'PASS: checksum, non-root startup, empty/reused extension volume, no runtime network, corruption/conflict rejection.'
    Write-Host 'This verifies image preparation only; Sonar startup and PITest import require activation validation.'
} finally {
    if ($created) {
        # Remove only the uniquely named temporary volume created by this test.
        & docker volume rm $volume | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove test volume $volume" }
    }
}
