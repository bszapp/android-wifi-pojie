package io.github.bszapp.wifitoolbox.ui.model

/** WPS PIN 输入弹窗的表单状态；提交期间禁用输入。 */
data class WpsPinFormState(
    val ssid: String,
    val submitting: Boolean = false,
    val visible: Boolean = true,
)
