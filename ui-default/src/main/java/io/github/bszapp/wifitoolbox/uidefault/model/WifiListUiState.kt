package io.github.bszapp.wifitoolbox.uidefault.model

import io.github.bszapp.wifitoolbox.contract.IAppController
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiListUiState(
    private val controller: IAppController,
    private val scope: CoroutineScope,
) {

    /** Service 独立维护并传输的 Wi-Fi 运行状态。 */
    val state = controller.wifiList.state

    /** 与 WifiState 同级、独立传输的已保存 Wi-Fi 列表。 */
    val savedWifiList = controller.wifiList.savedWifiList

    private val scanRequestGuard = AtomicBoolean(false)
    private val _isSendingScanRequest = MutableStateFlow(false)

    /** App 正在同步等待 Service 确认 WifiScanner 是否接受了扫描请求。 */
    val isSendingScanRequest: StateFlow<Boolean> = _isSendingScanRequest.asStateFlow()

    private var previousWifiState: WifiState? = null

    init {
        scope.launch {
            state.collect { current ->
                val previous = previousWifiState
                previousWifiState = current

                // App 初次订阅时 null -> Enabled 不扫描。
                // Disabled/Error -> Enabled 时，App 只发送扫描指令；数据刷新由扫描任务完成。
                val shouldAutoScan =
                    (previous is WifiState.Data.Disabled || previous is WifiState.Error) &&
                        current is WifiState.Data.Enabled &&
                        !current.isScanning

                if (shouldAutoScan) startScan()
            }
        }
    }

    fun updateSavedNetworks() = controller.wifiList.updateSavedNetworks()

    /**
     * ViewModel 层异步发送扫描请求，并维护“正在等待 Service 确认”的 UI 状态。
     * 错误已经由 App Controller 统一广播，这里只负责结束异步任务。
     */
    fun startScan() {
        if (!scanRequestGuard.compareAndSet(false, true)) return
        _isSendingScanRequest.value = true

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    controller.wifiList.startScan()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // App.WifiListController 已发送到统一 errors 广播。
            } finally {
                _isSendingScanRequest.value = false
                scanRequestGuard.set(false)
            }
        }
    }

    fun setWifiEnabled(enabled: Boolean) = controller.wifiList.setWifiEnabled(enabled)

    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch) =
        controller.wifiList.updateWifiConfig(networkId, patch)
}
