$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Src = Join-Path $Root "src"
$Out = Join-Path $Root "out"
$Dist = Join-Path $Root "dist"

if (Test-Path $Out) {
    Remove-Item -Recurse -Force $Out
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

$Sources = Get-ChildItem -Path $Src -Filter *.java | ForEach-Object { $_.FullName }
javac --release 17 -encoding UTF-8 -d $Out $Sources

$Jar = Join-Path $Dist "uloha1.jar"
if (Test-Path $Jar) {
    Remove-Item -Force $Jar
}
jar --create --file $Jar --main-class Main -C $Out .
Write-Host "Hotovo: $Jar"
