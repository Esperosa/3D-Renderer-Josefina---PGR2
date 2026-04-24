param(
    [ValidateSet("quick", "standard", "full")]
    [string] $BenchmarkMode = "standard",
    [double] $CpuScale = 0.7
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
$reportRoot = Join-Path $buildRoot ("metrics-" + [Guid]::NewGuid().ToString("N"))
$mainOut = Join-Path $reportRoot "classes"
$testOut = Join-Path $reportRoot "test-classes"

$javac = Get-JavaTool "javac"
$java = Get-JavaTool "java"

New-Item -ItemType Directory -Path $mainOut -Force | Out-Null
New-Item -ItemType Directory -Path $testOut -Force | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$testFiles = Get-ChildItem -Path (Join-Path $repoRoot "tests") -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "Compiling main sources..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Compiling tests and report..."
& $javac "-encoding" "UTF-8" "-cp" $mainOut "-d" $testOut @testFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$pathSeparator = [System.IO.Path]::PathSeparator
$classPath = "$mainOut$pathSeparator$testOut"

Write-Host "Generating project report..."
& $java "-Dmetrics.benchmark.mode=$BenchmarkMode" "-Dmetrics.cpu.scale=$CpuScale" "-cp" $classPath "ProjectMetricsReport"
exit $LASTEXITCODE
