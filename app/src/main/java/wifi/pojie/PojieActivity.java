package wifi.pojie;

import static wifi.pojie.MainActivity.getVersionName;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import View.SegmentedButtonGroup;

import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
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
    private FloatingActionButton fab;
    private SegmentedButtonGroup failSign;
    private LinearLayout failTimeoutGroup;
    private LinearLayout failCountGroup;
    private EditText failTimeoutInput;
    private EditText failCountInput;

    private volatile boolean isRunning = false;
    private String[] dictionary = new String[]{}; // 默认词典
    private WifiPojieService wifiPojieService;
    private boolean isServiceBound = false;
    private SettingsManager settingsManager;

    private static final String ACTION_PIP_EXECUTE = "wifi.pojie.ACTION_PIP_EXECUTE";

    private final BroadcastReceiver pipBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PIP_EXECUTE.equals(intent.getAction())) {
                if (executeButton != null) {
                    executeButton.performClick();
                }
            }
        }
    };

    private final BroadcastReceiver pipButtonClickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("wifi.pojie.ACTION_PIP_BUTTON_CLICK".equals(intent.getAction())) {
                if (executeButton != null) {
                    executeButton.performClick();
                }
            }
        }
    };

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

                requireActivity().runOnUiThread(() -> {
                    if (text != null) {
                        progressText.setText(text);
                    }
                    startLine.setText(String.valueOf(progress));
                    progressBar.setProgress(progress - 1);
                    progressBar.setMax(total);

                    ((MainActivity) requireActivity()).setPipProgress(progress - 1, total, text);
                });

            } else if (WifiPojieService.ACTION_FINISHED.equals(action)) {
                requireActivity().runOnUiThread(() -> progressText.append("【已暂停】"));
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

    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<Intent> wifiSelectionLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsManager = new SettingsManager(requireContext());
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        // 在 onAttach 中注册所有的 ActivityResultLauncher
        filePickerLauncher = registerForActivityResult(
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
                }
        );

        wifiSelectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == AppCompatActivity.RESULT_OK && result.getData() != null) {
                        String wifiName = result.getData().getStringExtra("wifi_name");
                        if (wifiName != null) {
                            wifiSsid.setText(wifiName);
                        }
                    }
                }
        );
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
        // 发送初始状态广播
        Intent initialStateIntent = new Intent("ACTION_STATE_CHANGED");
        initialStateIntent.putExtra("isRunning", isRunning);
        if (getActivity() != null) {
            getActivity().sendBroadcast(initialStateIntent);
            ((MainActivity) getActivity()).setPipRunning(false);
        }

        logSettings();
        setupClickListeners();

        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiPojieService.ACTION_LOG_OUTPUT);
        filter.addAction(WifiPojieService.ACTION_PROGRESS_UPDATE);
        filter.addAction(WifiPojieService.ACTION_FINISHED);

        if (getActivity() != null) {
            getActivity().registerReceiver(serviceBroadcastReceiver, filter);
            getActivity().registerReceiver(pipBroadcastReceiver, new IntentFilter(ACTION_PIP_EXECUTE));
            getActivity().registerReceiver(pipButtonClickReceiver, new IntentFilter("wifi.pojie.ACTION_PIP_BUTTON_CLICK"));

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
        fab = view.findViewById(R.id.fab);
        failSign = view.findViewById(R.id.fail_sign);
        failTimeoutGroup = view.findViewById(R.id.fail_sign_timeout);
        failCountGroup = view.findViewById(R.id.fail_sign_count);
        failTimeoutInput = view.findViewById(R.id.fail_sign_timeout_input);
        failCountInput = view.findViewById(R.id.fail_sign_count_input);

        String appVersion = "v" + getVersionName(requireContext());

        scrollView = (ScrollView) commandOutput.getParent().getParent();
        horizontalScrollView = (HorizontalScrollView) commandOutput.getParent();

        failSign.listener = selectedId -> {
            if (settingsManager.getInt(SettingsManager.KEY_READ_MODE) == 0 && selectedId == 2) {
                failSign.setSelectedId(0);
                new MaterialAlertDialogBuilder(requireActivity())
                        .setTitle("切换模式")
                        .setMessage("监听握手次数模式读取网络状态只能使用命令行模式，是否去切换模式？")
                        .setNegativeButton("取消", (dialog, which) -> {
                            dialog.dismiss();
                        })
                        .setPositiveButton("去设置", (dialog, which) -> {
                            dialog.dismiss();
                            startActivity(new Intent(getActivity(), WorkmodeActivity.class));
                        })
                        .show();
                return;
            }
            failTimeoutGroup.setVisibility(selectedId == 1 ? View.VISIBLE : View.GONE);
            failCountGroup.setVisibility(selectedId == 2 ? View.VISIBLE : View.GONE);
        };
        failSign.addOption("标准模式", 0);
        failSign.addOption("握手超时", 1);
        failSign.addOption("握手超次", 2);
        failSign.setSelectedId(0);


        addLog("wifi密码工具" + appVersion);
    }

    private void logSettings() {
        Map<String, ?> allEntries = settingsManager.getAllSettings();
        if (allEntries.isEmpty()) {
            return;
        }

        StringBuilder settingsLog = new StringBuilder("\n当前设置项:");
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

        ((MainActivity) requireActivity()).addPipLog(output);
    }

    private void clearLog() {
        commandOutput.setText("");
        ((MainActivity) requireActivity()).clearPipLog();
    }

    private void alert(String text,String title){
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(title)
                .setMessage(text)
                .setNegativeButton("知道了", (dialog, which) -> {
                    dialog.dismiss();
                })
                .show();
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
                String ssid=wifiSsid.getText().toString();
                if(ssid.isEmpty()){
                    alert("wifi名称为空，请先选择wifi","缺失参数");
                    return;
                }
                if(dictionary.length==0){
                    alert("字典为空，请先选择字典文件","缺失参数");
                    return;
                }

                clearLog();

                List<String> missingPermissions = ((MainActivity) requireActivity()).getMissingPermissionsSummary();
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
                    Intent intent = new Intent("ACTION_STATE_CHANGED");
                    intent.putExtra("isRunning", true);
                    getActivity().sendBroadcast(intent);

                    getActivity().runOnUiThread(() -> {
                        executeButton.setText("结束运行");
                        runningTip.setVisibility(View.VISIBLE);
                        progressText.setText("等待开始运行");

                        ((MainActivity) getActivity()).setPipRunning(true);

                        int color = ContextCompat.getColor(requireContext(), android.R.color.holo_red_light);
                        Drawable background = executeButton.getBackground().mutate();
                        ColorStateList colorStateList = ColorStateList.valueOf(color);
                        background.setTintList(colorStateList);
                        updatePictureInPictureParams();
                    });
                    if (settingsManager.getBoolean(SettingsManager.KEY_KEEP_SCREEN_ON)) {
                        if (getActivity() != null) {
                            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        }
                    }
                }

                if (settingsManager.getBoolean(SettingsManager.KEY_SHOW_NOTIFICATION))
                    createNotificationChannel();

                // 启动前台服务执行WiFi破解任务
                if (getActivity() != null) {
                    Map<String, Object> config = new HashMap<>();

                    config.put("ssid", ssid);
                    config.put("dictionary", dictionary);
                    config.put("timeoutMillis", Integer.parseInt(tryTime.getText().toString()));
                    config.put("startLine", Integer.parseInt(startLine.getText().toString()));
                    config.put("failSign", failSign.getSelectedId());
                    config.put("failSignTimeout",Integer.parseInt(failTimeoutInput.getText().toString()));
                    config.put("failSignCount",Integer.parseInt(failCountInput.getText().toString()));


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

        clearBtn.setOnClickListener(v -> clearLog());

        chooseWifiBtn.setOnClickListener(v -> {
            if (getActivity() != null) {
                Intent intent = new Intent(getActivity(), WifiSelectionDialog.class);
                wifiSelectionLauncher.launch(intent);
            }
        });

        fab.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (getActivity() != null) {
                    updatePictureInPictureParams();
                    PictureInPictureParams.Builder paramsBuilder = new PictureInPictureParams.Builder();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        paramsBuilder.setAspectRatio(new Rational(3, 2));
                    }
                    getActivity().enterPictureInPictureMode(paramsBuilder.build());
                }
            }
        });
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
                getActivity().unregisterReceiver(pipBroadcastReceiver);
                getActivity().unregisterReceiver(pipButtonClickReceiver);
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

        if (settingsManager.getBoolean(SettingsManager.KEY_KEEP_SCREEN_ON)) {
            if (getActivity() != null) {
                getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }

        if (getActivity() != null) {
            Intent intent = new Intent("ACTION_STATE_CHANGED");
            intent.putExtra("isRunning", false);
            getActivity().sendBroadcast(intent);

            getActivity().runOnUiThread(() -> {
                executeButton.setText("开始运行");

                ((MainActivity) getActivity()).setPipProgressText(String.valueOf(progressText.getText()));
                ((MainActivity) getActivity()).setPipRunning(false);

                runningTip.setVisibility(View.GONE);

                int color = ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark);
                Drawable background = executeButton.getBackground().mutate();
                ColorStateList colorStateList = ColorStateList.valueOf(color);
                background.setTintList(colorStateList);
                updatePictureInPictureParams();
            });
        }
    }

    private void updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && getActivity() != null) {
            List<RemoteAction> actions = new ArrayList<>();
            Icon icon = Icon.createWithResource(getContext(), isRunning ? R.drawable.ic_pause : R.drawable.ic_play);
            String title = isRunning ? "停止" : "开始";
            Intent intent = new Intent(ACTION_PIP_EXECUTE);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(getContext(), 0, intent, PendingIntent.FLAG_IMMUTABLE);
            actions.add(new RemoteAction(icon, title, title, pendingIntent));

            PictureInPictureParams.Builder paramsBuilder = new PictureInPictureParams.Builder();
            paramsBuilder.setActions(actions);
            getActivity().setPictureInPictureParams(paramsBuilder.build());
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
