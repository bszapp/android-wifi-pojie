@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.wifilist

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.wifilist.IWifiListController
import io.github.bszapp.wifitoolbox.contract.wifilist.ScanState
import io.github.bszapp.wifitoolbox.contract.wifilist.ScanStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.tools.AndroidApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WifiListController(
    private val scope: CoroutineScope,
    private val getAndroidApi: () -> AndroidApiClient?,
) : IWifiListController {

    private var scanJob: Job? = null

    private val _state = MutableStateFlow(ScanState())
    override val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _savedWifiList = MutableStateFlow<List<WifiConfiguration>>(emptyList())
    override val savedWifiList: StateFlow<List<WifiConfiguration>> = _savedWifiList.asStateFlow()

    private var previousResults: Map<String, ScanResult> = emptyMap()

    private var isWifiEnabled = true

    fun updateWifiEnabled(enabled: Boolean) {
        isWifiEnabled = enabled
        if (!enabled) {
            scanJob?.cancel()
            scanJob = null
            _state.value = ScanState(status = ScanStatus.NOT_ENABLED)
        }
    }

    fun refreshScanResults() {
        if (!isWifiEnabled) {
            Log.w(TAG, "refreshScanResults() 失败：wifi未开启")
            _state.value = ScanState(status = ScanStatus.NOT_ENABLED)
            return
        }

        val androidApi = getAndroidApi() ?: run {
            setScanError(Exception("服务未运行"), "refreshScanResults() 失败：服务未运行")
            return
        }

        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            try {
                val results = readScanResults(androidApi)
                diffAndLog(results)
                previousResults = results.associateBy { requireBssid(it) }
                _state.value = ScanState(
                    status = ScanStatus.LIST,
                    scanResults = results,
                    isScanning = false
                )
                refreshSavedWifiList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setScanError(e, "读取扫描结果失败：${e.message}")
            }
        }
    }

    override fun startScan() {
        if (!isWifiEnabled) {
            Log.w(TAG, "startScan() 失败：wifi未开启")
            _state.value = ScanState(status = ScanStatus.NOT_ENABLED)
            return
        }

        val androidApi = getAndroidApi() ?: run {
            setScanError(Exception("服务未运行"), "startScan() 失败：服务未运行")
            return
        }

        Log.d(TAG, "获取到服务实例，准备启动扫描协程")
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            try {
                val startResult = androidApi.startScan()
                val startTime = System.currentTimeMillis()
                Log.d(TAG, "androidApi.startScan() 返回 $startResult，开始时间：$startTime")

                if (!startResult) {
                    throw IllegalStateException("startScan 返回 false")
                }

                _state.value = ScanState(
                    status = ScanStatus.LIST,
                    scanResults = _state.value.scanResults,
                    isScanning = true
                )
                delay(500.milliseconds)

                while (System.currentTimeMillis() - startTime < 3_000L) {
                    val results = readScanResults(androidApi)
                    diffAndLog(results)
                    previousResults = results.associateBy { requireBssid(it) }
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "轮询拉取：结果数=${results.size}，已用时=${elapsed}ms")
                    _state.value = ScanState(
                        status = ScanStatus.LIST,
                        scanResults = results,
                        isScanning = true
                    )
                    delay(250.milliseconds)
                }

                val finalResults = readScanResults(androidApi)
                diffAndLog(finalResults)
                previousResults = finalResults.associateBy { requireBssid(it) }
                Log.d(TAG, "扫描结束，最终结果数=${finalResults.size}")
                _state.value = ScanState(
                    status = ScanStatus.LIST,
                    scanResults = finalResults,
                    isScanning = false
                )
                refreshSavedWifiList()

            } catch (e: CancellationException) {
                Log.d(TAG, "扫描协程被取消（用户重新发起扫描）")
                throw e
            } catch (e: Exception) {
                setScanError(e, "扫描过程中发生异常：${e.message}")
            }
        }
    }

    private fun readScanResults(androidApi: AndroidApiClient): List<ScanResult> {
        return androidApi.getScanResults().filterIndexed { index, result ->
            val keep = !result.BSSID.isNullOrBlank()
            if (!keep) Log.w(TAG, "丢弃第 $index 项扫描结果：BSSID 为空")
            keep
        }
    }

    private fun requireBssid(result: ScanResult): String = result.BSSID!!

    private fun diffAndLog(newList: List<ScanResult>) {
        if (previousResults.isEmpty()) return
        val newMap = newList.associateBy { requireBssid(it) }
        val appeared = newMap.keys - previousResults.keys
        val disappeared = previousResults.keys - newMap.keys
        appeared.forEach { Log.d(TAG, "新增 AP: ${newMap[it]?.SSID} [$it]") }
        disappeared.forEach { Log.d(TAG, "消失 AP: ${previousResults[it]?.SSID} [$it]") }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    override fun refreshSavedWifiList() {
        val androidApi = getAndroidApi() ?: return
        val parcel = Parcel.obtain()
        try {
            val bytes = androidApi.getSavedWifiList()
            if (bytes.isEmpty()) {
                throw IllegalStateException("服务返回的 Wi-Fi 配置序列化数据为空")
            }
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val creator = WifiConfiguration::class.java
                .getField("CREATOR").get(null) as Parcelable.Creator<WifiConfiguration>
            _savedWifiList.value = parcel.createTypedArrayList(creator)
                ?: throw IllegalStateException("反序列化 Wi-Fi 配置列表返回 null")
        } catch (e: Exception) {
            setScanError(e, "refreshSavedWifiList() 失败: ${e.message}")
        } finally {
            parcel.recycle()
        }
    }

    override fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) {
        val androidApi = getAndroidApi() ?: return
        scope.launch(Dispatchers.IO) {
            val success = androidApi.updateWifiConfig(networkId, patch)
            if (success) refreshSavedWifiList()
        }
    }

    override fun setWifiEnabled(enabled: Boolean) {
        val androidApi = getAndroidApi() ?: return
        scope.launch(Dispatchers.IO) {
            androidApi.setWifiEnabled(enabled)
        }
    }

    private fun setScanError(e: Exception, logMessage: String) {
        Log.e(TAG, logMessage, e)
        _state.value = ScanState(
            status = ScanStatus.ERROR,
            isScanning = false,
            errorException = e
        )
    }

    companion object {
        private const val TAG = "WifiListController"
    }
}
