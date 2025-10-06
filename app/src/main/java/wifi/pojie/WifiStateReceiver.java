package wifi.pojie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiManager;
import android.os.Handler; // 导入 Handler
import android.os.Looper; // 导入 Looper
import android.util.Log;

import java.util.function.Consumer;

public class WifiStateReceiver extends BroadcastReceiver {
    private final Consumer<String> onResult;
    private final Context context;
    private final int handshakeTimeout;

    private final Handler handshakeTimeoutHandler;
    private final Runnable handshakeTimeoutRunnable;
    private boolean isDestroyed = false; // 添加一个销毁状态标志

    public WifiStateReceiver(Context context, int handshakeTimeout, Consumer<String> onResult) {
        this.onResult = onResult;
        this.context = context;
        this.handshakeTimeout = handshakeTimeout;

        this.handshakeTimeoutHandler = new Handler(Looper.getMainLooper());
        this.handshakeTimeoutRunnable = () -> {
            if (!isDestroyed) {
                addLog("握手超时");
                onResult.accept("handshake_timeout");
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        context.registerReceiver(this, intentFilter);
    }

    public void destroy() {
        if (!isDestroyed) {
            isDestroyed = true; // 先标记为已销毁
            try {
                context.unregisterReceiver(this);
            } catch (Exception e) {
                Log.w("WifiStateReceiver", "接收器注销时出错", e);
            }
            handshakeTimeoutHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (isDestroyed || intent == null) return;

        String action = intent.getAction();

        if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
            SupplicantState newState = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
            int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);

            String stateText;
            if (SupplicantState.DISCONNECTED.equals(newState) && error == WifiManager.ERROR_AUTHENTICATING) {
                stateText = "认证失败 (密码错误) ❌";
                handshakeTimeoutHandler.removeCallbacks(handshakeTimeoutRunnable);
                onResult.accept("auth_fail");
            } else {
                assert newState != null;
                stateText = newState.toString();
            }

            if (handshakeTimeout > 0 && stateText.equals("FOUR_WAY_HANDSHAKE")) {
                addLog("四次握手开始，设置超时: " + handshakeTimeout + "ms");
                handshakeTimeoutHandler.removeCallbacks(handshakeTimeoutRunnable);
                handshakeTimeoutHandler.postDelayed(handshakeTimeoutRunnable, handshakeTimeout);
            }

            addLog("Supplicant 状态: " + stateText);

        }

        // 2. 处理网络连接状态变化（最终结果）
        else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
            android.net.NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

            assert networkInfo != null;
            if (networkInfo.isConnected()) {
                addLog("网络连接状态: 已连接 (成功) ✅");
                handshakeTimeoutHandler.removeCallbacks(handshakeTimeoutRunnable);
                onResult.accept("success");
            } else if (networkInfo.getState() == android.net.NetworkInfo.State.DISCONNECTED) {
                addLog("网络连接状态: 已断开 ⛔");
            } else {
                addLog("网络连接状态: " + networkInfo.getState().toString() + "...");
            }
        }
    }

    private void addLog(String s) {
        Log.d("WifiStateReceiver", "Receiver:" + s);
    }
}
