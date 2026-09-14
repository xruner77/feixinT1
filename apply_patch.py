import subprocess
import os

def run_cmd(cmd):
    full_cmd = f"echo 31183118 | su 0 sh -c '{cmd}'"
    res = subprocess.run(["d:\\tools\\adb\\adb.exe", "shell", full_cmd], capture_output=True, text=True)
    return res.stdout, res.stderr

print("1. Pushing patched block to /data/local/tmp/...")
subprocess.run(["d:\\tools\\adb\\adb.exe", "connect", "192.168.123.98:5555"], capture_output=True)
subprocess.run(["d:\\tools\\adb\\adb.exe", "push", "d:\\tools\\adb\\block_327225_patched.bin", "/data/local/tmp/patched_block.bin"], check=True)

print("2. Writing block 327225 on /dev/block/system...")
out, err = run_cmd("dd if=/data/local/tmp/patched_block.bin of=/dev/block/system bs=4096 seek=327225 count=1 conv=notrunc")
print("dd stdout:", out)
print("dd stderr:", err)

print("3. Syncing disks and dropping page cache...")
run_cmd("sync; echo 3 > /proc/sys/vm/drop_caches")

print("4. Verifying /system/etc/seccomp_policy/mediacodec-seccomp.policy...")
out, err = run_cmd("head -n 10 /system/etc/seccomp_policy/mediacodec-seccomp.policy")
print("Policy file head:\n" + out)

if "sendto: 1" in out:
    print("SUCCESS! sendto: 1 is now LIVE in /system/etc/seccomp_policy/mediacodec-seccomp.policy!")
    print("5. Restarting media.codec...")
    run_cmd("kill -9 $(pidof media.codec)")
    import time
    time.sleep(1)
    out, err = run_cmd("ps | grep media.codec")
    print("New media.codec process:", out.strip())
else:
    print("WARNING: sendto: 1 not found in policy file output!")
