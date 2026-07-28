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
) {
    private val sdk = Build.VERSION.SDK_INT

    fun execute(request: AndroidApiRequest): AndroidApiResponse {
        Log.d(
            TAG,
            "收到 AndroidApi 请求：action=${request.action}；" +
                "参数=${describeRequestArguments(request)}",
        )
        return runCatching {
            when (request.action) {
                AndroidApiAction.WIFI_IS_ENABLED -> booleanResponse(isWifiEnabledDirect())
                AndroidApiAction.WIFI_SET_ENABLED -> {
                    setWifiEnabledDirect(request.arguments.getBoolean(AndroidApiKeys.ENABLED))
                    AndroidApiResponse.success()
                }
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
            onSuccess = { response ->
                Log.d(TAG, "AndroidApi 请求完成：action=${request.action}；success=${response.success}")
                response
            },
            onFailure = { error ->
                Log.e(TAG, "AndroidApi 请求失败：action=${request.action}", error)
                AndroidApiResponse.failure(error)
            },
        )
    }


    fun getScanResultsDirect(): List<ScanResult> {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val raw = systemApi(
            apiName = "getScanResults",
            operation = "读取当前 Wi-Fi 扫描结果",
            parameters = if (sdk >= 30) {
                "signature=(String,String), callerPackage=$callerPackage, featureId=null"
            } else {
                "signature=(String), callerPackage=$callerPackage"
            },
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

        return parseScanResultList(raw, "getScanResults").also { results ->
            Log.d(TAG, "Wi-Fi 扫描结果解析完成：有效结果数量=${results.size}")
        }
    }

    fun getSavedWifiListBytesDirect(): ByteArray {
        val list = getSavedWifiListDirect()
        Log.d(TAG, "序列化已保存 Wi-Fi 列表：配置数量=${list.size}")
        val parcel = Parcel.obtain()
        return try {
            parcel.writeTypedList(list)
            parcel.marshall().also { bytes ->
                Log.d(TAG, "已保存 Wi-Fi 列表序列化完成：字节数=${bytes.size}")
            }
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

        return list.distinctBy { it.networkId }.also { distinctList ->
            Log.d(TAG, "受保护 Wi-Fi 配置读取完成：去重后数量=${distinctList.size}")
        }
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
            operation = "读取包含受保护字段的已保存 Wi-Fi 配置",
            parameters = when {
                sdk >= 33 -> "signature=(String,String,Bundle), user=$callerUser, " +
                    "callerPackage=$callerPackage, attributionUid=${Process.myUid()}"
                sdk >= 30 -> "signature=(String,String), callerPackage=$callerPackage, featureId=null"
                sdk >= 28 -> "signature=(String), callerPackage=$callerPackage"
                else -> "signature=(), 无参数"
            },
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
            operation = "读取普通已保存 Wi-Fi 配置列表",
            parameters = when {
                sdk >= 30 -> "signature=(String,String), callerPackage=$callerPackage, featureId=null"
                sdk >= 28 -> "signature=(String), callerPackage=$callerPackage"
                else -> "signature=(), 无参数"
            },
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
        return list.distinctBy { it.networkId }.also { distinctList ->
            Log.d(TAG, "普通 Wi-Fi 配置读取完成：去重后数量=${distinctList.size}")
        }
    }

    fun updateWifiConfigDirect(networkId: Int, patchBytes: ByteArray): Boolean {
        val patch = readWifiConfigPatch(patchBytes)
        Log.d(
            TAG,
            "更新 Wi-Fi 配置：networkId=$networkId, patchBytes=${patchBytes.size}字节, " +
                "enabled=${patch.enabled}, autoJoin=${patch.autoJoin}",
        )

        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        patch.enabled?.let { enabled ->
            val result = systemApi(
                apiName = if (enabled) "enableNetwork" else "disableNetwork",
                operation = if (enabled) "启用指定 Wi-Fi 配置" else "停用指定 Wi-Fi 配置",
                parameters = if (enabled) {
                    if (sdk >= 29) {
                        "signature=(Int,Boolean,String), networkId=$networkId, " +
                            "disableOthers=false, callerPackage=$callerPackage"
                    } else {
                        "signature=(Int,Boolean), networkId=$networkId, disableOthers=false"
                    }
                } else {
                    if (sdk >= 29) {
                        "signature=(Int,String), networkId=$networkId, callerPackage=$callerPackage"
                    } else {
                        "signature=(Int), networkId=$networkId"
                    }
                },
            ) {
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
                systemApi(
                    apiName = "allowAutojoin",
                    operation = "设置指定 Wi-Fi 配置是否允许自动连接",
                    parameters = "signature=(Int,Boolean), networkId=$networkId, allow=$autoJoin",
                ) {
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

                systemApi(
                    apiName = "WifiConfiguration.allowAutojoin.setBoolean",
                    operation = "在旧版系统配置对象中写入自动连接字段",
                    parameters = "field=allowAutojoin, networkId=$networkId, value=$autoJoin",
                ) {
                    WifiConfiguration::class.java
                        .getField("allowAutojoin")
                        .setBoolean(config, autoJoin)
                }

                val result = systemApi(
                    apiName = "updateNetwork",
                    operation = "写回修改过自动连接字段的 Wi-Fi 配置",
                    parameters = "signature=(WifiConfiguration), networkId=$networkId, " +
                        "allowAutojoin=$autoJoin",
                ) {
                    clazz.getMethod(
                        "updateNetwork",
                        WifiConfiguration::class.java
                    ).invoke(wifiService, config)
                }

                requireNonNegativeInt("updateNetwork", result)
            }
        }

        Log.d(TAG, "Wi-Fi 配置更新完成：networkId=$networkId")
        return true
    }

    fun isWifiEnabledDirect(): Boolean {
        val wifiService = getWifiService()
        val state = systemApi(
            apiName = "getWifiEnabledState",
            operation = "读取 Wi-Fi 开关状态",
            parameters = "signature=(), 无参数",
        ) {
            wifiService::class.java
                .getMethod("getWifiEnabledState")
                .invoke(wifiService)
        } as? Int ?: throw IllegalStateException("getWifiEnabledState 返回类型不是 Int")

        return state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
    }

    fun setWifiEnabledDirect(enabled: Boolean) {
        val wifiService = getWifiService()
        val clazz = wifiService::class.java

        val result = systemApi(
            apiName = "setWifiEnabled",
            operation = if (enabled) "打开 Wi-Fi" else "关闭 Wi-Fi",
            parameters = if (sdk >= 29) {
                "signature=(String,Boolean), callerPackage=$callerPackage, enabled=$enabled"
            } else {
                "signature=(Boolean), enabled=$enabled"
            },
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
                    operation = "从系统返回的列表包装对象中取出 Wi-Fi 配置列表",
                    parameters = "signature=(), wrapperType=${raw.javaClass.name}",
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
                    operation = "从系统返回的列表包装对象中取出扫描结果列表",
                    parameters = "signature=(), wrapperType=${raw.javaClass.name}",
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
                Log.w(TAG, "$apiName 丢弃第 $index 项：BSSID 为空")
                null
            } else {
                result
            }
        }
    }

    private fun getWifiService(): Any {
        val binder = systemApi(
            apiName = "ServiceManager.getService",
            operation = "取得系统 Wi-Fi Binder 服务",
            parameters = "signature=(String), name=wifi",
        ) {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "wifi")
        } as? IBinder
            ?: throw IllegalStateException("Wifi service 不存在")

        return systemApi(
            apiName = "IWifiManager.Stub.asInterface",
            operation = "把 Wi-Fi Binder 转换为 IWifiManager 接口",
            parameters = "signature=(IBinder), binderType=${binder.javaClass.name}, " +
                "binderAlive=${binder.isBinderAlive}",
        ) {
            Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }
            ?: throw IllegalStateException("IWifiManager.asInterface 返回 null")
    }

    private inline fun <T> systemApi(
        apiName: String,
        operation: String,
        parameters: String,
        block: () -> T,
    ): T {
        Log.d(
            TAG,
            "调用系统 API：api=$apiName；操作=$operation；参数=$parameters；sdk=$sdk",
        )
        return try {
            block().also { result ->
                Log.d(TAG, "系统 API 调用完成：api=$apiName；结果=${describeSystemApiResult(result)}")
            }
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

    private fun describeRequestArguments(request: AndroidApiRequest): String =
        when (request.action) {
            AndroidApiAction.WIFI_SET_ENABLED ->
                "enabled=${request.arguments.getBoolean(AndroidApiKeys.ENABLED)}"
            AndroidApiAction.WIFI_UPDATE_CONFIG -> {
                val patchSize = request.arguments.getByteArray(AndroidApiKeys.PATCH_BYTES)?.size
                "networkId=${request.arguments.getInt(AndroidApiKeys.NETWORK_ID)}, " +
                    "patchBytes=${patchSize?.let { "$it 字节" } ?: "null"}"
            }
            AndroidApiAction.WIFI_IS_ENABLED,
            AndroidApiAction.WIFI_GET_SCAN_RESULTS,
            AndroidApiAction.WIFI_GET_SAVED_LIST,
            -> "无参数"
            else -> "keys=${request.arguments.keySet().sorted()}"
        }

    private fun describeSystemApiResult(result: Any?): String = when (result) {
        null -> "null"
        is Boolean -> "Boolean($result)"
        is Number -> "${result.javaClass.simpleName}($result)"
        is List<*> -> "List(size=${result.size})"
        is IBinder -> "IBinder(type=${result.javaClass.name}, alive=${result.isBinderAlive})"
        else -> "type=${result.javaClass.name}"
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
