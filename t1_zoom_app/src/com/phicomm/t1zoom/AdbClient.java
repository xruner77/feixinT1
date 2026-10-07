package com.phicomm.t1zoom;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class AdbClient {
    private static final String TAG = "T1AdbClient";

    // ADB Protocol Constants
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

    // Channel Constants
    public static final int CHANNEL_UNKNOWN = 0;
    public static final int CHANNEL_LOCAL_SU = 1;
    public static final int CHANNEL_LOCAL_PHICOMM = 2;
    public static final int CHANNEL_ADB_DIRECT = 3;
    public static final int CHANNEL_ADB_MEDIA = 4;
    public static final int CHANNEL_ADB_PHICOMM = 5;
    public static final int CHANNEL_ADB_SU_C = 6;

    private static int activeChannel = CHANNEL_UNKNOWN;
    private static String activeChannelDesc = "未检测";

    // Current State
    private static int curZoom = 100;
    private static int curMode = 0;
    private static int curBrightness = 0;
    private static int curContrast = 0;
    private static int curSaturation = 0;
    private static int curHue = 0;
    private static int curDnlp = 0;
    private static int curCm = 0;

    public static int getCurZoom() { return curZoom; }
    public static int getCurMode() { return curMode; }
    public static int getCurBrightness() { return curBrightness; }
    public static int getCurContrast() { return curContrast; }
    public static int getCurSaturation() { return curSaturation; }
    public static int getCurHue() { return curHue; }
    public static int getCurDnlp() { return curDnlp; }
    public static int getCurCm() { return curCm; }

    public static void setCurBrightness(int b) { curBrightness = b; }
    public static void setCurContrast(int c) { curContrast = c; }
    public static void setCurSaturation(int s) { curSaturation = s; }
    public static void setCurHue(int h) { curHue = h; }
    public static void setCurDnlp(int d) { curDnlp = d; }
    public static void setCurCm(int cm) { curCm = cm; }

    // =========================================================================
    // Multi-Channel Adaptive Execution Engine
    // =========================================================================

    private static byte[] buildMessage(int cmd, int arg0, int arg1, byte[] payload) {
        int length = (payload != null) ? payload.length : 0;
        int checksum = 0;
        if (payload != null) {
            for (byte b : payload) {
                checksum += (b & 0xff);
            }
        }
        int magic = cmd ^ 0xffffffff;

        ByteBuffer bb = ByteBuffer.allocate(24 + length);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd);
        bb.putInt(arg0);
        bb.putInt(arg1);
        bb.putInt(length);
        bb.putInt(checksum);
        bb.putInt(magic);
        if (payload != null) {
            bb.put(payload);
        }
        return bb.array();
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws Exception {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, total, len - total);
            if (n < 0) throw new Exception("EOF reading stream");
            total += n;
        }
    }

    public static boolean isAdbPortOpen() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1000);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static String execLocal(String[] cmdArray) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmdArray);
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERR: " + e.getMessage();
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }
    }

    public static synchronized String execAdbRaw(String cmd) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(3000);
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1. Send CNXN
            byte[] cnxnPayload = new byte[]{'h', 'o', 's', 't', ':', ':', 0};
            out.write(buildMessage(A_CNXN, A_VERSION, MAX_DATA, cnxnPayload));
            out.flush();

            // Read CNXN response (24 bytes header)
            byte[] header = new byte[24];
            readFully(in, header, 24);

            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cnxnCmd = bb.getInt();
            bb.getInt(); bb.getInt(); // arg0, arg1
            int dlen = bb.getInt();
            if (dlen > 0) {
                byte[] banner = new byte[dlen];
                readFully(in, banner, dlen);
            }

            if (cnxnCmd == A_AUTH) {
                return "ERR: ADB 需要 RSA 调试授权 (ro.adb.secure=1)";
            }

            // 2. Send OPEN
            String serviceCmd = "shell:" + cmd + "\0";
            byte[] openPayload = serviceCmd.getBytes("UTF-8");
            out.write(buildMessage(A_OPEN, 1, 0, openPayload));
            out.flush();

            // Read OPEN response
            readFully(in, header, 24);
            bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int respCmd = bb.getInt();
            int remoteId = bb.getInt();
            if (respCmd != A_OKAY) {
                return "ERR: ADB OPEN 被拒绝 (0x" + Integer.toHexString(respCmd) + ")";
            }

            // 3. Read stream packets until CLSE
            StringBuilder sb = new StringBuilder();
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 3000) {
                try {
                    readFully(in, header, 24);
                    bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    int pktCmd = bb.getInt();
                    bb.getInt(); bb.getInt();
                    int pktLen = bb.getInt();

                    if (pktLen > 0) {
                        byte[] data = new byte[pktLen];
                        readFully(in, data, pktLen);
                        sb.append(new String(data, "UTF-8"));
                        out.write(buildMessage(A_OKAY, 1, remoteId, null));
                        out.flush();
                    }

                    if (pktCmd == A_CLSE) {
                        break;
                    }
                } catch (Exception e) {
                    break;
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERR: " + e.getMessage();
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static synchronized int detectChannel(boolean forceRecheck) {
        if (!forceRecheck && activeChannel != CHANNEL_UNKNOWN) {
            return activeChannel;
        }

        // 1. Try local standard su (Magisk / SuperSU / Open root)
        String res = execLocal(new String[]{"su", "-c", "id"});
        if (res.contains("uid=0")) {
            activeChannel = CHANNEL_LOCAL_SU;
            activeChannelDesc = "本地免密 Root (su -c)";
            Log.i(TAG, "Selected channel: " + activeChannelDesc);
            return activeChannel;
        }

        // 2. Try local Phicomm password su
        res = execLocal(new String[]{"sh", "-c", "printf \"31183118\\n\" | /system/xbin/su 0 sh -c id"});
        if (res.contains("uid=0")) {
            activeChannel = CHANNEL_LOCAL_PHICOMM;
            activeChannelDesc = "本地斐讯专有 Root (printf 31183118 | su 0)";
            Log.i(TAG, "Selected channel: " + activeChannelDesc);
            return activeChannel;
        }

        // 3. Try ADB 127.0.0.1:5555
        if (isAdbPortOpen()) {
            // 3a. ADB Direct uid=0
            res = execAdbRaw("id");
            if (res.contains("uid=0")) {
                activeChannel = CHANNEL_ADB_DIRECT;
                activeChannelDesc = "ADB 网络原生 Root (127.0.0.1:5555 direct)";
                Log.i(TAG, "Selected channel: " + activeChannelDesc);
                return activeChannel;
            }

            // 3b. ADB with Phicomm Media su (Optimal for stock T1 zoom)
            res = execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c id");
            if (res.contains("uid=1013") || res.contains("media")) {
                activeChannel = CHANNEL_ADB_MEDIA;
                activeChannelDesc = "ADB 斐讯媒体授权 (127.0.0.1:5555 + su 1013:1000)";
                Log.i(TAG, "Selected channel: " + activeChannelDesc);
                return activeChannel;
            }

            // 3c. ADB with Phicomm Root su 0
            res = execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 0 sh -c id");
            if (res.contains("uid=0")) {
                activeChannel = CHANNEL_ADB_PHICOMM;
                activeChannelDesc = "ADB 斐讯管理员授权 (127.0.0.1:5555 + su 0)";
                Log.i(TAG, "Selected channel: " + activeChannelDesc);
                return activeChannel;
            }

            // 3d. ADB with standard su -c
            res = execAdbRaw("su -c id");
            if (res.contains("uid=0")) {
                activeChannel = CHANNEL_ADB_SU_C;
                activeChannelDesc = "ADB 通用免密 Root (127.0.0.1:5555 + su -c)";
                Log.i(TAG, "Selected channel: " + activeChannelDesc);
                return activeChannel;
            }
        }

        activeChannel = CHANNEL_UNKNOWN;
        activeChannelDesc = "未找到可用提权通道 (本地su失败且ADB 5555未开启)";
        Log.w(TAG, "No working channel found!");
        return activeChannel;
    }

    public static synchronized String execute(String cmd) {
        int channel = detectChannel(false);
        String res;
        switch (channel) {
            case CHANNEL_LOCAL_SU:
                res = execLocal(new String[]{"su", "-c", cmd});
                break;
            case CHANNEL_LOCAL_PHICOMM:
                res = execLocal(new String[]{"sh", "-c", "printf \"31183118\\n\" | /system/xbin/su 0 sh -c '" + cmd.replace("'", "'\\''") + "'"});
                break;
            case CHANNEL_ADB_DIRECT:
                res = execAdbRaw(cmd);
                break;
            case CHANNEL_ADB_MEDIA:
                res = execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"" + cmd.replace("\"", "\\\"") + "\"");
                break;
            case CHANNEL_ADB_PHICOMM:
                res = execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"" + cmd.replace("\"", "\\\"") + "\"");
                break;
            case CHANNEL_ADB_SU_C:
                res = execAdbRaw("su -c \"" + cmd.replace("\"", "\\\"") + "\"");
                break;
            default:
                // Fallback attempt via ADB media
                res = execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"" + cmd.replace("\"", "\\\"") + "\"");
                if (res.startsWith("ERR:")) {
                    return "ERR: 提权通道不可用 (" + activeChannelDesc + ", " + res + ")";
                }
                break;
        }
        return res;
    }

    public static String readSysfs(String path) {
        try {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                FileInputStream fis = new FileInputStream(f);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                String line = reader.readLine();
                reader.close();
                fis.close();
                if (line != null) return line.trim();
            }
        } catch (Exception ignored) {}

        String out = execute("cat " + path + " 2>/dev/null").trim();
        if (out.startsWith("ERR:")) return "";
        return out;
    }

    // =========================================================================
    // Zoom and Video Control APIs with Direct Verification
    // =========================================================================

    public static String setZoom(int zoom) {
        curZoom = zoom;
        // 1. Try standard execute write
        execute("echo " + zoom + " > /sys/class/video/zoom");
        String verify = readSysfs("/sys/class/video/zoom").trim();
        if (String.valueOf(zoom).equals(verify)) {
            return "OK:" + verify;
        }

        // 2. If failed, attempt direct media su via ADB
        if (isAdbPortOpen()) {
            execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"echo " + zoom + " > /sys/class/video/zoom\"");
            verify = readSysfs("/sys/class/video/zoom").trim();
            if (String.valueOf(zoom).equals(verify)) {
                activeChannel = CHANNEL_ADB_MEDIA;
                activeChannelDesc = "ADB 斐讯媒体授权 (su 1013:1000)";
                return "OK:" + verify;
            }

            // 3. Attempt root su 0 via ADB
            execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo " + zoom + " > /sys/class/video/zoom\"");
            verify = readSysfs("/sys/class/video/zoom").trim();
            if (String.valueOf(zoom).equals(verify)) {
                activeChannel = CHANNEL_ADB_PHICOMM;
                activeChannelDesc = "ADB 斐讯管理员授权 (su 0)";
                return "OK:" + verify;
            }
        }

        return "ERR: 变焦写入未生效 (当前节点值=" + verify + ", 期望=" + zoom + ", 通道=" + activeChannelDesc + ")";
    }

    public static boolean isScreenModeMatched(String verify, int expectedMode) {
        if (verify == null || verify.isEmpty()) return false;
        String v = verify.trim();
        if (v.equals(String.valueOf(expectedMode))) return true;
        if (v.startsWith(expectedMode + ":") || v.startsWith(expectedMode + " ")) return true;
        String[] parts = v.split("[:\\s]");
        return parts.length > 0 && parts[0].trim().equals(String.valueOf(expectedMode));
    }

    public static String setScreenMode(int mode) {
        curMode = mode;
        execute("echo " + mode + " > /sys/class/video/screen_mode");
        String verify = readSysfs("/sys/class/video/screen_mode").trim();
        if (isScreenModeMatched(verify, mode)) {
            return "OK:" + verify;
        }

        // 备用重试：如果本地写入未生效，尝试通过 ADB root 注入写入
        if (isAdbPortOpen()) {
            execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo " + mode + " > /sys/class/video/screen_mode\"");
            verify = readSysfs("/sys/class/video/screen_mode").trim();
            if (isScreenModeMatched(verify, mode)) {
                return "OK:" + verify;
            }
        }

        return "ERR: 模式写入未生效 (当前=" + verify + ", 期望=" + mode + ")";
    }

    public static String setBrightness(int val) {
        curBrightness = val;
        execute("echo " + val + " > /sys/class/amvecm/brightness; echo " + val + " > /sys/class/video/brightness");
        return "OK:" + val;
    }

    public static String setContrast(int val) {
        curContrast = val;
        execute("echo " + val + " > /sys/class/video/contrast");
        return "OK:" + val;
    }

    public static String setSaturation(int val) {
        curSaturation = val;
        execute("echo " + curSaturation + " " + curHue + " > /sys/class/amvecm/saturation_hue_pre");
        return "OK:" + val;
    }

    public static String setHue(int val) {
        curHue = val;
        execute("echo " + curSaturation + " " + curHue + " > /sys/class/amvecm/saturation_hue_pre");
        return "OK:" + val;
    }

    public static String setCm(int val) {
        curCm = val;
        if (val == 1) {
            execute("echo 1 > /sys/module/am_vecm/parameters/cm_en; echo 1 > /sys/module/am_vecm/parameters/cm_level");
        } else {
            execute("echo 0 > /sys/module/am_vecm/parameters/cm_en");
        }
        return "OK:" + val;
    }

    public static String setDnlp(int val) {
        curDnlp = val;
        if (val == 1) {
            execute("echo 0x1 > /sys/class/amvecm/dnlp; echo 1 > /sys/module/am_vecm/parameters/dnlp_en; echo 1 > /sys/module/am_vecm/parameters/dnlp_en_2; echo 8 > /sys/module/am_vecm/parameters/dnlp_adj_level; echo 8 > /sys/module/am_vecm/parameters/ve_dnlp_adj_level; echo 255 > /sys/module/am_vecm/parameters/ve_dnlp_strength; echo blk_ext_en > /sys/class/amvecm/pq_user_set");
        } else {
            execute("echo 0x0 > /sys/class/amvecm/dnlp; echo 0 > /sys/module/am_vecm/parameters/dnlp_en; echo 0 > /sys/module/am_vecm/parameters/dnlp_en_2; echo blk_ext_dis > /sys/class/amvecm/pq_user_set");
        }
        return "OK:" + val;
    }

    public static String resetAll() {
        curZoom = 100;
        curMode = 0;
        setZoom(100);
        execute("echo 0 > /sys/class/video/screen_mode; echo 0 0 0 0 > /sys/class/video/crop");
        return "OK:resetAll";
    }

    public static String resetAllPq() {
        curBrightness = 0;
        curContrast = 0;
        curSaturation = 0;
        curHue = 0;
        curDnlp = 0;
        curCm = 0;
        execute("echo 0 > /sys/class/amvecm/brightness; echo 0 > /sys/class/video/brightness; echo 0 > /sys/class/video/contrast; echo 0 > /sys/class/amvecm/contrast; echo 0 0 > /sys/class/amvecm/saturation_hue_pre; echo 0x0 > /sys/class/amvecm/dnlp; echo 0 > /sys/module/am_vecm/parameters/dnlp_en; echo 0 > /sys/module/am_vecm/parameters/dnlp_en_2; echo blk_ext_dis > /sys/class/amvecm/pq_user_set; echo 0 > /sys/module/am_vecm/parameters/cm_en");
        return "OK:resetAllPq";
    }

    public static void initPqValues() {
        try {
            String b = readSysfs("/sys/class/amvecm/brightness");
            if (!b.isEmpty()) curBrightness = Integer.parseInt(b);
        } catch (Exception ignored) {}
        try {
            String c = readSysfs("/sys/class/amvecm/contrast");
            if (!c.isEmpty()) curContrast = Integer.parseInt(c);
        } catch (Exception ignored) {}
        try {
            String s = readSysfs("/sys/class/amvecm/saturation_hue_pre");
            String[] parts = s.split("\\s+");
            if (parts.length > 0) curSaturation = Integer.parseInt(parts[0]);
            if (parts.length > 1) curHue = Integer.parseInt(parts[1]);
        } catch (Exception ignored) {}
        try {
            String d = readSysfs("/sys/module/am_vecm/parameters/dnlp_en");
            if (!d.isEmpty()) curDnlp = Integer.parseInt(d);
        } catch (Exception ignored) {}
        try {
            String cm = readSysfs("/sys/module/am_vecm/parameters/cm_en");
            if (!cm.isEmpty()) curCm = Integer.parseInt(cm);
        } catch (Exception ignored) {}
        try {
            String m = readSysfs("/sys/class/video/screen_mode");
            if (!m.isEmpty()) {
                String[] parts = m.split("[:\\s]");
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    curMode = Integer.parseInt(parts[0].trim());
                }
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Environment Doctor & Deep Diagnostic Probe
    // =========================================================================

    public static class DiagnosticResult {
        public String model = Build.MANUFACTURER + " " + Build.MODEL;
        public String romDisplay = Build.DISPLAY;
        public String androidVer = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        public String selinux = "未知";

        public String rootChannel = "未知";
        public boolean rootOk = false;
        public boolean adbPortOpen = false;

        public boolean seccompPatched = false;
        public String seccompInfo = "";
        public String mediaCodecPid = "";

        public String zoomVal = "";
        public boolean zoomTestWritable = false;
        public String screenModeVal = "";
        public String axisVal = "";
        public String cropVal = "";

        public boolean videoPlaying = false;
        public String frameCount = "0";
        public String videoStatus = "";

        public boolean kodiRunning = false;
        public boolean kodiRpcOk = false;
        public String kodiRpcMsg = "";

        public List<String> issues = new ArrayList<String>();
        public List<String> suggestions = new ArrayList<String>();
    }

    public static DiagnosticResult runFullDiagnostic() {
        DiagnosticResult r = new DiagnosticResult();

        // 1. System info & SELinux
        try {
            String se = readSysfs("/sys/fs/selinux/enforce");
            if ("1".equals(se)) r.selinux = "Enforcing (强制拦截)";
            else if ("0".equals(se)) r.selinux = "Permissive (宽容模式)";
            else r.selinux = se.isEmpty() ? "未知" : se;
        } catch (Exception ignored) {}

        // 2. Root channel detection
        r.adbPortOpen = isAdbPortOpen();
        int ch = detectChannel(true);
        r.rootChannel = activeChannelDesc;
        r.rootOk = (ch != CHANNEL_UNKNOWN);

        if (!r.rootOk) {
            r.issues.add("Root 提权通道不可用：本地 su 与 ADB 127.0.0.1:5555 均无法获得管理权限。");
            r.suggestions.add("请确保盒子已开启网络 ADB 调试，或在固件授权管理中授予该应用 Root 权限。");
        }

        // 3. Seccomp patch verification
        String policy = execute("cat /system/etc/seccomp_policy/mediacodec-seccomp.policy 2>/dev/null");
        if (policy.contains("sendto: 1")) {
            r.seccompPatched = true;
            r.seccompInfo = "已包含 sendto 白名单 (硬解热补丁生效)";
        } else if (policy.isEmpty() || policy.startsWith("ERR:")) {
            r.seccompPatched = false;
            r.seccompInfo = "无法读取策略文件 (可能缺少 Root 或文件不存在)";
            r.issues.add("无法读取 mediacodec-seccomp.policy，无法验证硬解补丁。");
        } else {
            r.seccompPatched = false;
            r.seccompInfo = "缺少 sendto 白名单 (硬解热补丁未生效！)";
            r.issues.add("系统 Seccomp 策略缺少 sendto 白名单，导致硬件解码器崩溃！");
            r.suggestions.add("请重新运行电脑端【一键自适应打补丁.bat】，并在提示成功后重启盒子。");
        }

        String pid = execute("pidof media.codec 2>/dev/null").trim();
        r.mediaCodecPid = pid.isEmpty() ? "未运行/未获取到" : pid;

        // 4. Hardware Sysfs Nodes
        r.zoomVal = readSysfs("/sys/class/video/zoom");
        r.screenModeVal = readSysfs("/sys/class/video/screen_mode");
        r.axisVal = readSysfs("/sys/class/video/axis");
        r.cropVal = readSysfs("/sys/class/video/crop");

        // Test writing zoom
        if (!r.zoomVal.isEmpty()) {
            String testVal = "100".equals(r.zoomVal) ? "115" : "100";
            String testRes = setZoom(Integer.parseInt(testVal));
            if (testRes.startsWith("OK")) {
                r.zoomTestWritable = true;
                // Restore
                setZoom(Integer.parseInt(r.zoomVal));
            } else {
                r.zoomTestWritable = false;
                r.issues.add("写入 /sys/class/video/zoom 测试失败：" + testRes);
                r.suggestions.add("驱动节点写保护，请检查是否具备 media (uid 1013) 或 root 写入权限。");
            }
        } else {
            r.issues.add("未找到 /sys/class/video/zoom 节点，当前固件可能缺少晶晨 VPP 视频驱动！");
        }

        // 5. Video Playback Activity
        r.frameCount = readSysfs("/sys/class/video/frame_count");
        r.videoStatus = readSysfs("/sys/class/video/video_status");
        long fc = 0;
        try { fc = Long.parseLong(r.frameCount); } catch (Exception ignored) {}

        if (fc > 0 || (r.videoStatus.contains("running") || r.videoStatus.contains("active"))) {
            r.videoPlaying = true;
        } else {
            r.videoPlaying = false;
            r.issues.add("⚠️ 核心提醒：当前未检测到底层硬件视频流在解码播放！");
            r.suggestions.add("晶晨芯片硬件变焦(Zoom)仅对【硬解播放中的视频】有效，桌面与APP界面绝不缩放。请在 Kodi 播放视频时测试！");
        }

        // 6. Kodi Status & JSON-RPC Port 9090
        String kodiPid = execute("pidof org.xbmc.kodi 2>/dev/null").trim();
        r.kodiRunning = !kodiPid.isEmpty();

        Socket kodiSock = null;
        try {
            kodiSock = new Socket();
            kodiSock.connect(new InetSocketAddress("127.0.0.1", 9090), 1000);
            kodiSock.setSoTimeout(1500);
            String ping = "{\"jsonrpc\":\"2.0\",\"method\":\"JSONRPC.Ping\",\"id\":1}\n";
            kodiSock.getOutputStream().write(ping.getBytes("UTF-8"));
            kodiSock.getOutputStream().flush();
            byte[] buf = new byte[512];
            int n = kodiSock.getInputStream().read(buf);
            String resp = (n > 0) ? new String(buf, 0, n, "UTF-8") : "";
            if (resp.contains("pong")) {
                r.kodiRpcOk = true;
                r.kodiRpcMsg = "9090 端口畅通，JSON-RPC 通信正常";
            } else {
                r.kodiRpcOk = false;
                r.kodiRpcMsg = "9090 端口已连接但响应异常: " + resp;
            }
        } catch (Exception e) {
            r.kodiRpcOk = false;
            r.kodiRpcMsg = "9090 端口拒绝连接或超时 (" + e.getMessage() + ")";
            r.issues.add("Kodi 本地控制端口 9090 未联通：无法自动解除宽银幕电影上下黑边。");
            r.suggestions.add("请在 Kodi【设置】->【服务】->【控制】中开启“允许通过本地应用控制”并关闭密码。");
        } finally {
            if (kodiSock != null) {
                try { kodiSock.close(); } catch (Exception ignored) {}
            }
        }

        return r;
    }

    public static String runDiagnosticText() {
        DiagnosticResult r = runFullDiagnostic();
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("   斐讯 T1 变焦与系统环境体检诊断报告\n");
        sb.append("========================================\n\n");

        sb.append("【1. 设备与系统固件】\n");
        sb.append("• 设备型号: ").append(r.model).append("\n");
        sb.append("• 固件版本: ").append(r.romDisplay).append("\n");
        sb.append("• 安卓版本: ").append(r.androidVer).append("\n");
        sb.append("• SELinux: ").append(r.selinux).append("\n\n");

        sb.append("【2. Root 权限与提权通道】\n");
        sb.append("• 生效通道: ").append(r.rootChannel).append("\n");
        sb.append("• ADB 端口 (127.0.0.1:5555): ").append(r.adbPortOpen ? "已开启 [✓]" : "未开启/连接拒绝 [✗]").append("\n");
        sb.append("• 提权可用性: ").append(r.rootOk ? "完全正常 [✓]" : "无法提权 [✗]").append("\n\n");

        sb.append("【3. Seccomp 硬解补丁状态】\n");
        sb.append("• 策略文件: ").append(r.seccompInfo).append(r.seccompPatched ? " [✓]" : " [✗]").append("\n");
        sb.append("• media.codec 进程 PID: ").append(r.mediaCodecPid).append("\n\n");

        sb.append("【4. 晶晨视频底层硬件节点】\n");
        sb.append("• /sys/class/video/zoom: 当前值=").append(r.zoomVal)
          .append("，回读写入测试=").append(r.zoomTestWritable ? "成功通过 [✓]" : "写入失败 [✗]").append("\n");
        sb.append("• /sys/class/video/screen_mode: ").append(r.screenModeVal).append("\n");
        sb.append("• /sys/class/video/axis: ").append(r.axisVal).append("\n");
        sb.append("• /sys/class/video/crop: ").append(r.cropVal).append("\n\n");

        sb.append("【5. 视频播放与硬件解码活跃状态】\n");
        sb.append("• 正在硬解播放视频: ").append(r.videoPlaying ? "是 [✓]" : "否 (当前未检测到视频流) [⚠️]").append("\n");
        sb.append("• 当前帧计数: ").append(r.frameCount).append("\n\n");

        sb.append("【6. Kodi 播放器与 9090 端口】\n");
        sb.append("• Kodi 运行状态: ").append(r.kodiRunning ? "后台运行中 [✓]" : "未运行").append("\n");
        sb.append("• 9090 RPC 端口: ").append(r.kodiRpcOk ? "响应正常 [✓]" : "未联通 [⚠️]").append(" (").append(r.kodiRpcMsg).append(")\n\n");

        sb.append("----------------------------------------\n");
        sb.append("【综合诊断结论与排查指引】\n");
        if (r.issues.isEmpty()) {
            sb.append("✅ 系统底层环境非常健康！提权通道、硬件节点读写、硬解补丁完全正常。\n");
            sb.append("提示：如果变焦画面无变化，请确认：\n");
            sb.append("1. 正在硬解播放视频（变焦只对视频生效，桌面不缩放）；\n");
            sb.append("2. Kodi 设置中开启了“允许硬件加速 - MediaCodec (Surface)”。\n");
        } else {
            sb.append("发现以下 ").append(r.issues.size()).append(" 项需要注意的问题：\n");
            for (int i = 0; i < r.issues.size(); i++) {
                sb.append(i + 1).append(". ").append(r.issues.get(i)).append("\n");
            }
            sb.append("\n【建议解决步骤】\n");
            for (int i = 0; i < r.suggestions.size(); i++) {
                sb.append("👉 ").append(r.suggestions.get(i)).append("\n");
            }
        }
        sb.append("========================================\n");
        return sb.toString();
    }

    public static String runDiagnosticJson() {
        DiagnosticResult r = runFullDiagnostic();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"status\":\"ok\",");
        sb.append("\"model\":\"").append(escapeJson(r.model)).append("\",");
        sb.append("\"romDisplay\":\"").append(escapeJson(r.romDisplay)).append("\",");
        sb.append("\"androidVer\":\"").append(escapeJson(r.androidVer)).append("\",");
        sb.append("\"selinux\":\"").append(escapeJson(r.selinux)).append("\",");
        sb.append("\"rootChannel\":\"").append(escapeJson(r.rootChannel)).append("\",");
        sb.append("\"rootOk\":").append(r.rootOk).append(",");
        sb.append("\"adbPortOpen\":").append(r.adbPortOpen).append(",");
        sb.append("\"seccompPatched\":").append(r.seccompPatched).append(",");
        sb.append("\"seccompInfo\":\"").append(escapeJson(r.seccompInfo)).append("\",");
        sb.append("\"mediaCodecPid\":\"").append(escapeJson(r.mediaCodecPid)).append("\",");
        sb.append("\"zoomVal\":\"").append(escapeJson(r.zoomVal)).append("\",");
        sb.append("\"zoomWritable\":").append(r.zoomTestWritable).append(",");
        sb.append("\"screenModeVal\":\"").append(escapeJson(r.screenModeVal)).append("\",");
        sb.append("\"axisVal\":\"").append(escapeJson(r.axisVal)).append("\",");
        sb.append("\"videoPlaying\":").append(r.videoPlaying).append(",");
        sb.append("\"frameCount\":\"").append(escapeJson(r.frameCount)).append("\",");
        sb.append("\"kodiRunning\":").append(r.kodiRunning).append(",");
        sb.append("\"kodiRpcOk\":").append(r.kodiRpcOk).append(",");
        sb.append("\"kodiRpcMsg\":\"").append(escapeJson(r.kodiRpcMsg)).append("\",");
        sb.append("\"reportText\":\"").append(escapeJson(runDiagnosticText())).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
