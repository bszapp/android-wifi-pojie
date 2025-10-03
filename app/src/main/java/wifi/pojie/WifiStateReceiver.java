package wifi.pojie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiManager;

import java.util.function.Consumer;

public class WifiStateReceiver extends BroadcastReceiver {
    Consumer<String> onResult;
    Context context;
    public WifiStateReceiver(Context context_, Consumer<String> f) {
        onResult=f;
        context=context_;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        context.registerReceiver(this, intentFilter);
    }

    public void destroy(){
        context.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();

        if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
            SupplicantState newState = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
            int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);

            String stateText;
            if (SupplicantState.DISCONNECTED.equals(newState) && error == WifiManager.ERROR_AUTHENTICATING) {
                stateText = "认证失败 (密码错误) ❌";
                onResult.accept("auth_fail");
            } else {
                assert newState != null;
                stateText = newState.toString();
            }

            addLog("Supplicant 状态: " + stateText);

        }

        // 2. 处理网络连接状态变化（最终结果）
        else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
            android.net.NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

            assert networkInfo != null;
            if (networkInfo.isConnected()) {
                addLog("网络连接状态: 已连接 (成功) ✅");
                onResult.accept("success");
            } else if (networkInfo.getState() == android.net.NetworkInfo.State.DISCONNECTED) {
                addLog("网络连接状态: 已断开 ⛔");
            } else {
                addLog("网络连接状态: " + networkInfo.getState().toString() + "...");
            }
        }
    }

    private void addLog(String s) {
        //Log.d(TAG, "Receiver:"+s);
    }
}