# -*- coding: utf-8 -*-
import os
import shutil
import zipfile

BASE_DIR = r"d:\tools\adb"
STAGE_DIR = os.path.join(BASE_DIR, "斐讯T1_全固件自适应硬解修复与环境诊断工具包")
DIST_DIR = os.path.join(BASE_DIR, "dist")

if os.path.exists(STAGE_DIR):
    shutil.rmtree(STAGE_DIR)
os.makedirs(STAGE_DIR, exist_ok=True)

files_to_copy = [
    (os.path.join(BASE_DIR, "adb.exe"), "adb.exe"),
    (os.path.join(BASE_DIR, "AdbWinApi.dll"), "AdbWinApi.dll"),
    (os.path.join(BASE_DIR, "AdbWinUsbApi.dll"), "AdbWinUsbApi.dll"),
    (os.path.join(DIST_DIR, "T1_Seccomp_Patcher.exe"), "T1_Seccomp_Patcher.exe"),
    (os.path.join(DIST_DIR, "T1_Doctor.exe"), "T1_Doctor.exe"),
    (os.path.join(BASE_DIR, "T1ZoomHelper.apk"), "T1ZoomHelper.apk"),
    (os.path.join(BASE_DIR, "一键自适应打补丁.bat"), "一键自适应打补丁.bat"),
    (os.path.join(BASE_DIR, "OneClick_Patch.bat"), "OneClick_Patch.bat"),
    (os.path.join(BASE_DIR, "一键诊断盒子环境.bat"), "一键诊断盒子环境.bat"),
    (os.path.join(BASE_DIR, "OneClick_Diagnose.bat"), "OneClick_Diagnose.bat"),
    (os.path.join(BASE_DIR, "使用说明与Zoom排查指南.txt"), "使用说明与Zoom排查指南.txt"),
]

print("--> Copying files to staging directory...")
for src, dst_name in files_to_copy:
    dst = os.path.join(STAGE_DIR, dst_name)
    shutil.copyfile(src, dst)
    print(f"  + {dst_name} ({os.path.getsize(dst)} bytes)")

# Update T1_Adaptive_Seccomp_Patcher as well
LEGACY_STAGE = os.path.join(BASE_DIR, "T1_Adaptive_Seccomp_Patcher")
if os.path.exists(LEGACY_STAGE):
    shutil.rmtree(LEGACY_STAGE)
shutil.copytree(STAGE_DIR, LEGACY_STAGE)

# Create Zip Packages
zip_targets = [
    os.path.join(BASE_DIR, "斐讯T1_全固件自适应硬解修复与环境诊断工具包.zip"),
    os.path.join(BASE_DIR, "斐讯T1_全固件自适应硬解修复工具包.zip")
]

for zip_path in zip_targets:
    print(f"--> Creating zip: {zip_path}...")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(STAGE_DIR):
            for file in files:
                full_p = os.path.join(root, file)
                rel_p = os.path.relpath(full_p, STAGE_DIR)
                zf.write(full_p, arcname=rel_p)
    print(f"*** Created: {zip_path} ({os.path.getsize(zip_path)} bytes) ***")

print("\nPackaging completed successfully!")
