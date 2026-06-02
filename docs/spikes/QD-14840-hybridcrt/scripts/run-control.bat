@echo off
set PATH=C:\Windows\System32;C:\Windows;C:\Windows\System32\Wbem
"%~dp0control-helloworld.exe"
echo CONTROL_EXITCODE=%errorlevel%
