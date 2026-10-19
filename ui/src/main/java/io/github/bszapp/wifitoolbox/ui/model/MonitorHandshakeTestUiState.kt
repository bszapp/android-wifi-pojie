package io.github.bszapp.wifitoolbox.ui.model

import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorHandshakeTestOutcome

/**
 * 握手校验请求的纯 UI 状态。
 * 从 WifiListUiState.kt 拆成独立文件：列表页预览渲染闭包只需要这个数据类，
 * 不需要（也不应）携带 WifiListUiState 的控制器依赖。
 */
data class MonitorHandshakeTestUiState(
    val requestId: String? = null,
    val outcome: MonitorHandshakeTestOutcome? = null,
)
