$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path $Root "dist\uloha1.jar"
if (-not (Test-Path $Jar)) {
    & (Join-Path $Root "build.ps1")
}
java -jar $Jar
