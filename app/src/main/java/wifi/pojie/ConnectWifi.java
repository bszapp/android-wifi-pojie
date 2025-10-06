package wifi.pojie;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ConnectWifi {
    private static final String TAG = "ConnectWifi";

    private final ConnectWIfiListener connectWIfiListener;
    public final WifiManager wifiManager;
    ConnectivityManager connectivityManager;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> timeoutTask;
    private volatile boolean isDestroyed = false;
    private long startTime;
    private final int connectType;
    private final int listenType;
    private final int manageMode;
    Map<String, ?> settings;

    private final Context context;

    public ConnectWifi(Context context, Map<String, ?> settings, Map<String, ?> config) {
        this.settings = settings;
        this.connectType = (int) this.settings.get(SettingsManager.KEY_CONNECT_MODE);
        this.manageMode = (int) this.settings.get(SettingsManager.KEY_MANAGE_MODE);
        this.listenType = (int) this.settings.get(SettingsManager.KEY_READ_MODE);
        this.context = context;

        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        if (connectType == 1)
            this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        this.connectWIfiListener = new ConnectWIfiListener(context, this.listenType, (int) this.settings.get(SettingsManager.KEY_READ_MODE_CMD),config);
    }

    private void clearListener() {
        connectWIfiListener.onEvent = null;
    }

    public void connect(String ssid, String password, int timeout, Consumer<String> onConnectionResult) {
        if (isDestroyed) {
            return;
        }
        clearListener();

        runConnect(ssid, password);

        startTime = System.currentTimeMillis();

        this.connectWIfiListener.onEvent = data -> {
            if (System.currentTimeMillis() - startTime < 3000 && Objects.equals(data, "auth_fail"))
                return;

            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            onConnectionResult.accept(data);
            clearListener();
        };

        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel(false);
        }

        timeoutTask = scheduler.schedule(() -> {
            if (isDestroyed) return;
            onConnectionResult.accept("timeout");
            clearListener();

        }, timeout, TimeUnit.MILLISECONDS);

    }

    public boolean wifiIsEnabled() {
        if (listenType == 0)
            return wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
        else if (listenType == 1)
            return !runCommand("cmd wifi status", (int) settings.get(SettingsManager.KEY_READ_MODE_CMD)).startsWith("Wifi is disabled");
        return false;
    }

    private void runConnect(String ssid, String password) {
        if (connectType == 0) {
            //0:API28
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = "\"" + ssid + "\"";
            wifiConfig.preSharedKey = "\"" + password + "\"";
            int netId = wifiManager.addNetwork(wifiConfig);

            if (netId != -1) {
                Log.d(TAG, "使用wifiManager添加网络" + netId);
                wifiManager.enableNetwork(netId, true);
            } else {
                throw new RuntimeException("connect fail\n忘记密码操作可能执行失败，请去设置手动点击忘记");
            }
        } else if (connectType == 1) {
            //1:API29
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder()
                        .setSsid(ssid);

                if (!password.isEmpty()) {
                    builder.setWpa2Passphrase(password);
                }

                WifiNetworkSpecifier networkSpecifier = builder.build();

                NetworkRequest networkRequest = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(networkSpecifier)
                        .build();

                ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {

                    @Override
                    public void onAvailable(@NonNull Network network) {
                        super.onAvailable(network);
                        connectivityManager.unregisterNetworkCallback(this);
                        if (connectWIfiListener.onEvent != null)
                            connectWIfiListener.onEvent.accept("success");
                    }
                };

                connectivityManager.requestNetwork(networkRequest, networkCallback);
            } else throw new RuntimeException("系统不支持，请使用API28模式");
        } else if (connectType == 2) {
            //2:命令行
            Log.d(TAG, "执行cmd连接wifi" + password);
            String connectResult = runCommand("cmd wifi connect-network \"" + ssid + "\" wpa2 \"" + password + "\"", 0);
            if (connectResult.contains("does not have access to connect-network wifi command")) {
                throw new RuntimeException(connectResult);
            }
        }

    }

    public static String runCommand(String command, int type) {
        if (type == 0) {
            return CommandRunner.executeCommandSync(command, true);
        } else if (type == 1) {
            return ShizukuHelper.executeCommandSync(command);
        }
        return "";
    }

    public void forgetWifiName(String ssid) {
        if (manageMode == 0) {
            Log.d(TAG,"使用wifiManager忘记网络"+ssid);
            if (wifiManager.removeNetwork(getWifiId(ssid))) wifiManager.saveConfiguration();
        } else if (manageMode == 2) {
            runCommand("cmd wifi forget-network " + getWifiId(ssid), (int) settings.get(SettingsManager.KEY_MANAGE_MODE_CMD));
        }
    }


    public int getWifiId(String ssid) {
        if (manageMode == 0) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw new RuntimeException("定位权限获取失败");
            }
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();

            for (WifiConfiguration config : configuredNetworks) {
                if (config.SSID != null && config.SSID.replace("\"", "").equals(ssid)) {
                    return config.networkId;
                }
            }
        } else if (manageMode == 1) {
            String result = runCommand("cmd wifi list-networks", (int) settings.get(SettingsManager.KEY_MANAGE_MODE_CMD));
            String[] lines = result.split("\n");

            for (int i = 1; i < lines.length; i++) {
                String name = lines[i].substring(13, 46).split(" ")[0];
                int id = Integer.parseInt(lines[i].substring(0, 13).split(" ")[0]);
                if (Objects.equals(name, ssid)) {
                    return id;
                }
            }
        }
        return -1;
    }

    public void destroy() {
        isDestroyed = true;
        connectWIfiListener.destroy();
        if (timeoutTask != null) timeoutTask.cancel(false);
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdown();
    }
}
