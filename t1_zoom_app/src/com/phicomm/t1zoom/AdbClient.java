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
    private static long lastDetectFailTs = 0;

    // Current State
    private static int curZoom = 100;
    private static int curMode = 0;
    private static int curBrightness = 0;
    private static int curContrast = 35;
    private static int curSaturation = 0;
    private static int curHue = 0;
    private static int curDnlp = 1;
    private static int curCm = 1;
    private static int curEotf = 110;
    private static int curBext = 8;
    private static int curDither = 1;
    private static int curNr10 = 1;
    private static int curBt2390 = 1;
    private static int curKneeFactor = 256; // 0 to 256
    private static int curSdrSat = 50;      // 0 to 100
    private static int curWhtext = 16;      // 0 to 64

    public static int getCurZoom() { return curZoom; }
    public static int getCurMode() { return curMode; }
    public static int getCurBrightness() { return curBrightness; }
    public static int getCurContrast() { return curContrast; }
    public static int getCurSaturation() { return curSaturation; }
    public static int getCurHue() { return curHue; }
    public static int getCurDnlp() { return curDnlp; }
    public static int getCurCm() { return curCm; }
    public static int getCurEotf() { return curEotf; }
    public static int getCurBext() { return curBext; }
    public static int getCurDither() { return curDither; }
    public static int getCurNr10() { return curNr10; }
    public static int getCurBt2390() { return curBt2390; }
    public static int getCurKneeFactor() { return curKneeFactor; }
    public static int getCurSdrSat() { return curSdrSat; }
    public static int getCurWhtext() { return curWhtext; }

    public static void setCurBrightness(int b) { curBrightness = b; }
    public static void setCurContrast(int c) { curContrast = c; }
    public static void setCurSaturation(int s) { curSaturation = s; }
    public static void setCurHue(int h) { curHue = h; }
    public static void setCurDnlp(int d) { curDnlp = d; }
    public static void setCurCm(int cm) { curCm = cm; }
    public static void setCurEotf(int e) { curEotf = e; }
    public static void setCurBext(int b) { curBext = b; }
    public static void setCurDither(int d) { curDither = d; }
    public static void setCurNr10(int nr) { curNr10 = nr; }
    public static void setCurBt2390(int bt) { curBt2390 = bt; }
    public static void setCurKneeFactor(int kf) { curKneeFactor = kf; }
    public static void setCurSdrSat(int ss) { curSdrSat = ss; }
    public static void setCurWhtext(int wt) { curWhtext = wt; }

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
            socket.setSoTimeout(1500);
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1. Send CNXN
            byte[] cnxnPayload = new byte[]{'h', 'o', 's', 't', ':', ':', 0};
            out.write(buildMessage(A_CNXN, A_VERSION, MAX_DATA, cnxnPayload));
            out.flush();

            // Read CNXN response (24 bytes header)
            byte[] header = new byte[24];
            try {
                readFully(in, header, 24);
            } catch (java.net.SocketTimeoutException te) {
                // 该固件 adbd 仅允许一个 TCP 客户端，电脑端 adb 连着时会卡在握手
                return "ERR: ADB_BUSY (5555 已被其它客户端占用，如电脑 adb connect)";
            }
            socket.setSoTimeout(3000);

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
        // 探测失败后 10 秒内不再重复探测，避免每个请求都卡数秒
        if (!forceRecheck && System.currentTimeMillis() - lastDetectFailTs < 10000) {
            return CHANNEL_UNKNOWN;
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
        activeChannelDesc = "未找到可用提权通道 (本地su失败，ADB 5555 未开启或被电脑端 adb 占用)";
        lastDetectFailTs = System.currentTimeMillis();
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
    // PQ 直写层：一次 root 解锁节点权限，之后 App 直接写 sysfs，不再依赖 ADB
    // (该固件 adbd 只允许一个 TCP 客户端，电脑 adb 连着时 App 的 ADB 通道会被挡住)
    // =========================================================================

    private static final String VECM = "/sys/module/am_vecm/parameters/";
    private static final String[] PQ_NODES = {
            "/sys/class/video/zoom", "/sys/class/video/screen_mode",
            "/sys/class/video/crop", "/sys/class/video/axis",
            "/sys/class/video/contrast", "/sys/class/video/brightness",
            "/sys/class/amvecm/brightness", "/sys/class/amvecm/contrast",
            "/sys/class/amvecm/saturation_hue_pre", "/sys/class/amvecm/dnlp",
            "/sys/class/amvecm/pq_user_set", "/sys/class/amhdmitx/amhdmitx0/attr",
            VECM + "video_eotf_coeff", VECM + "reload_mtx", VECM + "reload_lut",
            VECM + "ve_dnlp_set_bext", VECM + "vpp_dith_en", VECM + "vpp_dith_mode",
            VECM + "hdr_mode", VECM + "knee_setting", VECM + "knee_interpolation_mode",
            VECM + "knee_factor", VECM + "range_control", VECM + "sdr_saturation_offset",
            VECM + "dnlp_en", VECM + "dnlp_en_2", VECM + "dnlp_adj_level",
            VECM + "ve_dnlp_adj_level", VECM + "ve_dnlp_strength", VECM + "ve_dnlp_whtext",
            VECM + "ve_dnlp_set_wext", VECM + "cm_en", VECM + "cm_level",
            "/sys/module/di/parameters/nr10bit_surpport"
    };
    private static volatile boolean pqUnlocked = false;
    private static long lastUnlockFailTs = 0;
    private static String lastUnlockErr = "";

    public static boolean isPqUnlocked() { return pqUnlocked; }
    public static String getLastUnlockErr() { return lastUnlockErr; }

    /** 以 uid=0 执行（优先已探测到的 root 通道，否则走 ADB + 斐讯 su 0），绝不使用 media 通道 */
    public static String execRoot(String cmd) {
        int ch = activeChannel;
        if (ch == CHANNEL_LOCAL_SU || ch == CHANNEL_LOCAL_PHICOMM || ch == CHANNEL_ADB_DIRECT
                || ch == CHANNEL_ADB_PHICOMM || ch == CHANNEL_ADB_SU_C) {
            return execute(cmd);
        }
        return execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"" + cmd.replace("\"", "\\\"") + "\"");
    }

    private static boolean writeDirect(String path, String val) {
        File f = new File(path);
        if (!f.exists()) return true; // 节点不存在的固件直接跳过
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(f);
            fos.write((val + "\n").getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean pqNodesWritable() {
        for (String n : PQ_NODES) {
            File f = new File(n);
            if (f.exists() && !f.canWrite()) return false;
        }
        return true;
    }

    /** 通过 root 放开 PQ 节点写权限 (chmod 666 + setenforce 0)，重启后需重新执行 */
    public static synchronized boolean unlockPqNodes() {
        if (pqUnlocked && pqNodesWritable()) return true;
        pqUnlocked = false;
        if (pqNodesWritable()) { pqUnlocked = true; return true; }
        if (System.currentTimeMillis() - lastUnlockFailTs < 5000) return false; // 失败冷却，避免每次请求都卡
        StringBuilder sb = new StringBuilder("setenforce 0 2>/dev/null; ");
        for (String n : PQ_NODES) sb.append("chmod 666 ").append(n).append(" 2>/dev/null; ");
        sb.append("echo UNLOCK_DONE");
        String r = execRoot(sb.toString());
        // 斐讯 su 移除了 CAP_FOWNER，media 所有的 video 节点需由 uid=1013 (media) 解锁
        File zoomFile = new File("/sys/class/video/zoom");
        if (zoomFile.exists() && !zoomFile.canWrite()) {
            execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"chmod 666 /sys/class/video/zoom /sys/class/video/screen_mode /sys/class/video/axis 2>/dev/null; echo MEDIA_UNLOCK_DONE\"");
        }
        if (r.contains("UNLOCK_DONE") && pqNodesWritable()) {
            pqUnlocked = true;
            lastUnlockErr = "";
            Log.i(TAG, "PQ & Zoom nodes unlocked, direct sysfs write enabled");
            return true;
        }
        lastUnlockFailTs = System.currentTimeMillis();
        lastUnlockErr = r.length() > 160 ? r.substring(0, 160) : r;
        Log.w(TAG, "PQ unlock failed: " + lastUnlockErr);
        return false;
    }

    /** 按 path,value,path,value... 顺序写入 PQ 节点；返回 OK 或 ERR:原因 */
    public static synchronized String writePq(String... kv) {
        if (directWriteAll(kv)) return "OK";
        if (unlockPqNodes() && directWriteAll(kv)) return "OK";
        // 兜底：整条命令走 root shell
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append("echo ").append(kv[i + 1]).append(" > ").append(kv[i]).append("; ");
        }
        String r = execRoot(sb.toString());
        if (r.startsWith("ERR")) {
            Log.w(TAG, "writePq failed: " + r);
            return r;
        }
        return "OK";
    }

    private static boolean directWriteAll(String[] kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (!writeDirect(kv[i], kv[i + 1])) return false;
        }
        return true;
    }

    // =========================================================================
    // Zoom and Video Control APIs with Direct Verification
    // =========================================================================

    public static String setZoom(int zoom) {
        curZoom = zoom;
        // 1. 优先走直写/一次解锁通道
        writePq("/sys/class/video/zoom", "" + zoom);
        String verify = readSysfs("/sys/class/video/zoom").trim();
        if (String.valueOf(zoom).equals(verify)) {
            return "OK:" + verify;
        }

        // 2. Try standard execute write
        execute("echo " + zoom + " > /sys/class/video/zoom");
        verify = readSysfs("/sys/class/video/zoom").trim();
        if (String.valueOf(zoom).equals(verify)) {
            return "OK:" + verify;
        }

        // 3. If failed, attempt direct media su via ADB
        if (isAdbPortOpen()) {
            execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"echo " + zoom + " > /sys/class/video/zoom\"");
            verify = readSysfs("/sys/class/video/zoom").trim();
            if (String.valueOf(zoom).equals(verify)) {
                activeChannel = CHANNEL_ADB_MEDIA;
                activeChannelDesc = "ADB 斐讯媒体授权 (su 1013:1000)";
                return "OK:" + verify;
            }

            // 4. Attempt root su 0 via ADB
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
        writePq("/sys/class/video/screen_mode", "" + mode);
        String verify = readSysfs("/sys/class/video/screen_mode").trim();
        if (isScreenModeMatched(verify, mode)) {
            return "OK:" + verify;
        }

        execute("echo " + mode + " > /sys/class/video/screen_mode");
        verify = readSysfs("/sys/class/video/screen_mode").trim();
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

    private static String res(String r, int val) {
        return "OK".equals(r) ? "OK:" + val : r;
    }

    public static String setBrightness(int val) {
        curBrightness = val;
        return res(writePq("/sys/class/amvecm/brightness", "" + val, "/sys/class/video/brightness", "" + val), val);
    }

    public static String setContrast(int val) {
        curContrast = val;
        return res(writePq("/sys/class/video/contrast", "" + val), val);
    }

    public static String getEotfCoeff(int boostPct) {
        if (boostPct <= 100) {
            return "2048,0,0,0,2048,0,0,0,2048,1";
        }
        double factor = boostPct / 100.0;
        int[] base = {2509, -461, 0, -86, 2134, 0, -40, -161, 2249};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length; i++) {
            long val = Math.round(base[i] * factor);
            sb.append(val).append(",");
        }
        sb.append("1");
        return sb.toString();
    }

    public static void ensure10BitColorOutput() {
        String cfg = readSysfs("/sys/class/amhdmitx/amhdmitx0/config");
        if (!cfg.contains("cd = 5")) {
            writePq("/sys/class/amhdmitx/amhdmitx0/attr", "420,10bit");
            execAdbRaw("printf \"31183118\\n\" | /system/xbin/su 1000 sh -c \"echo 2160p60hz420 > /sys/class/display/mode\"");
        }
    }

    public static String setEotf(int boostPct) {
        curEotf = boostPct;
        String coeff = getEotfCoeff(boostPct);
        return res(writePq(VECM + "video_eotf_coeff", coeff, VECM + "reload_mtx", "1", VECM + "reload_lut", "1"), boostPct);
    }

    public static String setBext(int val) {
        curBext = val;
        return res(writePq(VECM + "ve_dnlp_set_bext", "" + val), val);
    }

    public static String setDither(int val) {
        curDither = val;
        return res(writePq(VECM + "vpp_dith_en", "" + val, VECM + "vpp_dith_mode", "0"), val);
    }

    public static String setNr10(int val) {
        curNr10 = val;
        return res(writePq("/sys/module/di/parameters/nr10bit_surpport", "" + val), val);
    }

    public static String setBt2390(int val) {
        curBt2390 = val;
        String r;
        if (val == 1) {
            curDnlp = 1; // BT.2390 套餐内会开启 DNLP，保持状态一致
            ensure10BitColorOutput();
            String knee = "0,16,46,76,107,137,168,198,228,306,388,444,489,529,565,599,630,660,688,714,740,765,789,812,834,856,877,898,919,938,958,977,996,1014,1023";
            r = writePq(
                    VECM + "hdr_mode", "1",
                    VECM + "knee_setting", knee,
                    VECM + "knee_interpolation_mode", "1",
                    VECM + "knee_factor", "" + curKneeFactor,
                    VECM + "range_control", "0",
                    VECM + "sdr_saturation_offset", "" + curSdrSat,
                    "/sys/class/amvecm/dnlp", "0x1",
                    VECM + "dnlp_en", "1",
                    VECM + "dnlp_en_2", "1",
                    VECM + "dnlp_adj_level", "8",
                    VECM + "ve_dnlp_adj_level", "8",
                    VECM + "ve_dnlp_strength", "255",
                    VECM + "ve_dnlp_whtext", "" + curWhtext,
                    VECM + "ve_dnlp_set_wext", "1",
                    "/sys/class/amvecm/pq_user_set", "blk_ext_en",
                    VECM + "ve_dnlp_set_bext", "" + curBext,
                    VECM + "vpp_dith_en", "" + curDither,
                    "/sys/module/di/parameters/nr10bit_surpport", "" + curNr10,
                    VECM + "video_eotf_coeff", getEotfCoeff(curEotf),
                    VECM + "reload_lut", "1",
                    VECM + "reload_mtx", "1",
                    "/sys/class/video/contrast", "" + curContrast);
        } else {
            r = writePq(
                    VECM + "hdr_mode", "2",
                    VECM + "knee_factor", "0",
                    VECM + "ve_dnlp_set_bext", "0",
                    "/sys/class/video/contrast", "0",
                    VECM + "reload_lut", "1");
        }
        return res(r, val);
    }

    /** 曲线介入强度 knee_factor (0 ~ 256) */
    public static String setKneeFactor(int val) {
        curKneeFactor = val;
        return res(writePq(VECM + "knee_factor", "" + val, VECM + "reload_lut", "1"), val);
    }

    /** HDR 广色域转 SDR 饱和度补偿 sdr_saturation_offset (0 ~ 100) */
    public static String setSdrSat(int val) {
        curSdrSat = val;
        return res(writePq(VECM + "sdr_saturation_offset", "" + val, VECM + "reload_mtx", "1", VECM + "reload_lut", "1"), val);
    }

    /** 白场高光动态拉伸 ve_dnlp_whtext (0 ~ 64) */
    public static String setWhtext(int val) {
        curWhtext = val;
        return res(writePq(VECM + "ve_dnlp_whtext", "" + val, VECM + "ve_dnlp_set_wext", "1"), val);
    }

    public static String setSaturation(int val) {
        curSaturation = val;
        return res(writePq("/sys/class/amvecm/saturation_hue_pre", curSaturation + " " + curHue), val);
    }

    public static String setHue(int val) {
        curHue = val;
        return res(writePq("/sys/class/amvecm/saturation_hue_pre", curSaturation + " " + curHue), val);
    }

    public static String setCm(int val) {
        if (val >= 1 && val <= 3) {
            curCm = val;
            return res(writePq(VECM + "cm_en", "1", VECM + "cm_level", "" + val), val);
        }
        curCm = 0;
        return res(writePq(VECM + "cm_en", "0"), 0);
    }

    public static String setDnlp(int val) {
        curDnlp = val;
        if (val == 1) {
            return res(writePq("/sys/class/amvecm/dnlp", "0x1", VECM + "dnlp_en", "1", VECM + "dnlp_en_2", "1",
                    VECM + "dnlp_adj_level", "8", VECM + "ve_dnlp_adj_level", "8", VECM + "ve_dnlp_strength", "255",
                    "/sys/class/amvecm/pq_user_set", "blk_ext_en"), val);
        }
        return res(writePq("/sys/class/amvecm/dnlp", "0x0", VECM + "dnlp_en", "0", VECM + "dnlp_en_2", "0",
                "/sys/class/amvecm/pq_user_set", "blk_ext_dis"), val);
    }

    public static String resetAll() {
        curZoom = 100;
        curMode = 0;
        String zr = setZoom(100);
        String mr = writePq("/sys/class/video/screen_mode", "0", "/sys/class/video/crop", "0 0 0 0");
        if (!zr.startsWith("OK")) return zr;
        if (!"OK".equals(mr)) return mr;
        return "OK:resetAll";
    }

    public static String resetAllPq() {
        curBrightness = 0;
        curContrast = 0;
        curSaturation = 0;
        curHue = 0;
        curDnlp = 0;
        curCm = 0;
        return res(writePq("/sys/class/amvecm/brightness", "0", "/sys/class/video/brightness", "0",
                "/sys/class/video/contrast", "0", "/sys/class/amvecm/contrast", "0",
                "/sys/class/amvecm/saturation_hue_pre", "0 0", "/sys/class/amvecm/dnlp", "0x0",
                VECM + "dnlp_en", "0", VECM + "dnlp_en_2", "0",
                "/sys/class/amvecm/pq_user_set", "blk_ext_dis", VECM + "cm_en", "0"), 0);
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
            if ("1".equals(cm)) {
                String lvl = readSysfs("/sys/module/am_vecm/parameters/cm_level");
                int lv = (!lvl.isEmpty()) ? Integer.parseInt(lvl) : 1;
                curCm = (lv >= 1 && lv <= 3) ? lv : 1;
            } else if ("0".equals(cm)) {
                curCm = 0;
            }
        } catch (Exception ignored) {}
        try {
            String e = readSysfs("/sys/module/am_vecm/parameters/video_eotf_coeff");
            if (e.contains("2760")) curEotf = 110;
            else if (e.contains("2634")) curEotf = 105;
            else if (e.contains("2885")) curEotf = 115;
            else if (e.contains("3136")) curEotf = 125;
            else if (e.contains("2048")) curEotf = 100;
        } catch (Exception ignored) {}
        try {
            String bx = readSysfs("/sys/module/am_vecm/parameters/ve_dnlp_set_bext");
            if (!bx.isEmpty()) curBext = Integer.parseInt(bx);
        } catch (Exception ignored) {}
        try {
            String dit = readSysfs("/sys/module/am_vecm/parameters/vpp_dith_en");
            if (!dit.isEmpty()) curDither = Integer.parseInt(dit);
        } catch (Exception ignored) {}
        try {
            String nr = readSysfs("/sys/module/di/parameters/nr10bit_surpport");
            if (!nr.isEmpty()) curNr10 = Integer.parseInt(nr);
        } catch (Exception ignored) {}
        try {
            String kf = readSysfs("/sys/module/am_vecm/parameters/knee_factor");
            if (!kf.isEmpty()) {
                int kfv = Integer.parseInt(kf.trim());
                if (kfv > 0) {
                    curBt2390 = 1;
                    curKneeFactor = kfv;
                } else {
                    curBt2390 = 0;
                }
            }
        } catch (Exception ignored) {}
        try {
            String ssat = readSysfs("/sys/module/am_vecm/parameters/sdr_saturation_offset");
            if (!ssat.isEmpty()) curSdrSat = Integer.parseInt(ssat.trim());
        } catch (Exception ignored) {}
        try {
            String wext = readSysfs("/sys/module/am_vecm/parameters/ve_dnlp_whtext");
            if (!wext.isEmpty()) curWhtext = Integer.parseInt(wext.trim());
        } catch (Exception ignored) {}
        try {
            String z = readSysfs("/sys/class/video/zoom");
            if (!z.isEmpty()) curZoom = Integer.parseInt(z);
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
