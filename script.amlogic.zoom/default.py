# -*- coding: utf-8 -*-
import os
import sys
import xbmc
import xbmcgui

def test_direct_write():
    nodes = {
        "/sys/class/video/screen_mode": "0",
        "/sys/class/video/zoom": "100",
        "/sys/class/video/crop": "0 0 0 0",
        "/sys/class/video/axis": "0 0 -1 -1",
    }
    results = []
    for path, val in nodes.items():
        # Test read
        r_ok, r_val = False, ""
        try:
            with open(path, "r") as f:
                r_val = f.read().strip()
                r_ok = True
        except Exception as e:
            r_val = str(e)

        # Test write
        w_ok, w_val = False, ""
        try:
            with open(path, "w") as f:
                f.write(val)
                w_ok = True
                w_val = "OK"
        except Exception as e:
            w_val = str(e)

        results.append(f"{os.path.basename(path)}: R({'OK' if r_ok else 'FAIL'}) W({'OK' if w_ok else 'FAIL'}: {w_val})")

    report = "\n".join(results)
    xbmc.log(f"[AmlogicZoomTest] {report}", level=xbmc.LOGINFO)
    try:
        with open("/sdcard/kodi_zoom_test.txt", "w") as f:
            f.write(report)
    except:
        pass
    dialog = xbmcgui.Dialog()
    dialog.ok("晶晨硬件缩放权限测试", report)

if __name__ == "__main__":
    test_direct_write()
