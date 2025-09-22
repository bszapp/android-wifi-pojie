package wifi.pojie;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSION = 1001;
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "wifi_pojie_channel";
    private static final int REQUEST_CODE_NOTIFICATION_PERMISSION = 1002;

    private TextView commandOutput;
    private Button executeButton;
    private TextView progressText;
    private EditText wifiSsid;
    private EditText tryTime;
    private Button dictionarySelect;
    private EditText startLine;
    private LinearLayout rootLayout;
    private LinearLayout layout1;
    private LinearLayout layout2;
    private ProgressBar progressBar;
    private ProgressBar runningTip;
    private CheckBox autoscroll;
    private ScrollView scrollView;
    private HorizontalScrollView horizontalScrollView;

    private volatile boolean isRunning = false;
    private String[] dictionary = new String[]{}; // 默认词典
    private WifiPojieService wifiPojieService;
    private boolean isServiceBound = false;

    private String appVersion;

    // 广播接收器，用于接收来自服务的消息
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
                
                runOnUiThread(() -> {
                    if (text != null) {
                        progressText.setText(text);
                    }
                    startLine.setText(String.valueOf(progress));
                    progressBar.setProgress(progress - 1);
                    progressBar.setMax(total);
                });
            } else if (WifiPojieService.ACTION_FINISHED.equals(action)) {
                stopRunningCommand();
                // 任务结束后清除常驻通知
                hideProgressNotification();
            }
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            runOnUiThread(() -> {
                commandOutput.setText("");
                addLog("Shizuku权限被拒绝\n" +
                        "本应用需要使用adb权限控制wifi连接，否则无法正常运行");
            });
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::onBinderReceivedCallback;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::onBinderDeadCallback;

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
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            dictionary = readDictionaryFromFile(uri);
                            Toast.makeText(this, "加载完毕，共" + dictionary.length + "项", Toast.LENGTH_SHORT).show();
                            
                            // 获取文件名并设置到按钮上
                            String fileName = getFileNameFromUri(uri);
                            if (fileName != null) {
                                runOnUiThread(() -> dictionarySelect.setText(fileName));
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "读取字典文件失败", e);
                            Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });

    private final ActivityResultLauncher<Intent> wifiSelectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String wifiName = result.getData().getStringExtra("wifi_name");
                    if (wifiName != null) {
                        wifiSsid.setText(wifiName);
                    }
                }
            });

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @SuppressLint({"SetTextI18n", "UnspecifiedRegisterReceiverFlag"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        createNotificationChannel();
        requestNotificationPermission();
        initViews();
        setupClickListeners();

        // 注册广播接收器 - 根据API级别决定是否传入receiverFlags
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiPojieService.ACTION_LOG_OUTPUT);
        filter.addAction(WifiPojieService.ACTION_PROGRESS_UPDATE);
        filter.addAction(WifiPojieService.ACTION_FINISHED);

        registerReceiver(serviceBroadcastReceiver, filter, Context.RECEIVER_EXPORTED);

        // 绑定服务
        Intent intent = new Intent(this, WifiPojieService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 根据当前屏幕方向调整布局
        adjustLayoutForOrientation(getResources().getConfiguration().orientation);
    }

    /**
     * 创建通知渠道（仅在Android O及以上版本需要）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CHANNEL_ID,
                    "WiFi破解进度通知",
                    android.app.NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示WiFi密码破解工具运行进度的通知");
            
            android.app.NotificationManager notificationManager = getSystemService(android.app.NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 隐藏进度通知
     */
    private void hideProgressNotification() {
        android.app.NotificationManager notificationManager = 
            (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }
    
    /**
     * 请求通知权限（仅在Android 13及以上版本需要）
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 
                                   REQUEST_CODE_NOTIFICATION_PERMISSION);
            }
        }
    }

    /**
     * 初始化所有视图组件
     */
    @SuppressLint("SetTextI18n")
    private void initViews() {
        rootLayout = findViewById(R.id.root_layout);
        layout1 = findViewById(R.id.layout1);
        layout2 = findViewById(R.id.layout2);
        commandOutput = findViewById(R.id.commandOutput);
        executeButton = findViewById(R.id.startbtn);
        wifiSsid = findViewById(R.id.wifi_ssid);
        tryTime = findViewById(R.id.try_time);
        dictionarySelect = findViewById(R.id.dictionary_select);
        startLine = findViewById(R.id.start_line);
        progressText = findViewById(R.id.progress_text);
        progressBar = findViewById(R.id.progressBar);
        runningTip = findViewById(R.id.running_tip);
        autoscroll = findViewById(R.id.autoscroll);
        appVersion = "v"+getVersionName(this);

        ((TextView)findViewById(R.id.bottom_tip)).setText("仅供研究原理使用，严禁用于非法用途");
        ((TextView)findViewById(R.id.version_text)).setText(appVersion);

        // 获取滚动视图组件
        scrollView = (ScrollView) commandOutput.getParent().getParent();
        horizontalScrollView = (HorizontalScrollView) commandOutput.getParent();

        if (ShizukuHelper.isShizukuAvailable()) {
            ShizukuHelper.addPermissionListener(permissionResultListener);
            ShizukuHelper.addBinderListener(binderReceivedListener, binderDeadListener);

            if (ShizukuHelper.checkPermission()) {
                Log.d(TAG, "Requesting Shizuku permission");
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION);
            }
            addLog("wifi密码工具"+appVersion+"\n" +
                    "使用方式：\n" +
                    "1.选择wifi密码字典（txt文件，一行一个密码）\n" +
                    "2.选择wifi（选择wifi页面可能是以前的扫描信息，请先下拉刷新）\n" +
                    "3.点击开始运行\n" +
                    "由于技术限制（作者懒得加输入框），只能使用WPA2协议连接wifi（绝大多数路由器支持）\n" +
                    "如果需要在后台运行，请授予通知权限，并设置省电策略为无限制\n" +
                    "（对于各品牌手机的额外防杀后台设置，请自行查阅资料）");
        } else {
            addLog("启动状态检查发生错误：\n" +
                    "Shizuku服务未启动\n" +
                    "请检查是否安装Shizuku并且服务已启动\n" +
                    "Shizuku安装教程请自行查阅");
        }
    }
private void addLog(String output){
        if(commandOutput.getText().length()==0)commandOutput.append(output);
        else commandOutput.append("\n"+output);

        if (autoscroll.isChecked()) {
            Log.d(TAG, "自动滚动已启用");
            scrollView.postDelayed(() -> {
                int contentHeight = scrollView.getChildAt(0).getMeasuredHeight();
                scrollView.smoothScrollTo(0, contentHeight);
                horizontalScrollView.smoothScrollTo(0, 0);
            }, 1);
        }else{
            Log.d(TAG,"自动滚动禁用");
        }
    }

    public static String getVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * 根据屏幕方向调整布局
     */
    private void adjustLayoutForOrientation(int orientation) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏时设置为水平布局
            rootLayout.setOrientation(LinearLayout.HORIZONTAL);

            // 设置layout1参数
            LinearLayout.LayoutParams params1 = (LinearLayout.LayoutParams) layout1.getLayoutParams();
            params1.width = 0;
            params1.height = LinearLayout.LayoutParams.MATCH_PARENT;
            params1.weight = 1.0f;
            // 添加右边距使两个布局分开
            params1.setMargins(0, 0, (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics()), 0);
            layout1.setLayoutParams(params1);

            // 设置layout2参数
            LinearLayout.LayoutParams params2 = (LinearLayout.LayoutParams) layout2.getLayoutParams();
            params2.width = 0;
            params2.height = LinearLayout.LayoutParams.MATCH_PARENT;
            params2.weight = 1.0f;
            params2.setMargins(0, 0, 0, 0);
            layout2.setLayoutParams(params2);
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // 竖屏时设置为垂直布局
            rootLayout.setOrientation(LinearLayout.VERTICAL);

            // 设置layout1参数
            LinearLayout.LayoutParams params1 = (LinearLayout.LayoutParams) layout1.getLayoutParams();
            params1.width = LinearLayout.LayoutParams.MATCH_PARENT;
            params1.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            params1.weight = 0;
            params1.setMargins(0, 0, 0, 0);
            layout1.setLayoutParams(params1);

            // 设置layout2参数
            LinearLayout.LayoutParams params2 = (LinearLayout.LayoutParams) layout2.getLayoutParams();
            params2.width = LinearLayout.LayoutParams.MATCH_PARENT;
            params2.height = 0;
            params2.weight = 1.0f;
            params2.setMargins(0, 0, 0, 0);
            layout2.setLayoutParams(params2);
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
                if (!ShizukuHelper.isShizukuAvailable()) {
                    Toast.makeText(this, "Shizuku服务未启动", Toast.LENGTH_LONG).show();
                    return;
                }

                if (ShizukuHelper.checkPermission()) {
                    Shizuku.requestPermission(REQUEST_CODE_PERMISSION);
                } else {

                    commandOutput.setText("");
                    isRunning = true;
                    runOnUiThread(() -> {
                        executeButton.setText("结束运行");
                        runningTip.setVisibility(View.VISIBLE);

                        int color = ContextCompat.getColor(this, android.R.color.holo_red_light);
                        Drawable background = executeButton.getBackground().mutate();
                        ColorStateList colorStateList = ColorStateList.valueOf(color);
                        background.setTintList(colorStateList);
                    });

                    // 启动前台服务执行WiFi破解任务
                    Intent serviceIntent = new Intent(this, WifiPojieService.class);
                    serviceIntent.putExtra("ssid", wifiSsid.getText().toString());
                    serviceIntent.putExtra("dictionary", dictionary);
                    serviceIntent.putExtra("startLine", Integer.parseInt(startLine.getText().toString()));
                    serviceIntent.putExtra("timeoutMillis", Integer.parseInt(tryTime.getText().toString()));
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
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

        Button copyBtn = findViewById(R.id.copybtn);
        Button clearBtn = findViewById(R.id.clearbtn);
        Button chooseWifiBtn = findViewById(R.id.button6);

        copyBtn.setOnClickListener(v -> copyTextToClipboard());

        clearBtn.setOnClickListener(v -> commandOutput.setText(""));

        chooseWifiBtn.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, WifiSelectionDialog.class);
            wifiSelectionLauncher.launch(intent);
        });
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Log.d(TAG, "屏幕方向改变: " + (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ? "横屏" : "竖屏"));

        // 根据屏幕方向调整布局
        adjustLayoutForOrientation(newConfig.orientation);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 移除监听器
        if (ShizukuHelper.isShizukuAvailable()) {
            ShizukuHelper.removePermissionListener(permissionResultListener);
            ShizukuHelper.removeBinderListener(binderReceivedListener, binderDeadListener);
        }

        stopRunningCommand();
        
        // 解绑服务
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        
        // 注销广播接收器
        try {
            unregisterReceiver(serviceBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            // 接收器未注册，忽略异常
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
        InputStream inputStream = getContentResolver().openInputStream(uri);
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
            if (uri.getScheme() != null && uri.getScheme().equals("content")) {
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
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

    private void onBinderReceivedCallback() { }

    private void onBinderDeadCallback() { }

    private void stopRunningCommand() {
        if (wifiPojieService != null) {
            wifiPojieService.stopWifiPojie();
        }
        isRunning = false;
        runOnUiThread(() -> {
            executeButton.setText("开始运行");
            progressText.setText("等待开始运行");
            runningTip.setVisibility(View.GONE);

            int color = ContextCompat.getColor(this, android.R.color.holo_blue_dark);
            Drawable background = executeButton.getBackground().mutate();
            ColorStateList colorStateList = ColorStateList.valueOf(color);
            background.setTintList(colorStateList);
        });
    }

    /**
     * 将commandOutput中的所有文本复制到剪贴板
     */
    private void copyTextToClipboard() {
        String textToCopy = commandOutput.getText().toString();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("命令输出", textToCopy);
        clipboard.setPrimaryClip(clip);

        // 显示提示信息
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

}