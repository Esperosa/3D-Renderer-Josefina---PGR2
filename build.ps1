param(
    [switch] $Run
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-JavaTool {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$Name.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    throw "Nástroj '$Name' nebyl nalezen ani v PATH, ani v JAVA_HOME. Nainstalujte JDK 17+ a nastavte PATH nebo JAVA_HOME."
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path $repoRoot "build"
$mainOut = Join-Path $buildRoot "classes"

$javac = Get-JavaTool "javac"
$java = Get-JavaTool "java"

if (Test-Path $mainOut) {
    Remove-Item -Recurse -Force $mainOut
}
New-Item -ItemType Directory -Path $mainOut -Force | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($srcFiles.Count -eq 0) {
    throw "Ve složce src nebyly nalezeny žádné Java zdrojáky."
}

Write-Host "Kompiluji hlavní zdrojáky..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Build dokončen do $mainOut"
if ($Run) {
    & $java "-cp" $mainOut Main
    exit $LASTEXITCODE
}
