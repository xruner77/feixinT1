# 斐讯 T1 (Amlogic S912) HDMI 4K 60Hz 10bit 开机固化指南

本文档记录了在斐讯 T1 盒子（系统固件版本 1.6T57 / q201）连接电视或投影仪时，**彻底解决开机被 EDID 重置为 8bit、无需任何开机自启脚本、直接在底层闪存固化 4K 60Hz 10bit HDR 输出**的关键步骤与底层原理。

---

## 一、问题背景

1. **开机重置问题**：斐讯 T1 盒子每次冷启动开机或 HDMI 重新插拔握手时，电视/投影仪回传的 EDID 往往会导致系统将显示模式自动重置为保守的 `444,8bit`。
2. **脚本自启壁垒**：此前尝试通过脚本延时写入 `/sys/class/amhdmitx/amhdmitx0/attr`，但在原厂固件（v1.6T57）下遇到以下限制：
   - `/system` 分区默认只读保护；
   - 原厂内置 `/system/xbin/su` 权限为 `750`（`-rwsr-x--- 1 root shell`），且二进制内部硬编码仅允许 UID 0 (root) 和 UID 2000 (shell) 调用，导致普通 Android 软件（如 Kodi、自启管家、Tasker）无法正常自动提权。

---

## 二、解决原理（底层机制）

晶晨（Amlogic）平台的显示由底层的守护进程 **`systemcontrol`**（`/system/bin/systemcontrol`）统一控制：

1. **关键核心开关：`is.bestmode`**
   - 当 `is.bestmode = true` 时：系统开机会强制读取显示设备的 EDID 并自动协商。若投影仪/电视握手信息不完整，就会被强行降级为 8bit。
   - 当 `is.bestmode = false` 时：**彻底关闭自动重协商**，系统开机将无条件读取底层 U-Boot ENV 闪存参数并锁死输出。
2. **4K 60Hz 10bit 的标准格式：`420,10bit`**
   - 在 HDMI 2.0 带宽（18Gbps）下，4K 60Hz RGB/444 10bit（需 20Gbps）超标无法传输；
   - **`420,10bit`** 是国际 Ultra HD 蓝光原盘、流媒体 4K HDR 视频的最标准编码格式（11.1Gbps），画质与片源 1:1 点对点无损，且连接最稳定、不闪屏。

---

## 三、一键固化关键操作步骤

盒机开机连接局域网 ADB（IP 如 `192.168.123.98:5555`）：

### 方式 1：在电脑 PowerShell / CMD 中一键执行（推荐）

依次执行以下两条命令即可完成固化（已自动填入 root 密码 `31183118`）：

```powershell
# 1. 连接盒子
.\adb.exe connect 192.168.123.98:5555

# 2. 写入底层闪存参数并重启显示控制服务
powershell -Command "echo '31183118`ndumpsys system_control -b set ubootenv.var.is.bestmode false`ndumpsys system_control -b set ubootenv.var.outputmode 2160p60hz420`ndumpsys system_control -b set ubootenv.var.hdmimode 2160p60hz420`ndumpsys system_control -b set ubootenv.var.colorattribute 420,10bit`ndumpsys system_control -b set ubootenv.var.2160p60hz420_deepcolor 420,10bit`nstop system_control`nstart system_control`nexit' | .\adb.exe shell su"
```

### 方式 2：在终端 `adb shell` 中手动执行

```bash
# 进入 shell 并获取 root 权限
adb shell
su
# 输入密码：31183118

# 1. 锁死手动模式（关闭 EDID 自动自适应）
dumpsys system_control -b set ubootenv.var.is.bestmode false

# 2. 设置 4K 60Hz 模式
dumpsys system_control -b set ubootenv.var.outputmode 2160p60hz420
dumpsys system_control -b set ubootenv.var.hdmimode 2160p60hz420

# 3. 固化色彩深度为 420 10-bit
dumpsys system_control -b set ubootenv.var.colorattribute 420,10bit
dumpsys system_control -b set ubootenv.var.2160p60hz420_deepcolor 420,10bit

# 4. 即时重启显示服务使配置生效
stop system_control
start system_control
```

---

## 四、验证方法

重启盒子测试（执行 `adb reboot`）：

