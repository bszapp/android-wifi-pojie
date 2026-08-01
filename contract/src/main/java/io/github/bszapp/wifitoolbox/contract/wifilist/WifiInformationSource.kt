package io.github.bszapp.wifitoolbox.contract.wifilist

enum class WifiInformationSource(
    val wireValue: Int,
    val displayName: String,
) {
    SYSTEM(0, "系统模式"),
    HYBRID(1, "混合模式");

    companion object {
        fun fromWireValue(value: Int): WifiInformationSource =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("未知 Wi-Fi 信息源: $value")
    }
}

data class WifiInformationSourceState(
    val source: WifiInformationSource,
    val initializing: Boolean,
)
