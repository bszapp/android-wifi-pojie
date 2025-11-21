package com.wifi.toolbox.tools

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import rikka.shizuku.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

object ShizukuTool {
    const val PACKAGE_NAME = "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any {
        return Class.forName("$className\$Stub").run {
            getMethod("asInterface", IBinder::class.java).invoke(
                null,
                ShizukuBinderWrapper(original)
            )!!
        }
    }

    fun lookScreen() {
        val inputManagerBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
        val input = asInterface("android.hardware.input.IInputManager", inputManagerBinder)
        val inject = input::class.java.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.java
        )
        val now = SystemClock.uptimeMillis()
        val injectMode = 2
        inject.invoke(
            input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), injectMode
        )
        inject.invoke(
            input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), injectMode
        )
    }

    fun setMediaVolumeMax() {
        val audioManagerBinder = SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)
        val audioService = asInterface("android.media.IAudioService", audioManagerBinder)

        val getStreamMaxVolumeMethod = audioService::class.java.getMethod(
            "getStreamMaxVolume", Int::class.java
        )
        val maxVolume =
            getStreamMaxVolumeMethod.invoke(audioService, AudioManager.STREAM_MUSIC) as Int

        val setStreamVolumeMethod = audioService::class.java.getMethod(
            "setStreamVolume", Int::class.java, Int::class.java, Int::class.java, String::class.java
        )
        setStreamVolumeMethod.invoke(
            audioService,
            AudioManager.STREAM_MUSIC,
            maxVolume,
            0,
            PACKAGE_NAME
        )
    }

    fun setWifiEnabled(enabled: Boolean) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)
        val packageName = "com.android.shell"

        val methods = wifiService::class.java.declaredMethods

        val setWifiEnabledMethod15 = methods.firstOrNull {
            it.name == "setWifiEnabled" && it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == java.lang.Boolean.TYPE &&
            it.parameterTypes[2] == Bundle::class.java
        }
        if (setWifiEnabledMethod15 != null) {
            setWifiEnabledMethod15.invoke(wifiService, packageName, enabled, Bundle())
            return
        }

        val setWifiEnabledMethod29 = methods.firstOrNull {
            it.name == "setWifiEnabled" && it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1] == java.lang.Boolean.TYPE
        }
        if (setWifiEnabledMethod29 != null) {
            setWifiEnabledMethod29.invoke(wifiService, packageName, enabled)
            return
        }

        val setWifiEnabledMethodLegacy = methods.firstOrNull {
            it.name == "setWifiEnabled" && it.parameterTypes.size == 1 &&
            it.parameterTypes[0] == java.lang.Boolean.TYPE
        }
        if (setWifiEnabledMethodLegacy != null) {
            setWifiEnabledMethodLegacy.invoke(wifiService, enabled)
            return
        }

        throw NoSuchMethodException("没有发现setWifiEnabled方法，请将hidden_api_policy设为1然后重启应用")
    }

    fun connectToWifi(ssid: String, password: String) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
        val wifiConfig = wifiConfigurationClass.getDeclaredConstructor().newInstance()

        wifiConfigurationClass.getField("SSID").set(wifiConfig, "\"$ssid\"")

        val keyMgmtBitSet = java.util.BitSet()
        if (password.isEmpty()) {
            keyMgmtBitSet.set(0)
        } else {
            wifiConfigurationClass.getField("preSharedKey").set(wifiConfig, "\"$password\"")
            keyMgmtBitSet.set(1)
        }
        wifiConfigurationClass.getField("allowedKeyManagement").set(wifiConfig, keyMgmtBitSet)

        var netId: Int = -1
        var founded = false
        val methods = wifiService::class.java.declaredMethods

        val addOrUpdateNetworkMethodApi29 = methods.firstOrNull {
            it.name == "addOrUpdateNetwork" && it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == wifiConfigurationClass &&
            it.parameterTypes[1] == String::class.java &&
            it.parameterTypes[2] == Bundle::class.java
        }
        if (addOrUpdateNetworkMethodApi29 != null) {
            founded = true
            Log.d("ShizukuTool","addOrUpdateNetworkMethodApi29")
            netId = addOrUpdateNetworkMethodApi29.invoke(wifiService, wifiConfig, PACKAGE_NAME, Bundle()) as Int
        }

        if (netId == -1) {
            val addOrUpdateNetworkMethodApi27 = methods.firstOrNull {
                it.name == "addOrUpdateNetwork" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == wifiConfigurationClass &&
                it.parameterTypes[1] == String::class.java
            }
            if (addOrUpdateNetworkMethodApi27 != null) {
                founded = true
                Log.d("ShizukuTool", "addOrUpdateNetworkMethodApi27")
                netId = addOrUpdateNetworkMethodApi27.invoke(wifiService, wifiConfig, PACKAGE_NAME) as Int
            }
        }

        if (netId == -1) {
            val addOrUpdateNetworkMethodLegacy = methods.firstOrNull {
                it.name == "addOrUpdateNetwork" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == wifiConfigurationClass
            }
            if (addOrUpdateNetworkMethodLegacy != null) {
                founded = true
                Log.d("ShizukuTool", "addOrUpdateNetworkMethodLegacy")
                @Suppress("DEPRECATION")
                netId = addOrUpdateNetworkMethodLegacy.invoke(wifiService, wifiConfig) as Int
            }
        }

        if (!founded) {
            throw NoSuchMethodException("没有发现addOrUpdateNetwork方法，请将hidden_api_policy设为1然后重启应用")
        }

        if (netId == -1) {
            throw RuntimeException("添加网络失败（netid=-1）")
        }

        val enableNetworkMethod = wifiService::class.java.getMethod(
            "enableNetwork",
            Integer.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java
        )
        enableNetworkMethod.invoke(wifiService, netId, true, PACKAGE_NAME)
    }

    /**
     * 执行命令
     * @param command 命令文本
     * @param onOutputReceived 当接收到输出时的回调函数
     * @param onCommandFinished 当命令全部结束时的回调函数
     * @return 停止执行的函数
     */
    fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<String>?
    ): Runnable {
        // 使用AtomicBoolean以便在匿名内部类中修改
        val isCancelled = AtomicBoolean(false)
        val isRunning = AtomicBoolean(true)


        // 存储所有输出
        val allOutput = StringBuilder()


        // 创建进程引用
        val processHolder = arrayOfNulls<Process>(1)

        val outputThread = Thread {
            try {
                // 使用反射调用 Shizuku.newProcess 方法
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val cmd = parseCommand(command)


                // 调用 Shizuku.newProcess 方法
                val process = newProcessMethod.invoke(null, cmd, null, "/") as Process
                processHolder[0] = process


                // 读取标准输出
                val inputStream = process.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String? = null

                while (isRunning.get() && (reader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) {
                        break
                    }


                    // 添加到总输出
                    allOutput.append(line).append("\n")


                    // 回调通知新行输出
                    onOutputReceived?.accept(line ?: "")
                }


                // 读取错误输出
                val errorStream = process.errorStream
                val errorReader = BufferedReader(InputStreamReader(errorStream))
                while (isRunning.get() && (errorReader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) {
                        break
                    }


                    // 添加到总输出
                    allOutput.append(line).append("\n")


                    // 回调通知新行输出（错误信息也通过这个回调）
                    onOutputReceived?.accept(line ?: "")
                }


                // 等待进程结束
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }


                // 如果没有被取消，则执行结束回调
                if (!isCancelled.get()) {
                    onCommandFinished?.accept(allOutput.toString())
                }
            } catch (e: Exception) {
                if (!isCancelled.get()) {
                    onCommandFinished?.accept(e.stackTraceToString())
                }
            } finally {
                isRunning.set(false)
            }
        }

        outputThread.start()


        // 返回停止执行的函数
        return Runnable {
            isCancelled.set(true)
            isRunning.set(false)

            processHolder[0]?.destroy()
            outputThread.interrupt()
        }
    }

    /**
     * 同步执行命令，等待全部执行完毕后返回输出结果
     * @param command 命令文本
     * @return CompletableFuture 异步返回命令执行的完整输出
     */
    fun executeCommandSync(command: String): String {
        val future = CompletableFuture<String?>()

        executeCommand(command, null) { t: String? -> future.complete(t) }

        try {
            return future.get() ?: ""
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    /**
     * 解析命令行参数，正确处理引号内的空格
     * @param command 完整的命令行字符串
     * @return 解析后的参数数组
     */
    private fun parseCommand(command: String): Array<String> {
        val args = mutableListOf<String>()
        var inQuotes = false
        val currentArg = StringBuilder()

        for (c in command) {
            when {
                c == '\"' -> inQuotes = !inQuotes
                c == ' ' && !inQuotes -> {
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