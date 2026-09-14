import urllib.request
import time
import subprocess
import json

ADB = r"d:\tools\adb\adb.exe"
BOX = "192.168.123.98:5555"

def get_json(url):
    with urllib.request.urlopen(url, timeout=3) as r:
        return json.loads(r.read().decode('utf-8'))

print("1. Setting customized PQ values via web API...")
get_json("http://192.168.123.98:8989/api/pq?type=brightness&val=16")
get_json("http://192.168.123.98:8989/api/pq?type=contrast&val=9")
get_json("http://192.168.123.98:8989/api/pq?type=saturation&val=18")
get_json("http://192.168.123.98:8989/api/pq?type=dnlp&val=1")

time.sleep(0.5)
st1 = get_json("http://192.168.123.98:8989/api/status")
print("Status after set:", st1)

print("\n2. Checking shared_prefs XML file on box...")
res = subprocess.run([ADB, "-s", BOX, "shell", "echo 31183118 | su 0 cat /data/data/com.phicomm.t1zoom/shared_prefs/t1_pq_prefs.xml"], capture_output=True, text=True)
print("XML content:\n", res.stdout)

print("\n3. Simulating app restart / reboot by restarting ZoomService...")
subprocess.run([ADB, "-s", BOX, "shell", "am force-stop com.phicomm.t1zoom"], capture_output=True)
time.sleep(1)
subprocess.run([ADB, "-s", BOX, "shell", "am start -n com.phicomm.t1zoom/.MainActivity"], capture_output=True)
time.sleep(2)

print("\n4. Checking if service restored values after restart...")
st2 = get_json("http://192.168.123.98:8989/api/status")
print("Restored status:", st2)

print("\n5. Checking hardware nodes after restore...")
b_node = subprocess.run([ADB, "-s", BOX, "shell", "cat /sys/class/amvecm/brightness"], capture_output=True, text=True).stdout.strip()
c_node = subprocess.run([ADB, "-s", BOX, "shell", "cat /sys/class/amvecm/contrast"], capture_output=True, text=True).stdout.strip()
s_node = subprocess.run([ADB, "-s", BOX, "shell", "cat /sys/class/amvecm/saturation_hue_pre"], capture_output=True, text=True).stdout.strip()
d_node = subprocess.run([ADB, "-s", BOX, "shell", "cat /sys/module/am_vecm/parameters/dnlp_en"], capture_output=True, text=True).stdout.strip()

print(f"Hardware nodes: brightness={b_node}, contrast={c_node}, saturation={s_node}, dnlp={d_node}")

if st2.get('brightness') == 16 and st2.get('contrast') == 9 and st2.get('saturation') == 18 and st2.get('dnlp') == 1:
    print("\n*** PERSISTENCE TEST PASSED: ALL VALUES REMEMBERED AND RESTORED! ***")
else:
    print("\n*** TEST FAILED ***")
