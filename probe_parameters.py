import inspect_hdr

def test_sysfs(name, path, test_val=None):
    cur = inspect_hdr.run_adb(f"cat {path} 2>/dev/null", timeout=2)
    print(f"{name} ({path}): cur = {cur}")
    if test_val is not None:
        inspect_hdr.run_adb(f"echo {test_val} > {path}", timeout=2)
        after = inspect_hdr.run_adb(f"cat {path} 2>/dev/null", timeout=2)
        print(f"   -> after write '{test_val}': {after}")

print("=== 1. amvecm PQ / DNLP / Dithering ===")
test_sysfs("DNLP Enable", "/sys/module/am_vecm/parameters/dnlp_en")
test_sysfs("DNLP Adj Level", "/sys/module/am_vecm/parameters/dnlp_adj_level")
test_sysfs("Color Management Enable", "/sys/module/am_vecm/parameters/cm_en")
test_sysfs("Color Management Level", "/sys/module/am_vecm/parameters/cm_level")
test_sysfs("VPP Dithering Enable", "/sys/module/am_vecm/parameters/vpp_dith_en")
test_sysfs("VPP Dithering Mode", "/sys/module/am_vecm/parameters/vpp_dith_mode")

print("\n=== 2. amvideo Super Scaler ===")
test_sysfs("Super Scaler", "/sys/module/amvideo/parameters/super_scaler")
test_sysfs("Vert Chroma Filter", "/sys/module/amvideo/parameters/vert_chroma_filter_en")

print("\n=== 3. HDMI Framerate & Range ===")
test_sysfs("Frac Rate Policy", "/sys/class/amhdmitx/amhdmitx0/frac_rate_policy")
test_sysfs("Range Control", "/sys/module/am_vecm/parameters/range_control")

print("\n=== 4. CPU & GPU Governors ===")
test_sysfs("CPU Gov", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
test_sysfs("CPU Available Govs", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors")
test_sysfs("CPU Cur Freq", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")
test_sysfs("GPU Cur Freq", "/sys/class/mpgpu/cur_freq 2>/dev/null")
