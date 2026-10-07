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
import java.net.Socket;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private TextView tvDiagResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Ensure background service is running
        Intent serviceIntent = new Intent(this, ZoomService.class);
        startService(serviceIntent);

        TextView tvIp = (TextView) findViewById(R.id.tv_ip);
        tvDiagResult = (TextView) findViewById(R.id.tv_diag_result);

        String ip = getIpAddress();
        if (ip != null) {
            tvIp.setText("http://" + ip + ":8989");
        } else {
            tvIp.setText("http://192.168.123.98:8989");
        }

        // 125% Zoom Button
        findViewById(R.id.btn_zoom_125).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = AdbClient.setZoom(125);
                        if (res.startsWith("OK")) {
                            setKodiViewMode(2); // Zoom mode to unlock letterbox
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "已切换为 125% 变焦 (节点回读确认生效)", Toast.LENGTH_SHORT).show();
                                    appendStatus(">>> [变焦成功] 硬件节点已成功写入 125%！\n⚠️ 提醒：变焦仅对硬解播放中的视频生效，桌面不缩放。");
                                }
                            });
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "变焦失败: " + res, Toast.LENGTH_LONG).show();
                                    appendStatus(">>> [变焦写入失败] " + res);
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        // Smart Stretch Button
        findViewById(R.id.btn_smart_stretch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = AdbClient.setScreenMode(4);
                        if (res.startsWith("OK")) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "已切换为智能非线性拉伸 (防人物变扁)", Toast.LENGTH_SHORT).show();
                                    appendStatus(">>> [拉伸成功] 屏幕模式已设为 4 (智能非线性拉伸)。");
                                }
                            });
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "设置失败: " + res, Toast.LENGTH_LONG).show();
                                    appendStatus(">>> [模式设置失败] " + res);
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        // Reset Button
        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = AdbClient.resetAll();
                        setKodiViewMode(0); // Reset Kodi to normal
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, "已恢复 100% 原始比例", Toast.LENGTH_SHORT).show();
                                appendStatus(">>> [比例复位] 变焦与裁切已重置回 100% 原始大小。");
                            }
                        });
                    }
                }).start();
            }
        });

        // Diagnose Button
        findViewById(R.id.btn_diagnose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDoctor();
            }
        });

        // Auto-run doctor on startup
        runDoctor();
    }

    private void runDoctor() {
        if (tvDiagResult != null) {
            tvDiagResult.setText("正在执行深度环境体检 (探测Root通道、底层硬件节点、Seccomp补丁、Kodi端口)... 请稍候...");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = AdbClient.runDiagnosticText();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tvDiagResult != null) {
                            tvDiagResult.setText(report);
                        }
                        Toast.makeText(MainActivity.this, "环境体检完成，请查看报告", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void appendStatus(final String msg) {
        if (tvDiagResult != null) {
            String cur = tvDiagResult.getText().toString();
            tvDiagResult.setText(msg + "\n\n" + cur);
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
