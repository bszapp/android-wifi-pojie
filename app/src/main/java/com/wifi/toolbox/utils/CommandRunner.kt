package com.wifi.toolbox.utils

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer


object CommandRunner {
    /**
     * 执行命令，可选择是否以 Root 模式执行
     *
     * @param command           命令文本
     * @param isRoot            是否以 Root 模式执行
     * @param onOutputReceived  当接收到输出时的回调函数
     * @param onCommandFinished 当命令全部结束时的回调函数
     * @return 停止执行的函数
     */
    fun executeCommand(
        command: String, isRoot: Boolean,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<String>?
    ): Runnable {
        val isCancelled = AtomicBoolean(false)
        val isRunning = AtomicBoolean(true)

        val allOutput = StringBuilder()
        val processHolder = arrayOfNulls<Process>(1)

        val outputThread = Thread {
            try {
                val process: Process
                if (isRoot) {
                    process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                } else {
                    process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                }
                processHolder[0] = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = null

                while (isRunning.get() && (reader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) break
                    line?.let {
                        allOutput.append(it).append("\n")
                        onOutputReceived?.accept(it)
                    }
                }

                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                line = null // Reset line for error stream
                while (isRunning.get() && (errorReader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) break
                    line?.let {
                        allOutput.append(it).append("\n")
                        onOutputReceived?.accept(it)
                    }
                }

                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                }

                if (!isCancelled.get() && onCommandFinished != null) onCommandFinished.accept(
                    allOutput.toString()
                )
            } catch (e: Exception) {
                if (!isCancelled.get() && onCommandFinished != null) onCommandFinished.accept("Error: " + e.message)
            } finally {
                isRunning.set(false)
            }
        }

        outputThread.start()

        return Runnable {
            isCancelled.set(true)
            isRunning.set(false)

            if (processHolder[0] != null) processHolder[0]!!.destroy()
            outputThread.interrupt()
        }
    }

    /**
     * 同步执行命令，等待全部执行完毕后返回输出结果
     *
     * @param command 命令文本
     * @return CompletableFuture 异步返回命令执行的完整输出
     */
    fun executeCommandSync(command: String, isRoot: Boolean): String {
        val future = CompletableFuture<String>()

        executeCommand(
            command,
            isRoot,
            null
        ) { t: String -> future.complete(t) }

        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }
}
