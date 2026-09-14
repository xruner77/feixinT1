import base64
import struct
import subprocess

def get_b64(cmd):
    full_cmd = f"echo 31183118 | su 0 sh -c '{cmd}'"
    res = subprocess.run(["d:\\tools\\adb\\adb.exe", "shell", full_cmd], capture_output=True, text=True)
    clean = "".join(res.stdout.split())
    return base64.b64decode(clean)

# Read block 327225
data = get_b64("dd if=/dev/block/system bs=4096 skip=327225 count=1 2>/dev/null | base64")
print("Block length:", len(data))

# Compare with /system/etc/seccomp_policy/mediacodec-seccomp.policy
with open("d:\\tools\\adb\\run_box_cmd.py") as f:
    pass

file_content = get_b64("cat /system/etc/seccomp_policy/mediacodec-seccomp.policy | base64")
print("Policy file length:", len(file_content))

# Check if block starts with policy file content
if data[:len(file_content)] == file_content:
    print("MATCH! Block 327225 contains the EXACT file content at offset 0!")
    print("Trailing bytes in block:", len(data) - len(file_content))
    print("Trailing bytes sample:", data[len(file_content):len(file_content)+50])
else:
    print("MISMATCH! Investigating...")
