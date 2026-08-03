package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

internal fun frequencyToChannel(frequency: Int): Int? = when {
    frequency == 2484 -> 14
    frequency in 2412..2472 -> (frequency - 2407) / 5
    frequency in 4910..4980 -> (frequency - 4000) / 5
    frequency in 5000..5895 -> (frequency - 5000) / 5
    frequency == 5935 -> 2
    frequency in 5955..7115 -> (frequency - 5950) / 5
    frequency in 58_320..70_200 -> ((frequency - 58_320) / 2160) + 1
    else -> null
}

internal fun frequencyBand(frequency: Int): String = when (frequency) {
    in 2400..2500 -> "2.4 GHz"
    in 4900..5900 -> "5 GHz"
    in 5925..7125 -> "6 GHz"
    in 58_000..71_000 -> "60 GHz"
    else -> "未知频段"
}
