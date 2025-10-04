package wifi.pojie;

import static androidx.core.app.ActivityCompat.requestPermissions;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Objects;
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

    private Shizuku.OnRequestPermissionResultListener shizukuPermissionResultListener;
    private static final int REQUEST_CODE_NOTIFICATION_PERMISSION = 1002;

    private boolean allowRoot = false;

    public PermissionManager(AppCompatActivity activity) {
        this.activity = activity;

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
                });

        // 初始化电池优化设置启动器
        batteryOptimizationLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // 用户从设置页面返回后，重新检查状态并回调
                    if (isBatteryOptimizationIgnored()) {
                        if (batteryOptimizationSetCallback != null) {
                            batteryOptimizationSetCallback.accept(isBatteryOptimizationIgnored());
                        }
                    }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                callback.accept(true);
            }
        } else {
            callback.accept(true);
        }
    }

    /**
     * 请求用户将应用的省电策略设置为“无限制”
     *
     * @param callback 用户从设置页返回且设置成功后的回调
     */
    public void requestToIgnoreBatteryOptimizations(Consumer<Boolean> callback) {
        this.batteryOptimizationSetCallback = callback;
        @SuppressLint("BatteryLife") Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + activity.getPackageName()));
        batteryOptimizationLauncher.launch(intent);
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
        this.shizukuPermissionResultListener = (reqCode, grantResult) -> {
            if (reqCode == requestCode) {
                callback.accept(grantResult == PackageManager.PERMISSION_GRANTED);
                Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener);
            }
        };

        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener);
        Shizuku.requestPermission(requestCode);
    }
}
