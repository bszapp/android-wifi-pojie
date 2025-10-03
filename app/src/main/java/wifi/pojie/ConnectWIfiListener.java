package wifi.pojie;

import android.content.Context;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ConnectWIfiListener {
    Context context;
    int listenType;
    public Consumer<String> onEvent;

    private Runnable stopLogcatRunnable;
    private WifiStateReceiver wifiStateReceiver;
    private boolean isDestroyed = false;

    public ConnectWIfiListener(Context context_, int listen_type){
        context=context_;
        listenType=listen_type;

        if (listenType == 0) {
            //0:BroadcastReceiver

            wifiStateReceiver = new WifiStateReceiver(context,data->{
                if (isDestroyed) return;
                if (this.onEvent != null) {
                    this.onEvent.accept(data);
                }
            });
        } else if (listenType == 1) {
            //1:logcat

            ShizukuHelper.executeCommandSync("logcat -c");
            stopLogcatRunnable = ShizukuHelper.executeCommand(
                    "logcat -s \"WifiService:D\" \"wpa_supplicant:D\" \"DhcpClient:D\"",
                    (line) -> {
                        if (isDestroyed) return;

                        if (Pattern.matches(".*WPA: 4-Way Handshake failed - pre-shared key may be incorrect.*", line)) {
                            if (this.onEvent != null) {
                                this.onEvent.accept("auth_fail");
                            }
                            return;
                        }

                        if (Pattern.matches(".*Received packet: .* ACK: your new IP .*(?:[0-9]{1,3}\\.){3}[0-9]{1,3}.*", line)) {
                            if (this.onEvent != null) {
                                this.onEvent.accept("success");
                            }
                        }
                    },
                    null
            );
        }
    }

    public void destroy(){
        if(listenType == 0){
            wifiStateReceiver.destroy();
        }else if(listenType == 1){
            stopLogcatRunnable.run();
        }
        isDestroyed = true;
    }

}
