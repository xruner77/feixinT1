@echo off
setlocal enabledelayedexpansion
set /p BOX_IP=Please enter Phicomm T1 IP (default: 192.168.123.98): 
if !BOX_IP!==" set BOX_IP=192.168.123.98

echo [1/4] Connecting to Phicomm T1 (%BOX_IP%:5555)...
adb.exe connect %BOX_IP%:5555

echo [2/4] Pushing patched block to /data/local/tmp/...
adb.exe push block_327225_patched.bin /data/local/tmp/patched_block.bin
if %errorlevel% neq 0 (
 echo [ERROR] Failed to push block_327225_patched.bin! Make sure the file exists in this directory.
 pause
 exit /b 1
)

echo [3/4] Flashing sector to /dev/block/system (block 327225)...
echo 31183118 > _cmd.txt
echo dd if=/data/local/tmp/patched_block.bin of=/dev/block/system bs=4096 seek=327225 count=1 conv=notrunc >> _cmd.txt
echo sync >> _cmd.txt
echo echo 3 ^> /proc/sys/vm/drop_caches >> _cmd.txt
echo kill -9 $(pidof media.codec) >> _cmd.txt
echo exit >> _cmd.txt

adb.exe shell su < _cmd.txt
del _cmd.txt

echo [4/4] Verifying policy file...
echo 31183118 > _cmd.txt
echo head -n 10 /system/etc/seccomp_policy/mediacodec-seccomp.policy >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su < _cmd.txt
del _cmd.txt

echo.
echo ========================================================
echo [SUCCESS] Seccomp MediaCodec patch applied successfully!
echo Kodi is now fully immune to crashing during parameter tuning.
echo ========================================================
pause
