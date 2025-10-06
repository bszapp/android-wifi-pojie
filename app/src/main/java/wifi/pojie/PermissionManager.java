package wifi.pojie;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

public class PermissionManager {

    private final AppCompatActivity activity;

    // Callbacks
    private Consumer<Boolean> locationPermissionCallback;
    private Runnable locationServicesEnabledCallback;
    private Runnable locationServicesDeniedCallback;
    private Consumer<Boolean> notificationPermissionCallback;
    Consumer<Boolean> batteryOptimizationSetCallback;

    // ActivityResultLaunchers
    private final ActivityResultLauncher<String[]> locationPermissionLauncher;
    private final ActivityResultLauncher<Intent> locationServicesLauncher;
    private final ActivityResultLauncher<String> notificationPermissionLauncher;
    private final ActivityResultLauncher<Intent> batteryOptimizationLauncher;
    private final ActivityResultLauncher<Intent> appNotificationSettingsLauncher;

    private Shizuku.OnRequestPermissionResultListener shizukuPermissionResultListener;
    private final SettingsManager settingsManager;

    private boolean allowRoot = false;

    public PermissionManager(AppCompatActivity activity) {
        this.activity = activity;

        settingsManager = new SettingsManager(activity);
        // 初始化位置权限请求启动器
        locationPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    if (locationPermissionCallback != null) {
                        boolean granted = Boolean.TRUE.equals(permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) ||
                                Boolean.TRUE.equals(permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false));
                        locationPermissionCallback.accept(granted);
                    }
                });

        // 初始化定位服务设置启动器
        locationServicesLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 无论用户是否开启，都检查当前状态并回调
                    if (isLocationServicesEnabled()) {
                        if (locationServicesEnabledCallback != null) {
                            locationServicesEnabledCallback.run();
                        }
                    } else {
                        if (locationServicesDeniedCallback != null) {
                            locationServicesDeniedCallback.run();
                        }
                    }
                });

        // 初始化通知权限请求启动器
        notificationPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (notificationPermissionCallback != null) {
                        notificationPermissionCallback.accept(isGranted);
                    }
                    settingsManager.setBoolean(SettingsManager.KEY_SHOW_NOTIFICATION, isGranted);
                });
        appNotificationSettingsLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    boolean isGranted = hasNotificationPermission();
                    if (notificationPermissionCallback != null) {
                        notificationPermissionCallback.accept(isGranted);
                    }
                    settingsManager.setBoolean(SettingsManager.KEY_SHOW_NOTIFICATION, isGranted);
                });

        batteryOptimizationLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {

                    final android.os.Handler handler = new android.os.Handler(activity.getMainLooper());
                    final int maxAttempts = 10;
                    final int interval = 100;
                    final int[] currentAttempt = {0};

                    final Runnable checkRunnable = new Runnable() {
                        @Override
                        public void run() {
                            currentAttempt[0]++;
                            boolean isIgnored = isBatteryOptimizationIgnored();

                            if (isIgnored) {
                                if (batteryOptimizationSetCallback != null) {
                                    batteryOptimizationSetCallback.accept(true);
                                }
                            } else if (currentAttempt[0] < maxAttempts) {
                                handler.postDelayed(this, interval);
                            } else {
                                if (batteryOptimizationSetCallback != null) {
                                    batteryOptimizationSetCallback.accept(false);
                                }
                            }
                        }
                    };
                    handler.post(checkRunnable);
                });

    }

    // --- 检查方法 ---

    /**
     * 检查是否已授予位置权限
     *
     * @return 如果授予了精确或粗略位置权限，则返回 true
     */
    public boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 检查设备的定位服务（GPS）是否开启
     *
     * @return 如果开启则返回 true
     */
    public boolean isLocationServicesEnabled() {
        LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /**
     * 检查是否已授予通知权限（仅在 Android 13+ 上需要）
     *
     * @return 如果已授予或系统版本低于 Android 13，则返回 true
     */
    public boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // 对于 Android 13 以下的版本，此权限默认为授予状态
    }

    /**
     * 检查当前应用的电池省电策略是否为“无限制”（即忽略电池优化）
     *
     * @return 如果是“无限制”，则返回 true
     */
    public boolean isBatteryOptimizationIgnored() {
        PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(activity.getPackageName());
    }

    /**
     * 检查设备root状态
     *
     * @return -1:无法获取权限 0:可获取，同意未知 1:已同意
     */
    public int checkRootStatus() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(":")) {
                if (new File(path, "su").exists()) {

                    Log.d("pm", "allowRoot:" + allowRoot);
                    return allowRoot ? 1 : 0;
                }
            }
        }
        return -1;
    }

    /**
     * 检查 Shizuku 服务的状态和权限。
     *
     * @return 1: 已授权, 0: 未授权, -1: Shizuku 服务未运行
     */
    public int getShizukuStatus() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ? 1 : 0;
        } catch (IllegalStateException e) {
            return -1;
        }
    }


    // --- 请求方法 ---

    /**
     * 请求位置权限
     *
     * @param callback 用户授权或拒绝后的回调 (true: 授权, false: 拒绝)
     */
    public void requestLocationPermission(Consumer<Boolean> callback) {
        this.locationPermissionCallback = callback;
        String[] permissions = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        locationPermissionLauncher.launch(permissions);
    }

    /**
     * 请求用户开启定位服务
     *
     * @param onEnabledCallback 用户开启服务后的回调
     * @param onDeniedCallback  用户拒绝或未开启服务的回调
     */
    public void requestToEnableLocationServices(Runnable onEnabledCallback, Runnable onDeniedCallback) {
        this.locationServicesEnabledCallback = onEnabledCallback;
        this.locationServicesDeniedCallback = onDeniedCallback;
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        locationServicesLauncher.launch(intent);
    }

    /**
     * 请求通知权限 (Android 13+)
     *
     * @param callback 用户授权或拒绝后的回调 (true: 授权, false: 拒绝)
     */
    public void requestNotificationPermission(Consumer<Boolean> callback) {
        this.notificationPermissionCallback = callback;
        if (getTargetSdkVersion() >= Build.VERSION_CODES.TIRAMISU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            //系统33+且应用33+
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            //系统33-
            if (callback != null) {
                callback.accept(true);
            }
        } else if (getTargetSdkVersion() < Build.VERSION_CODES.TIRAMISU) {
            //系统33+但应用33-
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            appNotificationSettingsLauncher.launch(intent);
        }

    }

    public int getTargetSdkVersion() {
        try {
            ApplicationInfo applicationInfo = activity.getPackageManager().getApplicationInfo(
                    activity.getPackageName(), 0
            );
            return applicationInfo.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
            return Build.VERSION.SDK_INT;
        }
    }

    /**
     * 请求用户将应用的省电策略设置为“无限制”
     *
     * @param callback 用户从设置页返回且设置成功后的回调
     */
    public void requestToIgnoreBatteryOptimizations(Consumer<Boolean> callback) {
        this.batteryOptimizationSetCallback = callback;

        @SuppressLint("BatteryLife")
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));

        if (intent.resolveActivity(activity.getPackageManager()) != null) {
            batteryOptimizationLauncher.launch(intent);
        } else {
            Intent appDetailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
            appDetailsIntent.setData(uri);
            batteryOptimizationLauncher.launch(appDetailsIntent);
        }
    }


    public void requestRootPermission(Consumer<Boolean> callback) {
        CommandRunner.executeCommand("su -c whoami", true, null, t -> {
            allowRoot = t.startsWith("root");
            Log.d("pm", "set allowRoot:" + allowRoot);
            callback.accept(allowRoot);
        });
    }

    /**
     * 请求 Shizuku 权限。
     * <p>
     * 注意：此方法依赖于 Shizuku 的回调机制，您需要在 Activity/Fragment 的生命周期中
     * 添加/移除监听器来接收结果。
     *
     * @param requestCode 自定义请求码，用于在回调中识别此请求。
     * @param callback    用户授权或拒绝后的回调 (true: 授权, false: 拒绝)。
     */
    public void requestShizukuPermission(final int requestCode, Consumer<Boolean> callback) {
        try {
            this.shizukuPermissionResultListener = (reqCode, grantResult) -> {
                if (reqCode == requestCode) {
                    if (grantResult != PackageManager.PERMISSION_GRANTED) toast("用户拒绝请求");
                    callback.accept(grantResult == PackageManager.PERMISSION_GRANTED);
                    Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener);
                }
            };

            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener);
            Shizuku.requestPermission(requestCode);
        } catch (RuntimeException e) {
            toast("服务未启动");
        }
    }

    public void toast(String s) {
        Toast.makeText(activity, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 检查并汇总当前缺失的权限。
     * 此方法基于 refreshStatus 中的逻辑，检查哪些权限相关的按钮会处于“可点击”（即未授权）状态。
     *
     * @return 返回一个包含所有当前缺失权限描述的字符串列表。如果所有权限都已授予，则返回空列表。
     */
    public List<String> getMissingPermissionsSummary(boolean isRun) {
        Set<String> missingPermissions = new HashSet<>(); // 使用 Set 避免重复项

        // --- 读取所有相关的工作模式设置 ---
        int readMode = settingsManager.getInt(SettingsManager.KEY_READ_MODE);
        int readModeCmd = settingsManager.getInt(SettingsManager.KEY_READ_MODE_CMD);

        int scanMode = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE);
        int scanModeCmd = settingsManager.getInt(SettingsManager.KEY_SCAN_MODE_CMD);

        int turnonMode = settingsManager.getInt(SettingsManager.KEY_TURNON_MODE);
        int turnonModeCmd = settingsManager.getInt(SettingsManager.KEY_TURNON_MODE_CMD);

        int connectMode = settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE);
        int connectModeCmd = settingsManager.getInt(SettingsManager.KEY_CONNECT_MODE_CMD);

        int manageMode = settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE);
        int manageModeCmd = settingsManager.getInt(SettingsManager.KEY_MANAGE_MODE_CMD);

        // --- 1. 检查通用权限（电池和通知），这些按钮总是可见的 ---
        if(!isRun) {
            if (!isBatteryOptimizationIgnored()) {
                missingPermissions.add("电池优化“无限制”设置");
            }
            if (!hasNotificationPermission()) {
                missingPermissions.add("通知权限");
            }
        }

        // --- 2. 检查位置权限 ---
        // `scanModeApiButton` 和 `manageModeApiButton` 需要位置权限
        boolean locationNeeded = (scanMode == 0) || (manageMode == 0);
        if (locationNeeded) {
            if (!hasLocationPermission()) {
                missingPermissions.add("位置信息权限");
            }
            if (isRun&&!isLocationServicesEnabled()) {
                missingPermissions.add("系统定位服务处于关闭状态");
            }
        }

        // --- 3. 检查 Root/Shizuku 权限 ---
        // 只有当至少有一个命令行模式被激活时，才检查这两种权限
        boolean cmdModeActive = (readMode == 1) || (scanMode == 1) || (turnonMode == 1) || (connectMode == 2) || (manageMode == 1);

        if (cmdModeActive) {
            // 检查是否需要Root权限
            boolean rootNeeded = (readMode == 1 && readModeCmd == 0) ||
                    (scanMode == 1 && scanModeCmd == 0) ||
                    (turnonMode == 1 && turnonModeCmd == 0) ||
                    (connectMode == 2 && connectModeCmd == 0) ||
                    (manageMode == 1 && manageModeCmd == 0);
            int rootStatus = checkRootStatus();
            if (rootNeeded) {
                if (!isRun && rootStatus != 1 || isRun && rootStatus == -1) {
                    missingPermissions.add("Root权限");
                }
            }

            // 检查是否需要Shizuku权限
            boolean shizukuNeeded = (readMode == 1 && readModeCmd == 1) ||
                    (scanMode == 1 && scanModeCmd == 1) ||
                    (turnonMode == 1 && turnonModeCmd == 1) ||
                    (connectMode == 2 && connectModeCmd == 1) ||
                    (manageMode == 1 && manageModeCmd == 1);

            if (shizukuNeeded && getShizukuStatus() != 1) {
                missingPermissions.add("Shizuku权限");
            }
        }

        return new ArrayList<>(missingPermissions);
    }


}
