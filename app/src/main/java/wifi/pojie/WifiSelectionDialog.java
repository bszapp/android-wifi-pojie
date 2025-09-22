package wifi.pojie;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class WifiSelectionDialog extends AppCompatActivity {
    private static final String TAG = "WifiSelectionDialog";
    private ArrayAdapter<String> adapter;
    private List<String> wifiList;
    private List<WifiInfo> wifiInfoList;
    private Handler handler;
    private Runnable addWifiRunnable;
    private SwipeRefreshLayout swipeRefreshLayout;

    private static class WifiInfo {
        String name;
        int rssi;

        WifiInfo(String name, int rssi) {
            this.name = name;
            this.rssi = rssi;
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
            params.height = getResources().getDisplayMetrics().heightPixels * 2 / 3;
            window.setAttributes(params);
        }

        initViews();
        setupWifiList();
    }

    private void initViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        ListView wifiListView = findViewById(R.id.wifi_list);
        wifiList = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, wifiList);
        wifiListView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::refreshWifiList);

        wifiListView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {}

            @Override
            public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                swipeRefreshLayout.setEnabled(firstVisibleItem == 0);
            }
        });

        wifiListView.setOnItemClickListener((parent, view, position, id) -> {
            WifiInfo selectedWifi = wifiInfoList.get(position);
            Intent resultIntent = new Intent();
            resultIntent.putExtra("wifi_name", selectedWifi.name);
            setResult(RESULT_OK, resultIntent);
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

    private void loadWifiListData() {
        try {
            ShizukuHelper.executeCommandSync("cmd wifi start-scan");
            String result = ShizukuHelper.executeCommandSync("cmd wifi list-scan-results");
            String[] lines = result.split("\n");

            // 初始化列表
            wifiInfoList = new ArrayList<>();

            for (int i = 1; i < lines.length; i++) {
                try {
                    String name = lines[i].substring(64, 99).split(" ")[0];
                    int rssi = Integer.parseInt(lines[i].substring(38, 53).split(" ")[0]);
                    if (name.isEmpty()) continue;
                    wifiInfoList.add(new WifiInfo(name, rssi));
                } catch (RuntimeException ignored) {}
            }

            // 按RSSI从大到小排序
            wifiInfoList.sort((wifi1, wifi2) -> Integer.compare(wifi2.rssi, wifi1.rssi));

            // 将排序后的WiFi信息转换为显示字符串列表
            wifiList.clear();
            for (WifiInfo wifiInfo : wifiInfoList) {
                wifiList.add(wifiInfo.name + " (" + wifiInfo.rssi + "dBm)");
            }

            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                swipeRefreshLayout.setRefreshing(false);
            });
        } catch (ExecutionException | InterruptedException e) {
            runOnUiThread(() -> {
                swipeRefreshLayout.setRefreshing(false);
            });
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && addWifiRunnable != null) {
            handler.removeCallbacks(addWifiRunnable);
        }
    }
}