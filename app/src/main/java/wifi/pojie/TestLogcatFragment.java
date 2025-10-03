package wifi.pojie;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class TestLogcatFragment extends Fragment {
    private static final String TAG = "WifiStateMonitor"; // 修改TAG

    private TextView commandOutput;
    private Button executeButton; // 重命名或修改其功能为启动/停止监听

    // --- 新增：Wi-Fi 状态广播接收器 ---
    private WifiStateReceiver wifiStateReceiver;

    private void addLog(String output){
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // 限制日志长度以避免内存问题，或滚动到底部
            if(commandOutput.getText().length()==0)commandOutput.append(output);
            else commandOutput.append("\n"+output);
        });
    }

    // 状态不再需要 'running' 和 'stopFunc'，因为我们只监听
    // 为了适应原有按钮逻辑，我们将其改为控制监听的注册与注销

    private boolean isMonitoring = false; // 用于跟踪是否正在监听

    private void setIsMonitoring(boolean v) {
        if (getActivity() == null || executeButton == null) return;
        executeButton.setText(v ? "停止监听" : "开始监听");
        isMonitoring = v;
        int color = ContextCompat.getColor(getActivity(), v ? android.R.color.holo_red_light : android.R.color.holo_blue_dark);
        Drawable background = executeButton.getBackground().mutate();
        ColorStateList colorStateList = ColorStateList.valueOf(color);
        background.setTintList(colorStateList);
        if(v){
            commandOutput.setText("--- 开始监听 Wi-Fi 连接状态 ---\n");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_test_logcat, container, false);

        commandOutput = view.findViewById(R.id.commandOutput);
        executeButton = view.findViewById(R.id.startbtn);

        // 实例化 Wi-Fi 状态接收器
        wifiStateReceiver = new WifiStateReceiver();

        executeButton.setOnClickListener(v -> {
            if (!isMonitoring) {
                registerWifiReceiver();
                setIsMonitoring(true);
            } else {
                unregisterWifiReceiver();
                setIsMonitoring(false);
                addLog("--- 停止监听 Wi-Fi 连接状态 ---");
            }
        });

        setIsMonitoring(false); // 初始状态设置为未监听
        return view;
    }

    // --- 新增：广播接收器注册与注销方法 ---

    private void registerWifiReceiver() {
        Context context = getContext();
        if (context == null || isMonitoring) return;

        IntentFilter filter = new IntentFilter();
        // 核心：监听 Wi-Fi 客户端状态变化
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        // 监听网络连接变化（可选，但通常有助于判断最终连接结果）
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

        context.registerReceiver(wifiStateReceiver, filter);
    }

    private void unregisterWifiReceiver() {
        Context context = getContext();
        if (context == null || !isMonitoring) return;

        try {
            context.unregisterReceiver(wifiStateReceiver);
        } catch (IllegalArgumentException e) {
            // 避免未注册时注销导致的崩溃
        }
    }

    // --- 新增：内部类 WifiStateReceiver 来处理广播事件 ---
    class WifiStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();

            // 1. 处理 Supplicant 状态变化（认证/连接过程中的关键步骤）
            if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                SupplicantState newState = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
                int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);

                String stateText;
                if (SupplicantState.DISCONNECTED.equals(newState) && error == WifiManager.ERROR_AUTHENTICATING) {
                    stateText = "认证失败 (密码错误) ❌";
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
                } else if (networkInfo.getState() == android.net.NetworkInfo.State.DISCONNECTED) {
                    addLog("网络连接状态: 已断开 ⛔");
                } else {
                    addLog("网络连接状态: " + networkInfo.getState().toString() + "...");
                }
            }
        }
    }
}