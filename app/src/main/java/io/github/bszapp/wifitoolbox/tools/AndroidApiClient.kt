@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.tools

import android.net.wifi.ScanResult
import android.os.Bundle
import android.os.Parcel
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiAction
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiKeys
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.service.IMainService

/** App 侧 AndroidApi 命令调用器；所有失败都会先送入 App 的统一错误广播源。 */
class AndroidApiClient(
    private val service: IMainService,
    private val onError: (operation: String, error: Throwable) -> Unit,
) {

    fun isWifiEnabled(): Boolean = invoke("查询 Wi-Fi 开关状态") {
        execute(AndroidApiAction.WIFI_IS_ENABLED)
            .requireSuccess()
            .getBoolean(AndroidApiKeys.RESULT)
    }

    fun setWifiEnabled(enabled: Boolean) {
        invoke("设置 Wi-Fi 开关为 $enabled") {
            execute(
                AndroidApiAction.WIFI_SET_ENABLED,
                Bundle().apply { putBoolean(AndroidApiKeys.ENABLED, enabled) },
            ).requireSuccess()
            Unit
        }
    }

    fun getScanResults(): List<ScanResult> = invoke("读取 Wi-Fi 扫描结果") {
        val data = execute(AndroidApiAction.WIFI_GET_SCAN_RESULTS).requireSuccess()
        data.classLoader = ScanResult::class.java.classLoader
        data.getParcelableArrayList<ScanResult>(AndroidApiKeys.SCAN_RESULTS).orEmpty()
    }

    fun getSavedWifiList(): ByteArray = invoke("读取已保存 Wi-Fi 列表") {
        execute(AndroidApiAction.WIFI_GET_SAVED_LIST)
            .requireSuccess()
            .getByteArray(AndroidApiKeys.SAVED_WIFI_LIST_BYTES)
            ?: throw IllegalStateException("服务未返回 Wi-Fi 配置列表数据")
    }

    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) =
        invoke("更新 Wi-Fi 配置 networkId=$networkId") {
            val patchBytes = encodePatch(patch)
            val updated = execute(
                AndroidApiAction.WIFI_UPDATE_CONFIG,
                Bundle().apply {
                    putInt(AndroidApiKeys.NETWORK_ID, networkId)
                    putByteArray(AndroidApiKeys.PATCH_BYTES, patchBytes)
                },
            ).requireSuccess().getBoolean(AndroidApiKeys.RESULT)

            if (!updated) {
                throw IllegalStateException("系统拒绝更新 networkId=$networkId")
            }
        }

    fun disconnectCurrentNetwork(networkId: Int) =
        invoke("断开当前 Wi-Fi networkId=$networkId") {
            val disconnected = execute(
                AndroidApiAction.WIFI_DISCONNECT_CURRENT,
                Bundle().apply { putInt(AndroidApiKeys.NETWORK_ID, networkId) },
            ).requireSuccess().getBoolean(AndroidApiKeys.RESULT)

            if (!disconnected) {
                throw IllegalStateException("系统拒绝断开 networkId=$networkId")
            }
        }

    private inline fun <T> invoke(operation: String, block: () -> T): T {
        return try {
            block()
        } catch (error: Throwable) {
            onError(operation, error)
            throw error
        }
    }

    private fun execute(
        action: String,
        arguments: Bundle = Bundle.EMPTY,
    ): AndroidApiResponse = service.executeAndroidApi(
        AndroidApiRequest(action, arguments),
    )

    private fun encodePatch(patch: WifiConfigPatch): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(patch, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }
}