```bash
# 1. 查看当前 HDMI 实际输出色彩属性
cat /sys/class/amhdmitx/amhdmitx0/attr
# 期望输出：420,10bit

# 2. 查看当前分辨率模式
cat /sys/class/display/mode
# 期望输出：2160p60hz420

# 3. 查看硬件闪存 ENV 分区保存的持久化数据
strings /dev/block/env | grep -E 'color|bestmode|outputmode'
# 期望看到：
# is.bestmode=false
# outputmode=2160p60hz420
# colorattribute=420,10bit
# 2160p60hz420_deepcolor=420,10bit
```

---

## 五、技术备忘

1. **免交互提权命令**：
   原厂 `su` 语法为 `su [UID] [COMMAND]`，免交互输入密码的标准管道写法为：
   ```bash
   echo 31183118 | su 0 <命令>
   ```
2. **闪存分区位置**：
   环境变量保存在 `/dev/block/env`（Amlogic U-Boot env 分区），通过 `dumpsys system_control -b set` 会实时触发写入物理闪存，系统重置（恢复出厂设置）前永久有效。

---

## 六、晶晨底层硬件视频缩放与去黑边控制（解决 Kodi 硬解无法放大问题）

### 1. 问题成因与技术背景
在斐讯 T1（晶晨 S912 芯片）上连接投影仪使用 Kodi 播放宽银幕电影（如 2.35:1 常见上下黑边片源）时，经常会遇到画面无法放大的问题：
- **Kodi 缩放失效原因**：
  Kodi 在启用 **`MediaCodec (Surface)`** 硬件加速模式时，画面直接由底层芯片直通渲染到 `SurfaceView`。当在 Kodi 播放界面的“视频设置”中调节“缩放量 (Zoom)”大于 1.0 时，由于扩大后的视窗会超出屏幕分辨率产生负坐标，Android 底层 SurfaceFlinger 与晶晨驱动会执行硬截断（Clamp），强行将其限制在 `[0, 0, 屏幕宽, 屏幕高]`，导致 **Kodi 界面内只能缩小、无法放大**。
- **为什么绝不能关闭 Surface 硬件加速？**
  如果关闭 `MediaCodec (Surface)`（退回纯 `MediaCodec` 软件纹理管线），每一帧 4K / 1080p 解码数据都需要通过 GPU 进行 YUV->RGB 拷贝，S912 的 Mali-T820 图形核心和 DDR 内存总线会立刻出现性能瓶颈，引发严重掉帧、卡顿和音画不同步。
- **最佳硬件级解法**：
  **保持 `MediaCodec (Surface)` 开启不变**，直接调用晶晨芯片内置的 **VPP（Video Post Processor 视频后处理器）硬件控制层**。该硬件缩放引擎位于解码后、HDMI 输出前的物理管线，可实时对画面进行数字变焦与拉伸，**完全 0 占用 CPU / GPU 算力，丝滑流畅且画质无损**。

---

### 2. 晶晨 VPP 视频底层硬件控制节点一览

所有控制节点位于系统的 `/sys/class/video/` 目录下：

| 节点路径 | 默认值 | 可选值/格式 | 核心作用与使用说明 |
| :--- | :--- | :--- | :--- |
| `/sys/class/video/zoom` | `100` | `100` ~ `300` | **硬件数字放大（裁切上下黑边）**<br>• `100`: 1:1 原始大小<br>• `115`: 放大 115%（轻度裁切黑边）<br>• `125`: 放大 125%（典型 2.35:1 电影刚好铺满 16:9 投影幕布）<br>• `133`: 放大 133%（超宽画幅完全填满） |
| `/sys/class/video/screen_mode` | `0:normal` | `0` ~ `4` | **画面铺满与拉伸模式**<br>• `0`: `normal`（保持原片宽高比，上下/左右带黑边）<br>• `1`: `full stretch`（全屏强制拉伸铺满，快速消除黑边）<br>• `2`: `4:3` 比例<br>• `3`: `16:9` 比例<br>• `4`: `non-linear stretch`（智能非线性拉伸：画面中间保持比例防人物变形，仅平缓拉伸两侧铺满全屏） |
| `/sys/class/video/crop` | `0 0 0 0` | `top left bottom right` | **像素级黑边手动裁切**<br>按顺序指定 上、左、下、右 裁切的物理像素。<br>例如 `130 0 130 0` 表示上下各切掉 130 像素；设为 `0 0 0 0` 恢复全屏不裁切。 |
| `/sys/class/video/axis` | `0 0 -1 -1` | `left top right bottom` | **视频物理输出视窗坐标**<br>定义画面在投影幕布上的具体坐标区域，默认为全屏输出。 |

