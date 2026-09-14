package com.phicomm.t1zoom;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ensure background service is running
        Intent serviceIntent = new Intent(this, ZoomService.class);
        startService(serviceIntent);

        TextView tvIp = (TextView) findViewById(R.id.tv_ip);
        String ip = getIpAddress();
        if (ip != null) {
            tvIp.setText("http://" + ip + ":8989");
        } else {
            tvIp.setText("http://192.168.123.98:8989");
        }

        findViewById(R.id.btn_zoom_125).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        AdbClient.setZoom(125);
                        setKodiViewMode(2); // Zoom mode to unlock letterbox
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "已切换为 125% 变焦 (铺满幕布)", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });

        findViewById(R.id.btn_smart_stretch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        AdbClient.setScreenMode(4);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "已切换为智能非线性拉伸", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });

        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        AdbClient.resetAll();
                        setKodiViewMode(0); // Reset Kodi to normal
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "已恢复 100% 原始比例", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private void setKodiViewMode(final int viewMode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.Socket kodiSock = null;
                try {
                    kodiSock = new java.net.Socket("127.0.0.1", 9090);
                    kodiSock.setSoTimeout(2000);
                    String vmName = (viewMode == 0) ? "normal" : "zoom";
                    String jsonRpc = "{\"jsonrpc\":\"2.0\",\"method\":\"Player.SetViewMode\"," +
                            "\"params\":{\"viewmode\":\"" + vmName + "\"},\"id\":1}\n";
                    kodiSock.getOutputStream().write(jsonRpc.getBytes("UTF-8"));
                    kodiSock.getOutputStream().flush();
                } catch (Exception ignored) {
                } finally {
                    if (kodiSock != null) {
                        try { kodiSock.close(); } catch (Exception ignored) {}
                    }
                }
            }
        }).start();
    }

    private String getIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
