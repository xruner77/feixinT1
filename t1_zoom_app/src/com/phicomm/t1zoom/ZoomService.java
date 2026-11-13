package com.phicomm.t1zoom;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class ZoomService extends Service {
    private static final String TAG = "T1ZoomService";
    private static final int PORT = 8989;
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_NAME = "t1_pq_prefs";
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private BroadcastReceiver screenOnReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ZoomService onCreate");
        startAsForeground();
        startHttpServer();
        registerScreenOnReceiver();
        restorePqOnBoot();
    }

    private void savePqPref(String key, int val) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(key, val).apply();
            Log.d(TAG, "Saved PQ pref: " + key + "=" + val);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save pref " + key + ": " + e.getMessage());
        }
    }

    private void restorePqOnBoot() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            final int b = prefs.getInt("brightness", 0);
            final int c = prefs.getInt("contrast", 35);
            final int s = prefs.getInt("saturation", 0);
            final int h = prefs.getInt("hue", 0);
            final int d = prefs.getInt("dnlp", 1);
            final int cm = prefs.getInt("cm", 1);
            final int eotf = prefs.getInt("eotf", 110);
            final int bext = prefs.getInt("bext", 8);
            final int dither = prefs.getInt("dither", 1);
            final int nr10 = prefs.getInt("nr10", 1);
            final int bt2390 = prefs.getInt("bt2390", 1);
            final int kfactor = prefs.getInt("kfactor", 256);
            final int sdrsat = prefs.getInt("sdrsat", 50);
            final int whtext = prefs.getInt("whtext", 16);

            // Pre-set in-memory tracking so status API is instantly accurate
            AdbClient.setCurBrightness(b);
            AdbClient.setCurContrast(c);
            AdbClient.setCurSaturation(s);
            AdbClient.setCurHue(h);
            AdbClient.setCurDnlp(d);
            AdbClient.setCurCm(cm);
            AdbClient.setCurEotf(eotf);
            AdbClient.setCurBext(bext);
            AdbClient.setCurDither(dither);
            AdbClient.setCurNr10(nr10);
            AdbClient.setCurBt2390(bt2390);
            AdbClient.setCurKneeFactor(kfactor);
            AdbClient.setCurSdrSat(sdrsat);
            AdbClient.setCurWhtext(whtext);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1500);
                        // 使用应用时刻的最新值（若启动期间用户已在网页调整，以用户为准）
                        final int b = AdbClient.getCurBrightness(), c = AdbClient.getCurContrast();
                        final int s = AdbClient.getCurSaturation(), h = AdbClient.getCurHue();
                        final int d = AdbClient.getCurDnlp(), cm = AdbClient.getCurCm();
                        final int eotf = AdbClient.getCurEotf(), bext = AdbClient.getCurBext();
                        final int dither = AdbClient.getCurDither(), nr10 = AdbClient.getCurNr10();
                        final int bt2390 = AdbClient.getCurBt2390();
                        final int kf = AdbClient.getCurKneeFactor(), ss = AdbClient.getCurSdrSat();
                        final int wt = AdbClient.getCurWhtext();
                        Log.i(TAG, "Applying restored PQ to hardware: b=" + b + ", c=" + c + ", s=" + s + ", h=" + h + ", d=" + d + ", cm=" + cm + ", eotf=" + eotf + ", bext=" + bext + ", kf=" + kf + ", ss=" + ss + ", wt=" + wt);
                        if (bt2390 == 1) {
                            AdbClient.setBt2390(1);
                        } else {
                            AdbClient.setSdrSat(ss);
                            AdbClient.setWhtext(wt);
                        }
                        if (b != 0) AdbClient.setBrightness(b);
                        AdbClient.setContrast(c);
                        if (s != 0 || h != 0) {
                            AdbClient.setSaturation(s);
                            AdbClient.setHue(h);
                        }
                        if (d != 0) AdbClient.setDnlp(d);
                        AdbClient.setCm(cm);
                        if (eotf != 0) AdbClient.setEotf(eotf);
                        AdbClient.setBext(bext);
                        AdbClient.setDither(dither);
                        AdbClient.setNr10(nr10);
                    } catch (Exception e) {
                        Log.e(TAG, "Error restoring PQ: " + e.getMessage());
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read prefs: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String trigger = (intent != null) ? intent.getStringExtra("trigger_action") : "direct";
        Log.i(TAG, "ZoomService onStartCommand, trigger=" + trigger);
        if (!isRunning) {
            startHttpServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "ZoomService onDestroy");
        isRunning = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
        }
        unregisterScreenOnReceiver();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ========== Foreground Service ==========

    private void startAsForeground() {
        try {
            Notification notification = new Notification.Builder(this)
                    .setContentTitle("T1 画面缩放助手")
                    .setContentText("无线遥控服务运行中 (端口: " + PORT + ")")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
            Log.i(TAG, "Started as foreground service");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground: " + e.getMessage());
        }
    }

    // ========== Screen On Receiver ==========

    private void registerScreenOnReceiver() {
        try {
            screenOnReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "Screen ON detected, ensuring HTTP server is alive");
                    if (!isRunning) {
                        startHttpServer();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenOnReceiver, filter);
            Log.i(TAG, "Registered SCREEN_ON receiver");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register screen receiver: " + e.getMessage());
        }
    }

    private void unregisterScreenOnReceiver() {
        if (screenOnReceiver != null) {
            try {
                unregisterReceiver(screenOnReceiver);
            } catch (Exception ignored) {}
            screenOnReceiver = null;
        }
    }

    // ========== HTTP Server ==========

    private void startHttpServer() {
        isRunning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    Log.i(TAG, "HTTP server started on port " + PORT);
                    while (isRunning) {
                        try {
                            Socket client = serverSocket.accept();
                            handleClient(client);
                        } catch (Exception e) {
                            if (!isRunning) break;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Server error: " + e.getMessage());
                }
            }
        }).start();
    }

    private void handleClient(final Socket socket) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line = reader.readLine();
                    if (line == null) {
                        socket.close();
                        return;
                    }

                    String[] parts = line.split(" ");
                    String path = parts.length > 1 ? parts[1] : "/";
                    OutputStream out = socket.getOutputStream();

                    if (path.startsWith("/api/zoom?val=")) {
                        String valStr = path.substring(path.indexOf("val=") + 4);
                        try {
                            int val = Integer.parseInt(valStr);
                            String res = AdbClient.setZoom(val);
                            if (res.startsWith("OK")) {
                                if (val > 100) {
                                    syncKodiViewMode(val);
                                }
                                sendJson(out, "{\"status\":\"ok\",\"zoom\":" + val + ",\"msg\":\"" + escapeJson(res) + "\"}");
                            } else {
                                sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(res) + "\"}");
                            }
                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(e.getMessage()) + "\"}");
                        }
                    } else if (path.startsWith("/api/mode?val=")) {
                        String valStr = path.substring(path.indexOf("val=") + 4);
                        try {
                            int val = Integer.parseInt(valStr);
                            String res = AdbClient.setScreenMode(val);
                            if (res.startsWith("OK")) {
                                sendJson(out, "{\"status\":\"ok\",\"mode\":" + val + "}");
                            } else {
                                sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(res) + "\"}");
                            }
                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(e.getMessage()) + "\"}");
                        }
                    } else if (path.startsWith("/api/diagnose")) {
                        try {
                            String json = AdbClient.runDiagnosticJson();
                            sendJson(out, json);
                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(e.getMessage()) + "\"}");
                        }
                    } else if (path.startsWith("/api/pq?")) {
                        String type = getParam(path, "type");
                        String valStr = getParam(path, "val");
                        String item = getParam(path, "item");
                        try {
                            if ("reset".equals(type)) {
                                if ("brightness".equals(item)) {
                                    AdbClient.setBrightness(0);
                                    savePqPref("brightness", 0);
                                } else if ("contrast".equals(item)) {
                                    AdbClient.setContrast(0);
                                    savePqPref("contrast", 0);
                                } else if ("saturation".equals(item)) {
                                    AdbClient.setSaturation(0);
                                    savePqPref("saturation", 0);
                                } else if ("hue".equals(item)) {
                                    AdbClient.setHue(0);
                                    savePqPref("hue", 0);
                                } else if ("all".equals(item)) {
                                    AdbClient.resetAllPq();
                                    savePqPref("brightness", 0);
                                    savePqPref("contrast", 0);
                                    savePqPref("saturation", 0);
                                    savePqPref("hue", 0);
                                    savePqPref("dnlp", 0);
                                    savePqPref("cm", 0);
                                }
                                sendJson(out, "{\"status\":\"ok\",\"action\":\"reset\",\"item\":\"" + item + "\"}");
                            } else if ("brightness".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setBrightness(val);
                                savePqPref("brightness", val);
                                sendPqResult(out, "brightness", val, r);
                            } else if ("contrast".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setContrast(val);
                                savePqPref("contrast", val);
                                sendPqResult(out, "contrast", val, r);
                            } else if ("saturation".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setSaturation(val);
                                savePqPref("saturation", val);
                                sendPqResult(out, "saturation", val, r);
                            } else if ("hue".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setHue(val);
                                savePqPref("hue", val);
                                sendPqResult(out, "hue", val, r);
                            } else if ("dnlp".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setDnlp(val);
                                savePqPref("dnlp", val);
                                sendPqResult(out, "dnlp", val, r);
                            } else if ("cm".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setCm(val);
                                savePqPref("cm", val);
                                sendPqResult(out, "cm", val, r);
                            } else if ("eotf".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setEotf(val);
                                savePqPref("eotf", val);
                                sendPqResult(out, "eotf", val, r);
                            } else if ("bext".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setBext(val);
                                savePqPref("bext", val);
                                sendPqResult(out, "bext", val, r);
                            } else if ("dither".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setDither(val);
                                savePqPref("dither", val);
                                sendPqResult(out, "dither", val, r);
                            } else if ("nr10".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setNr10(val);
                                savePqPref("nr10", val);
                                sendPqResult(out, "nr10", val, r);
                            } else if ("bt2390".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setBt2390(val);
                                savePqPref("bt2390", val);
                                sendPqResult(out, "bt2390", val, r);
                            } else if ("kfactor".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setKneeFactor(val);
                                savePqPref("kfactor", val);
                                sendPqResult(out, "kfactor", val, r);
                            } else if ("sdrsat".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setSdrSat(val);
                                savePqPref("sdrsat", val);
                                sendPqResult(out, "sdrsat", val, r);
                            } else if ("whtext".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                String r = AdbClient.setWhtext(val);
                                savePqPref("whtext", val);
                                sendPqResult(out, "whtext", val, r);
                            } else if ("10bit".equals(type)) {
                                AdbClient.ensure10BitColorOutput();
                                sendJson(out, "{\"status\":\"ok\",\"type\":\"10bit\"}");
                            } else {
                                sendJson(out, "{\"status\":\"error\",\"msg\":\"invalid params\"}");
                            }

                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(e.getMessage()) + "\"}");
                        }
                    } else if (path.startsWith("/api/reset")) {
                        String r = AdbClient.resetAll();
                        setKodiViewMode(0);
                        if (r != null && r.startsWith("OK")) {
                            sendJson(out, "{\"status\":\"ok\",\"action\":\"reset\"}");
                        } else {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + escapeJson(r) + "\"}");
                        }
                    } else if (path.startsWith("/api/status")) {
                        sendJson(out, "{\"status\":\"ok\",\"service\":\"running\",\"port\":" + PORT +
                                ",\"zoom\":" + AdbClient.getCurZoom() +
                                ",\"mode\":" + AdbClient.getCurMode() +
                                ",\"brightness\":" + AdbClient.getCurBrightness() +
                                ",\"contrast\":" + AdbClient.getCurContrast() +
                                ",\"saturation\":" + AdbClient.getCurSaturation() +
                                ",\"hue\":" + AdbClient.getCurHue() +
                                ",\"dnlp\":" + AdbClient.getCurDnlp() +
                                ",\"cm\":" + AdbClient.getCurCm() +
                                ",\"eotf\":" + AdbClient.getCurEotf() +
                                ",\"bext\":" + AdbClient.getCurBext() +
                                ",\"dither\":" + AdbClient.getCurDither() +
                                ",\"nr10\":" + AdbClient.getCurNr10() +
                                ",\"bt2390\":" + AdbClient.getCurBt2390() +
                                ",\"kfactor\":" + AdbClient.getCurKneeFactor() +
                                ",\"sdrsat\":" + AdbClient.getCurSdrSat() +
                                ",\"whtext\":" + AdbClient.getCurWhtext() + "}");
                    } else {
                        // Serve Mobile Remote HTML Page
                        sendHtml(out, getMobileHtml());
                    }
                    socket.close();
                } catch (Exception e) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private static String getParam(String path, String key) {
        int idx = path.indexOf(key + "=");
        if (idx == -1) return null;
        int start = idx + key.length() + 1;
        int end = path.indexOf("&", start);
        if (end == -1) end = path.length();
        return path.substring(start, end);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ========== Kodi JSON-RPC Integration ==========

    private void syncKodiViewMode(int zoomLevel) {
        if (zoomLevel > 100) {
            setKodiViewMode(2); // Zoom mode
        }
    }

    private void setKodiViewMode(final int viewMode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket kodiSock = null;
                try {
                    kodiSock = new Socket("127.0.0.1", 9090);
                    kodiSock.setSoTimeout(2000);
                    String vmName = (viewMode == 0) ? "normal" : "zoom";
                    String jsonRpc = "{\"jsonrpc\":\"2.0\",\"method\":\"Player.SetViewMode\"," +
                            "\"params\":{\"viewmode\":\"" + vmName + "\"},\"id\":1}\n";
                    kodiSock.getOutputStream().write(jsonRpc.getBytes("UTF-8"));
                    kodiSock.getOutputStream().flush();
                    byte[] buf = new byte[1024];
                    int n = kodiSock.getInputStream().read(buf);
                    if (n > 0) {
                        Log.i(TAG, "Kodi RPC response: " + new String(buf, 0, n, "UTF-8"));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Kodi RPC sync skipped (not running or 9090 disabled): " + e.getMessage());
                } finally {
                    if (kodiSock != null) {
                        try { kodiSock.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
    }

    private void sendPqResult(OutputStream out, String type, int val, String r) throws Exception {
        if (r != null && r.startsWith("OK")) {
            sendJson(out, "{\"status\":\"ok\",\"type\":\"" + type + "\",\"val\":" + val + "}");
        } else {
            sendJson(out, "{\"status\":\"error\",\"type\":\"" + type + "\",\"val\":" + val + ",\"msg\":\"" + escapeJson(r) + "\"}");
        }
    }

    private void sendJson(OutputStream out, String json) throws Exception {
        byte[] bytes = json.getBytes("UTF-8");
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes("ISO-8859-1"));
        out.write(bytes);
        out.flush();
    }

    private void sendHtml(OutputStream out, String html) throws Exception {
        byte[] bytes = html.getBytes("UTF-8");
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes("ISO-8859-1"));
        out.write(bytes);
        out.flush();
    }

    private String getMobileHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no\">");
        sb.append("<title>斐讯 T1 影音与画质控制</title>");
        sb.append("<style>");
        sb.append("* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; margin: 0; padding: 0; }");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0b1329; color: #f8fafc; min-height: 100vh; display: flex; flex-direction: column; align-items: center; padding: 20px 14px 40px; }");
        sb.append(".header { text-align: center; margin-bottom: 16px; }");
        sb.append(".title { font-size: 21px; font-weight: 700; color: #38bdf8; }");
        sb.append(".subtitle { font-size: 12px; color: #94a3b8; margin-top: 4px; }");
        sb.append(".card { background: #1e293b; border-radius: 18px; padding: 18px; width: 100%; max-width: 420px; border: 1px solid #334155; margin-bottom: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }");
        sb.append(".section-title { font-size: 14px; font-weight: 700; color: #38bdf8; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #334155; padding-bottom: 8px; }");
        sb.append(".pq-item { margin-bottom: 16px; }");
        sb.append(".pq-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }");
        sb.append(".pq-label { font-size: 13px; font-weight: 600; color: #e2e8f0; display: flex; align-items: center; gap: 6px; }");
        sb.append(".pq-controls { display: flex; align-items: center; gap: 8px; }");
        sb.append(".val-badge { font-family: monospace; font-size: 13px; font-weight: 700; color: #38bdf8; background: #0f172a; padding: 2px 7px; border-radius: 6px; border: 1px solid #334155; min-width: 42px; text-align: center; }");
        sb.append(".btn-rst { background: #334155; border: 1px solid #475569; color: #cbd5e1; font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 6px; cursor: pointer; transition: all 0.15s ease; }");
        sb.append(".btn-rst:active { background: #0284c7; color: #fff; border-color: #38bdf8; transform: scale(0.94); }");
        sb.append("input[type=range] { width: 100%; -webkit-appearance: none; height: 7px; border-radius: 4px; background: #334155; outline: none; margin: 6px 0 2px; touch-action: pan-y; cursor: default; }");
        sb.append("input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 24px; height: 24px; border-radius: 50%; background: #38bdf8; box-shadow: 0 0 10px rgba(56,189,248,0.7); cursor: grab; border: 2px solid #fff; }");
        sb.append(".range-labels { display: flex; justify-content: space-between; font-size: 10px; color: #64748b; margin-top: 2px; }");
        sb.append(".btn-dnlp { background: #334155; color: #f8fafc; border: 1px solid #475569; border-radius: 12px; padding: 12px 10px; font-size: 14px; font-weight: 600; cursor: pointer; width: 100%; display: flex; align-items: center; justify-content: space-between; margin-top: 10px; }");
        sb.append(".btn-dnlp.active { background: linear-gradient(135deg, #0284c7, #0369a1); border-color: #38bdf8; }");
        sb.append(".btn-dnlp .status-tag { font-size: 12px; font-weight: normal; padding: 2px 8px; border-radius: 10px; background: rgba(0,0,0,0.3); }");
        sb.append(".btn-reset-pq { background: #334155; border: 1px solid #475569; color: #94a3b8; border-radius: 10px; padding: 10px; font-size: 12px; font-weight: 600; cursor: pointer; width: 100%; margin-top: 12px; text-align: center; }");
        sb.append(".btn-reset-pq:active { background: #475569; color: #fff; }");
        sb.append(".btn-grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; }");
        sb.append(".btn-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }");
        sb.append("button.zoom-btn { background: #334155; color: #f8fafc; border: 1px solid #475569; border-radius: 12px; padding: 13px 8px; font-size: 14px; font-weight: 600; cursor: pointer; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; width: 100%; }");
        sb.append("button.zoom-btn:active { transform: scale(0.96); filter: brightness(1.2); }");
        sb.append("button.zoom-btn.active { background: linear-gradient(135deg, #0284c7, #0369a1); border-color: #38bdf8; }");
        sb.append("button.zoom-btn .sub { font-size: 11px; font-weight: normal; color: #94a3b8; }");
        sb.append("button.zoom-btn.active .sub { color: #e0f2fe; }");
        sb.append(".btn-reset-zoom { background: #475569; margin-top: 8px; padding: 13px; border-radius: 12px; border: 1px solid #64748b; color: #fff; font-weight: 600; cursor: pointer; width: 100%; }");
        sb.append(".btn-reset-zoom:active { transform: scale(0.98); }");
        sb.append(".pill-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin: 6px 0 10px; }");
        sb.append(".btn-pill { background: #334155; border: 1px solid #475569; color: #cbd5e1; font-size: 11px; font-weight: 600; padding: 6px 2px; border-radius: 8px; cursor: pointer; text-align: center; transition: all 0.15s ease; }");
        sb.append(".btn-pill:active { background: #0284c7; color: #fff; transform: scale(0.95); }");
        sb.append(".btn-pill.active { background: #0284c7; color: #fff; border-color: #38bdf8; }");
        sb.append(".card-hdr { border: 1px solid #38bdf8; background: radial-gradient(circle at top left, #1e293b, #0f172a); }");
        sb.append(".toast { position: fixed; bottom: 25px; background: rgba(15, 23, 42, 0.95); color: #38bdf8; border: 1px solid #38bdf8; padding: 9px 18px; border-radius: 25px; font-size: 13px; font-weight: 600; opacity: 0; pointer-events: none; transition: opacity 0.2s ease; z-index: 999; box-shadow: 0 4px 15px rgba(0,0,0,0.5); }");
        sb.append(".toast.show { opacity: 1; }");
        sb.append("</style></head><body>");
        sb.append("<div class=\"header\"><div class=\"title\">🎬 斐讯 T1 影音画质控制中心</div><div class=\"subtitle\">晶晨 S912 硬件级零损耗缩放与 BT.2390 影院画质 (v4.0 HDR 增强版)</div></div>");

        // Card 0.5: HDR BT.2390 Cinema Tone Mapping
        sb.append("<div class=\"card card-hdr\">");
        sb.append("<div class=\"section-title\"><span>🌟 影院级 BT.2390 高动态映射 (明基 TK700)</span><span style=\"font-size:11px;color:#38bdf8;\">HDR➔SDR</span></div>");

        // BT.2390 Main Toggle
        sb.append("<button id=\"btn-bt2390\" class=\"btn-dnlp active\" onclick=\"toggleBt2390()\"><span>⚡ BT.2390 影院色调映射 (Ultra Punch 35点曲线)</span><span id=\"bt2390-txt\" class=\"status-tag\" style=\"background:#22c55e;color:#000;\">已开启</span></button>");

        // EOTF Highlight Boost Slider & Pills
        sb.append("<div class=\"pq-item\" style=\"margin-top:14px;\"><div class=\"pq-header\"><span class=\"pq-label\">✨ 高光动态爆发 (Highlight Boost)</span><div class=\"pq-controls\"><span id=\"val-eotf\" class=\"val-badge\">1.10x</span><button class=\"btn-rst\" onclick=\"setEotfPreset(110)\">↺ 黄金1.1x</button></div></div>");
        sb.append("<div class=\"pill-grid\">");
        sb.append("<button class=\"btn-pill\" id=\"pill-eotf-100\" onclick=\"setEotfPreset(100)\">1.00x 标准</button>");
        sb.append("<button class=\"btn-pill\" id=\"pill-eotf-105\" onclick=\"setEotfPreset(105)\">1.05x 温和</button>");
        sb.append("<button class=\"btn-pill active\" id=\"pill-eotf-110\" onclick=\"setEotfPreset(110)\">1.10x 推荐</button>");
        sb.append("<button class=\"btn-pill\" id=\"pill-eotf-115\" onclick=\"setEotfPreset(115)\">1.15x 极亮</button>");
        sb.append("</div>");
        sb.append("<input type=\"range\" id=\"range-eotf\" min=\"100\" max=\"125\" value=\"110\" step=\"1\" oninput=\"onSlideEotf(this.value)\">");
        sb.append("<div class=\"range-labels\"><span>1.00x (原盘)</span><span>1.10x (黄金推荐)</span><span>1.25x (超强)</span></div></div>");

        // Black Extension Slider & Pills
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🌑 暗场黑位沉底 (Black Extension)</span><div class=\"pq-controls\"><span id=\"val-bext\" class=\"val-badge\">8</span><button class=\"btn-rst\" onclick=\"setBextPreset(8)\">↺ 默认 8</button></div></div>");
        sb.append("<div class=\"pill-grid\">");
        sb.append("<button class=\"btn-pill\" id=\"pill-bext-0\" onclick=\"setBextPreset(0)\">0 原生</button>");
        sb.append("<button class=\"btn-pill active\" id=\"pill-bext-8\" onclick=\"setBextPreset(8)\">8 推荐</button>");
        sb.append("<button class=\"btn-pill\" id=\"pill-bext-16\" onclick=\"setBextPreset(16)\">16 纯黑</button>");
        sb.append("<button class=\"btn-pill\" id=\"pill-bext-24\" onclick=\"setBextPreset(24)\">24 极黑</button>");
        sb.append("</div>");
        sb.append("<input type=\"range\" id=\"range-bext\" min=\"0\" max=\"24\" value=\"8\" step=\"2\" oninput=\"onSlideBext(this.value)\">");
        sb.append("<div class=\"range-labels\"><span>0 (关闭下潜)</span><span>8 (保细节下潜)</span><span>24 (极致纯黑)</span></div></div>");

        // BT.2390 Custom Fine-Tuning Sliders
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">📐 曲线介入强度 (knee_factor)</span><div class=\"pq-controls\"><span id=\"val-kfactor\" class=\"val-badge\">256</span><button class=\"btn-rst\" onclick=\"setHdrParam('kfactor', 256)\">↺ 默认 256</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-kfactor\" min=\"0\" max=\"256\" value=\"256\" step=\"1\" oninput=\"onSlideHdr('kfactor', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>0 (线性无压缩)</span><span>128 (半幅混合)</span><span>256 (100% 满血曲线)</span></div></div>");

        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🎨 HDR➔SDR 饱和度补偿</span><div class=\"pq-controls\"><span id=\"val-sdrsat\" class=\"val-badge\">50</span><button class=\"btn-rst\" onclick=\"setHdrParam('sdrsat', 50)\">↺ 默认 50</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-sdrsat\" min=\"0\" max=\"100\" value=\"50\" step=\"1\" oninput=\"onSlideHdr('sdrsat', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>0 (无补偿)</span><span>50 (标准补偿)</span><span>100 (浓烈高饱)</span></div></div>");

        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">☀️ 白场高光动态拉伸</span><div class=\"pq-controls\"><span id=\"val-whtext\" class=\"val-badge\">16</span><button class=\"btn-rst\" onclick=\"setHdrParam('whtext', 16)\">↺ 默认 16</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-whtext\" min=\"0\" max=\"64\" value=\"16\" step=\"1\" oninput=\"onSlideHdr('whtext', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>0 (关闭拉伸)</span><span>16 (黄金推荐)</span><span>64 (极限提亮)</span></div></div>");

        // VPP Dither & 10bit NR Grid
        sb.append("<div class=\"btn-grid\" style=\"margin-top:10px;\">");
        sb.append("<button id=\"btn-dither\" class=\"zoom-btn active\" onclick=\"toggleDither()\">🌈 10-bit 空间平滑抖动<span id=\"dither-txt\" class=\"sub\" style=\"color:#e0f2fe;\">已开启 (杜绝断层)</span></button>");
        sb.append("<button id=\"btn-nr10\" class=\"zoom-btn active\" onclick=\"toggleNr10()\">🛡 10-bit 硬件降噪<span id=\"nr10-txt\" class=\"sub\" style=\"color:#e0f2fe;\">已开启 (纯净细腻)</span></button>");
        sb.append("</div>");

        // 10-bit HDMI Negotiation
        sb.append("<button class=\"btn-reset-pq\" style=\"margin-top:12px;background:#0369a1;color:#fff;border-color:#38bdf8;\" onclick=\"trigger10Bit()\">💎 4K 60Hz 10-bit 物理重协商 (cd=5)</button>");
        sb.append("</div>");

        // Card 0: System Doctor
        sb.append("<div class=\"card\" style=\"border:1px solid #0284c7;\">");
        sb.append("<div class=\"section-title\"><span>🔍 系统与环境深度诊断 (Doctor)</span><button class=\"btn-rst\" style=\"background:#0284c7;color:#fff;border-color:#38bdf8;padding:4px 10px;font-size:12px;\" onclick=\"runDiag()\">开始体检</button></div>");
        sb.append("<div id=\"diag-summary\" style=\"font-size:12px;color:#94a3b8;line-height:1.6;\">点击【开始体检】可探测 Root 提权通道、底层硬件节点、Seccomp 硬解补丁、视频硬解流及 Kodi 端口状态。</div>");
        sb.append("<div id=\"diag-details\" style=\"display:none;margin-top:10px;font-size:11px;line-height:1.5;background:#0f172a;padding:10px;border-radius:10px;border:1px solid #334155;white-space:pre-wrap;font-family:monospace;color:#cbd5e1;max-height:220px;overflow-y:auto;\"></div>");
        sb.append("<button id=\"btn-copy-diag\" class=\"btn-reset-pq\" style=\"display:none;margin-top:10px;background:#0369a1;color:#fff;font-size:12px;\" onclick=\"copyDiag()\">📋 复制完整诊断报告发给客服</button>");
        sb.append("</div>");

        // Card 1: Picture Quality
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>🎨 画面色彩与画质微调</span><span style=\"font-size:11px;font-weight:normal;color:#38bdf8;\">💾 自动记忆保存</span></div>");

        sb.append("<div style=\"background:rgba(56,189,248,0.08);border:1px solid rgba(56,189,248,0.25);border-radius:10px;padding:9px 12px;margin-bottom:14px;font-size:11px;color:#94a3b8;line-height:1.5;\">");
        sb.append("<strong style=\"color:#38bdf8;\">💡 生效提示：</strong>画质与缩放由芯片 <strong>VPP 硬件层</strong> 直接渲染，在<strong>播放电影/视频时（如 Kodi、播放器）</strong>即时生效（不影响安卓桌面静态 UI）。");
        sb.append("</div>");

        // Brightness Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">☀ 亮度 (Brightness)</span><div class=\"pq-controls\"><span id=\"val-brightness\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('brightness')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-brightness\" min=\"-100\" max=\"100\" value=\"0\" step=\"1\" oninput=\"onSlide('brightness', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-100 (极暗)</span><span>0 (默认)</span><span>+100 (极亮)</span></div></div>");

        // Contrast Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🌗 对比度 (Contrast)</span><div class=\"pq-controls\"><span id=\"val-contrast\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('contrast')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-contrast\" min=\"-100\" max=\"100\" value=\"0\" step=\"1\" oninput=\"onSlide('contrast', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-100 (低对比)</span><span>0 (默认)</span><span>+100 (超高对比)</span></div></div>");

        // Saturation Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🌈 色彩饱和度 (Color)</span><div class=\"pq-controls\"><span id=\"val-saturation\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('saturation')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-saturation\" min=\"-100\" max=\"100\" value=\"0\" step=\"1\" oninput=\"onSlide('saturation', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-100 (黑白无色)</span><span>0 (默认)</span><span>+100 (高浓艳)</span></div></div>");

        // Hue / Skin Tone Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🌸 肤色偏向 / 色相 (Hue)</span><div class=\"pq-controls\"><span id=\"val-hue\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('hue')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-hue\" min=\"-50\" max=\"50\" value=\"0\" step=\"1\" oninput=\"onSlide('hue', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-50 (红润/暖肤色)</span><span>0 (标准)</span><span>+50 (偏冷/青绿)</span></div></div>");

        // DNLP toggle
        sb.append("<button id=\"btn-dnlp\" class=\"btn-dnlp\" onclick=\"toggleDnlp()\"><span>⚡ 硬件动态对比度 (DNLP 智能去灰)</span><span id=\"dnlp-txt\" class=\"status-tag\">已关闭</span></button>");

        // CM2 Color Management (Off + 3 Levels)
        sb.append("<div class=\"pq-item\" style=\"margin-top:12px;margin-bottom:4px;\">");
        sb.append("<div class=\"pq-header\"><span class=\"pq-label\">🎭 CM2 色彩管理 (6色校正与肤色保护)</span><div class=\"pq-controls\"><span id=\"val-cm\" class=\"val-badge\">1档 自然</span></div></div>");
        sb.append("<div class=\"pill-grid\">");
        sb.append("<button class=\"btn-pill\" id=\"pill-cm-0\" onclick=\"setCmLevel(0)\">关闭</button>");
        sb.append("<button class=\"btn-pill active\" id=\"pill-cm-1\" onclick=\"setCmLevel(1)\">1档 自然</button>");
        sb.append("<button class=\"btn-pill\" id=\"pill-cm-2\" onclick=\"setCmLevel(2)\">2档 鲜艳</button>");
        sb.append("<button class=\"btn-pill\" id=\"pill-cm-3\" onclick=\"setCmLevel(3)\">3档 艳丽</button>");
        sb.append("</div></div>");

        // Reset All PQ
        sb.append("<button class=\"btn-reset-pq\" onclick=\"resetPq('all')\">↺ 复位所有画质参数至默认 (0)</button>");
        sb.append("</div>");

        // Card 2: Zoom
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>🔍 硬件数字变焦 (无损切黑边)</span></div>");
        sb.append("<div class=\"btn-grid-3\">");
        sb.append("<button id=\"btn-zoom-115\" class=\"zoom-btn\" onclick=\"setZ(115)\">115%<span class=\"sub\">轻微变焦</span></button>");
        sb.append("<button id=\"btn-zoom-125\" class=\"zoom-btn\" onclick=\"setZ(125)\">125%<span class=\"sub\">2.35:1 铺满</span></button>");
        sb.append("<button id=\"btn-zoom-133\" class=\"zoom-btn\" onclick=\"setZ(133)\">133%<span class=\"sub\">完全拉满</span></button>");
        sb.append("</div></div>");

        // Card 3: Screen Mode
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>📺 画面拉伸模式</span></div>");
        sb.append("<div class=\"btn-grid\">");
        sb.append("<button id=\"btn-mode-1\" class=\"zoom-btn mode-btn\" onclick=\"setM(1)\">全屏强制拉伸<span class=\"sub\">填满消除黑边</span></button>");
        sb.append("<button id=\"btn-mode-4\" class=\"zoom-btn mode-btn\" onclick=\"setM(4)\">智能非线性拉伸<span class=\"sub\">人物防变形</span></button>");
        sb.append("</div></div>");

        // Card 4: Reset Zoom
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>🔄 比例重置</span></div>");
        sb.append("<button class=\"btn-reset-zoom\" onclick=\"resetA()\">恢复 100% 原始比例<div style=\"font-size:11px;color:#cbd5e1;font-weight:normal;margin-top:2px;\">清除所有缩放与裁切</div></button>");
        sb.append("</div>");

        // Toast & Script
        sb.append("<div id=\"toast\" class=\"toast\">操作已生效</div>");
        sb.append("<script>");
        sb.append("let dnlpVal = 0;");
        sb.append("let cmVal = 0;");
        sb.append("let bt2390Val = 1;");
        sb.append("let ditherVal = 1;");
        sb.append("let nr10Val = 1;");
        sb.append("let eotfVal = 110;");
        sb.append("let bextVal = 8;");
        sb.append("let currentDiagReport = '';");
        sb.append("const timers = {};");
        sb.append("function toast(msg, isErr){");
        sb.append("  const t=document.getElementById('toast');");
        sb.append("  t.textContent=msg;");
        sb.append("  if(isErr){ t.style.borderColor='#ef4444'; t.style.color='#fca5a5'; }");
        sb.append("  else { t.style.borderColor='#38bdf8'; t.style.color='#38bdf8'; }");
        sb.append("  t.classList.add('show');");
        sb.append("  setTimeout(()=>t.classList.remove('show'),2200);");
        sb.append("}");
        sb.append("function fmtVal(v){const n=parseInt(v);return n>0?('+'+n):(''+n);}");
        sb.append("function onSlide(type, val){");
        sb.append("  document.getElementById('val-'+type).textContent=fmtVal(val);");
        sb.append("  clearTimeout(timers[type]);");
        sb.append("  timers[type]=setTimeout(()=>{");
        sb.append("    fetch('/api/pq?type='+type+'&val='+val).then(r=>r.json()).then(d=>d.status!=='ok'?toast('写入失败: '+(d.msg||''),true):toast(getName(type)+' 已设为 '+fmtVal(val))).catch(e=>toast('设置失败', true));");
        sb.append("  }, 60);");
        sb.append("}");
        sb.append("function getName(t){if(t==='brightness')return '亮度';if(t==='contrast')return '对比度';if(t==='saturation')return '色彩';if(t==='hue')return '肤色/色相';return t;}");
        sb.append("function onSlideEotf(v){");
        sb.append("  eotfVal = parseInt(v);");
        sb.append("  const f = (eotfVal/100).toFixed(2) + 'x';");
        sb.append("  document.getElementById('val-eotf').textContent = f;");
        sb.append("  updateEotfPill(eotfVal);");
        sb.append("  clearTimeout(timers['eotf']);");
        sb.append("  timers['eotf']=setTimeout(()=>{");
        sb.append("    fetch('/api/pq?type=eotf&val='+eotfVal).then(r=>r.json()).then(d=>d.status!=='ok'?toast('写入失败: '+(d.msg||''),true):toast('高光放大已设为 '+f)).catch(e=>toast('设置失败', true));");
        sb.append("  }, 60);");
        sb.append("}");
        sb.append("function setEotfPreset(v){");
        sb.append("  setRangeVal('range-eotf', v);");
        sb.append("  onSlideEotf(v);");
        sb.append("}");
        sb.append("function updateEotfPill(v){");
        sb.append("  document.querySelectorAll('[id^=\"pill-eotf-\"]').forEach(p=>p.classList.remove('active'));");
        sb.append("  const p = document.getElementById('pill-eotf-'+v);");
        sb.append("  if(p) p.classList.add('active');");
        sb.append("}");
        sb.append("function onSlideBext(v){");
        sb.append("  bextVal = parseInt(v);");
        sb.append("  document.getElementById('val-bext').textContent = bextVal;");
        sb.append("  updateBextPill(bextVal);");
        sb.append("  clearTimeout(timers['bext']);");
        sb.append("  timers['bext']=setTimeout(()=>{");
        sb.append("    fetch('/api/pq?type=bext&val='+bextVal).then(r=>r.json()).then(d=>d.status!=='ok'?toast('写入失败: '+(d.msg||''),true):toast('黑位沉底已设为 '+bextVal)).catch(e=>toast('设置失败', true));");
        sb.append("  }, 60);");
        sb.append("}");
        sb.append("function setBextPreset(v){");
        sb.append("  setRangeVal('range-bext', v);");
        sb.append("  onSlideBext(v);");
        sb.append("}");
        sb.append("function updateBextPill(v){");
        sb.append("  document.querySelectorAll('[id^=\"pill-bext-\"]').forEach(p=>p.classList.remove('active'));");
        sb.append("  const p = document.getElementById('pill-bext-'+v);");
        sb.append("  if(p) p.classList.add('active');");
        sb.append("}");
        sb.append("function getHdrName(t){if(t==='kfactor')return '曲线介入强度';if(t==='sdrsat')return '饱和度补偿';if(t==='whtext')return '白场动态拉伸';return t;}");
        sb.append("function onSlideHdr(type, val){");
        sb.append("  const n = parseInt(val);");
        sb.append("  document.getElementById('val-'+type).textContent = n;");
        sb.append("  clearTimeout(timers[type]);");
        sb.append("  timers[type]=setTimeout(()=>{");
        sb.append("    fetch('/api/pq?type='+type+'&val='+n).then(r=>r.json()).then(d=>d.status!=='ok'?toast('写入失败: '+(d.msg||''),true):toast(getHdrName(type)+' 已设为 '+n)).catch(e=>toast('设置失败', true));");
        sb.append("  }, 60);");
        sb.append("}");
        sb.append("function setHdrParam(type, val){");
        sb.append("  setRangeVal('range-'+type, val);");
        sb.append("  onSlideHdr(type, val);");
        sb.append("}");
        sb.append("function toggleBt2390(){");
        sb.append("  const nVal = bt2390Val === 1 ? 0 : 1;");
        sb.append("  fetch('/api/pq?type=bt2390&val='+nVal).then(r=>r.json()).then(d=>{if(d.status!=='ok'){toast('写入失败: '+(d.msg||''),true);return;}");
        sb.append("    setBt2390UI(nVal);");
        sb.append("    toast('BT.2390 影院映射 '+(nVal===1?'已开启':'已关闭'));");
        sb.append("  }).catch(e=>toast('设置失败', true));");
        sb.append("}");
        sb.append("function setBt2390UI(v){");
        sb.append("  bt2390Val = v;");
        sb.append("  const btn = document.getElementById('btn-bt2390');");
        sb.append("  const txt = document.getElementById('bt2390-txt');");
        sb.append("  if(v===1){btn.classList.add('active');txt.textContent='已开启 (接管算法)';txt.style.background='#22c55e';txt.style.color='#000';}");
        sb.append("  else{btn.classList.remove('active');txt.textContent='已关闭 (直通)';txt.style.background='rgba(0,0,0,0.3)';txt.style.color='#cbd5e1';}");
        sb.append("}");
        sb.append("function toggleDither(){");
        sb.append("  const nVal = ditherVal === 1 ? 0 : 1;");
        sb.append("  fetch('/api/pq?type=dither&val='+nVal).then(r=>r.json()).then(d=>{if(d.status!=='ok'){toast('写入失败: '+(d.msg||''),true);return;}");
        sb.append("    setDitherUI(nVal);");
        sb.append("    toast('10-bit 空间抖动 '+(nVal===1?'已开启':'已关闭'));");
        sb.append("  }).catch(e=>toast('设置失败', true));");
        sb.append("}");
        sb.append("function setDitherUI(v){");
        sb.append("  ditherVal = v;");
        sb.append("  const btn = document.getElementById('btn-dither');");
        sb.append("  const txt = document.getElementById('dither-txt');");
        sb.append("  if(v===1){btn.classList.add('active');txt.textContent='已开启 (杜绝断层)';txt.style.color='#e0f2fe';}");
        sb.append("  else{btn.classList.remove('active');txt.textContent='已关闭';txt.style.color='#94a3b8';}");
        sb.append("}");
        sb.append("function toggleNr10(){");
        sb.append("  const nVal = nr10Val === 1 ? 0 : 1;");
        sb.append("  fetch('/api/pq?type=nr10&val='+nVal).then(r=>r.json()).then(d=>{if(d.status!=='ok'){toast('写入失败: '+(d.msg||''),true);return;}");
        sb.append("    setNr10UI(nVal);");
        sb.append("    toast('10-bit 硬件降噪 '+(nVal===1?'已开启':'已关闭'));");
        sb.append("  }).catch(e=>toast('设置失败', true));");
        sb.append("}");
        sb.append("function setNr10UI(v){");
        sb.append("  nr10Val = v;");
        sb.append("  const btn = document.getElementById('btn-nr10');");
        sb.append("  const txt = document.getElementById('nr10-txt');");
        sb.append("  if(v===1){btn.classList.add('active');txt.textContent='已开启 (纯净细腻)';txt.style.color='#e0f2fe';}");
        sb.append("  else{btn.classList.remove('active');txt.textContent='已关闭';txt.style.color='#94a3b8';}");
        sb.append("}");
        sb.append("function trigger10Bit(){");
        sb.append("  fetch('/api/pq?type=10bit').then(r=>r.json()).then(d=>{if(d.status!=='ok'){toast('写入失败: '+(d.msg||''),true);return;}");
        sb.append("    toast('已触发 4K 10-bit 硬件握手重协商');");
        sb.append("  }).catch(e=>toast('重协商请求失败', true));");
        sb.append("}");
        sb.append("function resetPq(type){");
        sb.append("  if(type==='brightness'||type==='all'){setRangeVal('range-brightness',0);document.getElementById('val-brightness').textContent='0';}");
        sb.append("  if(type==='contrast'||type==='all'){setRangeVal('range-contrast',0);document.getElementById('val-contrast').textContent='0';}");
        sb.append("  if(type==='saturation'||type==='all'){setRangeVal('range-saturation',0);document.getElementById('val-saturation').textContent='0';}");
        sb.append("  if(type==='hue'||type==='all'){setRangeVal('range-hue',0);document.getElementById('val-hue').textContent='0';}");
        sb.append("  if(type==='all'){setDnlpUI(0);setCmUI(0);}");
        sb.append("  fetch('/api/pq?type=reset&item='+type).then(r=>r.json()).then(d=>d.status!=='ok'?toast('写入失败: '+(d.msg||''),true):toast((type==='all'?'全部画质':getName(type))+' 已复位为 0')).catch(e=>toast('复位失败', true));");
        sb.append("}");
        sb.append("function toggleDnlp(){");
        sb.append("  const nVal = dnlpVal === 1 ? 0 : 1;");
        sb.append("  fetch('/api/pq?type=dnlp&val='+nVal).then(r=>r.json()).then(d=>{if(d.status!=='ok'){toast('写入失败: '+(d.msg||''),true);return;}setDnlpUI(nVal);toast('动态对比度 (DNLP) '+(nVal===1?'已开启':'已关闭'));}).catch(e=>toast('设置失败', true));");
        sb.append("}");
        sb.append("function setDnlpUI(v){");
        sb.append("  dnlpVal = v;");
        sb.append("  const btn = document.getElementById('btn-dnlp');");
        sb.append("  const txt = document.getElementById('dnlp-txt');");
        sb.append("  if(v===1){btn.classList.add('active');txt.textContent='已开启 (去灰增强)';txt.style.background='#22c55e';txt.style.color='#000';}");
        sb.append("  else{btn.classList.remove('active');txt.textContent='已关闭';txt.style.background='rgba(0,0,0,0.3)';txt.style.color='#cbd5e1';}");
        sb.append("}");
        sb.append("const CM_NAMES = ['关闭', '1档 自然', '2档 鲜艳', '3档 艳丽'];");
        sb.append("function setCmLevel(v){");
        sb.append("  fetch('/api/pq?type=cm&val='+v).then(r=>r.json()).then(d=>{if(d.status!=='ok'){toast('写入失败: '+(d.msg||''),true);return;}setCmUI(v);toast(v===0?'CM2 色彩管理已关闭':'CM2 色彩管理: '+CM_NAMES[v]);}).catch(e=>toast('设置失败', true));");
        sb.append("}");
        sb.append("function setCmUI(v){");
        sb.append("  cmVal = v;");
        sb.append("  const b = document.getElementById('val-cm');");
        sb.append("  if(b) b.textContent = CM_NAMES[v] || '关闭';");
        sb.append("  document.querySelectorAll('[id^=\"pill-cm-\"]').forEach(p=>p.classList.remove('active'));");
        sb.append("  const p = document.getElementById('pill-cm-'+v);");
        sb.append("  if(p) p.classList.add('active');");
        sb.append("}");
        sb.append("function setZoomUI(v){");
        sb.append("  document.querySelectorAll('[id^=\"btn-zoom-\"]').forEach(b=>b.classList.remove('active'));");
        sb.append("  const b=document.getElementById('btn-zoom-'+v); if(b) b.classList.add('active');");
        sb.append("}");
        sb.append("function setZ(v){fetch('/api/zoom?val='+v).then(r=>r.json()).then(d=>{if(d.status==='ok'){setZoomUI(v);toast('已变焦至 '+v+'% (切除黑边)');}else toast('变焦失败: '+d.msg, true);}).catch(e=>toast('设置失败', true));}");
        sb.append("function setM(v){");
        sb.append("  const name = v === 1 ? '全屏强制拉伸 (消除黑边)' : (v === 4 ? '智能非线性拉伸 (人物防变形)' : ('屏幕模式 ' + v));");
        sb.append("  fetch('/api/mode?val='+v).then(r=>r.json()).then(d=>{");
        sb.append("    if(d.status==='ok'){");
        sb.append("      document.querySelectorAll('.mode-btn').forEach(b=>b.classList.remove('active'));");
        sb.append("      const b=document.getElementById('btn-mode-'+v); if(b) b.classList.add('active');");
        sb.append("      toast('已切换为 '+name);");
        sb.append("    } else toast('模式失败: '+d.msg, true);");
        sb.append("  }).catch(e=>toast('设置失败', true));");
        sb.append("}");
        sb.append("function resetA(){");
        sb.append("  fetch('/api/reset').then(r=>r.json()).then(d=>{");
        sb.append("    if(d.status!=='ok'){toast('重置失败: '+(d.msg||''),true);return;}");
        sb.append("    document.querySelectorAll('.mode-btn').forEach(b=>b.classList.remove('active'));");
        sb.append("    setZoomUI(100);");
        sb.append("    toast('已恢复 100% 原始比例');");
        sb.append("  }).catch(e=>toast('重置失败', true));");
        sb.append("}");
        sb.append("function runDiag(){");
        sb.append("  const sum=document.getElementById('diag-summary');");
        sb.append("  const det=document.getElementById('diag-details');");
        sb.append("  const btnCopy=document.getElementById('btn-copy-diag');");
        sb.append("  sum.innerHTML='⏳ 正在全面探测盒子底层环境，请稍候...';");
        sb.append("  fetch('/api/diagnose').then(r=>r.json()).then(d=>{");
        sb.append("    let html='<strong>【体检总结】</strong> '+(d.rootOk&&d.seccompPatched?'<span style=\"color:#22c55e;\">核心环境正常</span>':'<span style=\"color:#ef4444;\">发现异常项</span>')+'<br>';");
        sb.append("    html+='• 提权通道: <span style=\"color:#38bdf8;\">'+d.rootChannel+'</span><br>';");
        sb.append("    html+='• ADB 端口: '+(d.adbPortOpen?'<span style=\"color:#22c55e;\">5555端口通畅</span>':'<span style=\"color:#f59e0b;\">未开启/拒绝</span>')+'<br>';");
        sb.append("    html+='• Seccomp 补丁: '+(d.seccompPatched?'<span style=\"color:#22c55e;\">'+d.seccompInfo+'</span>':'<span style=\"color:#ef4444;\">'+d.seccompInfo+'</span>')+'<br>';");
        sb.append("    html+='• Zoom节点写入: '+(d.zoomWritable?'<span style=\"color:#22c55e;\">读写正常 (当前='+d.zoomVal+')</span>':'<span style=\"color:#ef4444;\">写入测试失败</span>')+'<br>';");
        sb.append("    html+='• 视频硬解流: '+(d.videoPlaying?'<span style=\"color:#22c55e;\">正在播放中 (已检测到硬件流)</span>':'<span style=\"color:#f59e0b;\">未播放硬解视频 (桌面不缩放)</span>')+'<br>';");
        sb.append("    html+='• Kodi 9090端口: '+(d.kodiRpcOk?'<span style=\"color:#22c55e;\">畅通</span>':'<span style=\"color:#94a3b8;\">未联通</span>');");
        sb.append("    sum.innerHTML=html;");
        sb.append("    currentDiagReport=d.reportText;");
        sb.append("    det.textContent=d.reportText;");
        sb.append("    det.style.display='block';");
        sb.append("    btnCopy.style.display='block';");
        sb.append("    toast('体检完成');");
        sb.append("  }).catch(e=>{ sum.innerHTML='<span style=\"color:#ef4444;\">体检请求失败: '+e+'</span>'; });");
        sb.append("}");
        sb.append("function copyDiag(){");
        sb.append("  if(!currentDiagReport)return;");
        sb.append("  if(navigator.clipboard&&navigator.clipboard.writeText){");
        sb.append("    navigator.clipboard.writeText(currentDiagReport).then(()=>toast('已复制体检报告')).catch(()=>fbCopy());");
        sb.append("  }else{ fbCopy(); }");
        sb.append("}");
        sb.append("function fbCopy(){");
        sb.append("  const ta=document.createElement('textarea');");
        sb.append("  ta.value=currentDiagReport;");
        sb.append("  document.body.appendChild(ta);");
        sb.append("  ta.select();");
        sb.append("  document.execCommand('copy');");
        sb.append("  document.body.removeChild(ta);");
        sb.append("  toast('已复制体检报告');");
        sb.append("}");
        sb.append("function setRangeVal(id, v){");
        sb.append("  const el=document.getElementById(id);");
        sb.append("  if(!el)return;");
        sb.append("  el.value=v;");
        sb.append("  el.dataset.val=String(v);");
        sb.append("}");
        sb.append("function initRangeGuard(){");
        sb.append("  document.querySelectorAll('input[type=\"range\"]').forEach(el=>{");
        sb.append("    el.dataset.val=el.value;");
        sb.append("    el._canDrag=false;");
        sb.append("    el._dragging=false;");
        sb.append("    const onStart=(x,e,isMouse)=>{");
        sb.append("      const rect=el.getBoundingClientRect();");
        sb.append("      const min=parseFloat(el.min||'0'), max=parseFloat(el.max||'100');");
        sb.append("      const cur=parseFloat(el.dataset.val!==undefined?el.dataset.val:el.value);");
        sb.append("      const ratio=(max>min)?(cur-min)/(max-min):0;");
        sb.append("      const thumbX=rect.left+12+ratio*(rect.width-24);");
        sb.append("      if(Math.abs(x-thumbX)<=24){");
        sb.append("        el._canDrag=true; el._dragging=false; el._startX=x;");
        sb.append("      }else{");
        sb.append("        el._canDrag=false; el._dragging=false;");
        sb.append("        if(isMouse&&e)e.preventDefault();");
        sb.append("      }");
        sb.append("    };");
        sb.append("    const onMove=(x)=>{");
        sb.append("      if(el._canDrag&&Math.abs(x-el._startX)>=2){el._dragging=true;}");
        sb.append("    };");
        sb.append("    const onEnd=()=>{");
        sb.append("      if(!el._dragging&&el.dataset.val!==undefined){el.value=el.dataset.val;}");
        sb.append("      el._canDrag=false; el._dragging=false;");
        sb.append("    };");
        sb.append("    el.addEventListener('touchstart',e=>{if(e.touches.length===1)onStart(e.touches[0].clientX,e,false);},{passive:true});");
        sb.append("    el.addEventListener('touchmove',e=>{if(e.touches.length===1)onMove(e.touches[0].clientX);},{passive:true});");
        sb.append("    el.addEventListener('touchend',onEnd);");
        sb.append("    el.addEventListener('touchcancel',onEnd);");
        sb.append("    el.addEventListener('mousedown',e=>onStart(e.clientX,e,true));");
        sb.append("    window.addEventListener('mousemove',e=>{if(el._canDrag)onMove(e.clientX);});");
        sb.append("    window.addEventListener('mouseup',()=>{if(el._canDrag)onEnd();});");
        sb.append("    const blockIfNotDrag=e=>{");
        sb.append("      if(!el._dragging){");
        sb.append("        if(el.dataset.val!==undefined)el.value=el.dataset.val;");
        sb.append("        e.stopImmediatePropagation();");
        sb.append("        return;");
        sb.append("      }");
        sb.append("      el.dataset.val=el.value;");
        sb.append("    };");
        sb.append("    el.addEventListener('input',blockIfNotDrag,true);");
        sb.append("    el.addEventListener('change',blockIfNotDrag,true);");
        sb.append("  });");
        sb.append("}");
        sb.append("window.addEventListener('DOMContentLoaded',()=>{");
        sb.append("  initRangeGuard();");
        sb.append("  fetch('/api/status').then(r=>r.json()).then(d=>{");
        sb.append("    if(d.brightness!==undefined){setRangeVal('range-brightness',d.brightness);document.getElementById('val-brightness').textContent=fmtVal(d.brightness);}");
        sb.append("    if(d.contrast!==undefined){setRangeVal('range-contrast',d.contrast);document.getElementById('val-contrast').textContent=fmtVal(d.contrast);}");
        sb.append("    if(d.saturation!==undefined){setRangeVal('range-saturation',d.saturation);document.getElementById('val-saturation').textContent=fmtVal(d.saturation);}");
        sb.append("    if(d.hue!==undefined){setRangeVal('range-hue',d.hue);document.getElementById('val-hue').textContent=fmtVal(d.hue);}");
        sb.append("    if(d.dnlp!==undefined){setDnlpUI(d.dnlp);}");
        sb.append("    if(d.cm!==undefined){setCmUI(d.cm);}");
        sb.append("    if(d.eotf!==undefined){setRangeVal('range-eotf',d.eotf);document.getElementById('val-eotf').textContent=(d.eotf/100).toFixed(2)+'x';updateEotfPill(d.eotf);}");
        sb.append("    if(d.bext!==undefined){setRangeVal('range-bext',d.bext);document.getElementById('val-bext').textContent=d.bext;updateBextPill(d.bext);}");
        sb.append("    if(d.dither!==undefined){setDitherUI(d.dither);}");
        sb.append("    if(d.nr10!==undefined){setNr10UI(d.nr10);}");
        sb.append("    if(d.bt2390!==undefined){setBt2390UI(d.bt2390);}");
        sb.append("    if(d.kfactor!==undefined){setRangeVal('range-kfactor',d.kfactor);document.getElementById('val-kfactor').textContent=d.kfactor;}");
        sb.append("    if(d.sdrsat!==undefined){setRangeVal('range-sdrsat',d.sdrsat);document.getElementById('val-sdrsat').textContent=d.sdrsat;}");
        sb.append("    if(d.whtext!==undefined){setRangeVal('range-whtext',d.whtext);document.getElementById('val-whtext').textContent=d.whtext;}");
        sb.append("    if(d.zoom!==undefined){setZoomUI(d.zoom);}");
        sb.append("    if(d.mode!==undefined){document.querySelectorAll('.mode-btn').forEach(b=>b.classList.remove('active'));const mb=document.getElementById('btn-mode-'+d.mode);if(mb)mb.classList.add('active');}");
        sb.append("  }).catch(()=>{});");
        sb.append("});");
        sb.append("</script></body></html>");
        return sb.toString();
    }
}