---

### 3. ADB 快速调节命令（即时生效）

> [!IMPORTANT]
> **权限特别说明**：
> `/sys/class/video/zoom` 节点的系统属主为 `media:system`（权限为 `rw-rw-r--`）。在斐讯 T1 固件的受限 su 环境下，写入 zoom 时建议指定媒体身份（`su 1013:1000`）；而 `screen_mode` 与 `crop` 节点使用 root（`su 0`）即可直接读写。

#### (1) 硬件放大画面（以放大 125% 切黑边铺满 16:9 幕布为例）
```bash
adb shell "echo 31183118 | su 1013:1000 sh -c 'echo 125 > /sys/class/video/zoom'"
```

#### (2) 恢复 100% 原始比例
```bash
adb shell "echo 31183118 | su 1013:1000 sh -c 'echo 100 > /sys/class/video/zoom'"
```

#### (3) 切换画面拉伸模式
```bash
# 强制全屏拉伸（铺满屏幕）
adb shell "echo 31183118 | su 0 sh -c 'echo 1 > /sys/class/video/screen_mode'"

# 智能非线性拉伸（中间人物保真、两侧拉伸铺满）
adb shell "echo 31183118 | su 0 sh -c 'echo 4 > /sys/class/video/screen_mode'"

# 恢复原片默认比例
adb shell "echo 31183118 | su 0 sh -c 'echo 0 > /sys/class/video/screen_mode'"
```

#### (4) 像素级切除上下黑边
```bash
# 上下各切除 130 像素
adb shell "echo 31183118 | su 0 sh -c 'echo 130 0 130 0 > /sys/class/video/crop'"

# 清空裁切（恢复原状）
adb shell "echo 31183118 | su 0 sh -c 'echo 0 0 0 0 > /sys/class/video/crop'"
```

---

### 4. 辅助批处理工具：`set_video_zoom.bat`

