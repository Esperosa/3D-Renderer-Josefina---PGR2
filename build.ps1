param(
    [switch] $Run,
    [ValidateSet("safe", "balanced", "fast")]
    [string] $Profile = "safe"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Get-JavaMajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string] $JavaExe
    )

    if (-not (Test-Path $JavaExe)) {
        return -1
    }

    try {
        $versionOutput = & $JavaExe "-version" 2>&1 | Select-Object -First 1
        if ($versionOutput -match '"([0-9]+)(?:\.[0-9]+)?(?:\.[0-9]+)?') {
            $major = [int] $Matches[1]
            if ($major -eq 1 -and $versionOutput -match '"1\.([0-9]+)') {
                $major = [int] $Matches[1]
            }
            return $major
        }
    } catch {
    }

    return -1
}

function Get-BestJavaHome {
    $homeCandidates = New-Object System.Collections.Generic.HashSet[string] ([System.StringComparer]::OrdinalIgnoreCase)

    if ($env:JAVA_HOME) {
        [void] $homeCandidates.Add($env:JAVA_HOME)
    }

    $pathJava = Get-Command java -ErrorAction SilentlyContinue
    if ($null -ne $pathJava -and $pathJava.Source) {
        $pathHome = Split-Path -Parent (Split-Path -Parent $pathJava.Source)
        if ($pathHome) {
            [void] $homeCandidates.Add($pathHome)
        }
    }

    $programRoots = @(
        $env:ProgramFiles,
        ${env:ProgramFiles(x86)}
    ) | Where-Object { $_ }
    foreach ($programRoot in $programRoots) {
        $vendorRoots = @(
            (Join-Path $programRoot "Eclipse Adoptium"),
            (Join-Path $programRoot "Java"),
            (Join-Path $programRoot "Microsoft")
        )
        foreach ($vendorRoot in $vendorRoots) {
            if (-not (Test-Path $vendorRoot)) {
                continue
            }
            Get-ChildItem -Path $vendorRoot -Directory -ErrorAction SilentlyContinue |
                ForEach-Object {
                    [void] $homeCandidates.Add($_.FullName)
                }
        }
    }

    $ranked = @()
    foreach ($javaHomeCandidate in $homeCandidates) {
        $javaExe = Join-Path $javaHomeCandidate "bin\java.exe"
        $javacExe = Join-Path $javaHomeCandidate "bin\javac.exe"
        if (-not (Test-Path $javaExe) -or -not (Test-Path $javacExe)) {
            continue
        }
        $major = Get-JavaMajorVersion -JavaExe $javaExe
        if ($major -lt 17) {
            continue
        }
        $ranked += [PSCustomObject]@{
            Home  = $javaHomeCandidate
            Java  = $javaExe
            Javac = $javacExe
            Major = $major
        }
    }

    if ($ranked.Count -eq 0) {
        throw "No JDK 17+ installation was found. Install JDK 21 (recommended) or newer JDK 17+, then rerun."
    }

    return $ranked |
        Sort-Object -Property @{ Expression = "Major"; Descending = $true }, @{ Expression = "Home"; Descending = $true } |
        Select-Object -First 1
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildRoot = Join-Path $repoRoot "build"
$mainOut = Join-Path $buildRoot "classes"

$selectedJdk = Get-BestJavaHome
$env:JAVA_HOME = $selectedJdk.Home
$env:Path = "$($selectedJdk.Home)\bin;" + $env:Path

$javac = $selectedJdk.Javac
$java = $selectedJdk.Java

if (Test-Path $mainOut) {
    Remove-Item -Recurse -Force $mainOut
}
New-Item -ItemType Directory -Path $mainOut -Force | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($srcFiles.Count -eq 0) {
    throw "No Java source files were found in src."
}

Write-Host "Compiling main sources..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Build completed in $mainOut"
Write-Host "Using JDK: $($selectedJdk.Home) (major $($selectedJdk.Major))"
if ($Run) {
    $runArgs = @("-XX:+ShowCodeDetailsInExceptionMessages")
    switch ($Profile) {
        "safe" {
            $runArgs += "-Xint"
        }
        "balanced" {
            $runArgs += "-XX:TieredStopAtLevel=1"
            $runArgs += "-XX:CompileCommand=exclude,engine/render/ray/core/PathTracerRenderer,tracePreviewPathInternal"
            $runArgs += "-XX:CompileCommand=exclude,engine/render/ray/core/PathTracerRenderer,sampleEnvironmentBackground"
            $runArgs += "-XX:CompileCommand=exclude,engine/material/MaterialGraphValueEvaluator,evaluateValueOutput"
        }
        "fast" {
        }
    }
    $runArgs += @("-cp", $mainOut, "Main")
    Write-Host "Run profile: $Profile"
    & $java @runArgs
    exit $LASTEXITCODE
}
