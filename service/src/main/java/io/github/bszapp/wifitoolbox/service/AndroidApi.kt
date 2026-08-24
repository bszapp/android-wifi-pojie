@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiAction
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiKeys
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import java.lang.reflect.InvocationTargetException

/**
 * service 内唯一负责“单次调用 Android 系统 API”的全能类。
 * MainService 只把 AndroidApiRequest 转交给本类，不关心这里有多少业务动作。
 */
@SuppressLint("PrivateApi")
@Suppress("DEPRECATION")
class AndroidApi(
    internal val callerPackage: String = defaultCallerPackage(),
    private val serviceContext: Context? = null,
) {
    private val sdk = Build.VERSION.SDK_INT
    private val temporaryWifiNetworkLock = Any()
    private var activeTemporaryWifiNetworkRequest: TemporaryWifiNetworkRequest? = null

    fun execute(request: AndroidApiRequest): AndroidApiResponse =
        runCatching {
            when (request.action) {
                AndroidApiAction.WIFI_IS_ENABLED -> booleanResponse(isWifiEnabled())
                AndroidApiAction.WIFI_SET_ENABLED -> {
                    setWifiEnabled(request.arguments.getBoolean(AndroidApiKeys.ENABLED))
                    AndroidApiResponse.success()
                }
                AndroidApiAction.WIFI_GET_SCAN_RESULTS -> {
                    val results = ArrayList(getScanResults())
                    AndroidApiResponse.success(Bundle().apply {
                        putParcelableArrayList(AndroidApiKeys.SCAN_RESULTS, results)
                    })
                }
                AndroidApiAction.WIFI_GET_SAVED_LIST -> AndroidApiResponse.success(Bundle().apply {
                    putByteArray(AndroidApiKeys.SAVED_WIFI_LIST_BYTES, getSavedWifiListBytes())
                })
                AndroidApiAction.WIFI_UPDATE_CONFIG -> {
                    val networkId = request.arguments.getInt(AndroidApiKeys.NETWORK_ID)
                    val patchBytes = request.arguments.getByteArray(AndroidApiKeys.PATCH_BYTES)
                        ?: throw IllegalArgumentException("缺少 WifiConfigPatch 数据")
                    booleanResponse(updateWifiConfig(networkId, patchBytes))
                }
                AndroidApiAction.WIFI_DISCONNECT_CURRENT -> {
                    val networkId = request.arguments.getInt(AndroidApiKeys.NETWORK_ID)
                    booleanResponse(disconnectCurrentNetwork(networkId))
                }
                else -> throw IllegalArgumentException("未知 AndroidApi action：${request.action}")
            }
        }.getOrElse(AndroidApiResponse::failure)


    fun getScanResults(): List<ScanResult> = androidBusinessCall(
        operation = "获取扫描的 Wi-Fi 列表",
        successMessage = { results ->
            "获取扫描的 Wi-Fi 列表成功：count=${results.size}"
        },
    ) {
        getScanResultsInternal()
    }

    fun getConnectionInfo(): WifiInfo = androidBusinessCall(
        operation = "获取当前 Wi-Fi 连接信息",
        successMessage = { info ->
            "获取当前 Wi-Fi 连接信息成功：networkId=${info.networkId} bssid=${info.bssid}"
        },
    ) {
        getConnectionInfoInternal()
    }

    private fun getConnectionInfoInternal(): WifiInfo {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        val raw = systemApi(apiName = "getConnectionInfo") {
            when {
                sdk >= 30 -> clazz.getMethod(
                    "getConnectionInfo",
                    String::class.java,
                    String::class.java,
                ).invoke(wifiService, callerPackage, null)

                sdk >= 28 -> clazz.getMethod(
                    "getConnectionInfo",
                    String::class.java,
                ).invoke(wifiService, callerPackage)

                else -> clazz.getMethod("getConnectionInfo").invoke(wifiService)
            }
        }
        return raw as? WifiInfo
            ?: throw IllegalStateException("getConnectionInfo 返回类型不是 WifiInfo：${raw?.javaClass?.name}")
    }

    private fun getScanResultsInternal(): List<ScanResult> {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val raw = systemApi(
            apiName = "getScanResults",
        ) {
            when {
                sdk >= 35 -> clazz.getMethod(
                    "getScanResults",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, callerPackage, null)

                sdk >= 30 -> clazz.getMethod(
                    "getScanResults",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, callerPackage, null)

                else -> clazz.getMethod(
                    "getScanResults",
                    String::class.java
                ).invoke(wifiService, callerPackage)
            }
        } ?: throw IllegalStateException("getScanResults 返回 null")

        return parseScanResultList(raw, "getScanResults")
    }

    fun getSavedWifiListBytes(): ByteArray {
        val list = getSavedWifiList()
        val parcel = Parcel.obtain()
        return try {
            parcel.writeTypedList(list)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun getSavedWifiList(): List<WifiConfiguration> = androidBusinessCall(
        operation = "获取已保存的 Wi-Fi 列表",
        successMessage = { networks ->
            "获取已保存的 Wi-Fi 列表成功：count=${networks.size}"
        },
    ) {
        getSavedWifiListInternal()
    }

    private fun getSavedWifiListInternal(): List<WifiConfiguration> {
        val raw = try {
            queryPrivilegedConfiguredNetworks()
        } catch (_: SecurityException) {
            return getSavedWifiListFallback()
        }

        val list = parseWifiConfigurationList(
            raw = raw ?: throw IllegalStateException("getPrivilegedConfiguredNetworks 返回 null"),
            apiName = "getPrivilegedConfiguredNetworks"
        )

        return list.distinctBy { it.networkId }
    }

    @SuppressLint("NewApi")
    private fun queryPrivilegedConfiguredNetworks(): Any? {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val callerUser = when (Process.myUid()) {
            0 -> "root"
            1000 -> "system"
            else -> "shell"
        }

        return systemApi(
            apiName = "getPrivilegedConfiguredNetworks",
        ) {
            when {
                sdk >= 33 -> {
                    val attrSource = AttributionSource::class.java
                        .getConstructor(
                            Int::class.java,
                            String::class.java,
                            String::class.java,
                            Set::class.java,
                            AttributionSource::class.java
                        )
                        .newInstance(Process.myUid(), callerPackage, callerPackage, null, null)

                    val bundle = Bundle().apply {
                        putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE", attrSource as Parcelable)
                    }

                    clazz.getMethod(
                        "getPrivilegedConfiguredNetworks",
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(wifiService, callerUser, callerPackage, bundle)
                }

                sdk >= 30 -> clazz.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, callerPackage, null)

                sdk >= 28 -> clazz.getMethod(
                    "getPrivilegedConfiguredNetworks",
                    String::class.java
                ).invoke(wifiService, callerPackage)

                else -> clazz.getMethod("getPrivilegedConfiguredNetworks").invoke(wifiService)
            }
        }
    }

    private fun getSavedWifiListFallback(): List<WifiConfiguration> {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val raw = systemApi(
            apiName = "getConfiguredNetworks",
        ) {
            when {
                sdk >= 30 -> clazz.getMethod(
                    "getConfiguredNetworks",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, callerPackage, null)

                sdk >= 28 -> clazz.getMethod(
                    "getConfiguredNetworks",
                    String::class.java
                ).invoke(wifiService, callerPackage)

                else -> clazz.getMethod("getConfiguredNetworks").invoke(wifiService)
            }
        } ?: throw IllegalStateException("getConfiguredNetworks 返回 null")

        val list = parseWifiConfigurationList(raw, "getConfiguredNetworks")
        return list.distinctBy { it.networkId }
    }

    fun updateWifiConfig(networkId: Int, patchBytes: ByteArray): Boolean =
        androidBusinessCall(
            operation = "更新 Wi-Fi 配置",
            details = "networkId=$networkId",
            successMessage = {
                "更新 Wi-Fi 配置成功：networkId=$networkId"
            },
        ) {
            updateWifiConfigInternal(networkId, patchBytes)
        }

    private fun updateWifiConfigInternal(networkId: Int, patchBytes: ByteArray): Boolean {
        val patch = readWifiConfigPatch(patchBytes)

        patch.enabled?.let { enabled ->
            setNetworkEnabledInternal(networkId, enabled, disableOthers = false)
        }

        patch.autoJoin?.let { autoJoin -> setWifiNetworkAutoJoinInternal(networkId, autoJoin) }

        return true
    }

    fun disconnectCurrentNetwork(networkId: Int): Boolean = androidBusinessCall(
        operation = "断开当前 Wi-Fi 并禁用配置",
        details = "networkId=$networkId",
        successMessage = {
            "断开当前 Wi-Fi 并禁用配置成功：networkId=$networkId"
        },
    ) {
        disconnectCurrentNetworkInternal(networkId)
    }

    private fun disconnectCurrentNetworkInternal(networkId: Int): Boolean {
        require(networkId >= 0) { "当前连接没有有效的 networkId" }
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        val result = systemApi(apiName = "disconnect") {
            if (sdk >= 28) {
                clazz.getMethod("disconnect", String::class.java)
                    .invoke(wifiService, callerPackage)
            } else {
                clazz.getMethod("disconnect").invoke(wifiService)
            }
        }
        requireBooleanSuccess("disconnect", result)
        setNetworkEnabledInternal(networkId, enabled = false, disableOthers = false)
        return true
    }

    fun connectWifiByNetworkId(networkId: Int): Boolean = androidBusinessCall(
        operation = "发送 enableNetwork 指令",
        details = "networkId=$networkId",
        successMessage = {
            "enableNetwork 指令发送成功：networkId=$networkId"
        },
    ) {
        require(networkId >= 0) { "networkId 必须大于等于 0" }
        setNetworkEnabledInternal(networkId, enabled = true, disableOthers = true)
        true
    }

    private fun setNetworkEnabledInternal(
        networkId: Int,
        enabled: Boolean,
        disableOthers: Boolean,
    ) {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        val apiName = if (enabled) "enableNetwork" else "disableNetwork"
        val result = systemApi(apiName = apiName) {
            if (enabled) {
                if (sdk >= 29) {
                    clazz.getMethod(
                        "enableNetwork",
                        Int::class.java,
                        Boolean::class.java,
                        String::class.java,
                    ).invoke(wifiService, networkId, disableOthers, callerPackage)
                } else {
                    clazz.getMethod(
                        "enableNetwork",
                        Int::class.java,
                        Boolean::class.java,
                    ).invoke(wifiService, networkId, disableOthers)
                }
            } else {
                if (sdk >= 29) {
                    clazz.getMethod(
                        "disableNetwork",
                        Int::class.java,
                        String::class.java,
                    ).invoke(wifiService, networkId, callerPackage)
                } else {
                    clazz.getMethod(
                        "disableNetwork",
                        Int::class.java,
                    ).invoke(wifiService, networkId)
                }
            }
        }
        requireBooleanSuccess(apiName, result)
    }

    fun isWifiEnabled(): Boolean = androidBusinessCall(
        operation = "查询 Wi-Fi 开关状态",
        successMessage = { enabled ->
            "查询 Wi-Fi 开关状态成功：enabled=$enabled"
        },
    ) {
        isWifiEnabledInternal()
    }

    private fun isWifiEnabledInternal(): Boolean {
        val wifiService = getWifiService()
        val state = systemApi(
            apiName = "getWifiEnabledState",
        ) {
            wifiService::class.java
                .getMethod("getWifiEnabledState")
                .invoke(wifiService)
        } as? Int ?: throw IllegalStateException("getWifiEnabledState 返回类型不是 Int")

        return state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
    }

    fun setWifiEnabled(enabled: Boolean) = androidBusinessCall(
        operation = "设置 Wi-Fi 开关",
        details = "enabled=$enabled",
        successMessage = {
            "设置 Wi-Fi 开关成功：enabled=$enabled"
        },
    ) {
        setWifiEnabledInternal(enabled)
    }

    private fun setWifiEnabledInternal(enabled: Boolean) {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val result = systemApi(
            apiName = "setWifiEnabled",
        ) {
            if (sdk >= 29) {
                clazz.getMethod(
                    "setWifiEnabled",
                    String::class.java,
                    Boolean::class.java
                ).invoke(wifiService, callerPackage, enabled)
            } else {
                clazz.getMethod(
                    "setWifiEnabled",
                    Boolean::class.java
                ).invoke(wifiService, enabled)
            }
        }

        requireBooleanSuccess("setWifiEnabled", result)
    }

    fun saveWifiNetwork(ssid: String, password: String): Int = androidBusinessCall(
        operation = "保存 WPA 网络配置并关闭自动加入",
        details = "ssid=$ssid",
        successMessage = { networkId ->
            "保存 WPA 网络配置并关闭自动加入成功：ssid=$ssid networkId=$networkId"
        },
    ) {
        require(ssid.isNotBlank()) { "SSID 不能为空" }
        val quotedSsid = quoteWifiValue(ssid)
        val existing = getSavedWifiListInternal().firstOrNull { it.SSID == quotedSsid }
        val config = existing ?: WifiConfiguration().apply {
            SSID = quotedSsid
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        config.preSharedKey = if (password.length == 64 && password.all { it.isHexDigit() }) {
            password
        } else {
            quoteWifiValue(password)
        }
        addOrUpdateWifiNetworkInternal(config).also { networkId ->
            setWifiNetworkAutoJoinInternal(networkId, false)
        }
    }

    internal fun requestTemporaryWifiNetwork(
        ssid: String,
        password: String,
    ): TemporaryWifiNetworkRequest = androidBusinessCall(
        operation = "准备测试连通性 Wi-Fi 配置",
        details = "ssid=$ssid",
        successMessage = { request ->
            "准备测试连通性 Wi-Fi 配置成功：ssid=$ssid " +
                "networkId=${request.networkId} " +
                "removedExistingConfiguration=${request.removedExistingConfiguration}"
        },
    ) {
        require(ssid.isNotBlank()) { "SSID 不能为空" }
        synchronized(temporaryWifiNetworkLock) {
            check(activeTemporaryWifiNetworkRequest == null) {
                "已有测试连通性 Wi-Fi 配置正在使用"
            }

            val quotedSsid = quoteWifiValue(ssid)
            val existingConfiguration = getSavedWifiListInternal()
                .firstOrNull { it.SSID == quotedSsid }
            existingConfiguration?.let { existing ->
                check(removeWifiNetworkInternal(existing.networkId)) {
                    "删除原 Wi-Fi 配置失败：networkId=${existing.networkId}"
                }
            }
            val testConfiguration = WifiConfiguration().apply { SSID = quotedSsid }
            applyTestCredentials(testConfiguration, password)

            var testNetworkId: Int? = null
            try {
                val configuredNetworkId = addOrUpdateWifiNetworkInternal(testConfiguration)
                testNetworkId = configuredNetworkId
                setNetworkEnabledInternal(
                    networkId = configuredNetworkId,
                    enabled = true,
                    disableOthers = true,
                )

                lateinit var request: TemporaryWifiNetworkRequest
                request = TemporaryWifiNetworkRequest(
                    networkId = configuredNetworkId,
                    removedExistingConfiguration = existingConfiguration != null,
                ) {
                    try {
                        removeTemporaryWifiNetwork(configuredNetworkId)
                    } finally {
                        synchronized(temporaryWifiNetworkLock) {
                            if (activeTemporaryWifiNetworkRequest === request) {
                                activeTemporaryWifiNetworkRequest = null
                            }
                        }
                    }
                }
                activeTemporaryWifiNetworkRequest = request
                request
            } catch (error: Throwable) {
                testNetworkId?.let { networkId ->
                    runCatching {
                        removeTemporaryWifiNetwork(networkId)
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        }
    }

    fun close() {
        val request = synchronized(temporaryWifiNetworkLock) {
            activeTemporaryWifiNetworkRequest
        }
        request?.close()
    }

    fun disconnectWifi(): Boolean = androidBusinessCall(
        operation = "断开当前 Wi-Fi",
        successMessage = { "断开当前 Wi-Fi 成功" },
    ) {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        val result = systemApi(apiName = "disconnect") {
            if (sdk >= 28) {
                clazz.getMethod("disconnect", String::class.java)
                    .invoke(wifiService, callerPackage)
            } else {
                clazz.getMethod("disconnect").invoke(wifiService)
            }
        }
        requireBooleanSuccess("disconnect", result)
        true
    }

    fun removeWifiNetwork(networkId: Int): Boolean = androidBusinessCall(
        operation = "移除 Wi-Fi 配置",
        details = "networkId=$networkId",
        successMessage = { "移除 Wi-Fi 配置成功：networkId=$networkId" },
    ) {
        require(networkId >= 0) { "networkId 必须大于等于 0" }
        check(removeWifiNetworkInternal(networkId)) {
            "removeNetwork 返回 false：networkId=$networkId"
        }
        true
    }

    private fun removeWifiNetworkInternal(networkId: Int): Boolean {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        val result = systemApi(apiName = "removeNetwork") {
            if (sdk >= 28) {
                clazz.getMethod(
                    "removeNetwork",
                    Int::class.java,
                    String::class.java,
                ).invoke(wifiService, networkId, callerPackage)
            } else {
                clazz.getMethod(
                    "removeNetwork",
                    Int::class.java,
                ).invoke(wifiService, networkId)
            }
        }
        return result !is Boolean || result
    }

    fun setWifiNetworkAutoJoin(networkId: Int, enabled: Boolean): Boolean = androidBusinessCall(
        operation = "设置 Wi-Fi 自动加入",
        details = "networkId=$networkId enabled=$enabled",
        successMessage = {
            "设置 Wi-Fi 自动加入成功：networkId=$networkId enabled=$enabled"
        },
    ) {
        setWifiNetworkAutoJoinInternal(networkId, enabled)
        true
    }

    private fun addOrUpdateWifiNetworkInternal(config: WifiConfiguration): Int {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        val result = systemApi(apiName = "addOrUpdateNetwork") {
            when {
                sdk >= 33 -> clazz.getMethod(
                    "addOrUpdateNetwork",
                    WifiConfiguration::class.java,
                    String::class.java,
                    Bundle::class.java,
                ).invoke(wifiService, config, callerPackage, Bundle())

                sdk >= 28 -> clazz.getMethod(
                    "addOrUpdateNetwork",
                    WifiConfiguration::class.java,
                    String::class.java,
                ).invoke(wifiService, config, callerPackage)

                else -> clazz.getMethod(
                    "addOrUpdateNetwork",
                    WifiConfiguration::class.java,
                ).invoke(wifiService, config)
            }
        }
        return (result as? Int)
            ?.also { require(it >= 0) { "addOrUpdateNetwork 返回 $it" } }
            ?: throw IllegalStateException("addOrUpdateNetwork 返回类型不是 Int：${result?.javaClass?.name}")
    }

    private fun setWifiNetworkAutoJoinInternal(networkId: Int, enabled: Boolean) {
        require(networkId >= 0) { "networkId 必须大于等于 0" }
        val wifiService = getWifiService()
        val clazz = wifiService::class.java
        if (sdk >= 30) {
            systemApi(apiName = "allowAutojoin") {
                clazz.getMethod(
                    "allowAutojoin",
                    Int::class.java,
                    Boolean::class.java,
                ).invoke(wifiService, networkId, enabled)
            }
            return
        }
        val config = getSavedWifiListInternal()
            .firstOrNull { it.networkId == networkId }
            ?: throw IllegalArgumentException("找不到 networkId=$networkId 的 Wi-Fi 配置")
        systemApi(apiName = "WifiConfiguration.allowAutojoin.setBoolean") {
            WifiConfiguration::class.java.getField("allowAutojoin").setBoolean(config, enabled)
        }
        val result = systemApi(apiName = "updateNetwork") {
            clazz.getMethod("updateNetwork", WifiConfiguration::class.java)
                .invoke(wifiService, config)
        }
        requireNonNegativeInt("updateNetwork", result)
    }

    private fun removeTemporaryWifiNetwork(
        testNetworkId: Int,
    ) = androidBusinessCall(
        operation = "清理测试连通性 Wi-Fi 配置",
        details = "networkId=$testNetworkId",
        successMessage = { "清理测试连通性 Wi-Fi 配置成功：已删除测试配置" },
    ) {
        val failures = mutableListOf<Throwable>()

        runCatching { disconnectWifi() }
            .exceptionOrNull()
            ?.let(failures::add)

        runCatching {
            val testConfigurationStillExists = getSavedWifiListInternal()
                .any { it.networkId == testNetworkId }
            if (testConfigurationStillExists) {
                check(removeWifiNetworkInternal(testNetworkId)) {
                    "removeNetwork 返回 false：networkId=$testNetworkId"
                }
            }
        }.exceptionOrNull()?.let(failures::add)

        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun applyTestCredentials(config: WifiConfiguration, password: String) {
        config.allowedKeyManagement.clear()
        if (password.isEmpty()) {
            config.preSharedKey = null
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            config.preSharedKey = if (password.length == 64 && password.all { it.isHexDigit() }) {
                password
            } else {
                quoteWifiValue(password)
            }
        }
    }

    private fun quoteWifiValue(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun readWifiConfigPatch(patchBytes: ByteArray): WifiConfigPatch {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(patchBytes, 0, patchBytes.size)
            parcel.setDataPosition(0)
            parcel.readParcelable<WifiConfigPatch>(WifiConfigPatch::class.java.classLoader)
                ?: throw IllegalArgumentException("patchBytes 无法解析为 WifiConfigPatch")
        } finally {
            parcel.recycle()
        }
    }

    private fun parseWifiConfigurationList(raw: Any, apiName: String): List<WifiConfiguration> {
        val rawList = when (raw) {
            is List<*> -> raw
            else -> {
                val list = systemApi(
                    apiName = "$apiName.getList",
                ) {
                    raw.javaClass.getMethod("getList").invoke(raw)
                }
                list as? List<*>
                    ?: throw IllegalStateException("$apiName.getList 返回类型不是 List：${list?.javaClass?.name}")
            }
        }

        return rawList.mapIndexed { index, item ->
            item as? WifiConfiguration
                ?: throw IllegalStateException("$apiName 返回第 $index 项不是 WifiConfiguration：$item")
        }
    }

    private fun parseScanResultList(raw: Any, apiName: String): List<ScanResult> {
        val rawList = when (raw) {
            is List<*> -> raw
            else -> {
                val list = systemApi(
                    apiName = "$apiName.getList",
                ) {
                    raw.javaClass.getMethod("getList").invoke(raw)
                }
                list as? List<*>
                    ?: throw IllegalStateException("$apiName.getList 返回类型不是 List：${list?.javaClass?.name}")
            }
        }

        return rawList.mapIndexedNotNull { index, item ->
            val result = item as? ScanResult
                ?: throw IllegalStateException("$apiName 返回第 $index 项不是 ScanResult：$item")
            if (result.BSSID.isNullOrBlank()) {
                null
            } else {
                result
            }
        }
    }

    private fun getWifiService(): Any {
        val binder = systemApi(
            apiName = "ServiceManager.getService",
        ) {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "wifi")
        } as? IBinder
            ?: throw IllegalStateException("Wifi service 不存在")

        return systemApi(
            apiName = "IWifiManager.Stub.asInterface",
        ) {
            Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }
            ?: throw IllegalStateException("IWifiManager.asInterface 返回 null")
    }

    private inline fun <T> systemApi(
        apiName: String,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (e: InvocationTargetException) {
            throw unwrapSystemApiError(apiName, e)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("$apiName 反射调用失败", e)
        }
    }

    private fun unwrapSystemApiError(apiName: String, e: InvocationTargetException): Throwable {
        val target = e.targetException ?: e
        return when (target) {
            is RuntimeException -> target
            is Error -> target
            else -> IllegalStateException("$apiName 调用失败", target)
        }
    }

    private fun requireBooleanSuccess(apiName: String, result: Any?) {
        if (result is Boolean && !result) throw IllegalStateException("$apiName 返回 false")
    }

    private fun requireNonNegativeInt(apiName: String, result: Any?) {
        if (result is Int && result < 0) throw IllegalStateException("$apiName 返回 $result")
    }

    private inline fun <T> androidBusinessCall(
        operation: String,
        details: String? = null,
        successMessage: (T) -> String,
        block: () -> T,
    ): T {
        return try {
            block().also { result ->
                Log.d(TAG, successMessage(result))
            }
        } catch (error: Throwable) {
            val message = if (details == null) {
                "$operation 失败"
            } else {
                "$operation 失败：$details"
            }
            Log.e(TAG, message, error)
            throw error
        }
    }

    private fun booleanResponse(value: Boolean): AndroidApiResponse =
        AndroidApiResponse.success(Bundle().apply { putBoolean(AndroidApiKeys.RESULT, value) })

    companion object {
        private const val TAG = "AndroidApi"

        private fun defaultCallerPackage(): String = when (Process.myUid()) {
            0, 1000 -> "android"
            else -> "com.android.shell"
        }
    }
}
