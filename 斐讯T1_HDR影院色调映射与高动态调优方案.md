# 斐讯 T1 (Amlogic S912) HDR 影院级色调映射与高动态画质重构方案

> **适用场景**：外接家用 4K 投影仪（如明基 BenQ TK700 等物理亮度 ~150-200 nits 设备）或非高端 HDR 电视，解决直通 HDR 发暗死黑、芯片默认映射泛白发灰、高光发闷无力的问题。  
> **状态**：已在实机（固件 1.6T57 / 明基 TK700 v1.02）全流程调校验证通过，效果通透拔群。  
> **归档位置**：`d:\tools\adb\斐讯T1_HDR影院色调映射与高动态调优方案.md`  
> **配套工具**：[`一键注入BT2390影院映射.bat`](file:///d:/tools/adb/一键注入BT2390影院映射.bat)、[`inject_bt2390.py`](file:///d:/tools/adb/inject_bt2390.py) 与 [`inject_bt2390.sh`](file:///d:/tools/adb/inject_bt2390.sh)

---

## 一、核心痛点与底层原理剖析

### 1. 为什么明基 TK700 直通 HDR 效果不佳？
- 家用 4K 投影仪在校准后的影院模式下，物理峰值亮度普遍在 **100 ~ 200 nits** 左右，而 4K HDR10 原盘通常按 **1000 ~ 4000 nits** 监视器母带压制；
- 投影仪内部的静态色调映射（Tone Mapping）为了保留 1000 nits 的高光细节，采取了**整体下压曲线**的粗暴做法，导致**人脸发黑、室内场景极度昏暗无光**；如果调高投影仪的 HDR 亮度设置，高光区域又会大面积过曝死白，丧失层次；
- **最佳工业实践**：由前端播放设备进行**高质量色调映射（Target ~150 nits HDR $\rightarrow$ SDR 转换）**，输出高反差、色彩饱和的视频信号。

### 2. 为什么 S912 原厂的 HDR $\rightarrow$ SDR 映射严重翻车？
通过反编译晶晨图像守护进程 `systemcontrol` 并深度读取内核 `am_vecm`（晶晨图像引擎）驱动，定位到五大缺陷：
1. **`knee_factor = 0`（完全禁用压缩）**：芯片默认把膝点平滑压缩设为 0，高动态范围遇到阈值直接走 100% 纯线性硬截断（Linear Clipping），高光没有过渡，全屏泛白；
2. **原厂预设 `knee_setting` 曲线严重下凹**：在中暗部（78 ~ 450 灰阶，代表人脸与室内中光）下凹近 30%，造成暗部大面积塌陷成死黑（Black Crush）；
3. **`range_control = 1`（限幅钳制）**：如果锁死 Limited 范围（16-235），10-bit 纯白信号被死死卡在 940（无法达到 1023），生生削掉约 10% 的投影灯泡峰值光输出，高光发闷；
4. **`sdr_saturation_offset = 20`**：BT.2020 广色域压到 BT.709 时严重褪色，默认补偿只有 20，画面干瘪如水洗；
5. **硬件对比度仅为 9 ~ 15**：芯片视频层增益不足，反差拉不开。

### 3. 为什么系统属性显示 `420,10bit`，但实际 HDMI 发送端依然是 8-bit (`cd=4`)？
深入逆向内核驱动 `amhdmitx` 与 `systemcontrol`，发现 Amlogic 平台的“假 10-bit 陷阱”：
1. **`attr` 节点仅为字符串暂存**：往 `/sys/class/amhdmitx/amhdmitx0/attr` 写入 `420,10bit`，内核驱动函数 `store_attr()` 仅执行 `memcpy(hdmitx_device.fmt_attr, buf, count)`，**完全不触发 HDMI PHY 芯片与 TMDS 时钟的时序重协商**；
2. **硬件时钟重协商触发点**：只有写入 `/sys/class/display/mode`（例如重新应用 `2160p60hz420`），内核才会执行 `set_current_vmode()` 并把 `fmt_attr` 应用到硬件，将色深由 `cd = 4`（8-bit）切换至 `cd = 5`（10-bit）；
3. **Linux DAC 权限壁垒**：`/sys/class/display/mode` 属主为 `system:system`（0644）。斐讯原厂 `su` 仅具备 `CAP_SETUID` 与 `CAP_SETGID`，**缺少 `CAP_DAC_OVERRIDE`**。因此以 `su 0` (root) 写入该节点会被 Linux 内核判定为 Other 并报 `Permission denied`；
4. **彻底突破方案**：以 `su 1000`（即 system 用户身份）执行重协商：
   ```bash
   echo 31183118 | su 1000 sh -c 'echo 2160p60hz420 > /sys/class/display/mode'
   ```
   执行后核验 `cat /sys/class/amhdmitx/amhdmitx0/config`，色深立即升阶为 **`cd = 5`（10-bit Deep Color，时钟频率 594 MHz）**！
5. **模式切换的连带效应**：每次重写 `display/mode` 会使驱动重置 `video/contrast`（回到 15）和 `video_eotf_coeff`（回到 1.0x），因此**必须先激活 10-bit 握手，再注入 BT.2390 曲线与对比度 50**！

---

## 二、最终定型实测参数方案（Golden Settings）

本方案针对明基 TK700 的光学特性，经过多轮实机 A/B 对比调校定型：

| 控制节点 / 寄存器 | 最终注入值 | 核心功能与画质收益 |
| :--- | :--- | :--- |
| **`/sys/class/amhdmitx/amhdmitx0/config`** | `cd = 5` (10-bit) | **4K 60Hz 10-bit 色深真实输出**，消除色彩断层（Banding） |
| **`/sys/module/am_vecm/parameters/hdr_mode`** | `1` | 强制走盒子端高质量色调映射，接管投影仪粗糙算法 |
| **`/sys/module/am_vecm/parameters/range_control`** | `0` (Auto) | 解除纯白天花板钳制，允许白色信号 100% 冲顶至 1023 |
| **`/sys/class/video/contrast`** | `35` | 视频层硬件对比度调节至 35，黑白层次分明且过渡自然 |
| **`/sys/module/am_vecm/parameters/video_eotf_coeff`** | `2760,-507,0,-95,2347,0,-44,-177,2474,1` | **1.10x EOTF 光学矩阵增益**，高光白位通透穿透 |
| **`/sys/module/am_vecm/parameters/knee_setting`** | *(见下文 35 点数组)* | **Ultra Punch 陡峭高光曲线**：暗部深邃不浮灰，高光爆发 |
| **`/sys/module/am_vecm/parameters/knee_factor`** | `256` | 100% 激活定制曲线 |
| **`/sys/module/am_vecm/parameters/knee_interpolation_mode`** | `1` | 启用硬件 **Cubic 三次样条插值** |
| **`/sys/module/am_vecm/parameters/sdr_saturation_offset`** | `50` | 广色域精准补偿，色彩浓烈饱满但不失真 |
| **`/sys/class/amvecm/dnlp` 系列参数** | 完整开启 | 芯片级直方图动态去雾 + **`ve_dnlp_whtext = 16` 白位自动扩展** |
| **`/sys/module/am_vecm/parameters/reload_lut` / `reload_mtx`** | `1` | 立即触发内核重新计算并将 289 点 LUT 烧入硬件 SRAM |

### 35 点 Ultra Punch 曲线数值：
```text
0,16,46,76,107,137,168,198,228,306,388,444,489,529,565,599,630,660,688,714,740,765,789,812,834,856,877,898,919,938,958,977,996,1014,1023
```
- **0 ~ 200 灰阶**：输入 78 $\rightarrow$ 输出 76，输入 202 $\rightarrow$ 输出 198，暗部纯净度极高，黑场深沉如墨；
- **350 ~ 1023 灰阶**：大幅拱起加速，输入 450 $\rightarrow$ 输出 599（+149 增益），输入 636 $\rightarrow$ 输出 765（+129 增益），让阳光、反光与爆炸具备刺眼的穿透力。

---

## 三、一键命令行注入与自动化工具

### 方式 1：双击运行电脑端批处理（推荐）
直接运行 `d:\tools\adb\一键注入BT2390影院映射.bat`，工具会自动推送 `inject_bt2390.sh` 脚本并完成 10-bit 与画质参数双重注入，末尾实时打印寄存器核验证书。

### 方式 2：在任意局域网电脑终端（PowerShell / CMD）中一键执行

```bash
adb connect 192.168.123.98:5555

# 1. 激活 4K 60Hz 10-bit 色深通道 (cd=5)
powershell -Command "echo '31183118`nsetenforce 0`necho 420,10bit > /sys/class/amhdmitx/amhdmitx0/attr`nexit' | .\adb.exe -s 192.168.123.98:5555 shell su"
powershell -Command "echo '31183118`necho 2160p60hz420 > /sys/class/display/mode`nexit' | .\adb.exe -s 192.168.123.98:5555 shell su 1000"

# 2. 注入 Ultra Punch 曲线与高动态对比度 50
powershell -Command "echo '31183118`necho 1 > /sys/module/am_vecm/parameters/hdr_mode`necho 0,16,46,76,107,137,168,198,228,306,388,444,489,529,565,599,630,660,688,714,740,765,789,812,834,856,877,898,919,938,958,977,996,1014,1023 > /sys/module/am_vecm/parameters/knee_setting`necho 1 > /sys/module/am_vecm/parameters/knee_interpolation_mode`necho 256 > /sys/module/am_vecm/parameters/knee_factor`necho 0 > /sys/module/am_vecm/parameters/range_control`necho 50 > /sys/module/am_vecm/parameters/sdr_saturation_offset`necho 0x1 > /sys/class/amvecm/dnlp`necho 1 > /sys/module/am_vecm/parameters/dnlp_en`necho 1 > /sys/module/am_vecm/parameters/dnlp_en_2`necho 8 > /sys/module/am_vecm/parameters/dnlp_adj_level`necho 8 > /sys/module/am_vecm/parameters/ve_dnlp_adj_level`necho 255 > /sys/module/am_vecm/parameters/ve_dnlp_strength`necho 16 > /sys/module/am_vecm/parameters/ve_dnlp_whtext`necho 1 > /sys/module/am_vecm/parameters/ve_dnlp_set_wext`necho blk_ext_en > /sys/class/amvecm/pq_user_set`necho 2760,-507,0,-95,2347,0,-44,-177,2474,1 > /sys/module/am_vecm/parameters/video_eotf_coeff`necho 1 > /sys/module/am_vecm/parameters/reload_lut`necho 1 > /sys/module/am_vecm/parameters/reload_mtx`necho 35 > /sys/class/video/contrast`nexit' | .\adb.exe -s 192.168.123.98:5555 shell su"
```

---

## 四、后续合并至 `T1ZoomHelper.apk` 的开发集成指南

后续将本功能合并至电视常驻服务（`t1_zoom_app`）时，需对以下两个文件进行增量扩展：

### 1. 修改 `AdbClient.java`
在 `AdbClient` 中封装 10-bit 激活与影院映射指令集：

```java
public static void ensure10BitColorOutput() {
    // 检查并强制激活 10-bit (cd=5) 物理通道
    String cfg = execAdbRaw("cat /sys/class/amhdmitx/amhdmitx0/config");
    if (!cfg.contains("cd = 5")) {
        execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo 420,10bit > /sys/class/amhdmitx/amhdmitx0/attr\"");
        // 关键点：以 system (uid 1000) 身份触发 display/mode 重协商
        execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1000 sh -c \"echo 2160p60hz420 > /sys/class/display/mode\"");
    }
}

