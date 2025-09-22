package wifi.pojie;

import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ShizukuHelper {
    private static final String TAG = "ShizukuHelper";

    private ShizukuHelper() {
        // 工具类不需要实例化
    }

    /**
     * 检查 Shizuku 服务是否可用
     */
    public static boolean isShizukuAvailable() {
        try {
            // 尝试调用 Shizuku.pingBinder() 方法检查服务是否可用
            return Shizuku.pingBinder();
        } catch (Exception e) {
            Log.w(TAG, "Shizuku is not available", e);
            return false;
        }
    }

    /**
     * 检查是否已获得 Shizuku 权限
     */
    public static boolean checkPermission() {
        // 首先检查 Shizuku 服务是否可用
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku service is not available");
            return true;
        }
        
        if (Shizuku.isPreV11()) {
            Log.w(TAG, "Shizuku version is pre-v11, not supported");
            return true;
        }

        return Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED;
    }


    /**
     * 添加权限请求结果监听器
     */
    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku service is not available, cannot add permission listener");
            return;
        }
        Shizuku.addRequestPermissionResultListener(listener);
    }

    /**
     * 移除权限请求结果监听器
     */
    public static void removePermissionListener(Shizuku.OnRequestPermissionResultListener listener) {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku service is not available, cannot remove permission listener");
            return;
        }
        Shizuku.removeRequestPermissionResultListener(listener);
    }

    /**
     * 添加 Binder 状态监听器
     */
    public static void addBinderListener(Shizuku.OnBinderReceivedListener receivedListener,
                                        Shizuku.OnBinderDeadListener deadListener) {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku service is not available, cannot add binder listener");
            return;
        }
        Shizuku.addBinderReceivedListener(receivedListener);
        Shizuku.addBinderDeadListener(deadListener);
    }

    /**
     * 移除 Binder 状态监听器
     */
    public static void removeBinderListener(Shizuku.OnBinderReceivedListener receivedListener,
                                           Shizuku.OnBinderDeadListener deadListener) {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku service is not available, cannot remove binder listener");
            return;
        }
        Shizuku.removeBinderReceivedListener(receivedListener);
        Shizuku.removeBinderDeadListener(deadListener);
    }
    
    /**
     * 执行命令
     * @param command 命令文本
     * @param onOutputReceived 当接收到输出时的回调函数
     * @param onCommandFinished 当命令全部结束时的回调函数
     * @return 停止执行的函数
     */
    public static Runnable executeCommand(String command, 
                                          Consumer<String> onOutputReceived, 
                                          Consumer<String> onCommandFinished) {
        // 使用AtomicBoolean以便在匿名内部类中修改
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        AtomicBoolean isRunning = new AtomicBoolean(true);
        
        // 存储所有输出
        StringBuilder allOutput = new StringBuilder();
        
        // 创建进程引用
        Process[] processHolder = new Process[1];
        
        Thread outputThread = new Thread(() -> {
            try {
                // 使用反射调用 Shizuku.newProcess 方法
                Method newProcessMethod = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);

                String[] cmd = parseCommand(command);
                
                // 调用 Shizuku.newProcess 方法
                Process process = (Process) newProcessMethod.invoke(null, (Object) cmd, null, "/");
                processHolder[0] = process;
                
                // 读取标准输出
                assert process != null;
                InputStream inputStream = process.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                
                while (isRunning.get() && (line = reader.readLine()) != null) {
                    if (isCancelled.get()) {
                        break;
                    }
                    
                    // 添加到总输出
                    allOutput.append(line).append("\n");
                    
                    // 回调通知新行输出
                    if (onOutputReceived != null) {
                        onOutputReceived.accept(line);
                    }
                }
                
                // 读取错误输出
                InputStream errorStream = process.getErrorStream();
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream));
                while (isRunning.get() && (line = errorReader.readLine()) != null) {
                    if (isCancelled.get()) {
                        break;
                    }
                    
                    // 添加到总输出
                    allOutput.append(line).append("\n");
                    
                    // 回调通知新行输出（错误信息也通过这个回调）
                    if (onOutputReceived != null) {
                        onOutputReceived.accept(line);
                    }
                }
                
                // 等待进程结束
                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Log.d(TAG, "Process was interrupted");
                }
                
                // 如果没有被取消，则执行结束回调
                if (!isCancelled.get() && onCommandFinished != null) {
                    onCommandFinished.accept(allOutput.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing command", e);
                if (!isCancelled.get() && onCommandFinished != null) {
                    onCommandFinished.accept("Error: " + e.getMessage());
                }
            } finally {
                isRunning.set(false);
            }
        });
        
        outputThread.start();
        
        // 返回停止执行的函数
        return () -> {
            isCancelled.set(true);
            isRunning.set(false);
            
            if (processHolder[0] != null) {
                processHolder[0].destroy();
            }

            outputThread.interrupt();
        };
    }
    
    /**
     * 同步执行命令，等待全部执行完毕后返回输出结果
     * @param command 命令文本
     * @return CompletableFuture 异步返回命令执行的完整输出
     */
    public static String executeCommandSync(String command)
            throws ExecutionException, InterruptedException {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        
        executeCommand(command, null, future::complete);
        
        return future.get();
    }
    
    /**
     * 解析命令行参数，正确处理引号内的空格
     * @param command 完整的命令行字符串
     * @return 解析后的参数数组
     */
    private static String[] parseCommand(String command) {
        List<String> args = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentArg = new StringBuilder();
        
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (currentArg.length() > 0) {
                    args.add(currentArg.toString());
                    currentArg.setLength(0);
                }
            } else {
                currentArg.append(c);
            }
        }

        if (currentArg.length() > 0) {
            args.add(currentArg.toString());
        }
        
        return args.toArray(new String[0]);
    }
}