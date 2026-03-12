param(
    [string] $Version = "dev",
    [switch] $Silent
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$appName = "3D-Render-Physics"
$appPublisher = "Esperosa"
$installerRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$payloadZip = Join-Path $installerRoot "3D-Render-Physics-payload.zip"
$defaultInstallDir = Join-Path $env:LOCALAPPDATA "Programs\$appName"
$installDir = if ([string]::IsNullOrWhiteSpace($env:3D_RENDER_PHYSICS_INSTALL_DIR)) {
    $defaultInstallDir
} else {
    $env:3D_RENDER_PHYSICS_INSTALL_DIR.Trim()
}
$desktopRoot = if ([string]::IsNullOrWhiteSpace($env:3D_RENDER_PHYSICS_DESKTOP_DIR)) {
    [Environment]::GetFolderPath("Desktop")
} else {
    $env:3D_RENDER_PHYSICS_DESKTOP_DIR.Trim()
}
$startMenuProgramsRoot = if ([string]::IsNullOrWhiteSpace($env:3D_RENDER_PHYSICS_START_MENU_DIR)) {
    [Environment]::GetFolderPath("Programs")
} else {
    $env:3D_RENDER_PHYSICS_START_MENU_DIR.Trim()
}
$skipLaunch = $Silent.IsPresent -or $env:3D_RENDER_PHYSICS_SKIP_LAUNCH -eq "1"
$skipShortcuts = $env:3D_RENDER_PHYSICS_NO_SHORTCUTS -eq "1"
$skipRegistry = $env:3D_RENDER_PHYSICS_NO_REGISTRY -eq "1"
$tempExtractRoot = Join-Path $env:TEMP ($appName + "-install-" + [Guid]::NewGuid().ToString("N"))
$expandedPayloadRoot = Join-Path $tempExtractRoot "payload"
$startMenuFolder = Join-Path $startMenuProgramsRoot $appName
$desktopShortcutPath = Join-Path $desktopRoot ($appName + ".lnk")
$startMenuShortcutPath = Join-Path $startMenuFolder ($appName + ".lnk")
$uninstallShortcutPath = Join-Path $startMenuFolder ("Odinstalovat " + $appName + ".lnk")
$uninstallRegistryName = if ([string]::IsNullOrWhiteSpace($env:3D_RENDER_PHYSICS_UNINSTALL_KEY_NAME)) {
    $appName
} else {
    $env:3D_RENDER_PHYSICS_UNINSTALL_KEY_NAME.Trim()
}
$uninstallRegistryKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$uninstallRegistryName"

function Remove-PathIfExists {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue
    )

    if (Test-Path $PathValue) {
        Remove-Item -Recurse -Force $PathValue
    }
}

function New-Shortcut {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ShortcutPath,
        [Parameter(Mandatory = $true)]
        [string] $TargetPath,
        [Parameter(Mandatory = $true)]
        [string] $WorkingDirectory,
        [Parameter(Mandatory = $true)]
        [string] $IconPath,
        [string] $Arguments = ""
    )

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($ShortcutPath)
    $shortcut.TargetPath = $TargetPath
    $shortcut.WorkingDirectory = $WorkingDirectory
    $shortcut.IconLocation = $IconPath
    $shortcut.Arguments = $Arguments
    $shortcut.Save()
}

function Get-PayloadContentRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ExpandedRoot
    )

    $files = @(Get-ChildItem -Path $ExpandedRoot -Force)
    $dirs = @($files | Where-Object { $_.PSIsContainer })
    if ($files.Count -eq 1 -and $dirs.Count -eq 1) {
        return $dirs[0].FullName
    }
    return $ExpandedRoot
}

function Write-UninstallLauncher {
    param(
        [Parameter(Mandatory = $true)]
        [string] $InstallRoot
    )

    $launcher = @'
@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0installer\uninstall.ps1" %*
exit /b %ERRORLEVEL%
'@
    Set-Content -Path (Join-Path $InstallRoot "Uninstall 3D-Render-Physics.bat") -Value $launcher -Encoding ASCII
}

