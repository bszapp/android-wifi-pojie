package wifi.pojie;

import android.content.Context;
import android.util.Log;

import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ConnectWIfiListener {
    Context context;
    int listenType;
    public Consumer<String> onEvent;

    private Runnable stopLogcatRunnable;
    private WifiStateReceiver wifiStateReceiver;
    private boolean isDestroyed = false;

    public ConnectWIfiListener(Context context, int listenType, int listenCmdMode) {
        this.context = context;
        this.listenType = listenType;

        if (listenType == 0) {
            //0:BroadcastReceiver

            wifiStateReceiver = new WifiStateReceiver(context, data -> {
                if (isDestroyed) return;
                if (this.onEvent != null) {
                    this.onEvent.accept(data);
                }
            });
        } else if (listenType == 1) {
            //1:logcat
            runCommandSync("logcat -c", listenCmdMode);
            stopLogcatRunnable = runCommand(
                    "logcat -s \"WifiService:D\" \"wpa_supplicant:D\" \"DhcpClient:D\"", listenCmdMode,
                    (line) -> {
                        if (isDestroyed) return;
                        Log.d("ConnectWifiListener","收到："+line);
                        if (Pattern.matches(".*WPA: 4-Way Handshake failed - pre-shared key may be incorrect.*", line)) {
                            Log.d("ConnectWifiListener", "连接失败");
                            if (this.onEvent != null) {
                                this.onEvent.accept("auth_fail");
                            }
                        } else if (Pattern.matches(".*Received packet: .* ACK: your new IP .*(?:[0-9]{1,3}\\.){3}[0-9]{1,3}.*", line)) {
                            Log.d("ConnectWifiListener", "连接成功");
                            if (this.onEvent != null) {
                                this.onEvent.accept("success");
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
