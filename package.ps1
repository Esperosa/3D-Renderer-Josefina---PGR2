param(
    [string] $Version
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

    throw "Nástroj '$Name' nebyl nalezen ani v PATH, ani v JAVA_HOME. Pro packaging potřebuji plný JDK 17+."
}

function Remove-PathIfExists {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue
    )

    if (Test-Path $PathValue) {
        Remove-Item -Recurse -Force $PathValue
    }
}

function Assert-Exists {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue
    )

    if (-not (Test-Path $PathValue)) {
        throw "Chybí očekávaná cesta: $PathValue"
    }
}

function Resolve-VersionTag {
    param(
        [Parameter(Mandatory = $true)]
        [string] $RepoRoot,
        [string] $RequestedVersion
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedVersion)) {
        return $RequestedVersion.Trim()
    }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($null -eq $git) {
        return "dev"
    }

    $latestTag = & $git.Source -C $RepoRoot describe --tags --abbrev=0 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($latestTag)) {
        return "dev"
    }
    return $latestTag.Trim()
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$versionTag = Resolve-VersionTag -RepoRoot $repoRoot -RequestedVersion $Version
$safeVersion = $versionTag.Trim()
$packageBaseName = "3D-Render-Physics-$safeVersion-windows-portable"

$buildRoot = Join-Path $repoRoot "build"
$classesRoot = Join-Path $buildRoot "classes"
$packageRoot = Join-Path $buildRoot "package"
$stagingRoot = Join-Path $packageRoot "staging"
$stagingAppRoot = Join-Path $stagingRoot "app"
$stagingRuntimeRoot = Join-Path $stagingRoot "runtime"
$distRoot = Join-Path $packageRoot $packageBaseName
$zipPath = Join-Path $packageRoot ($packageBaseName + ".zip")
$jarPath = Join-Path $stagingAppRoot "3D-Render-Physics.jar"

$javac = Get-JavaTool "javac"
$jar = Get-JavaTool "jar"
$jdeps = Get-JavaTool "jdeps"
$jlink = Get-JavaTool "jlink"

Remove-PathIfExists $stagingRoot
Remove-PathIfExists $distRoot
Remove-PathIfExists $zipPath
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null
New-Item -ItemType Directory -Path $stagingAppRoot -Force | Out-Null
New-Item -ItemType Directory -Path $packageRoot -Force | Out-Null

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($srcFiles.Count -eq 0) {
    throw "Ve složce src nebyly nalezeny žádné Java zdrojáky."
}

Write-Host "Kompiluji hlavní zdrojáky pro portable bundle..."
Remove-PathIfExists $classesRoot
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null
& $javac "-encoding" "UTF-8" "-d" $classesRoot @srcFiles
if ($LASTEXITCODE -ne 0) {
    throw "Kompilace hlavních zdrojáků selhala."
}

Write-Host "Vytvářím spustitelný JAR..."
& $jar "--create" "--file" $jarPath "--main-class" "Main" "-C" $classesRoot "."
if ($LASTEXITCODE -ne 0) {
    throw "Vytvoření JAR balíku selhalo."
}

Write-Host "Zjišťuji minimální Java moduly..."
$moduleDeps = (& $jdeps "--multi-release" "17" "--ignore-missing-deps" "--print-module-deps" $jarPath).Trim()
if ([string]::IsNullOrWhiteSpace($moduleDeps)) {
    throw "Nepodařilo se zjistit modulové závislosti přes jdeps."
}

Write-Host "Skládám vlastní runtime přes jlink..."
& $jlink "--add-modules" $moduleDeps "--strip-debug" "--no-header-files" "--no-man-pages" "--compress=2" "--output" $stagingRuntimeRoot
if ($LASTEXITCODE -ne 0) {
    throw "Vytvoření runtime image přes jlink selhalo."
}

Write-Host "Připravuji portable adresář..."
New-Item -ItemType Directory -Path $distRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $distRoot "app") -Force | Out-Null
Copy-Item $jarPath (Join-Path $distRoot "app") -Force
Copy-Item $stagingRuntimeRoot (Join-Path $distRoot "runtime") -Recurse -Force
Copy-Item (Join-Path $repoRoot "assets") (Join-Path $distRoot "assets") -Recurse -Force
Copy-Item (Join-Path $repoRoot "README.md") (Join-Path $distRoot "README.md") -Force

$guiLauncher = @'
@echo off
setlocal
for %%I in ("%~dp0.") do set "APP_HOME=%%~fI"
pushd "%APP_HOME%" >nul
"%APP_HOME%\runtime\bin\javaw.exe" -Duser.dir="%APP_HOME%" -jar "%APP_HOME%\app\3D-Render-Physics.jar" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul
exit /b %EXIT_CODE%
'@

$consoleLauncher = @'
@echo off
setlocal
for %%I in ("%~dp0.") do set "APP_HOME=%%~fI"
pushd "%APP_HOME%" >nul
"%APP_HOME%\runtime\bin\java.exe" -Duser.dir="%APP_HOME%" -jar "%APP_HOME%\app\3D-Render-Physics.jar" %*
set "EXIT_CODE=%ERRORLEVEL%"
popd >nul
exit /b %EXIT_CODE%
'@

Set-Content -Path (Join-Path $distRoot "3D-Render-Physics.bat") -Value $guiLauncher -Encoding ASCII
Set-Content -Path (Join-Path $distRoot "3D-Render-Physics-console.bat") -Value $consoleLauncher -Encoding ASCII

Write-Host "Ověřuji obsah portable bundle..."
Assert-Exists (Join-Path $distRoot "app\3D-Render-Physics.jar")
Assert-Exists (Join-Path $distRoot "runtime\bin\java.exe")
Assert-Exists (Join-Path $distRoot "runtime\bin\javaw.exe")
Assert-Exists (Join-Path $distRoot "assets\icons\IcoUni.png")
Assert-Exists (Join-Path $distRoot "assets\icons\IcoUni.ico")
Assert-Exists (Join-Path $distRoot "assets\models\cube.obj")
Assert-Exists (Join-Path $distRoot "assets\models\StartModel.glb")

try {
    Write-Host "Spouštím packaged help smoke test..."
    & (Join-Path $distRoot "3D-Render-Physics-console.bat") "--help"
    if ($LASTEXITCODE -ne 0) {
        throw "Packaged help smoke test selhal."
    }

    Write-Host "Spouštím packaged asset smoke test..."
    & (Join-Path $distRoot "3D-Render-Physics-console.bat") "--package-smoke"
    if ($LASTEXITCODE -ne 0) {
        throw "Packaged asset smoke test selhal."
    }
} catch {
    Remove-PathIfExists $distRoot
    Remove-PathIfExists $zipPath
    throw
}

Write-Host "Vytvářím ZIP archive portable bundle..."
Compress-Archive -Path $distRoot -DestinationPath $zipPath -Force

Write-Host "Portable bundle je hotový:"
Write-Host "  Folder: $distRoot"
Write-Host "  Zip:    $zipPath"
