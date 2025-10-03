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
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.util.List;

public class TestApiFragment extends Fragment {
    private static final String TAG = "TestApiFragment";

    private EditText wifiNameEditText;
    private EditText wifiPasswordEditText;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.activity_test_api, container, false);

        wifiNameEditText = view.findViewById(R.id.editTextText);
        wifiPasswordEditText = view.findViewById(R.id.editTextText2);
        Button connectButtonNew = view.findViewById(R.id.connect_new);
        Button connectButtonOld = view.findViewById(R.id.connect_old);

        connectButtonNew.setOnClickListener(v -> {
            String ssid = wifiNameEditText.getText().toString().trim();
            String password = wifiPasswordEditText.getText().toString().trim();

            connectToWifi(ssid, password,false);
        });
        connectButtonOld.setOnClickListener(v -> {
            String ssid = wifiNameEditText.getText().toString().trim();
            String password = wifiPasswordEditText.getText().toString().trim();

            connectToWifi(ssid, password,true);
        });

        return view;
    }

    private void connectToWifi(String ssid, String password,boolean isOld) {
        if(isOld){
            connectToWifiLegacy(ssid, password);
        }else{
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectToWifiModern(ssid, password);
            }else{
                Toast.makeText(getContext(), "设备版本不支持", Toast.LENGTH_SHORT).show();
            }
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void connectToWifiModern(String ssid, String password) {
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

        ConnectivityManager connectivityManager = (ConnectivityManager) requireContext().getSystemService(Context.CONNECTIVITY_SERVICE);

        ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                Log.d(TAG, "网络连接成功");
                requireActivity().runOnUiThread(() -> 
                    Toast.makeText(getContext(), "WiFi连接成功", Toast.LENGTH_SHORT).show()
                );
                // 连接成功后取消网络请求
                connectivityManager.unregisterNetworkCallback(this);
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                Log.d(TAG, "网络连接失败");
                requireActivity().runOnUiThread(() -> 
                    Toast.makeText(getContext(), "WiFi连接失败", Toast.LENGTH_SHORT).show()
                );
            }
        };

        connectivityManager.requestNetwork(networkRequest, networkCallback);
    }

    private void connectToWifiLegacy(String ssid, String password) {
        // 1. 获取 WifiManager 服务
        WifiManager wifiManager = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null) {
            Toast.makeText(getContext(), "无法获取 WifiManager 服务", Toast.LENGTH_SHORT).show();
            return;
        }

        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = "\""+ssid+"\""; // SSID of the network
        wifiConfig.preSharedKey = "\""+password+"\""; // Password for WPA/WPA2 networks
        removeWifiConfig(wifiManager, ssid);
        int netId = wifiManager.addNetwork(wifiConfig);

        if (netId != -1) {
            wifiManager.enableNetwork(netId, true);
            Toast.makeText(getContext(), "正在连接WiFi...", Toast.LENGTH_SHORT).show();
            //wifiManager.setWifiEnabled(true);
        } else {
            Log.e("WifiManager", "Failed to add network");
        }
    }


    public void removeWifiConfig(WifiManager wifiManager, String targetSsid) {
        ActivityCompat.requestPermissions(requireActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(getContext(), "请授予定位权限", Toast.LENGTH_SHORT).show();
            return;
        }
        List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();

        if (configuredNetworks == null) {
            return;
        }

        for (WifiConfiguration config : configuredNetworks) {
            if (config.SSID != null && config.SSID.replace("\"", "").equals(targetSsid)) {
                int netId = config.networkId;
                boolean success = wifiManager.removeNetwork(netId);
                if (success) {
                    wifiManager.saveConfiguration();
                    return;
                }
            }
        }
    }

}