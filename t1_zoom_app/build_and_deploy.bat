@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================================
echo    斐讯 T1 Zoom Helper APK 构建与部署工具
echo ============================================================

set TOOLS=d:\tools\adb\build_tools
set SRC=d:\tools\adb\t1_zoom_app
set OUT=%SRC%\bin
set BOX_IP=192.168.123.98

:: Clean previous build
echo [1/7] 清理旧构建...
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
mkdir "%OUT%\compiled"

:: Step 1: Compile resources with aapt2
echo [2/7] 编译资源文件 (aapt2 compile)...
for /R "%SRC%\res" %%f in (*.*) do (
    "%TOOLS%\aapt2.exe" compile "%%f" -o "%OUT%\compiled" 2>nul
)

:: Step 2: Link resources with aapt2
echo [3/7] 链接资源与 Manifest (aapt2 link)...
set FLAT_FILES=
for %%f in ("%OUT%\compiled\*.flat") do set FLAT_FILES=!FLAT_FILES! "%%f"
"%TOOLS%\aapt2.exe" link %FLAT_FILES% -I "%TOOLS%\android.jar" --manifest "%SRC%\AndroidManifest.xml" -o "%OUT%\res.apk" --java "%OUT%" --auto-add-overlay
if errorlevel 1 (
    echo [ERROR] aapt2 link 失败！
    pause
    exit /b 1
)

:: Step 3: Compile Java sources
echo [4/7] 编译 Java 源码 (javac)...
:: Use generated R.java from aapt2
dir /s /b "%OUT%\com\phicomm\t1zoom\R.java" > nul 2>&1
if errorlevel 1 (
    echo [WARN] aapt2 未生成 R.java，使用手工 R.java
    copy /Y "%SRC%\src\com\phicomm\t1zoom\R.java" "%OUT%\com\phicomm\t1zoom\R.java" >nul
)

mkdir "%OUT%\classes" 2>nul
javac -source 1.8 -target 1.8 -bootclasspath "%TOOLS%\android.jar" -classpath "%TOOLS%\android.jar" -d "%OUT%\classes" "%SRC%\src\com\phicomm\t1zoom\AdbClient.java" "%SRC%\src\com\phicomm\t1zoom\BootReceiver.java" "%SRC%\src\com\phicomm\t1zoom\ZoomService.java" "%SRC%\src\com\phicomm\t1zoom\MainActivity.java" "%OUT%\com\phicomm\t1zoom\R.java"
if errorlevel 1 (
    echo [ERROR] javac 编译失败！
    pause
    exit /b 1
)

:: Step 4: Convert to DEX via d8 (bundled inside r8.jar)
echo [5/7] 转换 DEX (d8)...
java -cp "%TOOLS%\r8.jar" com.android.tools.r8.D8 --lib "%TOOLS%\android.jar" --min-api 16 --output "%OUT%" "%OUT%\classes\com\phicomm\t1zoom\AdbClient.class" "%OUT%\classes\com\phicomm\t1zoom\BootReceiver.class" "%OUT%\classes\com\phicomm\t1zoom\ZoomService.class" "%OUT%\classes\com\phicomm\t1zoom\ZoomService$1.class" "%OUT%\classes\com\phicomm\t1zoom\ZoomService$2.class" "%OUT%\classes\com\phicomm\t1zoom\ZoomService$3.class" "%OUT%\classes\com\phicomm\t1zoom\ZoomService$4.class" "%OUT%\classes\com\phicomm\t1zoom\ZoomService$5.class" "%OUT%\classes\com\phicomm\t1zoom\MainActivity.class" "%OUT%\classes\com\phicomm\t1zoom\MainActivity$1.class" "%OUT%\classes\com\phicomm\t1zoom\MainActivity$2.class" "%OUT%\classes\com\phicomm\t1zoom\MainActivity$3.class" "%OUT%\classes\com\phicomm\t1zoom\R.class" "%OUT%\classes\com\phicomm\t1zoom\R$id.class" "%OUT%\classes\com\phicomm\t1zoom\R$layout.class" "%OUT%\classes\com\phicomm\t1zoom\R$string.class"
if errorlevel 1 (
    echo [ERROR] d8 DEX 转换失败！
    pause
    exit /b 1
)

:: Step 5: Package APK
echo [6/7] 打包 APK...
:: Extract res.apk contents and add classes.dex
copy /Y "%OUT%\res.apk" "%SRC%\unaligned.apk" >nul
cd /d "%OUT%"
jar uf "%SRC%\unaligned.apk" classes.dex
cd /d "%SRC%"

:: Step 6: Sign APK with jarsigner (v1 signing)
echo [7/7] 签名 APK (jarsigner)...
jarsigner -keystore "%TOOLS%\debug.keystore" -storepass android -keypass android -sigalg SHA1withRSA -digestalg SHA1 "%SRC%\unaligned.apk" androiddebugkey
if errorlevel 1 (
    echo [ERROR] APK 签名失败！
    pause
    exit /b 1
)

:: Rename final APK
copy /Y "%SRC%\unaligned.apk" "d:\tools\adb\T1ZoomHelper.apk" >nul

echo.
echo ============================================================
echo    构建完成: d:\tools\adb\T1ZoomHelper.apk
echo ============================================================

:: Deploy
echo.
echo 正在部署到斐讯 T1 (%BOX_IP%:5555)...
d:\tools\adb\adb.exe connect %BOX_IP%:5555
d:\tools\adb\adb.exe install -r "d:\tools\adb\T1ZoomHelper.apk"
if errorlevel 1 (
    echo [ERROR] APK 安装失败！
    pause
    exit /b 1
)

:: Launch MainActivity to clear stopped state
echo 正在首次启动 (清除 stopped 状态)...
d:\tools\adb\adb.exe shell am start -n com.phicomm.t1zoom/.MainActivity
timeout /t 2 >nul

:: Verify
echo 正在验证服务状态...
d:\tools\adb\adb.exe shell "ps | grep t1zoom"
d:\tools\adb\adb.exe shell "dumpsys package com.phicomm.t1zoom | grep stopped"

echo.
echo ============================================================
echo    部署完成！服务已自动启动。
echo    遥控器重启或待机唤醒后将自动恢复服务。
echo ============================================================
pause