public static void applyCinemaToneMapping(boolean enable) {
    if (enable) {
        // 先确保 10-bit 握手完成
        ensure10BitColorOutput();
        
        String knee = "0,16,46,76,107,137,168,198,228,306,388,444,489,529,565,599,630,660,688,714,740,765,789,812,834,856,877,898,919,938,958,977,996,1014,1023";
        String eotfCoeff = "2760,-507,0,-95,2347,0,-44,-177,2474,1";
        
        StringBuilder sb = new StringBuilder();
        sb.append("echo 1 > /sys/module/am_vecm/parameters/hdr_mode; ");
        sb.append("echo ").append(knee).append(" > /sys/module/am_vecm/parameters/knee_setting; ");
        sb.append("echo 1 > /sys/module/am_vecm/parameters/knee_interpolation_mode; ");
        sb.append("echo 256 > /sys/module/am_vecm/parameters/knee_factor; ");
        sb.append("echo 0 > /sys/module/am_vecm/parameters/range_control; ");
        sb.append("echo 50 > /sys/module/am_vecm/parameters/sdr_saturation_offset; ");
        sb.append("echo 0x1 > /sys/class/amvecm/dnlp; ");
        sb.append("echo 1 > /sys/module/am_vecm/parameters/dnlp_en; ");
        sb.append("echo 1 > /sys/module/am_vecm/parameters/dnlp_en_2; ");
        sb.append("echo 8 > /sys/module/am_vecm/parameters/dnlp_adj_level; ");
        sb.append("echo 8 > /sys/module/am_vecm/parameters/ve_dnlp_adj_level; ");
        sb.append("echo 255 > /sys/module/am_vecm/parameters/ve_dnlp_strength; ");
        sb.append("echo 16 > /sys/module/am_vecm/parameters/ve_dnlp_whtext; ");
        sb.append("echo 1 > /sys/module/am_vecm/parameters/ve_dnlp_set_wext; ");
        sb.append("echo blk_ext_en > /sys/class/amvecm/pq_user_set; ");
        sb.append("echo ").append(eotfCoeff).append(" > /sys/module/am_vecm/parameters/video_eotf_coeff; ");
        sb.append("echo 1 > /sys/module/am_vecm/parameters/reload_lut; ");
        sb.append("echo 1 > /sys/module/am_vecm/parameters/reload_mtx; ");
        // 注意：video/contrast 必须置于最后执行，防止被 amvecm 初始化覆盖
        sb.append("echo 35 > /sys/class/video/contrast");
        
        execute(sb.toString());
    } else {
        // 恢复原厂自动模式
        execute("echo 2 > /sys/module/am_vecm/parameters/hdr_mode; echo 0 > /sys/module/am_vecm/parameters/knee_factor; echo 0 > /sys/class/video/contrast; echo 1 > /sys/module/am_vecm/parameters/reload_lut");
    }
}
```

### 2. 修改 `ZoomService.java`
1. **SharedPreferences 持久化**：增加 `prefs.getBoolean("cinema_tm_en", true)`；
2. **开机自愈 (`restorePqOnBoot`)**：在恢复亮度、对比度之后，自动执行 `AdbClient.applyCinemaToneMapping(true)`，确保冷启动无需任何手动操作；
3. **Web 端前端（HTTP Server）**：在手机扫码打开的调色盘页面上，增加一个 `【影院高动态映射 (BT.2390)】` 的高保真切换开关（Toggle Switch），支持一键开启/直通对比。

### 3. 核心踩坑与注意事项（必读）
- **顺序依赖**：写入 `echo 35 > /sys/class/video/contrast` 必须在 DNLP 与 `amvecm` 指令之后，因为 `amvecm/contrast` 的内部换算会重写 `video/contrast`；
- **刷新必须触发**：修改 `knee_setting` 或 `video_eotf_coeff` 后，必须紧跟 `echo 1 > /sys/module/am_vecm/parameters/reload_lut`，否则参数仅停留在驱动内存，不会被 VPP 硬件 SRAM 采纳；
- **HDMI 握手防重置**：确保底层 U-Boot ENV 始终固化 `is.bestmode=false` 与 `outputmode=2160p60hz420`，防止热插拔再次触发设置菜单闪退。

