import inspect_hdr

def check_params(mod):
    print(f"=== Module: {mod} ===")
    out = inspect_hdr.run_adb(f"ls /sys/module/{mod}/parameters/")
    print(f"params: {out}")
    param_list = out.split()
    for p in param_list:
        val = inspect_hdr.run_adb(f"cat /sys/module/{mod}/parameters/{p} 2>/dev/null", timeout=1)
        if val and not val.startswith("[TIMEOUT]"):
            print(f"  {p} = {val}")

for m in ["am_vecm", "amvideo", "hdmitx20", "vpu", "di", "ppmgr"]:
    check_params(m)
