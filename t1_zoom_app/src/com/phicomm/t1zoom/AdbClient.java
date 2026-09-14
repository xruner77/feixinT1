package com.phicomm.t1zoom;

import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AdbClient {
    private static final String TAG = "T1AdbClient";
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;

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

    public static synchronized String execute(String cmd) {
        Socket socket = null;
        try {
            socket = new Socket("127.0.0.1", 5555);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            Log.i(TAG, "Connected to 127.0.0.1:5555");

            // 1. Send CNXN
            byte[] cnxnPayload = new byte[]{'h', 'o', 's', 't', ':', ':', 0};
            out.write(buildMessage(A_CNXN, A_VERSION, MAX_DATA, cnxnPayload));
            out.flush();

            // Read CNXN response (24 bytes header)
            byte[] header = new byte[24];
            readFully(in, header, 24);

            ByteBuffer bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cnxnCmd = bb.getInt(); // cmd
            bb.getInt(); bb.getInt(); // arg0, arg1
            int dlen = bb.getInt();
            if (dlen > 0) {
                byte[] banner = new byte[dlen];
                readFully(in, banner, dlen);
                Log.i(TAG, "CNXN ok, banner: " + new String(banner));
            }

            // 2. Send OPEN
            String serviceCmd = "shell:" + cmd + "\0";
            byte[] openPayload = serviceCmd.getBytes("UTF-8");
            out.write(buildMessage(A_OPEN, 1, 0, openPayload));
            out.flush();
            Log.i(TAG, "OPEN sent: " + serviceCmd);

            // Read OPEN response
            readFully(in, header, 24);
            bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int respCmd = bb.getInt();
            int remoteId = bb.getInt();
            Log.i(TAG, "OPEN resp: 0x" + Integer.toHexString(respCmd) + " r_id: " + remoteId);
            if (respCmd != A_OKAY) {
                Log.w(TAG, "OPEN rejected with cmd: 0x" + Integer.toHexString(respCmd));
                return "ERR: OPEN rejected";
            }

            // 3. Read stream packets until CLSE
            StringBuilder sb = new StringBuilder();
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 3000) {
                try {
                    readFully(in, header, 24);
                    bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    int pktCmd = bb.getInt();
                    bb.getInt(); bb.getInt(); // arg0, arg1
                    int pktLen = bb.getInt();
                    Log.i(TAG, "Stream pkt: 0x" + Integer.toHexString(pktCmd) + " len: " + pktLen);

                    if (pktLen > 0) {
                        byte[] data = new byte[pktLen];
                        readFully(in, data, pktLen);
                        sb.append(new String(data, "UTF-8"));
                        // Send OKAY ACK
                        out.write(buildMessage(A_OKAY, 1, remoteId, null));
                        out.flush();
                    }

                    if (pktCmd == A_CLSE) {
                        break;
                    }
                } catch (Exception e) {
                    Log.i(TAG, "Stream loop ended: " + e.getMessage());
                    break;
                }
            }
            String res = sb.toString();
            Log.i(TAG, "execute [" + cmd + "] -> " + res);
            return res;
        } catch (Exception e) {
            Log.e(TAG, "execute error: " + e.getMessage());
            return "ERR: " + e.getMessage();
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static int curZoom = 100;
    private static int curMode = 0;
    private static int curBrightness = 0;
    private static int curContrast = 0;
    private static int curSaturation = 0;
    private static int curDnlp = 0;

    public static int getCurZoom() { return curZoom; }
    public static int getCurMode() { return curMode; }
    public static int getCurBrightness() { return curBrightness; }
    public static int getCurContrast() { return curContrast; }
    public static int getCurSaturation() { return curSaturation; }
    public static int getCurDnlp() { return curDnlp; }

    public static void setCurBrightness(int b) { curBrightness = b; }
    public static void setCurContrast(int c) { curContrast = c; }
    public static void setCurSaturation(int s) { curSaturation = s; }
    public static void setCurDnlp(int d) { curDnlp = d; }


    public static void initPqValues() {
        try {
            String b = execute("cat /sys/class/amvecm/brightness").trim();
            curBrightness = Integer.parseInt(b);
        } catch (Exception ignored) {}
        try {
            String c = execute("cat /sys/class/amvecm/contrast").trim();
            curContrast = Integer.parseInt(c);
        } catch (Exception ignored) {}
        try {
            String s = execute("cat /sys/class/amvecm/saturation_hue_pre").trim();
            String[] parts = s.split("\\s+");
            if (parts.length > 0) curSaturation = Integer.parseInt(parts[0]);
        } catch (Exception ignored) {}
        try {
            String d = execute("cat /sys/module/am_vecm/parameters/dnlp_en").trim();
            curDnlp = Integer.parseInt(d);
        } catch (Exception ignored) {}
    }

    public static String setZoom(int zoom) {
        curZoom = zoom;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"echo " + zoom + " > /sys/class/video/zoom\"";
        return execute(cmd);
    }

    public static String setScreenMode(int mode) {
        curMode = mode;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo " + mode + " > /sys/class/video/screen_mode\"";
        return execute(cmd);
    }

    public static String setBrightness(int val) {
        curBrightness = val;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo " + val + " > /sys/class/amvecm/brightness; echo " + val + " > /sys/class/video/brightness\"";
        return execute(cmd);
    }

    public static String setContrast(int val) {
        curContrast = val;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo " + val + " > /sys/class/amvecm/contrast; echo " + val + " > /sys/class/video/contrast\"";
        return execute(cmd);
    }

    public static String setSaturation(int val) {
        curSaturation = val;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo \\\"" + val + " 0\\\" > /sys/class/amvecm/saturation_hue_pre\"";
        return execute(cmd);
    }

    public static String setDnlp(int val) {
        curDnlp = val;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo " + val + " > /sys/module/am_vecm/parameters/dnlp_en; echo 7 > /sys/module/am_vecm/parameters/dnlp_adj_level\"";
        return execute(cmd);
    }

    public static String resetAll() {
        curZoom = 100;
        curMode = 0;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 1013:1000 sh -c \"echo 100 > /sys/class/video/zoom\"; printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo 0 > /sys/class/video/screen_mode; echo 0 0 0 0 > /sys/class/video/crop\"";
        return execute(cmd);
    }

    public static String resetAllPq() {
        curBrightness = 0;
        curContrast = 0;
        curSaturation = 0;
        curDnlp = 0;
        String cmd = "printf \"31183118\\n\" | /system/xbin/su 0 sh -c \"echo 0 > /sys/class/amvecm/brightness; echo 0 > /sys/class/video/brightness; echo 0 > /sys/class/amvecm/contrast; echo 0 > /sys/class/video/contrast; echo \\\"0 0\\\" > /sys/class/amvecm/saturation_hue_pre; echo 0 > /sys/module/am_vecm/parameters/dnlp_en\"";
        return execute(cmd);
    }
}

