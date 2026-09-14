import os
import sys
import glob
import subprocess
import shutil

BASE_DIR = r"d:\tools\adb\t1_zoom_app"
TOOLS_DIR = r"d:\tools\adb\build_tools"
BIN_DIR = os.path.join(BASE_DIR, "bin")
COMPILED_DIR = os.path.join(BIN_DIR, "compiled")
CLASSES_DIR = os.path.join(BIN_DIR, "classes")

AAPT2 = os.path.join(TOOLS_DIR, "aapt2.exe")
ANDROID_JAR = os.path.join(TOOLS_DIR, "android.jar")
R8_JAR = os.path.join(TOOLS_DIR, "r8.jar")
KEYSTORE = os.path.join(TOOLS_DIR, "debug.keystore")
ADB = r"d:\tools\adb\adb.exe"

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')

def run_cmd(cmd, desc):
    print(f"--> {desc}...")
    res = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='ignore')
    if res.returncode != 0:
        print(f"FAILED: {desc}")
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        raise RuntimeError(f"{desc} failed with code {res.returncode}")
    return res.stdout


def build_and_deploy(target_ip="192.168.123.98"):
    # 1. Clean
    if os.path.exists(BIN_DIR):
        shutil.rmtree(BIN_DIR)
    os.makedirs(COMPILED_DIR, exist_ok=True)
    os.makedirs(CLASSES_DIR, exist_ok=True)

    # 2. aapt2 compile
    res_dir = os.path.join(BASE_DIR, "res")
    for root, dirs, files in os.walk(res_dir):
        for f in files:
            fp = os.path.join(root, f)
            run_cmd([AAPT2, "compile", fp, "-o", COMPILED_DIR], f"Compile res {f}")

    # 3. aapt2 link
    flat_files = glob.glob(os.path.join(COMPILED_DIR, "*.flat"))
    manifest = os.path.join(BASE_DIR, "AndroidManifest.xml")
    res_apk = os.path.join(BIN_DIR, "res.apk")
    link_cmd = [AAPT2, "link"] + flat_files + [
        "-I", ANDROID_JAR,
        "--manifest", manifest,
        "-o", res_apk,
        "--java", BIN_DIR,
        "--auto-add-overlay"
    ]
    run_cmd(link_cmd, "Link resources with aapt2")

    # 4. javac
    src_dir = os.path.join(BASE_DIR, "src")
    java_files = []
    for root, dirs, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".java") and f != "R.java":
                java_files.append(os.path.join(root, f))
    # Use generated R.java if available, else fallback
    r_java = os.path.join(BIN_DIR, "com", "phicomm", "t1zoom", "R.java")
    if os.path.exists(r_java):
        java_files.append(r_java)
    else:
        java_files.append(os.path.join(src_dir, "com", "phicomm", "t1zoom", "R.java"))

    javac_cmd = ["javac", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-cp", ANDROID_JAR, "-d", CLASSES_DIR] + java_files
    run_cmd(javac_cmd, "Compile Java files")




    # 5. d8 dex
    class_files = []
    for root, dirs, files in os.walk(CLASSES_DIR):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))

    d8_cmd = ["java", "-cp", R8_JAR, "com.android.tools.r8.D8", "--lib", ANDROID_JAR, "--min-api", "16", "--output", BIN_DIR] + class_files
    run_cmd(d8_cmd, "Convert classes to DEX with D8")

    # 6. Package APK
    unaligned_apk = os.path.join(BASE_DIR, "unaligned.apk")
    shutil.copyfile(res_apk, unaligned_apk)

    # jar uf unaligned.apk classes.dex
    jar_cmd = ["jar", "uf", unaligned_apk, "-C", BIN_DIR, "classes.dex"]
    run_cmd(jar_cmd, "Add classes.dex to APK")

    # 7. Sign APK
    sign_cmd = [
        "jarsigner", "-keystore", KEYSTORE,
        "-storepass", "android", "-keypass", "android",
        "-sigalg", "SHA1withRSA", "-digestalg", "SHA1",
        unaligned_apk, "androiddebugkey"
    ]
    run_cmd(sign_cmd, "Sign APK with jarsigner")

    out_apk = r"d:\tools\adb\T1ZoomHelper.apk"
    shutil.copyfile(unaligned_apk, out_apk)
    print(f"*** Build successful: {out_apk} ***")

    # 8. Deploy
    if target_ip:
        print(f"Deploying to {target_ip}:5555...")
        subprocess.run([ADB, "connect", f"{target_ip}:5555"], capture_output=True)
        res = subprocess.run([ADB, "-s", f"{target_ip}:5555", "install", "-r", out_apk], capture_output=True, text=True)
        print("Install output:", res.stdout, res.stderr)
        
        # Start activity
        subprocess.run([ADB, "-s", f"{target_ip}:5555", "shell", "am start -n com.phicomm.t1zoom/.MainActivity"], capture_output=True)
        print("Service restarted.")

if __name__ == '__main__':
    build_and_deploy()
