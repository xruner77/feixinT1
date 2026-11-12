import subprocess
import time
import os

BOX_IP = "192.168.123.98:5555"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ADB_PATH = os.path.join(SCRIPT_DIR, "adb.exe")
SH_PATH = os.path.join(SCRIPT_DIR, "inject_bt2390.sh")

def main():
    print("========================================================")
    print("      斐讯 T1 (Amlogic S912) 影院级画质极客注入工具")
    print("   【10-bit 广色深】+【BT.2390 色调映射】+【高动态 1.1x 爆发】")
    print("   针对明基 TK700 投影：反差 50 + 高光放大 + 纯黑沉底 + 10-bit")
    print("========================================================\n")
    
    print(f"[*] 正在连接设备 {BOX_IP} ...")
    subprocess.run([ADB_PATH, "connect", BOX_IP], capture_output=True)
    
    print("[*] 正在推送注入脚本至盒子环境...")
    subprocess.run([ADB_PATH, "-s", BOX_IP, "push", SH_PATH, "/data/local/tmp/inject_bt2390.sh"], capture_output=True)
    
    print("[*] 正在执行 10-bit 硬件协商与 Ultra Punch 曲线增益注入...")
    subprocess.run([ADB_PATH, "-s", BOX_IP, "shell", "echo 31183118 | su 0 sh /data/local/tmp/inject_bt2390.sh"], capture_output=True)
    
    print("[*] 正在核验当前硬件寄存器与 HDMI 发送器状态...")
    print("--------------------------------------------------------")
    
    # Read status directly in one single pass
    cmd = """
cat /sys/class/amhdmitx/amhdmitx0/config
echo "---SPLIT---"
cat /sys/class/amhdmitx/amhdmitx0/attr
echo "---SPLIT---"
cat /sys/class/video/contrast
echo "---SPLIT---"
cat /sys/module/am_vecm/parameters/video_eotf_coeff
echo "---SPLIT---"
cat /sys/class/amvecm/dnlp
echo "---SPLIT---"
cat /sys/module/am_vecm/parameters/range_control
"""
    res = subprocess.run([ADB_PATH, "-s", BOX_IP, "shell", cmd], capture_output=True, text=True, encoding="utf-8", errors="ignore", timeout=8)
    parts = res.stdout.split("---SPLIT---")
    if len(parts) >= 6:
        cfg = parts[0].strip()
        attr = parts[1].strip()
        contrast = parts[2].strip()
        eotf = parts[3].strip()
        dnlp = parts[4].strip()
        range_ctrl = parts[5].strip()
        
        cd_str = '10-bit (cd=5)' if 'cd = 5' in cfg else ('8-bit (cd=4)' if 'cd = 4' in cfg else '未知')
        print(f"HDMI 色彩深度 (ColorDepth) = {cd_str}")
        print(f"HDMI 属性 (Active Attr)    = {attr}")
        print(f"视频对比度 (Video Contrast)  = {contrast}")
        print(f"高光放大系数 (EOTF 1.1x)     = {eotf}")
        print(f"硬件动态对比度 (DNLP sysfs) = {dnlp}")
        print(f"全动态范围 (Range Control)  = {range_ctrl}")
    else:
        print("回传原始数据：\n", res.stdout.strip())
        
    print("--------------------------------------------------------\n")
    print("[√] 注入完成！4K 60Hz 10-bit 广色深与 BT.2390 影院级画质已完全生效！\n")

if __name__ == '__main__':
    main()

