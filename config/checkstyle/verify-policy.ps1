[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixture = Join-Path $repo ('target/checkstyle-policy-' + [Guid]::NewGuid().ToString('N'))
$sourceDirectory = Join-Path $fixture 'src/main/java/example'
[IO.Directory]::CreateDirectory($sourceDirectory) | Out-Null
$parent = '../../pom.xml'
$configuration = [Security.SecurityElement]::Escape($PSScriptRoot)
$pom = @"
<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.wesleosantos91</groupId>
    <artifactId>insurance-business-observability</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <relativePath>$parent</relativePath>
  </parent>
  <artifactId>checkstyle-policy-probe</artifactId>
  <properties><checkstyle.config.directory>$configuration</checkstyle.config.directory></properties>
</project>
"@
[IO.File]::WriteAllText((Join-Path $fixture 'pom.xml'),$pom,[Text.UTF8Encoding]::new($false))
$valid = @(
    'package example;', '',
    'public class StyleProbe {',
    '    public void publishEvent() {',
    '    }',
    '}'
) -join [char]10
$source = Join-Path $sourceDirectory 'StyleProbe.java'
# An isolated fixture under target: application sources are never changed.
[IO.File]::WriteAllText($source,$valid.Replace('publishEvent','publish_event') + [char]10,[Text.UTF8Encoding]::new($false))
$negativeLog = Join-Path $fixture 'rejected.log'
& mvn -B -ntp -f (Join-Path $fixture 'pom.xml') validate *> $negativeLog
if ($LASTEXITCODE -eq 0 -or -not (Select-String -Path $negativeLog -Pattern 'MethodName' -Quiet)) {
    throw "A deliberate naming violation did not fail Maven. See $negativeLog"
}
[IO.File]::WriteAllText($source,$valid + [char]10,[Text.UTF8Encoding]::new($false))
$positiveLog = Join-Path $fixture 'accepted.log'
& mvn -B -ntp -f (Join-Path $fixture 'pom.xml') validate *> $positiveLog
if ($LASTEXITCODE -ne 0) { throw "Compliant code did not pass Maven. See $positiveLog" }
Write-Host 'PASS: a deliberate MethodName violation fails validate; compliant code passes with the same configuration.'
Write-Host "Evidence: $fixture"
