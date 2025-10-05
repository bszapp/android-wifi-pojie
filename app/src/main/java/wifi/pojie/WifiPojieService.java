package wifi.pojie;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class WifiPojieService extends Service {
    private static final String TAG = "WifiPojieService";
    private static final int NOTIFICATION_ID = 2;
    private static final int FINISHED_NOTIFICATION_ID = 3;
    private static final String CHANNEL_ID = "wifi_pojie_channel";
    public static final String ACTION_LOG_OUTPUT = "wifi.pojie.LOG_OUTPUT";
    public static final String ACTION_PROGRESS_UPDATE = "wifi.pojie.PROGRESS_UPDATE";
    public static final String ACTION_FINISHED = "wifi.pojie.FINISHED";
    public static final String EXTRA_LOG_MESSAGE = "log_message";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_PROGRESS_TEXT = "progress_text";

    private final IBinder binder = new LocalBinder();
    private WifiPojie wifiPojie;
    private boolean isRunning = false;

    private boolean showNotification = false;

    public class LocalBinder extends Binder {
        WifiPojieService getService() {
            return WifiPojieService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "WifiPojieService created");
    }

    private Map<String, Object> parseConfig(String jsonConfig) {
        if (jsonConfig == null) {
            return new HashMap<>();
        }
        Gson gson = new Gson();
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<HashMap<String, Object>>() {
        }.getType();
        Map<String, Object> config = gson.fromJson(jsonConfig, type);
        if (config.containsKey("dictionary")) {
            Object dictionaryObj = config.get("dictionary");
            if (dictionaryObj instanceof java.util.ArrayList) {
                java.util.ArrayList<?> dictionaryList = (java.util.ArrayList<?>) dictionaryObj;
                String[] dictionaryArray = dictionaryList.toArray(new String[0]);
                config.put("dictionary", dictionaryArray);
            }
        }
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (entry.getValue() instanceof Double) {
                entry.setValue(((Double) entry.getValue()).intValue());
            }
        }
        return config;
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "WifiPojieService started");

        if (showNotification) showForegroundNotification();

        if (intent != null) {
            String jsonConfig = intent.getStringExtra("config");
            String jsonSettings = intent.getStringExtra("settings");

            Map<String, Object> config = parseConfig(jsonConfig);
            Map<String, Object> settings = parseConfig(jsonSettings);

            startWifiPojie(config, settings);
        }

        return START_NOT_STICKY;
    }


    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startWifiPojie(Map<String, Object> config, Map<String, Object> settings) {
        if (isRunning) {
            Log.w(TAG, "WifiPojie is already running");
            return;
        }
        showNotification = (boolean) settings.get(SettingsManager.KEY_SHOW_NOTIFICATION);

        isRunning = true;
        try {
            wifiPojie = new WifiPojie(
                    this,
                    config,
                    settings,
                    this::onLogOutput,
                    this::onProgressUpdate,
                    this::onFinished
            );
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Failed to start WifiPojie", e);
            isRunning = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private void onLogOutput(String output) {
        Log.d(TAG, "WifiPojie output: " + output);
        // 通过广播将日志信息传递给Activity
        Intent intent = new Intent(ACTION_LOG_OUTPUT);
        intent.putExtra(EXTRA_LOG_MESSAGE, output);
        sendBroadcast(intent);
    }

    private void onProgressUpdate(Integer progress, Integer total, String text) {
        Log.d(TAG, "WifiPojie progress: " + text);
        if (showNotification) showForegroundNotification("WiFi破解运行中", text, progress, total);

        // 通过广播将进度信息传递给Activity
        Intent intent = new Intent(ACTION_PROGRESS_UPDATE);
        intent.putExtra(EXTRA_PROGRESS, progress);
        intent.putExtra(EXTRA_TOTAL, total);
        intent.putExtra(EXTRA_PROGRESS_TEXT, text);
        sendBroadcast(intent);
    }

    private void onFinished() {
        Log.d(TAG, "WifiPojie finished");
        isRunning = false;

        // 发送任务完成的通知
        if (showNotification) showFinishedNotification();

        // 通过广播通知Activity任务已完成
        Intent intent = new Intent(ACTION_FINISHED);
        sendBroadcast(intent);

        stopForeground(true);
        stopSelf();
    }

    private void showForegroundNotification() {
        showForegroundNotification("WiFi破解工具启动中", "正在初始化...", 0, 0);
    }

    private void showForegroundNotification(String title, String content, int progress, int max) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Intent notificationIntent = new Intent(this, PojieActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(this, 0, notificationIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        builder.setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pendingIntent);

        if (max > 0) {
            builder.setProgress(max, progress, false);
        }

        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void showFinishedNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // 创建点击通知时跳转到主页面的意图
        Intent notificationIntent = new Intent(this, PojieActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(this, 0, notificationIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

        builder.setContentTitle("任务已结束")
                .setContentText("WiFi密码工具运行结束")
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .setContentIntent(pendingIntent);

        Notification notification = builder.build();
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(FINISHED_NOTIFICATION_ID, notification);
    }

    public void stopWifiPojie() {
        if (wifiPojie != null) {
            wifiPojie.destroy();
            wifiPojie = null;
        }
        isRunning = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "WifiPojieService destroyed");
        if (wifiPojie != null) {
            wifiPojie.destroy();
        }
        isRunning = false;
    }

}