package io.github.bszapp.wifitoolbox.contract.wifilist

data class MonitorHandshakeTestResult(
    val requestId: String,
    val outcome: MonitorHandshakeTestOutcome,
)

enum class MonitorHandshakeTestOutcome(val wireValue: Int) {
    NOT_MATCHED(0),
    MATCHED(1),
    FAILED(2);

    companion object {
        fun fromWireValue(value: Int): MonitorHandshakeTestOutcome =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("未知握手包校验结果: $value")
    }
}
