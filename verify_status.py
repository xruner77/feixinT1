import subprocess

def verify():
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
    subprocess.run(["d:\\tools\\adb\\adb.exe", "connect", "192.168.123.98:5555"], capture_output=True)
    res = subprocess.run(["d:\\tools\\adb\\adb.exe", "-s", "192.168.123.98:5555", "shell", cmd],
                         capture_output=True, text=True, encoding="utf-8", errors="ignore", timeout=5)
    
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
        print(f"高光放大系数 (EOTF)          = {eotf}")
        print(f"硬件动态对比度 (DNLP sysfs) = {dnlp}")
        print(f"全动态范围 (Range Control)  = {range_ctrl}")
    else:
        print("核验数据回传异常：\n", res.stdout)

if __name__ == '__main__':
    verify()
