@echo off
chcp 65001 >nul
title 斐讯 T1 变焦与系统环境一键诊断工具
setlocal enabledelayedexpansion

echo ========================================================
echo       斐讯 T1 变焦(Zoom)与系统环境一键诊断工具
echo ========================================================
echo.
echo 本工具将自动连接盒子，深度体检以下核心项目：
echo   1. Root 提权通道与 ADB 状态
echo   2. Seccomp 硬解补丁生效状态
echo   3. 晶晨底层 /sys/class/video/zoom 节点读写回验
echo   4. 当前底层硬件视频流活跃状态（排查是否桌面测试）
echo   5. Kodi 运行状态与远程控制
echo --------------------------------------------------------
echo.

if exist T1_Doctor.exe (
    T1_Doctor.exe
) else (
    python check_client_env.py
)

pause
