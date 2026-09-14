import inspect_hdr

nodes = [
    "/sys/class/amvecm/brightness",
    "/sys/class/amvecm/contrast",
    "/sys/class/amvecm/saturation_hue",
    "/sys/class/amvecm/saturation_hue_pre",
    "/sys/class/amvecm/saturation_hue_post",
    "/sys/class/amvecm/gamma",
    "/sys/class/amvecm/wb",
    "/sys/class/video/brightness",
    "/sys/class/video/contrast",
    "/sys/class/video/saturation",
    "/sys/class/video/vpp_brightness",
    "/sys/class/video/vpp_contrast",
    "/sys/class/video/vpp_saturation_hue",
]

for node in nodes:
    val = inspect_hdr.run_adb(f"cat {node} 2>/dev/null")
    print(f"{node}: {val}")

print("\n--- system_control help / pq ---")
print(inspect_hdr.run_adb("dumpsys system_control -h 2>/dev/null"))
print(inspect_hdr.run_adb("dumpsys system_control | grep -iE 'bright|contrast|sat|hue|picture|color' 2>/dev/null"))