function Write-UninstallRegistry {
    param(
        [Parameter(Mandatory = $true)]
        [string] $InstallRoot,
        [Parameter(Mandatory = $true)]
        [string] $VersionValue
    )

    $uninstallLauncher = Join-Path $InstallRoot "Uninstall 3D-Render-Physics.bat"
    $cmdExePath = Join-Path $env:SystemRoot "System32\cmd.exe"
    $iconPath = Join-Path $InstallRoot "assets\icons\IcoUni.ico"
    $installSizeKb = [Math]::Ceiling(((Get-ChildItem -Path $InstallRoot -Recurse -File | Measure-Object -Property Length -Sum).Sum) / 1KB)
    $quotedUninstallLauncher = '"' + $uninstallLauncher + '"'
    $uninstallCommand = '"' + $cmdExePath + '" /c ' + $quotedUninstallLauncher
    $quietUninstallCommand = $uninstallCommand + " -Silent"

    New-Item -Path $uninstallRegistryKey -Force | Out-Null
    Set-ItemProperty -Path $uninstallRegistryKey -Name "DisplayName" -Value $appName
    Set-ItemProperty -Path $uninstallRegistryKey -Name "DisplayVersion" -Value $VersionValue
    Set-ItemProperty -Path $uninstallRegistryKey -Name "Publisher" -Value $appPublisher
    Set-ItemProperty -Path $uninstallRegistryKey -Name "InstallLocation" -Value $InstallRoot
    Set-ItemProperty -Path $uninstallRegistryKey -Name "DisplayIcon" -Value $iconPath
    Set-ItemProperty -Path $uninstallRegistryKey -Name "UninstallString" -Value $uninstallCommand
    Set-ItemProperty -Path $uninstallRegistryKey -Name "QuietUninstallString" -Value $quietUninstallCommand
    Set-ItemProperty -Path $uninstallRegistryKey -Name "EstimatedSize" -Value ([int] $installSizeKb) -Type DWord
    Set-ItemProperty -Path $uninstallRegistryKey -Name "NoModify" -Value 1 -Type DWord
    Set-ItemProperty -Path $uninstallRegistryKey -Name "NoRepair" -Value 1 -Type DWord
}

try {
    if (-not (Test-Path $payloadZip)) {
        throw "Chybí payload zip vedle install.ps1: $payloadZip"
    }

    Write-Host "Rozbaluji installer payload..."
    Remove-PathIfExists $tempExtractRoot
    New-Item -ItemType Directory -Path $expandedPayloadRoot -Force | Out-Null
    Expand-Archive -Path $payloadZip -DestinationPath $expandedPayloadRoot -Force
    $payloadContentRoot = Get-PayloadContentRoot -ExpandedRoot $expandedPayloadRoot

    Write-Host "Instaluji do $installDir ..."
    Remove-PathIfExists $installDir
    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
    Copy-Item (Join-Path $payloadContentRoot "*") $installDir -Recurse -Force

    New-Item -ItemType Directory -Path (Join-Path $installDir "installer") -Force | Out-Null
    Copy-Item (Join-Path $installerRoot "uninstall.ps1") (Join-Path $installDir "installer\uninstall.ps1") -Force
    Write-UninstallLauncher -InstallRoot $installDir

    if (-not $skipShortcuts) {
        Write-Host "Vytvářím zástupce..."
        New-Item -ItemType Directory -Path $desktopRoot -Force | Out-Null
        New-Item -ItemType Directory -Path $startMenuFolder -Force | Out-Null
        $appLauncher = Join-Path $installDir "3D-Render-Physics.bat"
        $uninstallLauncher = Join-Path $installDir "Uninstall 3D-Render-Physics.bat"
        $appIcon = Join-Path $installDir "assets\icons\IcoUni.ico"
        New-Shortcut -ShortcutPath $desktopShortcutPath -TargetPath $appLauncher -WorkingDirectory $installDir -IconPath $appIcon
        New-Shortcut -ShortcutPath $startMenuShortcutPath -TargetPath $appLauncher -WorkingDirectory $installDir -IconPath $appIcon
        New-Shortcut -ShortcutPath $uninstallShortcutPath -TargetPath $uninstallLauncher -WorkingDirectory $installDir -IconPath $appIcon
    }

    if (-not $skipRegistry) {
        Write-Host "Zapisuji odinstalaci do registru..."
        Write-UninstallRegistry -InstallRoot $installDir -VersionValue $Version
    }

    Write-Host "Instalace je hotová."
    if (-not $skipLaunch) {
        Start-Process -FilePath (Join-Path $installDir "3D-Render-Physics.bat") -WorkingDirectory $installDir
    }
} finally {
    Remove-PathIfExists $tempExtractRoot
}
