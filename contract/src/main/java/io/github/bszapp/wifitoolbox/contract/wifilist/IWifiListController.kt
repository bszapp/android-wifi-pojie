package io.github.bszapp.wifitoolbox.contract.wifilist

import kotlinx.coroutines.flow.StateFlow

/** App 侧只负责接收 Service 的原始数据并向 UI 转发命令。 */
interface IWifiListController {
    val state: StateFlow<WifiState?>
    val savedWifiList: StateFlow<SavedWifiList?>
    val informationSourceState: StateFlow<WifiInformationSourceState?>

    /** 请求 Service 只更新独立的 SavedWifiList。 */
    fun updateSavedNetworks()

    /**
     * 同步等待 Service 确认 WifiScanner 已接受扫描请求。
     * 成功返回后扫描任务继续在 Service 后台运行；失败直接向调用者抛出异常。
     */
    @Throws(Exception::class)
    fun startScan()

    /** 请求 Service 切换扫描信息源；选择状态和初始化状态都由 Service 保存。 */
    fun setInformationSource(source: WifiInformationSource)

    fun setWifiEnabled(enabled: Boolean)
    fun updateWifiConfig(networkId: Int, patch: WifiConfigPatch)
}
