package wifi.pojie;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class ConnectWifi {
    private static final String TAG = "ConnectWifi";

    private final int timeoutMillis;
    private Consumer<Integer> onConnectionResultRef;
    private Runnable stopLogcatRunnable;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 添加一个ScheduledFuture字段来管理超时任务
    private ScheduledFuture<?> timeoutTask;
    private volatile boolean isDestroyed = false;
    private long startTime; // 记录开始时间

    /**
     * 构造函数
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public ConnectWifi(int timeoutMillis) /* throws ExecutionException, InterruptedException */ {
        this.timeoutMillis = timeoutMillis;
        try {
            //清空缓存区
            ShizukuHelper.executeCommandSync("logcat -c");
            //开始监听logcat
            stopLogcatRunnable = ShizukuHelper.executeCommand(
                    "logcat -s \"WifiService:D\" \"wpa_supplicant:D\" \"DhcpClient:D\"",
                    (line) -> {
                        if (isDestroyed) return;
                        
                        mainHandler.post(() -> {
                            if (isDestroyed) return;
                            Log.d(TAG, "收到执行响应：" + line);

                            // 检查是否匹配密码错误的模式
                            if (Pattern.matches(".*WPA: 4-Way Handshake failed - pre-shared key may be incorrect.*", line)) {
                                Log.d(TAG, "密码错误");
                                if (onConnectionResultRef != null) {
                                    long elapsedTime = System.currentTimeMillis() - startTime;
                                    // 如果在3秒内收到密码错误的结果，则忽略并继续等待
                                    if (elapsedTime < 3000) {
                                        Log.d(TAG, "忽略快速返回的密码错误结果，继续等待");
                                        return;
                                    }
                                    onConnectionResultRef.accept(1);
                                }
                                return;
                            }

                            // 检查是否匹配连接成功的模式
                            if (Pattern.matches(".*Received packet: .* ACK: your new IP .*(?:[0-9]{1,3}\\.){3}[0-9]{1,3}.*", line)) {
                                Log.d(TAG, "连接成功");
                                if (onConnectionResultRef != null) {
                                    onConnectionResultRef.accept(0);
                                }
                            }
                        });
                    },
                    null
            );
        } catch (Exception e) {
            Log.e(TAG, "初始化ConnectWifi时发生错误", e);
            // 即使初始化失败，也要确保stopLogcatRunnable不为null
            stopLogcatRunnable = () -> {};
        }
    }
    
    /**
     * 异步连接WiFi网络
     *
     * @param ssid 网络名称
     * @param password 网络密码
     */
    public void connect(String ssid, String password, Consumer<Integer> onConnectionResult) /* throws ExecutionException, InterruptedException */ {
        // 在后台线程执行命令，避免阻塞主线程
        new Thread(() -> {
            try {
                // 检查是否已经被销毁
                if (isDestroyed) return;
                
                String connectResult=ShizukuHelper.executeCommandSync("cmd wifi connect-network \"" + ssid + "\" wpa2 \"" + password + "\"");
                if(!connectResult.isEmpty()){
                    onConnectionResult.accept(3);
                    return;
                }
                mainHandler.post(() -> {
                    // 记录开始时间
                    startTime = System.currentTimeMillis();
                    
                    // 检查是否已经被销毁
                    if (isDestroyed) return;
                    
                    try {
                        // 保存超时任务的引用，以便后续可以取消
                        timeoutTask = scheduler.schedule(() -> {
                            mainHandler.post(() -> {
                                if (isDestroyed) return;
                                if (onConnectionResultRef != null) {
                                    onConnectionResultRef.accept(2);
                                }
                            });
                        }, timeoutMillis, TimeUnit.MILLISECONDS);

                        onConnectionResultRef = (result -> {
                            if (isDestroyed) return;
                            if (timeoutTask != null) {
                                timeoutTask.cancel(false);
                            }
                            onConnectionResultRef = null;
                            onConnectionResult.accept(result);
                        });
                    } catch (RejectedExecutionException e) {
                        Log.e(TAG, Objects.requireNonNull(e.getMessage()));
                        if (!isDestroyed) {
                            onConnectionResult.accept(2);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "连接WiFi时发生错误", e);
                mainHandler.post(() -> {
                    if (!isDestroyed) {
                        onConnectionResult.accept(3);
                    }
                });
            }
        }).start();
    }

    public void destroy() {
        isDestroyed = true;
        
        if (stopLogcatRunnable != null) {
            stopLogcatRunnable.run();
        }
        
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        
        // 关闭调度器
        scheduler.shutdownNow();
        
        onConnectionResultRef = null;
    }
}