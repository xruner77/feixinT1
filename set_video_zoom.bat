@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
set BOX_IP=192.168.123.98

echo [1/2] 正在连接斐讯 T1 (%BOX_IP%:5555)...
adb.exe connect %BOX_IP%:5555 >nul 2>&1

:MENU
cls
echo ========================================================
echo        斐讯 T1 (S912) 晶晨底层硬件视频缩放控制工具
echo ========================================================
echo 说明: 无需关闭 Kodi MediaCodec(Surface) 硬解，0 性能损耗
echo --------------------------------------------------------

:: 读取当前状态
echo 31183118 > _status.txt
echo echo "ZOOM:" `cat /sys/class/video/zoom` >> _status.txt
echo echo "MODE:" `cat /sys/class/video/screen_mode` >> _status.txt
echo echo "CROP:" `cat /sys/class/video/crop` >> _status.txt
echo exit >> _status.txt
echo [当前硬件状态]:
adb.exe shell su < _status.txt 2>nul | findstr /i "ZOOM MODE CROP"
del _status.txt 2>nul

echo --------------------------------------------------------
echo  [1] 放大 115%% (微调裁切黑边)
echo  [2] 放大 125%% (2.35:1 电影铺满 16:9 投影幕布 - 推荐)
echo  [3] 放大 133%% (超宽画幅完全铺满)
echo  [4] 全屏强制拉伸 (screen_mode 1: 强行撑满屏幕)
echo  [5] 智能非线性拉伸 (screen_mode 4: 中间保真、两侧拉伸)
echo  [6] 恢复原始状态 (100%% 原始比例，清除所有裁切)
echo  [7] 自定义缩放比例 (输入 100 ~ 300)
echo  [8] 自定义像素裁切 (格式: 上 左 下 右，如 130 0 130 0)
echo  [0] 退出
echo ========================================================
set /p choice=请选择操作 [0-8]: 

if "%choice%"=="1" goto SET_ZOOM_115
if "%choice%"=="2" goto SET_ZOOM_125
if "%choice%"=="3" goto SET_ZOOM_133
if "%choice%"=="4" goto SET_STRETCH
if "%choice%"=="5" goto SET_SMART
if "%choice%"=="6" goto SET_RESET
if "%choice%"=="7" goto SET_CUSTOM_ZOOM
if "%choice%"=="8" goto SET_CUSTOM_CROP
if "%choice%"=="0" goto EXIT
goto MENU

:SET_ZOOM_115
set VAL=115
goto APPLY_ZOOM

:SET_ZOOM_125
set VAL=125
goto APPLY_ZOOM

:SET_ZOOM_133
set VAL=133
goto APPLY_ZOOM

:SET_CUSTOM_ZOOM
set /p VAL=请输入缩放百分比 (100-300): 
if "%VAL%"=="" goto MENU
goto APPLY_ZOOM

:APPLY_ZOOM
echo 正在设置硬件缩放为 %VAL%%%...
echo 31183118 > _cmd.txt
echo echo %VAL% ^> /sys/class/video/zoom >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su 1013:1000 < _cmd.txt >nul 2>&1
del _cmd.txt 2>nul
echo [成功] 画面缩放已设为 %VAL%%%！
timeout /t 2 >nul
goto MENU

:SET_STRETCH
echo 正在切换为全屏强制拉伸...
echo 31183118 > _cmd.txt
echo echo 1 ^> /sys/class/video/screen_mode >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su 0 < _cmd.txt >nul 2>&1
del _cmd.txt 2>nul
echo [成功] 已切换为全屏拉伸！
timeout /t 2 >nul
goto MENU

:SET_SMART
echo 正在切换为智能非线性拉伸...
echo 31183118 > _cmd.txt
echo echo 4 ^> /sys/class/video/screen_mode >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su 0 < _cmd.txt >nul 2>&1
del _cmd.txt 2>nul
echo [成功] 已切换为智能非线性拉伸！
timeout /t 2 >nul
goto MENU

:SET_RESET
echo 正在恢复默认状态...
echo 31183118 > _cmd.txt
echo echo 100 ^> /sys/class/video/zoom >> _cmd.txt
echo echo 0 ^> /sys/class/video/screen_mode >> _cmd.txt
echo echo 0 0 0 0 ^> /sys/class/video/crop >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su 1013:1000 < _cmd.txt >nul 2>&1
adb.exe shell "echo 31183118 | su 0 sh -c 'echo 0 > /sys/class/video/screen_mode; echo 0 0 0 0 > /sys/class/video/crop'" >nul 2>&1
del _cmd.txt 2>nul
echo [成功] 已全部恢复为原始比例！
timeout /t 2 >nul
goto MENU

:SET_CUSTOM_CROP
set /p CROP_VAL=请输入裁切像素 (上 左 下 右，例如 130 0 130 0): 
if "%CROP_VAL%"=="" goto MENU
echo 正在设置黑边裁切...
echo 31183118 > _cmd.txt
echo echo %CROP_VAL% ^> /sys/class/video/crop >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su 0 < _cmd.txt >nul 2>&1
del _cmd.txt 2>nul
echo [成功] 裁切参数已设置！
timeout /t 2 >nul
goto MENU

:EXIT
echo 退出程序。
exit /b 0
