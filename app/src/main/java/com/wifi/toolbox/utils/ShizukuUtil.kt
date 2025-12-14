package com.wifi.toolbox.utils

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import com.wifi.toolbox.structs.WifiInfo
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import org.lsposed.hiddenapibypass.*
import java.util.BitSet

object ShizukuUtil {

    const val REQUEST_PERMISSION_CODE = 1001

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sharedPreferences =
                context.getSharedPreferences("settings_global", Context.MODE_PRIVATE)
            var hiddenApiBypassOption: Int
            try {
                hiddenApiBypassOption = sharedPreferences.getInt("hidden_api_bypass", 1)
            } catch (_: ClassCastException) {
                val stringValue = sharedPreferences.getString("hidden_api_bypass", null)
                hiddenApiBypassOption = stringValue?.toIntOrNull() ?: 1
            }

            when (hiddenApiBypassOption) {
                1 -> LSPass.addHiddenApiExemptions("")
                2 -> HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
    }

    const val PACKAGE_NAME = "com.android.settings"

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

        val methods = wifiService::class.java.declaredMethods

        val setWifiEnabledMethod15 = methods.firstOrNull {
            it.name == "setWifiEnabled" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE &&
                    it.parameterTypes[2] == Bundle::class.java
        }
        if (setWifiEnabledMethod15 != null) {
            setWifiEnabledMethod15.invoke(wifiService, PACKAGE_NAME, enabled, Bundle())
            return
        }

        val setWifiEnabledMethod29 = methods.firstOrNull {
            it.name == "setWifiEnabled" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE
        }
        if (setWifiEnabledMethod29 != null) {
            setWifiEnabledMethod29.invoke(wifiService, PACKAGE_NAME, enabled)
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

        throw NoSuchMethodException("没有发现setWifiEnabled方法")
    }

    fun connectToWifi(ssid: String, password: String) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
        val wifiConfig = wifiConfigurationClass.getDeclaredConstructor().newInstance()

        wifiConfigurationClass.getField("SSID").set(wifiConfig, "\"$ssid\"")