在电脑端 `d:\tools\adb\` 目录下提供了 `set_video_zoom.bat`。双击运行后提供可视化菜单，支持在播放电影时一键切换 115%、125%、133% 变焦、非线性拉伸或一键复原，无需手动记忆命令。

---

### 5. 永久免电脑：开机自启 TV 端控制程序 `T1ZoomHelper.apk` (v3.0 影音画质控制中心)

为了彻底告别“每次开机都需要电脑拉起”的痛点，已编译并升级安装了专用的电视端自启服务：`d:\tools\adb\T1ZoomHelper.apk`（包名：`com.phicomm.t1zoom`，当前版本：`v3.0`）。

#### (1) 底层免 Root 弹窗原理
- 斐讯 T1 系统属性为 `ro.adb.secure=0`（免密网络调试），且端口 5555 常开。
- APK 启动后，直接在盒子内部通过纯 Java Socket 环回连接本地 `127.0.0.1:5555`，直接获取 `shell`（UID 2000）身份，并自动向系统管道传入密码 `31183118` 调用 `su` 操作晶晨底层硬件。
- **用户无需刷 SuperSU/Magisk，电视端不弹任何授权框，自动完成提权并操作芯片硬件**。

#### (2) 多事件自启动机制（开机、快速启动、网络就绪秒级拉起）
针对 Android 7.1 系统可能遗漏或延迟广播的问题，注册了多重全冗余系统广播触发器（Priority 1000）：
- `android.intent.action.BOOT_COMPLETED`（冷重启通电开机）
- `android.intent.action.QUICKBOOT_POWERON`（晶晨快速开机）
- `android.net.conn.CONNECTIVITY_CHANGE`（网络连通建立）
- `android.intent.action.USER_PRESENT`（遥控器唤醒亮屏）
- `android.intent.action.MEDIA_MOUNTED`（外接移动硬盘/U盘挂载）
- 内置 3 秒防抖保护，多重事件同时触发时保证唯一实例平滑启动。
- **实测验证**：在冷重启（`reboot`）后无需打开任何应用，后台微型服务已 100% 自动就绪，端口 8989 自动监听！

#### (3) 前台服务静默保活（零遮挡屏幕、防杀后台）
- **常驻通知会遮挡屏幕吗？**
  **答案是：绝对不会，0 像素干扰！**
  服务采用 Android 标准前台机制（`startForeground`），但配置了 **`Notification.PRIORITY_MIN`（极低优先级）**。在 Android TV 系统中，极低优先级通知绝不产生 Heads-up 浮动横幅、不弹窗、不响铃，且在全屏播放视频或主界面时**完全不可见，绝对不会遮挡屏幕一丝一毫**。
- **防止低内存被杀（LMK 免疫）**：
  前台服务身份将进程 OOM 优先级提升至系统关键层级，即便长时间运行多个大型视频 App，后台控制服务也不会被系统误杀；若意外被杀，`START_STICKY` 也会促使系统在几秒内自动复活。

#### (4) Kodi 自动视图联动（双重去黑边保障）
- 当通过手机遥控器触发硬件放大（如 125%）时，服务会自动探测并向本地 Kodi 端口 9090 发送 JSON-RPC 指令（`Player.SetViewMode` -> `zoom`）；
- 自动将 Kodi 的底层 `SurfaceView` 视窗强制展开为全屏，从源头上打通晶晨 VPP 硬件缩放的物理边界，彻底杜绝“仅在黑边内缩放”的问题。

#### (5) 网页端全新升级（v3.0）：画面画质与色彩滑块微调
手机或电脑浏览器打开 `http://192.168.123.98:8989`，全新界面已集成：
1. **☀️ 亮度调节 (Brightness)**：滑块范围 `-50` ~ `+50`，支持实时拖动微调，配有独立 **【↺ 复位】** 按钮；
2. **🌗 对比度调节 (Contrast)**：滑块范围 `-30` ~ `+30`，支持实时拖动微调，配有独立 **【↺ 复位】** 按钮；
3. **🌈 色彩饱和度 (Saturation/Color)**：滑块范围 `-50` ~ `+50`，支持实时拖动微调，配有独立 **【↺ 复位】** 按钮；
4. **✨ 硬件动态对比度 (DNLP 去灰)**：一键开关芯片硬件级直方图对比度优化；
5. **🔄 全部画质复位**：一键将所有亮度、对比度、色彩参数重置回原厂默认值（0）。
6. **💾 开机自动记忆（持久化存储）**：
   通过 Android 原生 `SharedPreferences` 持久化，拖动滑块后自动静默保存。盒子冷开机、断电重启或快速启动时，后台服务自启后会自动将您上次调好的亮度、对比度、色彩与 DNLP 重新灌入芯片物理寄存器中，彻底省去开机重复调节的繁琐。
7. **开放 REST API**：
   - 获取当前参数：`/api/status`
   - 调节画质：`/api/pq?type=brightness&val=15`、`/api/pq?type=contrast&val=10`、`/api/pq?type=saturation&val=20`、`/api/pq?type=dnlp&val=1`
   - 独立复位：`/api/pq?type=reset&item=brightness`、`/api/pq?type=reset&item=contrast`、`/api/pq?type=reset&item=saturation`、`/api/pq?type=reset&item=all`
   - 变焦拉伸：`/api/zoom?val=125`、`/api/mode?val=1`、`/api/reset`

---



## 七、斐讯 T1 硬件解码（MediaCodec Surface）崩溃失效与底层根治修复

### 1. 现象与故障表现
在 Kodi 或系统内置播放器中播放 4K / 1080p 视频时，发现**硬件解码完全失效**，直接回退到 CPU 软解，导致画面严重卡顿、音画不同步、CPU 占用飙升至 100%。

