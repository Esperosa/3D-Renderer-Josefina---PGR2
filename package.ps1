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

function Get-IExpressTool {
    $candidate = Join-Path $env:WINDIR "System32\iexpress.exe"
    if (Test-Path $candidate) {
        return $candidate
    }
    throw "IExpress nebyl nalezen v System32. Bez něj tady nevytvořím Windows installer."
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

function Write-AppLaunchers {
    param(
        [Parameter(Mandatory = $true)]
        [string] $AppRoot
    )

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

    Set-Content -Path (Join-Path $AppRoot "3D-Render-Physics.bat") -Value $guiLauncher -Encoding ASCII
    Set-Content -Path (Join-Path $AppRoot "3D-Render-Physics-console.bat") -Value $consoleLauncher -Encoding ASCII
}

function Write-InstallerLauncher {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue,
        [Parameter(Mandatory = $true)]
        [string] $VersionValue
    )

    $launcher = @"
@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Version "$VersionValue" %*
exit /b %ERRORLEVEL%
"@
    Set-Content -Path $PathValue -Value $launcher -Encoding ASCII
}

function Write-IExpressSed {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue,
        [Parameter(Mandatory = $true)]
        [string] $InstallerExePath,
        [Parameter(Mandatory = $true)]
        [string] $InstallerStagingRoot
    )

    $sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=1
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=1
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$InstallerExePath
FriendlyName=3D-Render-Physics Installer
AppLaunched=install.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=install.cmd -Silent
UserQuietInstCmd=install.cmd -Silent
FILE0="3D-Render-Physics-payload.zip"
FILE1="install.ps1"
FILE2="uninstall.ps1"
FILE3="install.cmd"
[SourceFiles]
SourceFiles0=$InstallerStagingRoot
[SourceFiles0]
%FILE0%=
%FILE1%=
%FILE2%=
%FILE3%=
"@
    Set-Content -Path $PathValue -Value $sed -Encoding ASCII
}

function Remove-IExpressScratchFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PackageRoot,
        [Parameter(Mandatory = $true)]
        [string] $InstallerExePath
    )

    $scratchPrefix = "~" + [System.IO.Path]::GetFileNameWithoutExtension($InstallerExePath)
    Get-ChildItem -Path $PackageRoot -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Name.StartsWith($scratchPrefix, [System.StringComparison]::OrdinalIgnoreCase) } |
            ForEach-Object { Remove-Item -Recurse -Force $_.FullName }
}

function New-AppPayload {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PayloadRoot,
        [Parameter(Mandatory = $true)]
        [string] $JarPath,
        [Parameter(Mandatory = $true)]
        [string] $RuntimeRoot,
        [Parameter(Mandatory = $true)]
        [string] $RepoRoot
    )

    New-Item -ItemType Directory -Path $PayloadRoot -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $PayloadRoot "app") -Force | Out-Null
    Copy-Item $JarPath (Join-Path $PayloadRoot "app") -Force
    Copy-Item $RuntimeRoot (Join-Path $PayloadRoot "runtime") -Recurse -Force
    Copy-Item (Join-Path $RepoRoot "assets") (Join-Path $PayloadRoot "assets") -Recurse -Force
    Copy-Item (Join-Path $RepoRoot "README.md") (Join-Path $PayloadRoot "README.md") -Force
    Write-AppLaunchers -AppRoot $PayloadRoot
}

function Invoke-PayloadSmokeTests {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PayloadRoot
    )

    Write-Host "Spouštím payload help smoke test..."
    & (Join-Path $PayloadRoot "3D-Render-Physics-console.bat") "--help"
    if ($LASTEXITCODE -ne 0) {
        throw "Payload help smoke test selhal."
    }

    Write-Host "Spouštím payload asset smoke test..."
    & (Join-Path $PayloadRoot "3D-Render-Physics-console.bat") "--package-smoke"
    if ($LASTEXITCODE -ne 0) {
        throw "Payload asset smoke test selhal."
    }
}

function Wait-UntilMissing {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue,
        [int] $Attempts = 40,
        [int] $DelayMs = 250
    )

    for ($i = 0; $i -lt $Attempts; $i++) {
        if (-not (Test-Path $PathValue)) {
            return
        }
        Start-Sleep -Milliseconds $DelayMs
    }

    throw "Cesta pořád existuje i po čekání: $PathValue"
}

