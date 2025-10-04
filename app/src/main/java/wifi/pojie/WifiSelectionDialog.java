package wifi.pojie;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;

public class WifiSelectionDialog extends AppCompatActivity {
    private static final String TAG = "WifiSelectionDialog";
    private WifiListAdapter adapter;
    private List<WifiInfo> wifiInfoList;
    private Handler handler;
    private Runnable addWifiRunnable;
    private SwipeRefreshLayout swipeRefreshLayout;

    String result;

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
            params.height = getResources().getDisplayMetrics().heightPixels * 3 / 4;
            window.setAttributes(params);
        }

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

    private void loadWifiListData() {
        ShizukuHelper.executeCommandSync("cmd wifi start-scan");
        result = ShizukuHelper.executeCommandSync("cmd wifi list-scan-results");
        String[] lines = result.split("\n");

        // 清空并重新填充列表
        wifiInfoList.clear();

        for (int i = 1; i < lines.length; i++) {
            try {
                String name = lines[i].substring(64, 99).split(" ")[0];
                Log.d(TAG, "\"" + lines[i] + "\"");
                Log.d(TAG, "name: " + name + ", rssi: " + lines[i].substring(38, 53));
                int rssi = Integer.parseInt(lines[i].substring(38, 53).split(" ")[0]);
                if (name.isEmpty()) continue;
                wifiInfoList.add(new WifiInfo(name, rssi));
            } catch (RuntimeException ignored) {
            }
        }

        // 按RSSI从大到小排序
        wifiInfoList.sort((wifi1, wifi2) -> Integer.compare(wifi2.rssi, wifi1.rssi));

        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            swipeRefreshLayout.setRefreshing(false);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && addWifiRunnable != null) {
            handler.removeCallbacks(addWifiRunnable);
        }
    }

    private class WifiListAdapter extends ArrayAdapter<WifiInfo> {

        public WifiListAdapter(@NonNull Context context, @NonNull List<WifiInfo> objects) {
            super(context, 0, objects);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View listItemView = convertView;
            if (listItemView == null) {
                listItemView = LayoutInflater.from(getContext()).inflate(
                        R.layout.list_item_wifi, parent, false);
            }

            WifiInfo currentWifi = getItem(position);

            TextView wifiNameTextView = listItemView.findViewById(R.id.text_wifi_name);
            wifiNameTextView.setText(currentWifi.name);

            TextView wifiInfoTextView = listItemView.findViewById(R.id.text_wifi_info);
            wifiInfoTextView.setText("信号强度: " + currentWifi.rssi + "dBm"); // 可以添加更多信息

            Button selectNetworkButton = listItemView.findViewById(R.id.button_select_network);
            selectNetworkButton.setOnClickListener(v -> {
                Intent resultIntent = new Intent();
                resultIntent.putExtra("wifi_name", currentWifi.name);
                setResult(RESULT_OK, resultIntent);
                finish();
            });

            return listItemView;
        }
    }
}

