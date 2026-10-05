@echo off
chcp 65001 >nul
title 斐讯 T1 全固件自适应 Seccomp 硬解安全热补丁工具
setlocal enabledelayedexpansion

echo ========================================================
echo       斐讯 T1 全固件自适应 Seccomp 硬解安全热补丁工具
echo ========================================================
echo 特性: 
echo   1. 动态自适应解析当前固件 Inode 与物理块号 (跨固件通用)
echo   2. 写入前指纹双向比对 (非目标文件绝不写入，永不变砖)
echo   3. 严格等长微创补丁 (不破坏 Ext4 文件系统元数据)
echo --------------------------------------------------------

if exist "T1_Seccomp_Patcher.exe" (
    T1_Seccomp_Patcher.exe
) else (
    python safe_adaptive_patch.py
)

pause