function Invoke-InstallerSmokeTest {
    param(
        [Parameter(Mandatory = $true)]
        [string] $InstallerExePath,
        [Parameter(Mandatory = $true)]
        [string] $PackageRoot
    )

    $smokeRoot = Join-Path $PackageRoot "installer smoke"
    $installDir = Join-Path $smokeRoot "Classic Program"
    $desktopDir = Join-Path $smokeRoot "Desktop Shortcuts"
    $startMenuDir = Join-Path $smokeRoot "Start Menu"
    $uninstallKeyName = "3D-Render-Physics-Smoke"
    $registryPath = "Registry::HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Uninstall\$uninstallKeyName"
    $cmdExePath = Join-Path $env:SystemRoot "System32\cmd.exe"
    $envNames = @(
        "3D_RENDER_PHYSICS_INSTALL_DIR",
        "3D_RENDER_PHYSICS_DESKTOP_DIR",
        "3D_RENDER_PHYSICS_START_MENU_DIR",
        "3D_RENDER_PHYSICS_SKIP_LAUNCH",
        "3D_RENDER_PHYSICS_UNINSTALL_KEY_NAME"
    )
    $envBackup = @{}

    Remove-PathIfExists $smokeRoot
    New-Item -ItemType Directory -Path $desktopDir -Force | Out-Null
    New-Item -ItemType Directory -Path $startMenuDir -Force | Out-Null
    if (Test-Path $registryPath) {
        Remove-Item -Path $registryPath -Recurse -Force
    }

    foreach ($envName in $envNames) {
        $envBackup[$envName] = [Environment]::GetEnvironmentVariable($envName, "Process")
    }

    [Environment]::SetEnvironmentVariable("3D_RENDER_PHYSICS_INSTALL_DIR", $installDir, "Process")
    [Environment]::SetEnvironmentVariable("3D_RENDER_PHYSICS_DESKTOP_DIR", $desktopDir, "Process")
    [Environment]::SetEnvironmentVariable("3D_RENDER_PHYSICS_START_MENU_DIR", $startMenuDir, "Process")
    [Environment]::SetEnvironmentVariable("3D_RENDER_PHYSICS_SKIP_LAUNCH", "1", "Process")
    [Environment]::SetEnvironmentVariable("3D_RENDER_PHYSICS_UNINSTALL_KEY_NAME", $uninstallKeyName, "Process")

    try {
        Write-Host "Spouštím installer smoke test..."
        $installerProcess = Start-Process -FilePath $InstallerExePath -ArgumentList @("/Q") -Wait -PassThru
        if ($installerProcess.ExitCode -ne 0) {
            throw "Installer smoke test selhal už při spuštění installeru."
        }

        Assert-Exists (Join-Path $installDir "app\3D-Render-Physics.jar")
        Assert-Exists (Join-Path $installDir "runtime\bin\java.exe")
        Assert-Exists (Join-Path $installDir "assets\icons\IcoUni.ico")
        Assert-Exists (Join-Path $installDir "assets\models\StartModel.glb")
        Assert-Exists (Join-Path $desktopDir "3D-Render-Physics.lnk")
        Assert-Exists (Join-Path $startMenuDir "3D-Render-Physics\3D-Render-Physics.lnk")
        Assert-Exists (Join-Path $startMenuDir "3D-Render-Physics\Odinstalovat 3D-Render-Physics.lnk")
        Assert-Exists $registryPath
        $registryEntry = Get-ItemProperty -Path $registryPath
        $expectedUninstallLauncher = Join-Path $installDir "Uninstall 3D-Render-Physics.bat"
        $expectedUninstallString = '"' + $cmdExePath + '" /c "' + $expectedUninstallLauncher + '"'
        $expectedQuietUninstallString = $expectedUninstallString + " -Silent"
        if ($registryEntry.UninstallString -ne $expectedUninstallString) {
            throw "Installer zapsal špatný UninstallString do registru."
        }
        if ($registryEntry.QuietUninstallString -ne $expectedQuietUninstallString) {
            throw "Installer zapsal špatný QuietUninstallString do registru."
        }

        Write-Host "Spouštím installed asset smoke test..."
        & (Join-Path $installDir "3D-Render-Physics-console.bat") "--package-smoke"
        if ($LASTEXITCODE -ne 0) {
            throw "Instalovaná appka neprošla package smoke testem."
        }

        Write-Host "Spouštím uninstall smoke test..."
        & (Join-Path $installDir "Uninstall 3D-Render-Physics.bat") "-Silent"
        if ($LASTEXITCODE -ne 0) {
            throw "Odinstalace v installer smoke testu selhala."
        }

        Wait-UntilMissing -PathValue $installDir
        Wait-UntilMissing -PathValue (Join-Path $desktopDir "3D-Render-Physics.lnk")
        Wait-UntilMissing -PathValue (Join-Path $startMenuDir "3D-Render-Physics\3D-Render-Physics.lnk")
        Wait-UntilMissing -PathValue (Join-Path $startMenuDir "3D-Render-Physics\Odinstalovat 3D-Render-Physics.lnk")
        Wait-UntilMissing -PathValue $registryPath
    } finally {
        foreach ($envName in $envNames) {
            [Environment]::SetEnvironmentVariable($envName, $envBackup[$envName], "Process")
        }
        Remove-PathIfExists $smokeRoot
        if (Test-Path $registryPath) {
            Remove-Item -Path $registryPath -Recurse -Force
        }
    }
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$versionTag = Resolve-VersionTag -RepoRoot $repoRoot -RequestedVersion $Version
$safeVersion = $versionTag.Trim()
$installerBaseName = "3D-Render-Physics-$safeVersion-windows-installer.exe"

$buildRoot = Join-Path $repoRoot "build"
$classesRoot = Join-Path $buildRoot "classes"
$packageRoot = Join-Path $buildRoot "package"
$packageStagingRoot = Join-Path $packageRoot "staging"
$runtimeRoot = Join-Path $packageStagingRoot "runtime"
$payloadRoot = Join-Path $packageStagingRoot "app-payload"
$appRoot = Join-Path $payloadRoot "3D-Render-Physics"
$jarPath = Join-Path $packageStagingRoot "3D-Render-Physics.jar"
$installerStagingRoot = Join-Path $packageRoot "installer-staging"
$installerPayloadZipPath = Join-Path $installerStagingRoot "3D-Render-Physics-payload.zip"
$installerScriptPath = Join-Path $installerStagingRoot "install.ps1"
$uninstallerScriptPath = Join-Path $installerStagingRoot "uninstall.ps1"
$installerCmdPath = Join-Path $installerStagingRoot "install.cmd"
$installerSedPath = Join-Path $installerStagingRoot "package.sed"
$installerExePath = Join-Path $packageRoot $installerBaseName

$javac = Get-JavaTool "javac"
$jar = Get-JavaTool "jar"
$jdeps = Get-JavaTool "jdeps"
$jlink = Get-JavaTool "jlink"
$iExpress = Get-IExpressTool

Remove-PathIfExists $packageRoot
New-Item -ItemType Directory -Path $packageRoot -Force | Out-Null
New-Item -ItemType Directory -Path $packageStagingRoot -Force | Out-Null
New-Item -ItemType Directory -Path $installerStagingRoot -Force | Out-Null
Remove-IExpressScratchFiles -PackageRoot $packageRoot -InstallerExePath $installerExePath

$srcFiles = Get-ChildItem -Path (Join-Path $repoRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if ($srcFiles.Count -eq 0) {
    throw "Ve složce src nebyly nalezeny žádné Java zdrojáky."
}

Write-Host "Kompiluji hlavní zdrojáky pro offline installer..."
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
& $jlink "--add-modules" $moduleDeps "--strip-debug" "--no-header-files" "--no-man-pages" "--compress=2" "--output" $runtimeRoot
if ($LASTEXITCODE -ne 0) {
    throw "Vytvoření runtime image přes jlink selhalo."
}

Write-Host "Připravuji interní payload pro instalačku..."
New-AppPayload -PayloadRoot $appRoot -JarPath $jarPath -RuntimeRoot $runtimeRoot -RepoRoot $repoRoot

Write-Host "Ověřuji interní payload..."
Assert-Exists (Join-Path $appRoot "app\3D-Render-Physics.jar")
Assert-Exists (Join-Path $appRoot "runtime\bin\java.exe")
Assert-Exists (Join-Path $appRoot "runtime\bin\javaw.exe")
Assert-Exists (Join-Path $appRoot "assets\icons\IcoUni.png")
Assert-Exists (Join-Path $appRoot "assets\icons\IcoUni.ico")
Assert-Exists (Join-Path $appRoot "assets\models\cube.obj")
Assert-Exists (Join-Path $appRoot "assets\models\StartModel.glb")

try {
    Invoke-PayloadSmokeTests -PayloadRoot $appRoot

    Write-Host "Baluji payload pro one-file installer..."
    Compress-Archive -Path $appRoot -DestinationPath $installerPayloadZipPath -Force

    Copy-Item (Join-Path $repoRoot "installer\install.ps1") $installerScriptPath -Force
    Copy-Item (Join-Path $repoRoot "installer\uninstall.ps1") $uninstallerScriptPath -Force
    Write-InstallerLauncher -PathValue $installerCmdPath -VersionValue $safeVersion
    Write-IExpressSed -PathValue $installerSedPath -InstallerExePath $installerExePath -InstallerStagingRoot $installerStagingRoot

    Write-Host "Vytvářím one-file Windows installer..."
    $iExpressProcess = Start-Process -FilePath $iExpress -ArgumentList @("/N", "/Q", "/M", $installerSedPath) -Wait -PassThru
    if ($iExpressProcess.ExitCode -ne 0) {
        throw "IExpress selhal při tvorbě installeru."
    }
    Assert-Exists $installerExePath

    Invoke-InstallerSmokeTest -InstallerExePath $installerExePath -PackageRoot $packageRoot
    Remove-IExpressScratchFiles -PackageRoot $packageRoot -InstallerExePath $installerExePath
    Remove-PathIfExists $packageStagingRoot
    Remove-PathIfExists $installerStagingRoot
} catch {
    Remove-PathIfExists $installerExePath
    Remove-PathIfExists $packageStagingRoot
    Remove-PathIfExists $installerStagingRoot
    Remove-IExpressScratchFiles -PackageRoot $packageRoot -InstallerExePath $installerExePath
    throw
}

Write-Host "One-file offline installer je hotový:"
Write-Host "  Installer exe: $installerExePath"
