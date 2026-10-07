# -*- coding: utf-8 -*-
import subprocess
import sys
import os
import time

def get_base_dir():
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

class BoxDoctor:
    def __init__(self, ip=None):
        base_dir = get_base_dir()
        local_adb = os.path.join(base_dir, "adb.exe")
        self.adb = local_adb if os.path.exists(local_adb) else "adb"
        self.ip = ip
        self.su_mode = None

    def run_adb(self, cmd_args, timeout=6):
        base = [self.adb]
        if self.ip:
            base += ["-s", f"{self.ip}:5555"]
        try:
            return subprocess.run(base + cmd_args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        except subprocess.TimeoutExpired:
            return subprocess.CompletedProcess(args=base+cmd_args, returncode=-1, stdout="", stderr="Timeout")
        except Exception as e:
            return subprocess.CompletedProcess(args=base+cmd_args, returncode=-1, stdout="", stderr=str(e))

    def run_root(self, sh_cmd):
        if self.su_mode is None:
            # 1. Direct uid=0
            res = self.run_adb(["shell", "id"])
            if "uid=0" in res.stdout:
                self.su_mode = "direct"
            else:
                # 2. su -c
                res = self.run_adb(["shell", "su -c id"])
                if "uid=0" in res.stdout:
                    self.su_mode = "su_c"
                else:
                    # 3. Phicomm su password 31183118
                    res = self.run_adb(["shell", "echo 31183118 | su 0 sh -c id"])
                    if "uid=0" in res.stdout:
                        self.su_mode = "phicomm_su"
                    else:
                        self.su_mode = "direct"

        if self.su_mode == "direct":
            full_cmd = sh_cmd
        elif self.su_mode == "su_c":
            full_cmd = f"su -c '{sh_cmd}'"
        else:
            full_cmd = f"echo 31183118 | su 0 sh -c '{sh_cmd}'"

        res = self.run_adb(["shell", full_cmd])
        return res.stdout.strip(), res.stderr.strip()

    def run_media(self, sh_cmd):
        # Specific media uid for zoom node on stock T1
        if self.su_mode == "phicomm_su":
            full_cmd = f"echo 31183118 | su 1013:1000 sh -c '{sh_cmd}'"
            res = self.run_adb(["shell", full_cmd])
            return res.stdout.strip()
        return self.run_root(sh_cmd)[0]

    def diagnose(self):
        print("=" * 65)
        print("       斐讯 T1 变焦(Zoom)与系统环境深度诊断工具")
        print("=" * 65)

        if self.ip:
            print(f"[*] 正在连接设备 ({self.ip}:5555)...")
            self.run_adb(["connect", f"{self.ip}:5555"], timeout=3)
            time.sleep(1)

        dev_res = self.run_adb(["devices"])
        if self.ip and self.ip not in dev_res.stdout:
            print(f"[!] 无法连接到设备 {self.ip}，请检查IP是否正确以及网络是否畅通！")
            return

        print("\n" + "-" * 65)
        print("【1. 设备与固件信息】")
        model, _ = self.run_root("getprop ro.product.model")
        display, _ = self.run_root("getprop ro.build.display.id")
        ver, _ = self.run_root("getprop ro.build.version.release")
        selinux, _ = self.run_root("getenforce")
        print(f"  • 设备型号: {model}")
        print(f"  • 固件版本: {display}")
        print(f"  • 安卓版本: Android {ver}")
        print(f"  • SELinux 状态: {selinux}")

        print("\n" + "-" * 65)
        print("【2. Root 提权通道检测】")
        self.run_root("id")
        mode_desc = {
            "direct": "原生 ADB Root (uid=0) [✓]",
            "su_c": "通用免密 Root (su -c) [✓]",
            "phicomm_su": "斐讯专有 Root (echo 31183118 | su) [✓]"
        }.get(self.su_mode, "未识别/异常 [✗]")
        print(f"  • 提权模式: {mode_desc}")

        print("\n" + "-" * 65)
        print("【3. Seccomp 硬解补丁生效状态】")
        policy_out, _ = self.run_root("head -n 5 /system/etc/seccomp_policy/mediacodec-seccomp.policy 2>/dev/null")
        if "sendto: 1" in policy_out:
            print("  • 策略文件: [✓] 已包含 sendto 白名单 (硬解热补丁已生效！)")
        else:
            print("  • 策略文件: [✗] 缺少 sendto 白名单 (硬解补丁未生效或失效！)")

        codec_pid, _ = self.run_root("pidof media.codec")
        if codec_pid:
            print(f"  • 解码守护进程 media.codec: [✓] 正在运行 (PID: {codec_pid})")
        else:
            print("  • 解码守护进程 media.codec: [✗] 未运行")

        print("\n" + "-" * 65)
        print("【4. 晶晨芯片底层硬件节点读写测试】")
        cur_zoom, _ = self.run_root("cat /sys/class/video/zoom 2>/dev/null")
        print(f"  • /sys/class/video/zoom 当前读取值: {cur_zoom}")

        # Test writing
        print("  • 正在尝试向 /sys/class/video/zoom 写入测试数值 125...")
        self.run_media("echo 125 > /sys/class/video/zoom")
        after_write, _ = self.run_root("cat /sys/class/video/zoom 2>/dev/null")
        if after_write == "125":
            print(f"  • 回读核验: [✓] 成功写入 125！底层驱动与节点读写完全正常！")
        else:
            print(f"  • 回读核验: [✗] 写入失败 (回读值为: {after_write})，可能缺少 media 身份写入权限！")

        mode, _ = self.run_root("cat /sys/class/video/screen_mode 2>/dev/null")
        axis, _ = self.run_root("cat /sys/class/video/axis 2>/dev/null")
        print(f"  • 屏幕拉伸模式 screen_mode: {mode}")
        print(f"  • 视频物理视窗坐标 axis: {axis}")

        print("\n" + "-" * 65)
        print("【5. 视频流播放活跃状态（防呆核心检测）】")
        fc1, _ = self.run_root("cat /sys/class/video/frame_count 2>/dev/null")
        time.sleep(0.5)
        fc2, _ = self.run_root("cat /sys/class/video/frame_count 2>/dev/null")
        vstatus, _ = self.run_root("cat /sys/class/video/video_status 2>/dev/null")

        try:
            diff = int(fc2) - int(fc1)
        except:
            diff = 0

        if diff > 0 or (fc2 and int(fc2) > 0) or "running" in vstatus:
            print(f"  • 硬件视频流: [✓] 检测到底层硬件视频正在播放！(Frame: {fc2}, 增量: {diff})")
        else:
            print("  • 硬件视频流: [⚠️ 核心提醒] 当前未检测到硬件视频在播放！")
            print("    >>> 变焦(Zoom)是芯片 VPP 视频图层特性，仅在硬解播放视频时对画面有效！")
            print("    >>> 系统桌面、设置菜单和应用界面绝不缩放。请在播放视频时观察画面！")

        print("\n" + "-" * 65)
        print("【6. Kodi 播放器与 9090 端口】")
        kodi_pid, _ = self.run_root("pidof org.xbmc.kodi")
        if kodi_pid:
            print(f"  • Kodi 运行状态: [✓] 正在后台运行 (PID: {kodi_pid})")
        else:
            print("  • Kodi 运行状态: 未运行")

        print("\n" + "=" * 65)
        print("【综合诊断结论与排查指引】")
        if "sendto: 1" not in policy_out:
            print("❌ 核心缺陷：硬解补丁未生效！缺少 sendto 白名单会导致播放器崩溃回退到软解。")
            print("   👉 解决办法：请重新运行【一键自适应打补丁.bat】，并在提示成功后重启盒子。")
        elif after_write != "125":
            print("❌ 核心缺陷：zoom 节点写保护，指令未能写入芯片寄存器。")
        elif diff == 0 and not (fc2 and int(fc2) > 0):
            print("⚠️ 绝大多数客户误以为'无反应'的原因：在桌面静止测试！")
            print("   👉 解决办法：请打开 Kodi 播放宽屏电影后，再观察画面是否被放大切除黑边。")
            print("   👉 检查项：请确保 Kodi 内开启了【允许硬件加速 - MediaCodec (Surface)】！")
        else:
            print("✅ 恭喜！当前盒子底层环境与硬件节点读写 100% 正常！")
        print("=" * 65)

def main():
    ip = None
    if len(sys.argv) > 1 and sys.argv[1].strip():
        ip = sys.argv[1].strip()
    else:
        try:
            val = input("请输入斐讯 T1 盒子的 IP 地址 (直接回车默认: 192.168.123.98): ").strip()
            ip = val if val else "192.168.123.98"
        except:
            ip = "192.168.123.98"

    doc = BoxDoctor(ip=ip)
    doc.diagnose()
    input("\n按回车键退出诊断...")

if __name__ == '__main__':
    main()
