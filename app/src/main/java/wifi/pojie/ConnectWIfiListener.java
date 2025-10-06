package wifi.pojie;

import android.content.Context;
import android.util.Log;

import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ConnectWIfiListener {
    private final int listenType;
    public Consumer<String> onEvent;
    private final int failSign;
    private final int failSignTimeout;
    private final int failSignCount;

    private int handshakeCount = 0;

    private android.os.Handler handshakeTimeoutHandler;
    private Runnable handshakeTimeoutRunnable;

    private Runnable stopLogcatRunnable;
    private WifiStateReceiver wifiStateReceiver;
    private boolean isDestroyed = false;

    public ConnectWIfiListener(Context context, int listenType, int listenCmdMode, Map<String, ?> config) {
        this.listenType = listenType;

        this.failSign = (int) config.get("failSign");
        this.failSignTimeout = (int) config.get("failSignTimeout");
        this.failSignCount = (int) config.get("failSignCount");

        if (listenType == 0) {
            //0:BroadcastReceiver

            wifiStateReceiver = new WifiStateReceiver(context, failSign == 1 ? failSignTimeout : -1, data -> {
                if (isDestroyed) return;
                if (this.onEvent != null) {
                    this.onEvent.accept(data);
                }
            });
        } else if (listenType == 1) {
            //1:logcat
            handshakeTimeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            handshakeTimeoutRunnable = () -> {
                if (isDestroyed) return;
                Log.d("ConnectWifiListener", "握手超时");
                if (failSign == 1 && this.onEvent != null) {
                    this.onEvent.accept("handshake_timeout");
                }
            };

            runCommandSync("logcat -c", listenCmdMode);
            stopLogcatRunnable = runCommand(
                    "logcat -s \"WifiService:D\" \"wpa_supplicant:D\" \"DhcpClient:D\"", listenCmdMode,
                    (line) -> {
                        if (isDestroyed) return;
                        Log.d("ConnectWifiListener", "收到：" + line);

                        // --- 连接失败事件 ---
                        if (Pattern.matches(".*WPA: 4-Way Handshake failed - pre-shared key may be incorrect.*", line)) {
                            Log.d("ConnectWifiListener", "连接失败");
                            if (handshakeTimeoutHandler != null) {
                                handshakeTimeoutHandler.removeCallbacks(handshakeTimeoutRunnable);
                            }
                            handshakeCount = 0;

                            // failSign == 0 时，这是它的主要回调事件
                            if (failSign == 0 && this.onEvent != null) {
                                this.onEvent.accept("auth_fail");
                            }
                        }

                        // --- 连接成功事件 ---
                        else if (Pattern.matches(".*Received packet: .* ACK: your new IP .*(?:[0-9]{1,3}\\.){3}[0-9]{1,3}.*", line)) {
                            Log.d("ConnectWifiListener", "连接成功");
                            if (handshakeTimeoutHandler != null) {
                                handshakeTimeoutHandler.removeCallbacks(handshakeTimeoutRunnable);
                            }
                            handshakeCount = 0;

                            // 成功事件对所有模式都有效
                            if (this.onEvent != null) {
                                this.onEvent.accept("success");
                            }
                        }

                        // --- 握手事件 ---
                        else if (Pattern.matches(".*?:\\s+WPA:\\s+Sending\\s+EAPOL-Key\\s+2/4.*", line)) {
                            Log.d("ConnectWifiListener", "握手中, 次数: " + (handshakeCount + 1));

                            // 模式1：处理超时
                            if (failSign == 1 && handshakeCount == 0) {
                                // 仅在模式1且首次握手时，设置超时
                                if (handshakeTimeoutHandler != null) {
                                    Log.d("ConnectWifiListener", "设置握手超时任务: " + failSignTimeout + "ms");
                                    handshakeTimeoutHandler.postDelayed(handshakeTimeoutRunnable, failSignTimeout);
                                }
                            }
                            // 模式2：处理次数超限 (在计数增加后判断)
                            if (failSign == 2 && handshakeCount == failSignCount) {
                                Log.d("ConnectWifiListener", "握手次数超过最大值: " + failSignCount);
                                if (this.onEvent != null) {
                                    this.onEvent.accept("handshake_maximum");
                                }
                            }

                            handshakeCount++;

                        }

                        //握手成功
                        else if (Pattern.matches(".*?:\\s+WPA:\\s+Sending\\s+EAPOL-Key\\s+3/4.*", line) || Pattern.matches(".*?:\\s+WPA:\\s+Sending\\s+EAPOL-Key\\s+4/4.*", line)) {
                            if (handshakeTimeoutHandler != null) {
                                handshakeTimeoutHandler.removeCallbacks(handshakeTimeoutRunnable);
                            }
                        }
                    },
                    null
            );
        }
    }

    public static void runCommandSync(String command, int type) {
        if (type == 0) {
            CommandRunner.executeCommandSync(command, true);
        } else if (type == 1) {
            ShizukuHelper.executeCommandSync(command);
        }
    }

    public static Runnable runCommand(String command, int type,
                                      Consumer<String> onOutputReceived,
                                      Consumer<String> onCommandFinished) {
        if (type == 0) {
            return CommandRunner.executeCommand(command, true, onOutputReceived, onCommandFinished);
        } else if (type == 1) {
            return ShizukuHelper.executeCommand(command, onOutputReceived, onCommandFinished);
        }
        return null;
    }


    public void destroy() {
        if (listenType == 0) {
            wifiStateReceiver.destroy();
        } else if (listenType == 1) {
            stopLogcatRunnable.run();
        }
        isDestroyed = true;
    }

}
