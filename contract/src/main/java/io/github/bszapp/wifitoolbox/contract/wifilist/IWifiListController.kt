package io.github.bszapp.wifitoolbox.contract.wifilist

import kotlinx.coroutines.flow.StateFlow

/** App 侧只负责接收 Service 的原始数据并向 UI 转发命令。 */
interface IWifiListController {
    val state: StateFlow<WifiState?>
    val savedWifiList: StateFlow<SavedWifiList?>

    /** 连接 Service 并注册两种数据回调；App 启动时不主动刷新 Wi-Fi。 */
    fun initialize()

    /** 请求 Service 只更新独立的 SavedWifiList。 */
    fun updateSavedNetworks()

    /**
     * 同步等待 Service 确认 WifiScanner 已接受扫描请求。
     * 成功返回后扫描任务继续在 Service 后台运行；失败直接向调用者抛出异常。
     */
    @Throws(Exception::class)
    fun startScan()

    fun setWifiEnabled(enabled: Boolean)
    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch)
}
