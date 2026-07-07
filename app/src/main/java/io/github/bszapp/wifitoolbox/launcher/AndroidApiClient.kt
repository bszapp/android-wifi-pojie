@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.launcher

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiAction
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiKeys
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiRequest
import io.github.bszapp.wifitoolbox.contract.androidapi.AndroidApiResponse
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.service.IMainService

/** App 侧命令调用器：把统一 executeAndroidApi 通道包装成具体业务方法。 */
class AndroidApiClient(private val service: IMainService) {

    fun isWifiEnabled(): Boolean = execute(AndroidApiAction.WIFI_IS_ENABLED)
        .requireSuccess()
        .getBoolean(AndroidApiKeys.RESULT)

    fun setWifiEnabled(enabled: Boolean) {
        execute(
            AndroidApiAction.WIFI_SET_ENABLED,
            Bundle().apply { putBoolean(AndroidApiKeys.ENABLED, enabled) }
        ).requireSuccess()
    }

    fun startScan(): Boolean = execute(AndroidApiAction.WIFI_START_SCAN)
        .requireSuccess()
        .getBoolean(AndroidApiKeys.RESULT)

    fun getScanResults(): List<ScanResult> {
        val data = execute(AndroidApiAction.WIFI_GET_SCAN_RESULTS).requireSuccess()
        data.classLoader = ScanResult::class.java.classLoader
        return data.getParcelableArrayList<ScanResult>(AndroidApiKeys.SCAN_RESULTS).orEmpty()
    }

    fun getSavedWifiList(): ByteArray = execute(AndroidApiAction.WIFI_GET_SAVED_LIST)
        .requireSuccess()
        .getByteArray(AndroidApiKeys.SAVED_WIFI_LIST_BYTES)
        ?: throw IllegalStateException("服务未返回 Wi-Fi 配置列表数据")

    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch): Boolean {
        val patchBytes = encodePatch(patch)
        return execute(
            AndroidApiAction.WIFI_UPDATE_CONFIG,
            Bundle().apply {
                putInt(AndroidApiKeys.NETWORK_ID, networkId)
                putByteArray(AndroidApiKeys.PATCH_BYTES, patchBytes)
            }
        ).requireSuccess().getBoolean(AndroidApiKeys.RESULT)
    }

    private fun execute(action: String, arguments: Bundle = Bundle.EMPTY): AndroidApiResponse =
        service.executeAndroidApi(AndroidApiRequest(action, arguments))

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