        val keyMgmtBitSet = BitSet()
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
            Log.d("ShizukuTool", "addOrUpdateNetworkMethodApi29")
            netId = addOrUpdateNetworkMethodApi29.invoke(
                wifiService,
                wifiConfig,
                PACKAGE_NAME,
                Bundle()
            ) as Int
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
                netId = addOrUpdateNetworkMethodApi27.invoke(
                    wifiService,
                    wifiConfig,
                    PACKAGE_NAME
                ) as Int
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
            throw NoSuchMethodException("没有发现addOrUpdateNetwork方法")
        }

        if (netId == -1) {
            throw RuntimeException("添加网络失败（NetId=-1）")
        }

        val enableNetworkMethod = wifiService::class.java.getMethod(
            "enableNetwork",
            Integer.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java
        )
        enableNetworkMethod.invoke(wifiService, netId, true, PACKAGE_NAME)
    }

    fun startWifiScan(allowUseCommand: Boolean = false): Boolean {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val startScanMethod = wifiService::class.java.getMethod(
            "startScan",
            String::class.java,
            String::class.java
        )
        val scanInitiated = startScanMethod.invoke(wifiService, PACKAGE_NAME, null) as Boolean
        if (!scanInitiated && allowUseCommand) {
            return executeCommandSync("cmd wifi start-scan").exitCode == 0
        }
        return scanInitiated
    }

    fun getWifiScanResults(): List<WifiInfo> {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val results = mutableListOf<WifiInfo>()
        val getScanResultsMethod = wifiService::class.java.getMethod(
            "getScanResults",
            String::class.java,
            String::class.java
        )
        val parceledListSlice = getScanResultsMethod.invoke(wifiService, PACKAGE_NAME, null)

        val actualParceledListSliceClass = parceledListSlice.javaClass
        val getListMethod = actualParceledListSliceClass.getMethod("getList")

        @Suppress("UNCHECKED_CAST")
        val scanResultsList = getListMethod.invoke(parceledListSlice) as List<Any>

        if (scanResultsList.isEmpty()) {
            return results
        }

        val scanResultClass = Class.forName("android.net.wifi.ScanResult")
        val ssidField = scanResultClass.getField("SSID")
        val levelField = scanResultClass.getField("level")
        val capabilitiesField = scanResultClass.getField("capabilities")

        scanResultsList.forEach { result ->
            val ssid = ssidField.get(result)?.toString() ?: ""
            val level = levelField.get(result) as Int
            val capabilities = capabilitiesField.get(result)?.toString() ?: ""

            results.add(WifiInfo(ssid, level, capabilities))
        }
        results.sortByDescending { it.level }
        return results
    }

    fun getSavedWifiList(): List<Pair<Int, String>> {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val results = mutableListOf<Pair<Int, String>>()
        val methods = wifiService::class.java.declaredMethods
        val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")

        var parceledListSlice: Any? = null
        var founded = false

        // 1. 新版方法: getConfiguredNetworks(String packageName, String featureId, boolean callerNetworksOnly)
        val getConfiguredNetworksMethodNew = methods.firstOrNull {
            it.name == "getConfiguredNetworks" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType
        }
        if (getConfiguredNetworksMethodNew != null) {
            founded = true
            parceledListSlice = getConfiguredNetworksMethodNew.invoke(
                wifiService,
                PACKAGE_NAME,
                null,  // featureId
                false  // callerNetworksOnly = false 获取所有配置
            )
        }

        // 2. 老版本: getConfiguredNetworks(String packageName, Bundle options) (API 29)
        if (!founded) {
            val getConfiguredNetworksMethodApi29 = methods.firstOrNull {
                it.name == "getConfiguredNetworks" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == Bundle::class.java
            }
            if (getConfiguredNetworksMethodApi29 != null) {
                founded = true
                parceledListSlice = getConfiguredNetworksMethodApi29.invoke(
                    wifiService,
                    PACKAGE_NAME,
                    Bundle()
                )
            }
        }

        // 3. 老版本: getConfiguredNetworks(String packageName) (API 27)
        if (!founded) {
            val getConfiguredNetworksMethodApi27 = methods.firstOrNull {
                it.name == "getConfiguredNetworks" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
            }
            if (getConfiguredNetworksMethodApi27 != null) {
                founded = true
                parceledListSlice = getConfiguredNetworksMethodApi27.invoke(
                    wifiService,
                    PACKAGE_NAME
                )
            }
        }

        // 4. Legacy: getConfiguredNetworks()
        if (!founded) {
            val getConfiguredNetworksMethodLegacy = methods.firstOrNull {
                it.name == "getConfiguredNetworks" && it.parameterTypes.isEmpty()
            }
            if (getConfiguredNetworksMethodLegacy != null) {
                founded = true
                parceledListSlice = getConfiguredNetworksMethodLegacy.invoke(wifiService)
            }
        }

        if (!founded) throw NoSuchMethodException("没有发现getConfiguredNetworks方法")

        if (parceledListSlice == null) return emptyList()

        val getListMethod = parceledListSlice.javaClass.getMethod("getList")

        @Suppress("UNCHECKED_CAST")
        val configuredNetworksList = getListMethod.invoke(parceledListSlice) as List<Any>
        if (configuredNetworksList.isEmpty()) return results
        val networkIdField = wifiConfigurationClass.getField("networkId")

        val ssidFieldConfig = try {
            wifiConfigurationClass.getField("SSID")
        } catch (_: Exception) {
            null
        }

        val seenIds = HashSet<Int>()

        configuredNetworksList.forEach { config ->
            val networkId = networkIdField.get(config) as Int
            if (seenIds.contains(networkId)) return@forEach
            seenIds.add(networkId)
            var ssidValue = ""
            if (ssidFieldConfig != null) {
                ssidValue = try {
                    ssidFieldConfig.get(config)?.toString() ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            if (ssidValue.length >= 2 && ssidValue.startsWith("\"") && ssidValue.endsWith("\"")) {
                ssidValue = ssidValue.substring(1, ssidValue.length - 1)
            }
            results.add(Pair(networkId, ssidValue))
        }

        return results
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
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val isCancelled = AtomicBoolean(false)
        val isRunning = AtomicBoolean(true)

        val allOutput = StringBuilder()
        val processHolder = arrayOfNulls<Process>(1)

        val outputThread = Thread {
            var exitCode = -1
            try {
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val cmd = CommandRunner.parseCommand(command)

                val process = newProcessMethod.invoke(null, cmd, null, "/") as Process
                processHolder[0] = process

                val inputStream = process.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String? = null

                while (isRunning.get() && (reader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) break

                    allOutput.append(line).append("\n")
                    onOutputReceived?.accept(line ?: "")
                }

                try {
                    exitCode = process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                val errorStream = process.errorStream
                val errorReader = BufferedReader(InputStreamReader(errorStream))
                while (isRunning.get() && (errorReader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) {
                        break
                    }
                    allOutput.append(line).append("\n")
                    onOutputReceived?.accept(line ?: "")
                }

                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                if (!isCancelled.get()) {
                    onCommandFinished?.accept(CommandRunner.CommandResult(allOutput.toString(), exitCode))
                }
            } catch (e: Exception) {
                if (!isCancelled.get()) {
                    onCommandFinished?.accept(CommandRunner.CommandResult(e.stackTraceToString(), exitCode))
                }
            } finally {
                isRunning.set(false)
            }
        }

        outputThread.start()

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
    fun executeCommandSync(command: String): CommandRunner.CommandResult {
        val future = CompletableFuture<CommandRunner.CommandResult?>()

        executeCommand(command, null) { future.complete(it) }

        try {
            return future.get() ?: CommandRunner.CommandResult("", -1)
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }
}