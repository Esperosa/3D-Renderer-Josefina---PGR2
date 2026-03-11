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

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..")
$buildRoot = Join-Path $repoRoot "build"
$reportRoot = Join-Path $buildRoot ("metrics-" + [Guid]::NewGuid().ToString("N"))
$mainOut = Join-Path $reportRoot "classes"
$testOut = Join-Path $reportRoot "test-classes"

$javac = Get-JavaTool "javac"
$java = Get-JavaTool "java"

New-Item -ItemType Directory -Path $mainOut | Out-Null
New-Item -ItemType Directory -Path $testOut | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$testFiles = Get-ChildItem -Path (Join-Path $repoRoot "tests") -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "Kompiluji hlavní zdrojáky..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Kompiluji testy a report..."
& $javac "-encoding" "UTF-8" "-cp" $mainOut "-d" $testOut @testFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$pathSeparator = [System.IO.Path]::PathSeparator
$classPath = "$mainOut$pathSeparator$testOut"

Write-Host "Generuji report projektu..."
& $java "-cp" $classPath "ProjectMetricsReport"
exit $LASTEXITCODE
