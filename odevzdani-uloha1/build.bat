@echo off
setlocal
set "ROOT=%~dp0"
set "OUT=%ROOT%out"
set "DIST=%ROOT%dist"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%" 2>nul
mkdir "%DIST%" 2>nul
javac --release 17 -encoding UTF-8 -d "%OUT%" "%ROOT%src\*.java"
if errorlevel 1 exit /b %errorlevel%
jar --create --file "%DIST%\uloha1.jar" --main-class Main -C "%OUT%" .
echo Hotovo: %DIST%\uloha1.jar
