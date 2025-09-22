package wifi.pojie;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class WifiSelectionDialog extends AppCompatActivity {
    private static final String TAG = "WifiSelectionDialog";
    private ListView wifiListView;
    private ArrayAdapter<String> adapter;
    private List<String> wifiList;
    private Handler handler;
    private int currentCount = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_selection_dialog);

        wifiListView = findViewById(R.id.wifi_list_view);
        wifiList = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, wifiList);
        wifiListView.setAdapter(adapter);

        // 初始化Handler，每秒添加一个选项
        handler = new Handler();
        addOption();

        // 设置点击事件
        wifiListView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedWifi = wifiList.get(position);
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selected_wifi", selectedWifi);
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        });
    }

    private void addOption() {
        if (currentCount <= 10) {
            wifiList.add("WiFi" + currentCount);
            adapter.notifyDataSetChanged();
            currentCount++;
            handler.postDelayed(this::addOption, 1000); // 每秒添加一个
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}