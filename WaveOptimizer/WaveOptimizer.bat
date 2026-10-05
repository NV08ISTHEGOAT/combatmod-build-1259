@echo off
rem WaveOptimizer launcher - the script asks for admin rights itself.
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%~dp0WaveOptimizer.ps1"
