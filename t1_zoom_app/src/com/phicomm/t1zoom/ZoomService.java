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
            final int c = prefs.getInt("contrast", 0);
            final int s = prefs.getInt("saturation", 0);
            final int d = prefs.getInt("dnlp", 0);

            // Pre-set in-memory tracking so status API is instantly accurate
            AdbClient.setCurBrightness(b);
            AdbClient.setCurContrast(c);
            AdbClient.setCurSaturation(s);
            AdbClient.setCurDnlp(d);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Wait for system and adbd to be ready
                        Thread.sleep(1500);
                        Log.i(TAG, "Applying restored PQ to hardware: b=" + b + ", c=" + c + ", s=" + s + ", d=" + d);
                        if (b != 0) AdbClient.setBrightness(b);
                        if (c != 0) AdbClient.setContrast(c);
                        if (s != 0) AdbClient.setSaturation(s);
                        if (d != 0) AdbClient.setDnlp(d);
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

    // ========== Foreground Service (API 16-25 compatible) ==========

    @SuppressWarnings("deprecation")
    private void startAsForeground() {
        try {
            // API 16-25: use Notification.Builder without channel
            // PRIORITY_MIN = -2: completely silent, no visual intrusion
            Notification notification = new Notification.Builder(this)
                    .setContentTitle("T1 Zoom")
                    .setContentText("Port " + PORT)
                    .setSmallIcon(android.R.drawable.ic_menu_crop)
                    .setPriority(Notification.PRIORITY_MIN)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
            Log.i(TAG, "Started as foreground service");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground: " + e.getMessage());
        }
    }

    // ========== Screen On Receiver (dynamic) ==========

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
                            AdbClient.setZoom(val);
                            if (val > 100) {
                                syncKodiViewMode(val);
                            }
                            sendJson(out, "{\"status\":\"ok\",\"zoom\":" + val + "}");
                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
                        }
                    } else if (path.startsWith("/api/mode?val=")) {
                        String valStr = path.substring(path.indexOf("val=") + 4);
                        try {
                            int val = Integer.parseInt(valStr);
                            AdbClient.setScreenMode(val);
                            sendJson(out, "{\"status\":\"ok\",\"mode\":" + val + "}");
                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
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
                                } else if ("all".equals(item)) {
                                    AdbClient.resetAllPq();
                                    savePqPref("brightness", 0);
                                    savePqPref("contrast", 0);
                                    savePqPref("saturation", 0);
                                    savePqPref("dnlp", 0);
                                }
                                sendJson(out, "{\"status\":\"ok\",\"action\":\"reset\",\"item\":\"" + item + "\"}");
                            } else if ("brightness".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                AdbClient.setBrightness(val);
                                savePqPref("brightness", val);
                                sendJson(out, "{\"status\":\"ok\",\"type\":\"brightness\",\"val\":" + val + "}");
                            } else if ("contrast".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                AdbClient.setContrast(val);
                                savePqPref("contrast", val);
                                sendJson(out, "{\"status\":\"ok\",\"type\":\"contrast\",\"val\":" + val + "}");
                            } else if ("saturation".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                AdbClient.setSaturation(val);
                                savePqPref("saturation", val);
                                sendJson(out, "{\"status\":\"ok\",\"type\":\"saturation\",\"val\":" + val + "}");
                            } else if ("dnlp".equals(type) && valStr != null) {
                                int val = Integer.parseInt(valStr);
                                AdbClient.setDnlp(val);
                                savePqPref("dnlp", val);
                                sendJson(out, "{\"status\":\"ok\",\"type\":\"dnlp\",\"val\":" + val + "}");
                            } else {
                                sendJson(out, "{\"status\":\"error\",\"msg\":\"invalid params\"}");
                            }

                        } catch (Exception e) {
                            sendJson(out, "{\"status\":\"error\",\"msg\":\"" + e.getMessage() + "\"}");
                        }
                    } else if (path.startsWith("/api/reset")) {
                        AdbClient.resetAll();
                        setKodiViewMode(0);
                        sendJson(out, "{\"status\":\"ok\",\"action\":\"reset\"}");
                    } else if (path.startsWith("/api/status")) {
                        sendJson(out, "{\"status\":\"ok\",\"service\":\"running\",\"port\":" + PORT +
                                ",\"zoom\":" + AdbClient.getCurZoom() +
                                ",\"mode\":" + AdbClient.getCurMode() +
                                ",\"brightness\":" + AdbClient.getCurBrightness() +
                                ",\"contrast\":" + AdbClient.getCurContrast() +
                                ",\"saturation\":" + AdbClient.getCurSaturation() +
                                ",\"dnlp\":" + AdbClient.getCurDnlp() + "}");
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
                    Log.d(TAG, "Kodi RPC skipped: " + e.getMessage());
                } finally {
                    if (kodiSock != null) {
                        try { kodiSock.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
    }

    // ========== HTTP Response Helpers ==========

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
        sb.append(".header { text-align: center; margin-bottom: 20px; }");
        sb.append(".title { font-size: 21px; font-weight: 700; color: #38bdf8; }");
        sb.append(".subtitle { font-size: 12px; color: #94a3b8; margin-top: 4px; }");
        sb.append(".status { text-align: center; font-size: 12px; color: #22c55e; margin-top: 6px; font-weight: 500; }");
        sb.append(".card { background: #1e293b; border-radius: 18px; padding: 18px; width: 100%; max-width: 400px; border: 1px solid #334155; margin-bottom: 16px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }");
        sb.append(".section-title { font-size: 14px; font-weight: 700; color: #38bdf8; margin-bottom: 14px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #334155; padding-bottom: 8px; }");
        sb.append(".pq-item { margin-bottom: 16px; }");
        sb.append(".pq-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }");
        sb.append(".pq-label { font-size: 13px; font-weight: 600; color: #e2e8f0; display: flex; align-items: center; gap: 6px; }");
        sb.append(".pq-controls { display: flex; align-items: center; gap: 8px; }");
        sb.append(".val-badge { font-family: monospace; font-size: 13px; font-weight: 700; color: #38bdf8; background: #0f172a; padding: 2px 7px; border-radius: 6px; border: 1px solid #334155; min-width: 42px; text-align: center; }");
        sb.append(".btn-rst { background: #334155; border: 1px solid #475569; color: #cbd5e1; font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 6px; cursor: pointer; transition: all 0.15s ease; }");
        sb.append(".btn-rst:active { background: #0284c7; color: #fff; border-color: #38bdf8; transform: scale(0.94); }");
        sb.append("input[type=range] { width: 100%; -webkit-appearance: none; height: 7px; border-radius: 4px; background: #334155; outline: none; margin: 6px 0 2px; }");
        sb.append("input[type=range]::-webkit-slider-thumb { -webkit-appearance: none; width: 22px; height: 22px; border-radius: 50%; background: #38bdf8; box-shadow: 0 0 10px rgba(56,189,248,0.7); cursor: pointer; border: 2px solid #fff; }");
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
        sb.append(".toast { position: fixed; bottom: 25px; background: rgba(15, 23, 42, 0.95); color: #38bdf8; border: 1px solid #38bdf8; padding: 9px 18px; border-radius: 25px; font-size: 13px; font-weight: 600; opacity: 0; pointer-events: none; transition: opacity 0.2s ease; z-index: 999; box-shadow: 0 4px 15px rgba(0,0,0,0.5); }");
        sb.append(".toast.show { opacity: 1; }");
        sb.append("</style></head><body>");
        sb.append("<div class=\"header\"><div class=\"title\">🎬 斐讯 T1 影音画质控制</div><div class=\"subtitle\">晶晨 S912 硬件级零损耗缩放与色彩微调</div><div class=\"status\">🟢 硬件引擎已就绪 · 💾 开机自动记忆已启用</div></div>");

        // Card 1: Picture Quality (Brightness, Contrast, Saturation, DNLP)
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>🎨 画面色彩与画质微调</span><span style=\"font-size:11px;font-weight:normal;color:#38bdf8;\">💾 自动记忆保存</span></div>");

        sb.append("<div style=\"background:rgba(56,189,248,0.08);border:1px solid rgba(56,189,248,0.25);border-radius:10px;padding:9px 12px;margin-bottom:14px;font-size:11px;color:#94a3b8;line-height:1.5;\">");
        sb.append("<strong style=\"color:#38bdf8;\">💡 生效提示：</strong>画质、对比度与动态对比度(DNLP)由芯片 <strong>VPP 硬件层</strong>直接渲染，在<strong>播放电影/视频时（如 Kodi、影视仓、播放器）</strong>即时生效（不影响安卓桌面静态 UI）。");
        sb.append("</div>");

        // Brightness Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">☀️ 亮度 (Brightness)</span><div class=\"pq-controls\"><span id=\"val-brightness\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('brightness')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-brightness\" min=\"-100\" max=\"100\" value=\"0\" step=\"1\" oninput=\"onSlide('brightness', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-100 (极暗)</span><span>0 (默认)</span><span>+100 (极亮)</span></div></div>");

        // Contrast Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🌗 对比度 (Contrast)</span><div class=\"pq-controls\"><span id=\"val-contrast\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('contrast')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-contrast\" min=\"-100\" max=\"100\" value=\"0\" step=\"1\" oninput=\"onSlide('contrast', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-100 (柔和/低反差)</span><span>0 (默认)</span><span>+100 (高反差/通透)</span></div></div>");

        // Saturation Slider
        sb.append("<div class=\"pq-item\"><div class=\"pq-header\"><span class=\"pq-label\">🌈 色彩饱和度 (Color)</span><div class=\"pq-controls\"><span id=\"val-saturation\" class=\"val-badge\">0</span><button class=\"btn-rst\" onclick=\"resetPq('saturation')\">↺ 复位</button></div></div>");
        sb.append("<input type=\"range\" id=\"range-saturation\" min=\"-100\" max=\"100\" value=\"0\" step=\"1\" oninput=\"onSlide('saturation', this.value)\">");
        sb.append("<div class=\"range-labels\"><span>-100 (纯黑白)</span><span>0 (默认)</span><span>+100 (鲜艳浓郁)</span></div></div>");

        // DNLP toggle
        sb.append("<button id=\"btn-dnlp\" class=\"btn-dnlp\" onclick=\"toggleDnlp()\"><span>✨ 硬件动态对比度 (DNLP 智能去灰)</span><span id=\"dnlp-txt\" class=\"status-tag\">已关闭</span></button>");

        // Reset All PQ
        sb.append("<button class=\"btn-reset-pq\" onclick=\"resetPq('all')\">↺ 复位所有画质参数至默认 (0)</button>");
        sb.append("</div>");

        // Card 2: Zoom
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>✨ 硬件数字变焦 (无损切黑边)</span></div>");
        sb.append("<div class=\"btn-grid-3\">");
        sb.append("<button class=\"zoom-btn\" onclick=\"setZ(115)\">115%<span class=\"sub\">轻微变焦</span></button>");
        sb.append("<button class=\"zoom-btn active\" onclick=\"setZ(125)\">125%<span class=\"sub\">2.35:1 铺满</span></button>");
        sb.append("<button class=\"zoom-btn\" onclick=\"setZ(133)\">133%<span class=\"sub\">完全拉满</span></button>");
        sb.append("</div></div>");

        // Card 3: Screen Mode
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>📺 画面拉伸模式</span></div>");
        sb.append("<div class=\"btn-grid\">");
        sb.append("<button class=\"zoom-btn\" onclick=\"setM(1)\">全屏强制拉伸<span class=\"sub\">填满消除黑边</span></button>");
        sb.append("<button class=\"zoom-btn\" onclick=\"setM(4)\">智能非线性拉伸<span class=\"sub\">人物防变形</span></button>");
        sb.append("</div></div>");

        // Card 4: Reset Zoom
        sb.append("<div class=\"card\"><div class=\"section-title\"><span>🔄 比例重置</span></div>");
        sb.append("<button class=\"btn-reset-zoom\" onclick=\"resetA()\">恢复 100% 原始比例<div style=\"font-size:11px;color:#cbd5e1;font-weight:normal;margin-top:2px;\">清除所有缩放与裁切</div></button>");
        sb.append("</div>");

        // Toast & Script
        sb.append("<div id=\"toast\" class=\"toast\">操作已生效</div>");
        sb.append("<script>");
        sb.append("let dnlpVal = 0;");
        sb.append("const timers = {};");
        sb.append("function toast(msg){const t=document.getElementById('toast');t.textContent=msg;t.classList.add('show');setTimeout(()=>t.classList.remove('show'),1400);}");
        sb.append("function fmtVal(v){const n=parseInt(v);return n>0?('+'+n):(''+n);}");
        sb.append("function onSlide(type, val){");
        sb.append("  document.getElementById('val-'+type).textContent=fmtVal(val);");
        sb.append("  clearTimeout(timers[type]);");
        sb.append("  timers[type]=setTimeout(()=>{");
        sb.append("    fetch('/api/pq?type='+type+'&val='+val).then(r=>r.json()).then(d=>toast(getName(type)+' 已设为 '+fmtVal(val))).catch(e=>toast('设置失败'));");
        sb.append("  }, 60);");
        sb.append("}");
        sb.append("function getName(t){if(t==='brightness')return '亮度';if(t==='contrast')return '对比度';if(t==='saturation')return '色彩';return t;}");
        sb.append("function resetPq(type){");
        sb.append("  if(type==='brightness'||type==='all'){document.getElementById('range-brightness').value=0;document.getElementById('val-brightness').textContent='0';}");
        sb.append("  if(type==='contrast'||type==='all'){document.getElementById('range-contrast').value=0;document.getElementById('val-contrast').textContent='0';}");
        sb.append("  if(type==='saturation'||type==='all'){document.getElementById('range-saturation').value=0;document.getElementById('val-saturation').textContent='0';}");
        sb.append("  if(type==='all'){setDnlpUI(0);}");
        sb.append("  fetch('/api/pq?type=reset&item='+type).then(r=>r.json()).then(d=>toast((type==='all'?'全部画质':getName(type))+' 已复位为 0')).catch(e=>toast('复位失败'));");
        sb.append("}");
        sb.append("function toggleDnlp(){");
        sb.append("  const nVal = dnlpVal === 1 ? 0 : 1;");
        sb.append("  fetch('/api/pq?type=dnlp&val='+nVal).then(r=>r.json()).then(d=>{setDnlpUI(nVal);toast('动态对比度 (DNLP) '+(nVal===1?'已开启':'已关闭'));}).catch(e=>toast('设置失败'));");
        sb.append("}");
        sb.append("function setDnlpUI(v){");
        sb.append("  dnlpVal = v;");
        sb.append("  const btn = document.getElementById('btn-dnlp');");
        sb.append("  const txt = document.getElementById('dnlp-txt');");
        sb.append("  if(v===1){btn.classList.add('active');txt.textContent='已开启 (去灰增强)';txt.style.background='#22c55e';txt.style.color='#000';}");
        sb.append("  else{btn.classList.remove('active');txt.textContent='已关闭';txt.style.background='rgba(0,0,0,0.3)';txt.style.color='#cbd5e1';}");
        sb.append("}");
        sb.append("function setZ(v){fetch('/api/zoom?val='+v).then(r=>r.json()).then(d=>toast('已变焦至 '+v+'% (切除黑边)')).catch(e=>toast('设置失败'));}");
        sb.append("function setM(v){fetch('/api/mode?val='+v).then(r=>r.json()).then(d=>toast('已切换屏幕模式 '+v)).catch(e=>toast('设置失败'));}");
        sb.append("function resetA(){fetch('/api/reset').then(r=>r.json()).then(d=>toast('已恢复 100% 原始比例')).catch(e=>toast('重置失败'));}");
        sb.append("window.addEventListener('DOMContentLoaded',()=>{");
        sb.append("  fetch('/api/status').then(r=>r.json()).then(d=>{");
        sb.append("    if(d.brightness!==undefined){document.getElementById('range-brightness').value=d.brightness;document.getElementById('val-brightness').textContent=fmtVal(d.brightness);}");
        sb.append("    if(d.contrast!==undefined){document.getElementById('range-contrast').value=d.contrast;document.getElementById('val-contrast').textContent=fmtVal(d.contrast);}");
        sb.append("    if(d.saturation!==undefined){document.getElementById('range-saturation').value=d.saturation;document.getElementById('val-saturation').textContent=fmtVal(d.saturation);}");
        sb.append("    if(d.dnlp!==undefined){setDnlpUI(d.dnlp);}");
        sb.append("  }).catch(()=>{});");
        sb.append("});");
        sb.append("</script></body></html>");
        return sb.toString();
    }
}

