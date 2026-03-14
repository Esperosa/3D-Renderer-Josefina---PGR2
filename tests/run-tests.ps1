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

    $programRoots = @(
        $env:ProgramFiles,
        ${env:ProgramFiles(x86)}
    ) | Where-Object { $_ }
    foreach ($programRoot in $programRoots) {
        $javaHomes = @(
            (Join-Path $programRoot "Java"),
            (Join-Path $programRoot "Eclipse Adoptium"),
            (Join-Path $programRoot "Microsoft")
        )
        foreach ($javaHome in $javaHomes) {
            if (-not (Test-Path $javaHome)) {
                continue
            }
            $candidate = Get-ChildItem -Path $javaHome -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName "bin\$Name.exe" } |
                Where-Object { Test-Path $_ } |
                Select-Object -First 1
            if ($candidate) {
                return $candidate
            }
        }
    }

    throw "Tool '$Name' was not found in PATH, JAVA_HOME, or common JDK install folders under Program Files. Install JDK 17+ and set PATH or JAVA_HOME."
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
New-Item -ItemType Directory -Path $mainOut -Force | Out-Null
New-Item -ItemType Directory -Path $testOut -Force | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$testFiles = Get-ChildItem -Path (Join-Path $repoRoot "tests") -Recurse -Filter *.java | ForEach-Object { $_.FullName }

if ($srcFiles.Count -eq 0) {
    throw "No Java source files were found in src."
}
if ($testFiles.Count -eq 0) {
    throw "No Java test files were found in tests."
}

Write-Host "Compiling main sources..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Compiling tests..."
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
    Write-Host "Running $testClass ..."
    & $java "-cp" $classPath $testClass
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Write-Host "All tests passed."
exit 0
