@echo off
setlocal
set BOX_IP=192.168.123.98
echo Connecting to Phicomm T1 (%BOX_IP%:5555)...
adb.exe connect %BOX_IP%:5555

echo Setting 4K 60Hz 10bit...
echo 31183118 > _cmd.txt
echo dumpsys system_control -b set ubootenv.var.is.bestmode false >> _cmd.txt
echo dumpsys system_control -b set ubootenv.var.outputmode 2160p60hz420 >> _cmd.txt
echo dumpsys system_control -b set ubootenv.var.hdmimode 2160p60hz420 >> _cmd.txt
echo dumpsys system_control -b set ubootenv.var.colorattribute 420,10bit >> _cmd.txt
echo dumpsys system_control -b set ubootenv.var.2160p60hz420_deepcolor 420,10bit >> _cmd.txt
echo stop system_control >> _cmd.txt
echo start system_control >> _cmd.txt
echo exit >> _cmd.txt

adb.exe shell su < _cmd.txt
del _cmd.txt

echo.
echo Verification:
echo 31183118 > _cmd.txt
echo cat /sys/class/amhdmitx/amhdmitx0/attr >> _cmd.txt
echo cat /sys/class/display/mode >> _cmd.txt
echo exit >> _cmd.txt
adb.exe shell su < _cmd.txt
del _cmd.txt

echo.
echo [Done] 4K 60Hz 10bit locked!
pause
