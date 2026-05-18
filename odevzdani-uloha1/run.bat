@echo off
setlocal
set "ROOT=%~dp0"
if not exist "%ROOT%dist\uloha1.jar" call "%ROOT%build.bat"
java -jar "%ROOT%dist\uloha1.jar"
