@echo off
title Phicomm T1 Environment Doctor
setlocal enabledelayedexpansion

echo ========================================================
echo       Phicomm T1 Video Zoom & Environment Doctor
echo ========================================================
echo.

if exist T1_Doctor.exe (
    T1_Doctor.exe
) else (
    python check_client_env.py
)

pause
