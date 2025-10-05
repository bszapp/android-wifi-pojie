package wifi.pojie;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public class WifiListAdapter extends ArrayAdapter<WifiSelectionDialog.WifiInfo> {

    public WifiListAdapter(@NonNull Context context, @NonNull List<WifiSelectionDialog.WifiInfo> objects) {
        super(context, 0, objects);
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View listItemView = convertView;
        if (listItemView == null) {
            listItemView = LayoutInflater.from(getContext()).inflate(
                    R.layout.list_item_wifi, parent, false);
        }

        WifiSelectionDialog.WifiInfo currentWifi = getItem(position);

        TextView wifiNameTextView = listItemView.findViewById(R.id.text_wifi_name);
        assert currentWifi != null;
        if (currentWifi.isSaved) {
            wifiNameTextView.setText("【已保存】" + currentWifi.name);
        } else {
            wifiNameTextView.setText(currentWifi.name);
        }


        TextView wifiInfoTextView = listItemView.findViewById(R.id.text_wifi_info);
        wifiInfoTextView.setText("信号强度: " + currentWifi.rssi + "dBm");

        Button selectNetworkButton = listItemView.findViewById(R.id.button_select_network);
        selectNetworkButton.setOnClickListener(v -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("wifi_name", currentWifi.name);
            setResultAndFinish(Activity.RESULT_OK, resultIntent);
        });

        return listItemView;
    }

    private void setResultAndFinish(int resultCode, Intent data) {
        if (getContext() instanceof Activity) {
            ((Activity) getContext()).setResult(resultCode, data);
            ((Activity) getContext()).finish();
        }
    }
}

