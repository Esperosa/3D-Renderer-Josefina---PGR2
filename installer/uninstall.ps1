param(
    [switch] $Silent
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$installRoot = Split-Path -Parent $scriptDir
$appName = "3D-Render-Physics"
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

function Remove-ItemIfExists {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue
    )

    if (Test-Path $PathValue) {
        Remove-Item -Force $PathValue
    }
}

function Remove-DirectoryIfEmpty {
    param(
        [Parameter(Mandatory = $true)]
        [string] $PathValue
    )

    if ((Test-Path $PathValue) -and (@(Get-ChildItem -Path $PathValue -Force).Count -eq 0)) {
        Remove-Item -Force $PathValue
    }
}

try {
    Write-Host "Odinstaluji $appName ..."
    Remove-ItemIfExists $desktopShortcutPath
    Remove-ItemIfExists $startMenuShortcutPath
    Remove-ItemIfExists $uninstallShortcutPath
    Remove-DirectoryIfEmpty $startMenuFolder

    if (Test-Path $uninstallRegistryKey) {
        Remove-Item -Path $uninstallRegistryKey -Recurse -Force
    }
} finally {
    $cleanupScriptPath = Join-Path $env:TEMP ($appName + "-cleanup-" + [Guid]::NewGuid().ToString("N") + ".cmd")
    $cleanupLines = @(
        "@echo off",
        "setlocal",
        "set tries=0",
        ":retry",
        "rmdir /s /q ""$installRoot"" >nul 2>nul",
        "if exist ""$installRoot"" (",
        "  set /a tries+=1 >nul",
        "  if %tries% GEQ 90 goto end",
        "  ping 127.0.0.1 -n 2 >nul",
        "  goto retry",
        ")",
        ":end",
        "del /f /q ""%~f0"" >nul 2>nul"
    )
    Set-Content -Path $cleanupScriptPath -Value $cleanupLines -Encoding ASCII
    Start-Process -FilePath $cleanupScriptPath -WindowStyle Hidden
}

if (-not $Silent.IsPresent) {
    Write-Host "Odinstalace je hotová."
}
