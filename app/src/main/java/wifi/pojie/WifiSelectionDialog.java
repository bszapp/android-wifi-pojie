package wifi.pojie;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class WifiSelectionDialog extends AppCompatActivity {
    private static final String TAG = "WifiSelectionDialog";
    private WifiListAdapter adapter;
    private List<WifiInfo> wifiInfoList;
    private Handler handler;
    private Runnable addWifiRunnable;
    private SwipeRefreshLayout swipeRefreshLayout;

    private SettingsManager settingsManager;
    private WifiManager wifiManager;
    private WifiScanReceiver wifiScanReceiver;

    String result;

    private static final int REQUEST_CODE_FINE_LOCATION = 1001;

    public static class WifiInfo {
        String name;
        int rssi;
        boolean isSaved;

        public WifiInfo(String name, int rssi, boolean isSaved) {
            this.name = name;
            this.rssi = rssi;
            this.isSaved = isSaved;
        }
    }

    private class WifiScanReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (success) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    finish();
                    return;
                }
                List<ScanResult> scanResults = wifiManager.getScanResults();
                wifiInfoList.clear();
                List<String> savedNetworks = getSavedNetworks();
                for (ScanResult scanResult : scanResults) {
                    String name = scanResult.SSID;
                    int rssi = scanResult.level;
                    boolean isSaved = savedNetworks.contains(name);
                    if (!name.isEmpty()) {
                        wifiInfoList.add(new WifiInfo(name, rssi, isSaved));
                    }
                }
                wifiInfoList.sort((wifi1, wifi2) -> Integer.compare(wifi2.rssi, wifi1.rssi));
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    swipeRefreshLayout.setRefreshing(false);
                });
            } else {
                runOnUiThread(() -> swipeRefreshLayout.setRefreshing(false));
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_selection_dialog);

        setTitle("选择WiFi网络");
        Window window = getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getResources().getDisplayMetrics().widthPixels;
            params.height = getResources().getDisplayMetrics().heightPixels * 3 / 4;
            window.setAttributes(params);
        }
        settingsManager = new SettingsManager(this);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiScanReceiver = new WifiScanReceiver();
        registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        initViews();
        setupWifiList();
    }

    private void initViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        ListView wifiListView = findViewById(R.id.wifi_list);
        wifiInfoList = new ArrayList<>();
        adapter = new WifiListAdapter(this, wifiInfoList);
        wifiListView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::refreshWifiList);

        wifiListView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                swipeRefreshLayout.setEnabled(firstVisibleItem == 0);
            }
        });

        Button cancelButton = findViewById(R.id.button_cancel);
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

    }

    private void setupWifiList() {
        swipeRefreshLayout.setRefreshing(true);
        handler = new Handler();
        addWifiRunnable = this::loadWifiListData;
        handler.post(addWifiRunnable);
    }

    private void refreshWifiList() {
        handler = new Handler();
        addWifiRunnable = this::loadWifiListData;
        handler.post(addWifiRunnable);
    }

    private List<String> getSavedNetworks() {
        List<String> savedNetworks = new ArrayList<>();
        int manageMode = settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE);
        if (manageMode == 0) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // 权限未授予，无法获取已保存网络，返回空列表
                return savedNetworks;
            }
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            if (configuredNetworks != null) {
                for (WifiConfiguration config : configuredNetworks) {
                    if (config.SSID != null) {
                        savedNetworks.add(config.SSID.replace("\"", ""));
                    }
                }
            }
        } else if (manageMode == 1) {
            String result = runCommand("cmd wifi list-networks", settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD));
            String[] lines = result.split("\n");
            for (int i = 1; i < lines.length; i++) {
                try {
                    String name = lines[i].substring(13, 46).split(" ")[0];
                    savedNetworks.add(name);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing saved network: " + lines[i], e);
                }
            }
        }
        return savedNetworks;
    }

    private void loadWifiListData() {
        int scanMode = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE);
        if (scanMode == 0) {
            if (wifiManager != null) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            REQUEST_CODE_FINE_LOCATION);
                } else {
                    wifiManager.startScan();
                }
            }
        } else if (scanMode == 1) {
            int runType = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD);
            runCommand("cmd wifi start-scan", runType);
            result = runCommand("cmd wifi list-scan-results", runType);
            String[] lines = result.split("\n");

            // 清空并重新填充列表
            wifiInfoList.clear();
            List<String> savedNetworks = getSavedNetworks();

            for (int i = 1; i < lines.length; i++) {
                try {
                    String fullSubstring = lines[i].substring(64, 99);
                    int bracketIndex = fullSubstring.indexOf('[');
                    String name;
                    if (bracketIndex != -1) {
                        name = fullSubstring.substring(0, bracketIndex).trim();
                    } else {
                        name = fullSubstring.split(" ")[0].trim();
                    }
                    Log.d(TAG, lines[i]);
                    Log.d(TAG, "name: " + name + ", rssi: " + lines[i].substring(38, 53).trim());
                    int rssi = Integer.parseInt(lines[i].substring(38, 53).trim());
                    boolean isSaved = savedNetworks.contains(name);
                    if (name.isEmpty()) continue;
                    wifiInfoList.add(new WifiInfo(name, rssi, isSaved));
                } catch (RuntimeException ignored) {
                }
            }
            // 按RSSI从大到小排序
            wifiInfoList.sort((wifi1, wifi2) -> Integer.compare(wifi2.rssi, wifi1.rssi));

            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);
            });
        } else {
            runOnUiThread(() -> {
                swipeRefreshLayout.setRefreshing(false);
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_FINE_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，重新开始扫描
                if (wifiManager != null) {
                    wifiManager.startScan();
                }
            } else {
                // 权限被拒绝，停止刷新动画
                runOnUiThread(() -> {
                    swipeRefreshLayout.setRefreshing(false);
                });
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

    public int getWifiId(String ssid) {
        int manageMode = settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE);
        if (manageMode == 0) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw new RuntimeException("定位权限获取失败");
            }
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();

            for (WifiConfiguration config : configuredNetworks) {
                if (config.SSID != null && config.SSID.replace("\"", "").equals(ssid)) {
                    return config.networkId;
                }
            }
        } else if (manageMode == 1) {
            String result = runCommand("cmd wifi list-networks", settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD));
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && addWifiRunnable != null) {
            handler.removeCallbacks(addWifiRunnable);
        }
        unregisterReceiver(wifiScanReceiver);
    }
}