### 2. 底层崩溃根因分析（Tombstone 抓取诊断）
通过分析系统崩溃日志 `/data/tombstones/tombstone_00`~`08`，捕获到核心服务 `media.codec`（PID 4011 / 12993）崩溃时的调用栈：
```text
#05 pc 00006a39  /system/lib/libminijail.so (log_sigsys_handler+40)
#07 pc 00049cf0  /system/lib/libc.so (sendto+16)
#08 pc 0001e173  /system/lib/libc.so (send+12)
#09 pc 0002052b  /system/lib/libc.so (__system_property_set+206)
#10 pc 00022da1  /system/lib/libOmxVideo.so (OMX_GetHandle+176)
#11 pc 0001b3d1  /system/lib/libstagefright_omx.so (OMXMaster::makeComponentInstance+148)
```
- **崩溃链路**：
  1. 播放器尝试调用晶晨硬件解码器（`OMX.amlogic.hevc.decoder.awesome` 或 `OMX.amlogic.avc.decoder.awesome`）；
  2. 晶晨驱动库 `libOmxVideo.so` 在 `OMX_GetHandle` 初始化阶段，无条件调用 `property_set("media.debuginfo.vhwaccelerated", "YES")`；
  3. libc 的 `property_set` 尝试通过 UNIX Domain Socket 向 `init` 发送属性设置指令，底层调用了 `sendto()` 系统调用；
  4. Android 7.1 系统在 `mediacodec` 启动时载入了沙箱安全策略 `/system/etc/seccomp_policy/mediacodec-seccomp.policy`；
  5. 该策略文件中**遗漏了 `sendto` 系统调用白名单**，Linux 内核立即判定非法系统调用并抛出 `SIGSYS`；
  6. `libminijail` 捕获到非法系统调用后直接调用 `abort()`，导致 `media.codec` 守护进程瞬时崩溃自杀；
  7. Kodi 与内置播放器与解码服务的 Binder 连接断开（报错 `OMX/mediaserver died`），只能无奈回退至 CPU 软解。

### 3. 底层物理闪存无损热补丁（已实施，开机永久生效）
由于 `/system` 分区默认被挂载为只读，且常规方式无法直接写入，我们通过晶晨底层块设备映射：
1. 定位到 `/system/etc/seccomp_policy/mediacodec-seccomp.policy` 所在的物理 ext4 数据块为系统分区的 `327225` 号块；
2. 结合闪存分区物理起始扇区（`1794048`），精准定位到物理 eMMC `/dev/block/mmcblk0` 的第 `551481` 号 4KB 物理块；
3. 制作精准补丁块（严格保持 859 字节文件大小不变，将头部空置注释替换为）：
   ```text
   sendto: 1
   recvfrom: 1
   sendmsg: 1
   recvmsg: 1
   ```
4. 将修补后的 4096 字节数据块直接写入物理闪存并刷新文件系统缓存，使 `/system/etc/seccomp_policy/mediacodec-seccomp.policy` 拥有了网络套接字系统调用白名单。
5. **验证结果**：
   使用自编 `TestCodec` 工具对 `OMX.amlogic.hevc.decoder.awesome`（4K HEVC 10bit）和 `OMX.amlogic.avc.decoder.awesome`（AVC/H.264）进行完整实例化与流水线测试，全部 `create`、`configure`、`start`、`release` 一次通过，`media.codec` 进程持续稳定存活，硬解功能 100% 恢复正常！

---

## 八、宽银幕电影上下黑边无法消除（画面仅在黑边内缩放）的原理解析与解决

### 1. 现象与底层原理剖析
在播放 2.35:1 / 2.39:1 电影时，部分情况下调节晶晨芯片硬件缩放（`/sys/class/video/zoom`），会发现**画面只在现有的黑边视窗内部被放大裁切，上下两条粗黑边依然存在**。

- **根本原因（视窗物理截断）**：
  1. Kodi 默认的视图模式是 **“正常 (Normal)”**，它会根据片源自身宽高比（例如 4K 片源有效分辨率为 `3840x1608`）主动将 Android 底层图层 `SurfaceView` 的尺寸也调整为 `3840x1608`，并自动居中放置在 `3840x2160` 的屏幕中央；
  2. 晶晨硬件合成器 `hwcomposer.amlogic.so` 每帧会自动将该图层的物理边界写入芯片寄存器节点 `/sys/class/video/axis`：
     ```text
     axis: 0 278 3839 1883
     ```
     （即视频物理有效范围被硬性锁死在 Y 轴第 278 行至 1883 行之间，顶部 0~277 行与底部 1884~2159 行被留空为黑边）；
  3. 晶晨芯片的 VPP 硬件缩放是以 `axis` 视窗为物理渲染边界的。当 `axis` 自身被限制在黑边内部时，即使硬件放大 125%，超出的画面也会在视窗边界处被硬件截断，无法延伸到黑边区域。

---

### 2. 彻底解决方法（全屏视窗打通）

#### 方式 1：规范 Kodi 播放器的全局默认视图模式（切勿误选“拉伸 16:9”）

