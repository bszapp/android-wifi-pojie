@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.os.WorkSource
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
    private val callerPackage: String = defaultCallerPackage(),
) {
    private val sdk = Build.VERSION.SDK_INT

    fun execute(request: AndroidApiRequest): AndroidApiResponse {
        return runCatching {
            when (request.action) {
                AndroidApiAction.WIFI_IS_ENABLED -> booleanResponse(isWifiEnabledDirect())
                AndroidApiAction.WIFI_SET_ENABLED -> {
                    setWifiEnabledDirect(request.arguments.getBoolean(AndroidApiKeys.ENABLED))
                    AndroidApiResponse.success()
                }
                AndroidApiAction.WIFI_START_SCAN -> booleanResponse(startScanDirect())
                AndroidApiAction.WIFI_GET_SCAN_RESULTS -> {
                    val results = ArrayList(getScanResultsDirect())
                    AndroidApiResponse.success(Bundle().apply {
                        putParcelableArrayList(AndroidApiKeys.SCAN_RESULTS, results)
                    })
                }
                AndroidApiAction.WIFI_GET_SAVED_LIST -> AndroidApiResponse.success(Bundle().apply {
                    putByteArray(AndroidApiKeys.SAVED_WIFI_LIST_BYTES, getSavedWifiListBytesDirect())
                })
                AndroidApiAction.WIFI_UPDATE_CONFIG -> {
                    val networkId = request.arguments.getInt(AndroidApiKeys.NETWORK_ID)
                    val patchBytes = request.arguments.getByteArray(AndroidApiKeys.PATCH_BYTES)
                        ?: throw IllegalArgumentException("缺少 WifiConfigPatch 数据")
                    booleanResponse(updateWifiConfigDirect(networkId, patchBytes))
                }
                else -> throw IllegalArgumentException("未知 AndroidApi action：${request.action}")
            }
        }.fold(
            onSuccess = { it },
            onFailure = { AndroidApiResponse.failure(it) }
        )
    }

    fun startScanDirect(): Boolean {
        Log.d(TAG, "startScan() | SDK=$sdk | uid=${Process.myUid()}")

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val result = systemApi("startScan") {
            when {
                sdk >= 30 -> clazz.getMethod(
                    "startScan",
                    String::class.java,
                    String::class.java
                ).invoke(wifiService, callerPackage, null)

                sdk >= 28 -> clazz.getMethod(
                    "startScan",
                    String::class.java
                ).invoke(wifiService, callerPackage)

                else -> {
                    val scanSettings = Class.forName("android.net.wifi.ScanSettings")
                    if (sdk >= 26) {
                        clazz.getMethod(
                            "startScan",
                            scanSettings,
                            WorkSource::class.java,
                            String::class.java
                        ).invoke(wifiService, null, null, callerPackage)
                    } else {
                        clazz.getMethod(
                            "startScan",
                            scanSettings,
                            WorkSource::class.java
                        ).invoke(wifiService, null, null)
                    }
                }
            }
        }

        requireBooleanSuccess("startScan", result)
        return true
    }

    fun getScanResultsDirect(): List<ScanResult> {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val raw = systemApi("getScanResults") {
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

    fun getSavedWifiListBytesDirect(): ByteArray {
        val list = getSavedWifiListDirect()
        val parcel = Parcel.obtain()
        return try {
            parcel.writeTypedList(list)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun getSavedWifiListDirect(): List<WifiConfiguration> {
        val raw = try {
            queryPrivilegedConfiguredNetworks()
        } catch (e: SecurityException) {
            Log.w(TAG, "getPrivilegedConfiguredNetworks 被安全策略拒绝，回退到普通配置列表（不含密码）: ${e.message}")
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

        return systemApi("getPrivilegedConfiguredNetworks") {
            when {
                sdk >= 33 -> {
                    val user = when (Process.myUid()) {
                        0 -> "root"
                        1000 -> "system"
                        else -> "shell"
                    }

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
                    ).invoke(wifiService, user, callerPackage, bundle)
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

        val raw = systemApi("getConfiguredNetworks") {
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

    fun updateWifiConfigDirect(networkId: Int, patchBytes: ByteArray): Boolean {
        val patch = readWifiConfigPatch(patchBytes)

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        patch.enabled?.let { enabled ->
            val result = systemApi(if (enabled) "enableNetwork" else "disableNetwork") {
                if (enabled) {
                    if (sdk >= 29) {
                        clazz.getMethod(
                            "enableNetwork",
                            Int::class.java,
                            Boolean::class.java,
                            String::class.java
                        ).invoke(wifiService, networkId, false, callerPackage)
                    } else {
                        clazz.getMethod(
                            "enableNetwork",
                            Int::class.java,
                            Boolean::class.java
                        ).invoke(wifiService, networkId, false)
                    }
                } else {
                    if (sdk >= 29) {
                        clazz.getMethod(
                            "disableNetwork",
                            Int::class.java,
                            String::class.java
                        ).invoke(wifiService, networkId, callerPackage)
                    } else {
                        clazz.getMethod(
                            "disableNetwork",
                            Int::class.java
                        ).invoke(wifiService, networkId)
                    }
                }
            }

            requireBooleanSuccess(if (enabled) "enableNetwork" else "disableNetwork", result)
        }

        patch.autoJoin?.let { autoJoin ->
            if (sdk >= 30) {
                systemApi("allowAutojoin") {
                    clazz.getMethod(
                        "allowAutojoin",
                        Int::class.java,
                        Boolean::class.java
                    ).invoke(wifiService, networkId, autoJoin)
                }
            } else {
                val config = getSavedWifiListDirect()
                    .firstOrNull { it.networkId == networkId }
                    ?: throw IllegalArgumentException("找不到 networkId=$networkId 的 Wi-Fi 配置")

                WifiConfiguration::class.java
                    .getField("allowAutojoin")
                    .setBoolean(config, autoJoin)

                val result = systemApi("updateNetwork") {
                    clazz.getMethod(
                        "updateNetwork",
                        WifiConfiguration::class.java
                    ).invoke(wifiService, config)
                }

                requireNonNegativeInt("updateNetwork", result)
            }
        }

        return true
    }

    fun isWifiEnabledDirect(): Boolean {
        val wifiService = getWifiService()
        val state = systemApi("getWifiEnabledState") {
            wifiService::class.java
                .getMethod("getWifiEnabledState")
                .invoke(wifiService)
        } as? Int ?: throw IllegalStateException("getWifiEnabledState 返回类型不是 Int")

        return state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
    }

    fun setWifiEnabledDirect(enabled: Boolean) {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val result = systemApi("setWifiEnabled") {
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
                val list = systemApi("$apiName.getList") {
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
                val list = systemApi("$apiName.getList") {
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
                Log.w(TAG, "$apiName 丢弃第 $index 项：BSSID 为空")
                null
            } else {
                result
            }
        }
    }

    private fun getWifiService(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "wifi") as? IBinder
            ?: throw IllegalStateException("Wifi service 不存在")

        return Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
            ?: throw IllegalStateException("IWifiManager.asInterface 返回 null")
    }

    private inline fun <T> systemApi(apiName: String, block: () -> T): T {
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
