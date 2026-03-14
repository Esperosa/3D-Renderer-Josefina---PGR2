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
    throw "No Java source files were found in src."
}

Write-Host "Compiling main sources..."
& $javac "-encoding" "UTF-8" "-d" $mainOut @srcFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Build completed in $mainOut"
if ($Run) {
    & $java "-cp" $mainOut Main
    exit $LASTEXITCODE
}