> [!CAUTION]
> **避坑警告（严重）：切勿将【拉伸 16:9】设为默认设置！**
> 若在 Kodi 的“视频设置”中误将 **【拉伸 16:9 (Stretch 16:9)】** 点击了“设为所有媒体的默认设置”，Kodi 会将配置写入 `guisettings.xml` 的 `<defaultvideosettings><viewmode>4</viewmode>`。这会导致**以后无论播放任何电影，开机开播都会默认强制拉伸铺满全屏，造成人物严重变形变扁，必须手动点一次恢复才正常**。

**正确配置方案（二选一）**：
- **方案 A（推荐，原画默认 + 手机遥控按需切黑边）**：
  保持 Kodi 全局默认设置视图模式为 **【正常 (Normal)】**（`viewmode=0`）。
  平时的电视剧、16:9 动画均以 100% 原始比例播放；播放 2.35:1 宽银幕电影时，拿起手机轻触 **【125% 铺满幕布】**，后台服务会自动与 Kodi 进行 RPC 联动切换，无损切除黑边。
- **方案 B（Kodi 内部等比缩放默认）**：
  播放宽银幕电影时，选择 **【缩放 (Zoom)】**（`viewmode=1`，注意是 Zoom 等比放大，绝对不要选 Stretch 16:9），再滑动到底部点击 **【设为所有媒体的默认设置】**。

> [!TIP]
> **已为您自动修复**：
> 系统已通过底层将 Kodi 的 `guisettings.xml` 中的全局默认设置从 `4`（拉伸）恢复为 `0`（正常 Normal），以后打开任何视频均默认保真比例开播，不会再出现“默认拉伸畸变”的现象。

#### 方式 2：通过 Kodi 本地 JSON-RPC 接口远程程控
Kodi 在本地回环 `127.0.0.1:9090` 常驻开启了 JSON-RPC 接口，可以通过发送标准指令直接控制播放器视窗几何属性：
```bash
# 一键切换为【缩放 Zoom】模式（解除黑边视窗锁定，推荐！）
echo '{"jsonrpc": "2.0", "method": "Player.SetViewMode", "params": {"viewmode": "zoom"}, "id": 1}' | nc 127.0.0.1 9090

# 一键切换为【拉伸 16:9】模式
echo '{"jsonrpc": "2.0", "method": "Player.SetViewMode", "params": {"viewmode": "stretch16x9"}, "id": 1}' | nc 127.0.0.1 9090

# 恢复默认【正常 Normal】模式（带黑边）
echo '{"jsonrpc": "2.0", "method": "Player.SetViewMode", "params": {"viewmode": "normal"}, "id": 1}' | nc 127.0.0.1 9090
```
该接口已被集成到电视端微服务，手机遥控器与批处理工具可自动与 Kodi 进行联动协同控制。

---

### 3. 为什么“智能拉伸 (非线性拉伸)”不适合 2.35:1 宽银幕电影？

不少用户在尝试去黑边时会选择“智能非线性拉伸 (Non-linear Stretch)”，但会发现画面比例不对、人物变形，其根本原因如下：

1. **智能拉伸的设计初衷**：
   “非线性拉伸”是电视工业中专门为 **4:3 老电影/老电视节目适配 16:9 屏幕（消除左右两侧竖黑边）** 设计的技术。它在水平 X 轴上将画面中央 50% 保持 1:1 正常比例（防人物变胖），仅将左右两侧 25% 渐进式水平拉宽，从而遮盖左右黑边。
2. **在 2.35:1 电影上的失效原因**：
   - 2.35:1 宽银幕电影的黑边是在 **【上下两端 (Y轴)】**，画面本身的宽度（X轴）早已经占满了 16:9 幕布；
   - 此时若开启“非线性拉伸”，算法会在原本就占满的水平方向上强行进一步扭曲拉伸画面边缘，导致画面两侧的人物脸部、建筑线条出现严重的非线性横向畸变；
   - 且其垂直方向并未等比展开，无法真正自然地消除上下黑边。
3. **宽银幕电影的最佳去黑边方案：【等比变焦 (Zoom)】**：
   - 2.35:1 电影消除上下黑边，唯有使用 **等比缩放 (Zoom)**：X 轴与 Y 轴以相同的系数（约 130%~133%）同步等比放大。
   - **画面 1:1 点对点无畸变**，画面中央和边缘的人物身材、比例完全自然真实（原理与电影院 IMAX 满屏版完全一致），这才是最完美的宽银幕满屏方案。

