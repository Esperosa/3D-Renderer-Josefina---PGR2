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
$testBuildRoot = Join-Path $buildRoot "tests"
$mainOut = Join-Path $testBuildRoot "classes"
$testOut = Join-Path $testBuildRoot "test-classes"
$testClassList = Join-Path $scriptRoot "test-class-list.txt"

$javac = Get-JavaTool "javac"
$java = Get-JavaTool "java"

if (Test-Path $testBuildRoot) {
    Remove-Item -Recurse -Force $testBuildRoot
}
New-Item -ItemType Directory -Path $mainOut | Out-Null
New-Item -ItemType Directory -Path $testOut | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$testFiles = Get-ChildItem -Path (Join-Path $repoRoot "tests") -Recurse -Filter *.java | ForEach-Object { $_.FullName }

if ($srcFiles.Count -eq 0) {
    throw "Ve složce src nebyly nalezeny žádné Java zdrojáky."
}
if ($testFiles.Count -eq 0) {
    throw "Ve složce tests nebyly nalezeny žádné Java testy."
}

Write-Host "Kompiluji hlavní zdrojáky..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Kompiluji testy..."
& $javac "-encoding" "UTF-8" "-cp" $mainOut "-d" $testOut @testFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$pathSeparator = [System.IO.Path]::PathSeparator
$classPath = "$mainOut$pathSeparator$testOut"
$testClasses = Get-Content $testClassList |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Where-Object { -not $_.TrimStart().StartsWith("#") } |
        ForEach-Object { $_.Trim() }

foreach ($testClass in $testClasses) {
    Write-Host "Spouštím $testClass ..."
    & $java "-cp" $classPath $testClass
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host "Všechny testy prošly."
exit 0
