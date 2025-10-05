package wifi.pojie;

import static wifi.pojie.MainActivity.getVersionName;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PojieActivity extends Fragment {
    private static final String TAG = "PojieActivity";
    private static final int REQUEST_CODE_PERMISSION = 1001;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "wifi_pojie_channel";

    private TextView commandOutput;
    private Button executeButton;
    private TextView progressText;
    private EditText wifiSsid;
    private EditText tryTime;
    private Button dictionarySelect;
    private EditText startLine;
    private ProgressBar progressBar;
    private ProgressBar runningTip;
    private CheckBox autoscroll;
    private ScrollView scrollView;
    private HorizontalScrollView horizontalScrollView;

    private volatile boolean isRunning = false;
    private String[] dictionary = new String[]{}; // 默认词典
    private WifiPojieService wifiPojieService;
    private boolean isServiceBound = false;
    private SettingsManager settingsManager;
    private PermissionManager pm;

    private final BroadcastReceiver serviceBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiPojieService.ACTION_LOG_OUTPUT.equals(action)) {
                String logMessage = intent.getStringExtra(WifiPojieService.EXTRA_LOG_MESSAGE);
                if (logMessage != null) {
                    addLog(logMessage);
                }
            } else if (WifiPojieService.ACTION_PROGRESS_UPDATE.equals(action)) {
                int progress = intent.getIntExtra(WifiPojieService.EXTRA_PROGRESS, 0);
                int total = intent.getIntExtra(WifiPojieService.EXTRA_TOTAL, 0);
                String text = intent.getStringExtra(WifiPojieService.EXTRA_PROGRESS_TEXT);

                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (text != null) {
                            progressText.setText(text);
                        }
                        startLine.setText(String.valueOf(progress));
                        progressBar.setProgress(progress - 1);
                        progressBar.setMax(total);
                    });
                }
            } else if (WifiPojieService.ACTION_FINISHED.equals(action)) {
                stopRunningCommand();
                hideProgressNotification();
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            WifiPojieService.LocalBinder binder = (WifiPojieService.LocalBinder) service;
            wifiPojieService = binder.getService();
            isServiceBound = true;
            Log.d(TAG, "Connected to WifiPojieService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
            Log.d(TAG, "Disconnected from WifiPojieService");
        }
    };

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                    Toast t = Toast.makeText(getActivity(), "正在加载", Toast.LENGTH_SHORT);
                    t.show();
                    dictionarySelect.postDelayed(() -> {
                        Uri uri = result.getData().getData();
                        if (uri != null && getActivity() != null) {
                            try {
                                dictionary = readDictionaryFromFile(uri);
                                t.cancel();
                                Toast.makeText(getActivity(), "加载完毕，共" + dictionary.length + "项", Toast.LENGTH_SHORT).show();

                                // 获取文件名并设置到按钮上
                                String fileName = getFileNameFromUri(uri);
                                if (fileName != null) {
                                    getActivity().runOnUiThread(() -> dictionarySelect.setText(fileName));
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "读取字典文件失败", e);
                                if (getActivity() != null) {
                                    Toast.makeText(getActivity(), "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }
                        }
                    }, 0);
                }
            });

    private final ActivityResultLauncher<Intent> wifiSelectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                    String wifiName = result.getData().getStringExtra("wifi_name");
                    if (wifiName != null) {
                        wifiSsid.setText(wifiName);
                    }
                }
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(requireContext());
        pm = ((MainActivity) requireActivity()).pm;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_pojie, container, false);
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 处理状态栏边距
        handleStatusBarInset(view);

        initViews(view);
        logSettings();
        setupClickListeners();

        // 注册广播接收器 - 根据API级别决定是否传入receiverFlags
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiPojieService.ACTION_LOG_OUTPUT);
        filter.addAction(WifiPojieService.ACTION_PROGRESS_UPDATE);
        filter.addAction(WifiPojieService.ACTION_FINISHED);

        if (getActivity() != null) {
            getActivity().registerReceiver(serviceBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);

            // 绑定服务
            Intent intent = new Intent(getActivity(), WifiPojieService.class);
            getActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    /**
     * 处理状态栏边距，确保内容不会被状态栏遮挡
     */
    private void handleStatusBarInset(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            int statusBarHeight = windowInsets.getSystemWindowInsetTop();

            // 只应用状态栏高度作为顶部边距
            v.setPadding(v.getPaddingLeft(), statusBarHeight, v.getPaddingRight(), v.getPaddingBottom());

            return windowInsets;
        });
    }

    /**
     * 创建通知渠道（仅在Android O及以上版本需要）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && getActivity() != null) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID,
                    "进度通知",
                    android.app.NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于显示任务进度");

            android.app.NotificationManager notificationManager = getActivity().getSystemService(android.app.NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 隐藏进度通知
     */
    private void hideProgressNotification() {
        if (getActivity() != null) {
            android.app.NotificationManager notificationManager =
                    (android.app.NotificationManager) getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    /**
     * 初始化所有视图组件
     */
    @SuppressLint("SetTextI18n")
    private void initViews(View view) {
        commandOutput = view.findViewById(R.id.commandOutput);
        executeButton = view.findViewById(R.id.startbtn);
        wifiSsid = view.findViewById(R.id.wifi_ssid);
        tryTime = view.findViewById(R.id.try_time);
        dictionarySelect = view.findViewById(R.id.dictionary_select);
        startLine = view.findViewById(R.id.start_line);
        progressText = view.findViewById(R.id.progress_text);
        progressBar = view.findViewById(R.id.progressBar);
        runningTip = view.findViewById(R.id.running_tip);
        autoscroll = view.findViewById(R.id.autoscroll);
        String appVersion = "v" + getVersionName(requireContext());

        scrollView = (ScrollView) commandOutput.getParent().getParent();
        horizontalScrollView = (HorizontalScrollView) commandOutput.getParent();

        addLog("wifi密码工具" + appVersion);
    }

    private void logSettings() {
        Map<String, ?> allEntries = settingsManager.getAllSettings();
        if (allEntries.isEmpty()) {
            return;
        }

        StringBuilder settingsLog = new StringBuilder("当前设置项:");
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            settingsLog.append("\n").append(entry.getKey()).append(":").append(entry.getValue().toString());
        }
        addLog(settingsLog.toString());
    }

    private void addLog(String output) {
        if (commandOutput.getText().length() == 0) commandOutput.append(output);
        else commandOutput.append("\n" + output);

        if (autoscroll.isChecked()) {
            scrollView.postDelayed(() -> {
                int contentHeight = scrollView.getChildAt(0).getMeasuredHeight();
                scrollView.smoothScrollTo(0, contentHeight);
                horizontalScrollView.smoothScrollTo(0, 0);
            }, 0);
        }
    }


    /**
     * 设置所有按钮的点击监听器
     */
    @SuppressLint("SetTextI18n")
    private void setupClickListeners() {
        executeButton.setOnClickListener(v -> {
            if (isRunning) {
                stopRunningCommand();
            } else {
                commandOutput.setText("");

                List<String> missingPermissions=pm.getMissingPermissionsSummary(true);
                if (!missingPermissions.isEmpty()) {
                    StringBuilder output = new StringBuilder("缺失必要权限：\n");
                    for (String permission : missingPermissions) {
                        output.append("• ").append(permission).append("\n");
                    }
                    addLog(output.toString());
                    return;
                }

                isRunning = true;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        executeButton.setText("结束运行");
                        runningTip.setVisibility(View.VISIBLE);

                        int color = ContextCompat.getColor(requireContext(), android.R.color.holo_red_light);
                        Drawable background = executeButton.getBackground().mutate();
                        ColorStateList colorStateList = ColorStateList.valueOf(color);
                        background.setTintList(colorStateList);
                    });
                }

                if (settingsManager.getBoolean(SettingsManager.KEY_SHOW_NOTIFICATION))
                    createNotificationChannel();

                // 启动前台服务执行WiFi破解任务
                if (getActivity() != null) {
                    Map<String, Object> config = new HashMap<>();

                    config.put("ssid", wifiSsid.getText().toString());
                    config.put("dictionary", dictionary);
                    config.put("timeoutMillis", Integer.parseInt(tryTime.getText().toString()));
                    config.put("startLine", Integer.parseInt(startLine.getText().toString()));


                    Gson gson = new Gson();
                    String jsonConfig = gson.toJson(config);
                    String jsonSettings = gson.toJson(settingsManager.getAllSettings());

                    Intent serviceIntent = new Intent(getActivity(), WifiPojieService.class);
                    serviceIntent.putExtra("config", jsonConfig);
                    serviceIntent.putExtra("settings", jsonSettings);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getActivity().startForegroundService(serviceIntent);
                    } else {
                        getActivity().startService(serviceIntent);
                    }
                }
            }
        });

        dictionarySelect.setOnClickListener(v -> {
            Log.d(TAG, "Selecting dictionary");
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("text/plain");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            filePickerLauncher.launch(Intent.createChooser(intent, "选择字典文件"));
        });

        assert getView() != null;
        Button copyBtn = getView().findViewById(R.id.copybtn);
        Button clearBtn = getView().findViewById(R.id.clearbtn);
        Button chooseWifiBtn = getView().findViewById(R.id.button6);

        copyBtn.setOnClickListener(v -> copyTextToClipboard());

        clearBtn.setOnClickListener(v -> commandOutput.setText(""));

        chooseWifiBtn.setOnClickListener(v -> {
            if (getActivity() != null) {
                Intent intent = new Intent(getActivity(), WifiSelectionDialog.class);
                wifiSelectionLauncher.launch(intent);
            }
        });
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 由于已禁用横屏，此方法不再需要处理屏幕方向变化
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopRunningCommand();

        if (isServiceBound && getActivity() != null) {
            getActivity().unbindService(serviceConnection);
            isServiceBound = false;
        }

        try {
            if (getActivity() != null) {
                getActivity().unregisterReceiver(serviceBroadcastReceiver);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    /**
     * 从Uri读取文本文件并按行分割成字符串数组
     *
     * @param uri 文件Uri
     * @return 按行分割的字符串数组
     * @throws IOException 读取文件时发生错误
     */
    private String[] readDictionaryFromFile(Uri uri) throws IOException {
        List<String> lines = new ArrayList<>();
        if (getActivity() != null) {
            InputStream inputStream = getActivity().getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
                reader.close();
                inputStream.close();
            }
        }
        return lines.toArray(new String[0]);
    }

    /**
     * 从Uri获取文件名
     *
     * @param uri 文件Uri
     * @return 文件名
     */
    private String getFileNameFromUri(Uri uri) {
        try {
            String fileName = null;
            if (uri.getScheme() != null && uri.getScheme().equals("content") && getActivity() != null) {
                android.database.Cursor cursor = getActivity().getContentResolver().query(uri, null, null, null, null);
                if (cursor != null) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    cursor.moveToFirst();
                    fileName = cursor.getString(nameIndex);
                    cursor.close();
                }
            }

            if (fileName == null) {
                fileName = uri.getLastPathSegment();
            }

            return fileName;
        } catch (Exception e) {
            Log.e(TAG, "获取文件名失败", e);
            return null;
        }
    }

    private void stopRunningCommand() {
        if (wifiPojieService != null) {
            wifiPojieService.stopWifiPojie();
        }
        isRunning = false;
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                executeButton.setText("开始运行");
                progressText.setText("等待开始运行");
                runningTip.setVisibility(View.GONE);

                int color = ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark);
                Drawable background = executeButton.getBackground().mutate();
                ColorStateList colorStateList = ColorStateList.valueOf(color);
                background.setTintList(colorStateList);
            });
        }
    }

    /**
     * 将commandOutput中的所有文本复制到剪贴板
     */
    private void copyTextToClipboard() {
        String textToCopy = commandOutput.getText().toString();
        if (getActivity() != null) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("命令输出", textToCopy);
            clipboard.setPrimaryClip(clip);

            // 显示提示信息
            Toast.makeText(getActivity(), "已复制", Toast.LENGTH_SHORT).show();
        }
    }
}