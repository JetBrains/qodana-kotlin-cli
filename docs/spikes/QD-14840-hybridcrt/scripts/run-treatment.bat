@echo off
set PATH=C:\Windows\System32;C:\Windows;C:\Windows\System32\Wbem
"%~dp0qodana-cli.exe" --help
echo TREATMENT_EXITCODE=%errorlevel%
