# -*- coding: utf-8 -*-
import base64
import struct
import subprocess
import sys
import os
import time

TARGET_POLICY = "/system/etc/seccomp_policy/mediacodec-seccomp.policy"

def get_base_dir():
    if getattr(sys, 'frozen', False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))

class T1Patcher:
    def __init__(self, adb_path=None, ip=None):
        base_dir = get_base_dir()
        if adb_path:
            self.adb = adb_path
        else:
            local_adb = os.path.join(base_dir, "adb.exe")
            self.adb = local_adb if os.path.exists(local_adb) else "adb"
        self.ip = ip
        self.su_mode = None

    def log(self, msg):
        print(msg, flush=True)

    def run_adb(self, cmd_args, timeout=10):
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
            # 1. 尝试直接执行 (部分已 root 固件 adbd 本身就是 uid=0)
            res = self.run_adb(["shell", "id"])
            if "uid=0" in res.stdout:
                self.su_mode = "direct"
                self.log("[*] 检测到 ADB 具备原生 Root 权限 (uid=0)")
            else:
                # 2. 尝试免密 su
                res = self.run_adb(["shell", "su -c id"])
                if "uid=0" in res.stdout:
                    self.su_mode = "su_c"
                    self.log("[*] 检测到通用免密 Root (su -c)")
                else:
                    # 3. 尝试斐讯定制 su 密码 31183118
                    res = self.run_adb(["shell", "echo 31183118 | su 0 sh -c id"])
                    if "uid=0" in res.stdout:
                        self.su_mode = "phicomm_su"
                        self.log("[*] 检测到斐讯专有 Root 授权 (echo 31183118 | su)")
                    else:
                        self.su_mode = "direct"
                        self.log("[!] 未检测到常规 Root 认证，尝试直接执行")

        if self.su_mode == "direct":
            full_cmd = sh_cmd
        elif self.su_mode == "su_c":
            full_cmd = f"su -c '{sh_cmd}'"
        else:
            full_cmd = f"echo 31183118 | su 0 sh -c '{sh_cmd}'"

        res = self.run_adb(["shell", full_cmd])
        return res.stdout, res.stderr

    def get_block_b64(self, block_num):
        cmd = f"dd if=/dev/block/system bs=4096 skip={block_num} count=1 2>/dev/null | base64"
        out, _ = self.run_root(cmd)
        clean = "".join(out.split())
        try:
            return base64.b64decode(clean)
        except Exception as e:
            self.log(f"[!] Base64 解码块 {block_num} 失败: {e}")
            return b""

    def run(self):
        self.log("=" * 60)
        self.log("   斐讯 T1 全固件自适应 Seccomp 硬解安全热补丁")
        self.log("=" * 60)

        if self.ip:
            self.log(f"[*] 正在尝试连接盒子 ({self.ip}:5555)...")
            self.run_adb(["connect", f"{self.ip}:5555"], timeout=5)
            time.sleep(1)

        # 检查设备连接
        dev_res = self.run_adb(["devices"])
        if self.ip and self.ip not in dev_res.stdout:
            self.log(f"[!] 无法连接到设备 {self.ip}，请检查盒子的 IP 地址以及是否在同一局域网。")
            return False

        # 1. 检查当前策略文件状态
        self.log(f"[*] 正在读取沙箱策略文件: {TARGET_POLICY}...")
        content_b64, err = self.run_root(f"cat {TARGET_POLICY} 2>/dev/null | base64")
        if not content_b64.strip():
            self.log(f"[!] 错误: 未能在盒子上读取到 {TARGET_POLICY}")
            self.log(f"    可能原因: 当前固件不是 Android 7.1，或者权限不足。")
            return False

        orig_content = base64.b64decode("".join(content_b64.split()))
        if b"sendto: 1" in orig_content:
            self.log("\n" + "=" * 60)
            self.log("[✓] 检查完毕: 当前固件的策略文件已包含 sendto 白名单！")
            self.log("[✓] 系统硬件解码器非常健康，无需重复打补丁。")
            self.log("=" * 60)
            return True

        self.log(f"[*] 发现缺陷: 策略文件缺少 sendto 白名单 (大小: {len(orig_content)} 字节)")
        self.log("[*] 正在解析 Ext4 文件系统结构，动态计算物理扇区...")

        # 2. 获取当前文件的 Inode 编号
        out, _ = self.run_root(f"ls -i {TARGET_POLICY}")
        try:
            inode_num = int(out.strip().split()[0])
            self.log(f"[*] 当前固件中该文件的 Inode 编号为: {inode_num}")
        except Exception as e:
            self.log(f"[!] 解析 Inode 编号失败: {out}")
            return False

        # 3. 读取 Superblock 解析 Ext4 结构 (Block 0, 偏移 1024)
        sb_full = self.get_block_b64(0)
        if len(sb_full) < 2048:
            self.log("[!] 无法读取系统分区 Superblock，终止！")
            return False

        sb_data = sb_full[1024:2048]
        s_magic = struct.unpack_from("<H", sb_data, 56)[0]
        if s_magic != 0xef53:
            self.log(f"[!] 致命错误: Superblock 魔数不匹配 (0x{s_magic:x} != 0xef53)，安全终止！")
            return False

        s_log_block_size = struct.unpack_from("<I", sb_data, 24)[0]
        block_size = 1024 << s_log_block_size
        s_inodes_per_group = struct.unpack_from("<I", sb_data, 40)[0]
        s_inode_size = struct.unpack_from("<H", sb_data, 88)[0]
        s_desc_size = struct.unpack_from("<H", sb_data, 254)[0]
        if s_desc_size == 0:
            s_desc_size = 32

        # 4. 定位 Inode 所在的数据块
        group_idx = (inode_num - 1) // s_inodes_per_group
        idx_in_group = (inode_num - 1) % s_inodes_per_group

        gdt_data = self.get_block_b64(1)
        gdt_offset = group_idx * s_desc_size
        bg_inode_table_lo = struct.unpack_from("<I", gdt_data, gdt_offset + 8)[0]

        inode_offset_bytes = idx_in_group * s_inode_size
        inode_block = bg_inode_table_lo + (inode_offset_bytes // block_size)
        inode_in_block_offset = inode_offset_bytes % block_size

        # 5. 读取 Inode 结构并解析 Extents
        raw_inode_block = self.get_block_b64(inode_block)
        raw_inode = raw_inode_block[inode_in_block_offset:inode_in_block_offset + s_inode_size]
        eh_magic = struct.unpack_from("<H", raw_inode, 40)[0]
        if eh_magic != 0xf30a:
            self.log(f"[!] Extent Header 魔数不匹配 (0x{eh_magic:x} != 0xf30a)，安全终止！")
            return False

        ee_block, ee_len, ee_start_hi, ee_start_lo = struct.unpack_from("<IHHI", raw_inode, 52)
        target_block = (ee_start_hi << 32) | ee_start_lo
        self.log(f"[+] 动态寻址成功！当前固件下的物理扇区块号为: {target_block}")

        # 6. 【核心防呆安全校验】：回读该块并进行指纹比对
        self.log(f"[*] 正在回读物理块 {target_block} 进行 100% 逐字节验真...")
        target_block_data = self.get_block_b64(target_block)
        if len(target_block_data) != 4096:
            self.log("[!] 读出的物理块长度异常，安全中止！")
            return False

        if target_block_data[:len(orig_content)] != orig_content:
            self.log("[!] 致命警告: 读出的物理块数据与目标文件指纹不一致！")
            self.log("[!] 为保护设备安全，严禁写入！绝不冒风险盲刷。")
            return False

        self.log("[+] 指纹验真完全通过！确认目标物理块 100% 属于当前 policy 文件。")

        # 7. 构造严格等长的微创补丁块（维持原字节长度，绝不破坏文件元数据）
        lines = orig_content.splitlines(keepends=True)
        orig_prefix = lines[0] + lines[1]
        target_len = len(orig_prefix)

        prefix_parts = [
            b"sendto: 1\n",
            b"recvfrom: 1\n",
            b"sendmsg: 1\n",
            b"recvmsg: 1\n"
        ]
        curr_len = sum(len(p) for p in prefix_parts)
        if target_len < curr_len:
            self.log("[!] 头部空置注释空间不足以填充，安全中止！")
            return False

        remaining = target_len - curr_len
        comment_line = b"# " + b"-" * (remaining - 3) + b"\n"
        prefix_parts.append(comment_line)
        new_prefix = b"".join(prefix_parts)
        assert len(new_prefix) == target_len

        new_file_content = new_prefix + b"".join(lines[2:])
        assert len(new_file_content) == len(orig_content)

        new_block_data = new_file_content + target_block_data[len(orig_content):]
        assert len(new_block_data) == 4096

        # 8. 推送并安全注入
        self.log(f"[*] 正在将补丁数据写入物理块 {target_block}...")
        temp_local_bin = os.path.join(get_base_dir(), "_temp_patch_block.bin")
        temp_local_sh = os.path.join(get_base_dir(), "_temp_run_patch.sh")

        with open(temp_local_bin, "wb") as f:
            f.write(new_block_data)

        sh_script_content = (
            "#!/system/bin/sh\n"
            f"dd if=/data/local/tmp/smart_patch.bin of=/dev/block/system bs=4096 seek={target_block} count=1\n"
            "sync\n"
            "echo 3 > /proc/sys/vm/drop_caches\n"
        )
        with open(temp_local_sh, "w", newline="\n", encoding="utf-8") as f:
            f.write(sh_script_content)

        try:
            self.run_adb(["push", temp_local_bin, "/data/local/tmp/smart_patch.bin"])
            self.run_adb(["push", temp_local_sh, "/data/local/tmp/run_patch.sh"])

            out, err = self.run_root("sh /data/local/tmp/run_patch.sh")
            self.log(f"[*] 写入执行输出: {out.strip()} {err.strip()}")

            self.run_root("rm -f /data/local/tmp/smart_patch.bin /data/local/tmp/run_patch.sh")
        finally:
            if os.path.exists(temp_local_bin):
                try: os.remove(temp_local_bin)
                except: pass
            if os.path.exists(temp_local_sh):
                try: os.remove(temp_local_sh)
                except: pass

        # 9. 验收与热重启
        self.log(f"[*] 正在直接核验闪存芯片物理块 {target_block}...")
        rechecked_block = self.get_block_b64(target_block)
        flash_written = (b"sendto: 1" in rechecked_block)

        verify_txt, _ = self.run_root(f"head -n 5 {TARGET_POLICY}")
        self.log("\n[+] 策略文件当前系统读取输出:")
        for line in verify_txt.strip().splitlines()[:5]:
            self.log("    " + line)

        if "sendto: 1" in verify_txt:
            self.log("\n[+] 验证成功！sendto 白名单已实时生效！")
            self.log("[*] 正在热重启 media.codec 解码器...")
            self.run_root("kill -9 $(pidof media.codec)")
            self.log("\n" + "=" * 60)
            self.log("[SUCCESS] 恭喜！当前固件 Seccomp 漏洞修复完成，Kodi 调参永久稳定！")
            self.log("=" * 60)
            return True
        elif flash_written:
            self.log("\n[+] 物理闪存芯片底层已 100% 成功写入补丁！")
            self.log("[*] 当前内存 Page Cache 尚未刷新，需重启盒子以永久加载。")
            self.log("[*] 正在向盒子发送重启命令...")
            self.run_root("reboot")
            self.log("\n" + "=" * 60)
            self.log("[SUCCESS] 盒子已指令重启，重启完成后补丁将永久生效！")
            self.log("=" * 60)
            return True
        else:
            self.log("\n[!] 物理块回读未见 sendto，请检查 root 权限或设备状态。")
            return False

def main():
    ip = None
    if len(sys.argv) > 1 and sys.argv[1].strip():
        ip = sys.argv[1].strip()
    else:
        try:
            val = input("请输入斐讯 T1 的 IP 地址 (直接回车默认: 192.168.123.98): ").strip()
            ip = val if val else "192.168.123.98"
        except:
            ip = "192.168.123.98"

    patcher = T1Patcher(ip=ip)
    success = patcher.run()
    if not success:
        print("\n执行未能成功完成。")
    input("\n按回车键退出...")

if __name__ == "__main__":
    main()
