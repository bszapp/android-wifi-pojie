package wifi.pojie;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class CommandRunner {

    /**
     * 执行命令，可选择是否以 Root 模式执行
     *
     * @param command           命令文本
     * @param isRoot            是否以 Root 模式执行
     * @param onOutputReceived  当接收到输出时的回调函数
     * @param onCommandFinished 当命令全部结束时的回调函数
     * @return 停止执行的函数
     */
    public static Runnable executeCommand(String command, boolean isRoot,
                                          Consumer<String> onOutputReceived,
                                          Consumer<String> onCommandFinished) {
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        AtomicBoolean isRunning = new AtomicBoolean(true);

        StringBuilder allOutput = new StringBuilder();
        Process[] processHolder = new Process[1];

        Thread outputThread = new Thread(() -> {
            try {
                Process process;
                if (isRoot) {
                    process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                } else {
                    process = Runtime.getRuntime().exec(command);
                }
                processHolder[0] = process;

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;

                while (isRunning.get() && (line = reader.readLine()) != null) {
                    if (isCancelled.get()) break;
                    allOutput.append(line).append("\n");
                    if (onOutputReceived != null) onOutputReceived.accept(line);
                }

                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                while (isRunning.get() && (line = errorReader.readLine()) != null) {
                    if (isCancelled.get()) break;
                    allOutput.append(line).append("\n");
                    if (onOutputReceived != null) onOutputReceived.accept(line);
                }

                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    // 线程被中断
                }

                if (!isCancelled.get() && onCommandFinished != null)
                    onCommandFinished.accept(allOutput.toString());
            } catch (Exception e) {
                if (!isCancelled.get() && onCommandFinished != null)
                    onCommandFinished.accept("Error: " + e.getMessage());
            } finally {
                isRunning.set(false);
            }
        });

        outputThread.start();

        return () -> {
            isCancelled.set(true);
            isRunning.set(false);

            if (processHolder[0] != null) processHolder[0].destroy();
            outputThread.interrupt();
        };
    }

    /**
     * 同步执行命令，等待全部执行完毕后返回输出结果
     *
     * @param command 命令文本
     * @return CompletableFuture 异步返回命令执行的完整输出
     */
    public static String executeCommandSync(String command, boolean isRoot) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();

        executeCommand(command, isRoot, null, future::complete);

        try {
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}