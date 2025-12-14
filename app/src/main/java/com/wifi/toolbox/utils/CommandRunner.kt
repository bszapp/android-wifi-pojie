package com.wifi.toolbox.utils

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer


object CommandRunner {

    data class CommandResult(
        val output: String,
        val exitCode: Int
    )

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
        onCommandFinished: Consumer<CommandResult>?
    ): Runnable {
        val isCancelled = AtomicBoolean(false)
        val isRunning = AtomicBoolean(true)

        val allOutput = StringBuilder()
        val processHolder = arrayOfNulls<Process>(1)

        val outputThread = Thread {
            var exitCode = -1
            try {
                val process: Process
                val cmd = parseCommand(command)

                if (isRoot) process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                else process = Runtime.getRuntime().exec(cmd)

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
                line = null
                while (isRunning.get() && (errorReader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) break
                    line?.let {
                        allOutput.append(it).append("\n")
                        onOutputReceived?.accept(it)
                    }
                }

                try {
                    exitCode = process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                if (!isCancelled.get() && onCommandFinished != null) onCommandFinished.accept(
                    CommandResult(allOutput.toString(), exitCode)
                )
            } catch (e: Exception) {
                if (!isCancelled.get() && onCommandFinished != null) onCommandFinished.accept(
                    CommandResult(e.stackTraceToString(), -1)
                )
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
    fun executeCommandSync(command: String, isRoot: Boolean): CommandResult {
        val future = CompletableFuture<CommandResult>()

        executeCommand(
            command,
            isRoot,
            null
        ) { future.complete(it) }

        try {
            return future.get()
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    fun parseCommand(command: String): Array<String> {
        val args = mutableListOf<String>()
        var inQuotes = false
        val currentArg = StringBuilder()

        for (c in command) {
            when (c) {
                '\"' -> inQuotes = !inQuotes
                ' ' if !inQuotes -> {
                    if (currentArg.isNotEmpty()) {
                        args.add(currentArg.toString())
                        currentArg.clear()
                    }
                }

                else -> currentArg.append(c)
            }
        }

        if (currentArg.isNotEmpty()) {
            args.add(currentArg.toString())
        }

        return args.toTypedArray()
    }
}
