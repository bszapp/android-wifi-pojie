package wifi.pojie;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends Fragment {

    private ListView historyListView;
    private HistoryListAdapter historyAdapter;
    private List<HistoryItem> historyItemList;
    private SwipeRefreshLayout swipeRefreshLayout;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        handleStatusBarInset(view);

        historyListView = view.findViewById(R.id.history_listview);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);

        loadHistoryData();

        historyAdapter = new HistoryListAdapter(requireContext(), historyItemList);
        historyListView.setAdapter(historyAdapter);

        swipeRefreshLayout.setOnRefreshListener(() -> {
            loadHistoryData();
            historyAdapter.notifyDataSetChanged();
            swipeRefreshLayout.setRefreshing(false);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh data when the fragment becomes visible
        if (historyAdapter != null) {
            loadHistoryData();
            historyAdapter.notifyDataSetChanged();
        }
    }

    private void loadHistoryData() {
        if (historyItemList == null) {
            historyItemList = new ArrayList<>();
        } else {
            historyItemList.clear();
        }
        SharedPreferences sharedPreferences = requireActivity().getSharedPreferences("wifi_attempts", Context.MODE_PRIVATE);
        String json = sharedPreferences.getString("attempts", "[]");

        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jsonObject = jsonArray.getJSONObject(i);
                String ssid = jsonObject.getString("ssid");
                int attemptCount = jsonObject.getInt("attemptCount");
                String password = jsonObject.optString("correctPassword", "N/A");
                historyItemList.add(new HistoryItem(ssid, attemptCount, password));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveHistoryData() {
        SharedPreferences sharedPreferences = requireActivity().getSharedPreferences("wifi_attempts", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        JSONArray jsonArray = new JSONArray();
        for (HistoryItem item : historyItemList) {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("ssid", item.getSsid());
                jsonObject.put("attemptCount", item.getAttemptCount());
                jsonObject.put("correctPassword", item.getCorrectPassword());
                jsonArray.put(jsonObject);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        editor.putString("attempts", jsonArray.toString());
        editor.apply();
    }


    private void handleStatusBarInset(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            int statusBarHeight = windowInsets.getSystemWindowInsetTop();
            v.setPadding(v.getPaddingLeft(), statusBarHeight, v.getPaddingRight(), v.getPaddingBottom());
            return windowInsets;
        });
    }

    // Data class for history item
    private static class HistoryItem {
        private final String ssid;
        private final int attemptCount;
        private final String correctPassword;

        public HistoryItem(String ssid, int attemptCount, String correctPassword) {
            this.ssid = ssid;
            this.attemptCount = attemptCount;
            this.correctPassword = correctPassword;
        }

        public String getSsid() {
            return ssid;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public String getCorrectPassword() {
            return correctPassword;
        }
    }

    // Adapter for ListView
    private class HistoryListAdapter extends BaseAdapter {

        private final Context context;
        private final List<HistoryItem> historyItems;
        private final LayoutInflater inflater;

        public HistoryListAdapter(Context context, List<HistoryItem> historyItems) {
            this.context = context;
            this.historyItems = historyItems;
            this.inflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return historyItems.size();
        }

        @Override
        public Object getItem(int position) {
            return historyItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.list_item_history, parent, false);
                holder = new ViewHolder();
                holder.ssidTextView = convertView.findViewById(R.id.history_name);
                holder.detailsTextView = convertView.findViewById(R.id.history_details);
                holder.deleteButton = convertView.findViewById(R.id.delete_button);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            HistoryItem item = historyItems.get(position);
            holder.ssidTextView.setText(item.getSsid());

            String password = item.getCorrectPassword();
            if (password == null || password.equals("N/A") || password.isEmpty()) {
                password = "-";
            }
            holder.detailsTextView.setText("次数: " + item.getAttemptCount() + "  密码: " + password);

            holder.deleteButton.setOnClickListener(v -> {
                historyItems.remove(position);
                notifyDataSetChanged();
                saveHistoryData();
            });

            return convertView;
        }

        private class ViewHolder {
            TextView ssidTextView;
            TextView detailsTextView;
            ImageButton deleteButton;
        }
    }
}
