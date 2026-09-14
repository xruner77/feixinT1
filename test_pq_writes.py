import inspect_hdr

def test_node(node, val):
    out = inspect_hdr.run_adb(f"echo '{val}' > {node} 2>&1")
    new_val = inspect_hdr.run_adb(f"cat {node} 2>/dev/null")
    print(f"Write '{val}' to {node} -> err: [{out}] -> new_val: {new_val}")

print("--- Testing /sys/class/video/ ---")
test_node("/sys/class/video/brightness", "10")
test_node("/sys/class/video/brightness", "-10")
test_node("/sys/class/video/brightness", "0")

test_node("/sys/class/video/contrast", "10")
test_node("/sys/class/video/contrast", "0")

test_node("/sys/class/video/saturation", "140")
test_node("/sys/class/video/saturation", "128")

print("\n--- Testing /sys/class/amvecm/ ---")
test_node("/sys/class/amvecm/brightness", "10")
test_node("/sys/class/amvecm/brightness", "0")

test_node("/sys/class/amvecm/contrast", "10")
test_node("/sys/class/amvecm/contrast", "0")
