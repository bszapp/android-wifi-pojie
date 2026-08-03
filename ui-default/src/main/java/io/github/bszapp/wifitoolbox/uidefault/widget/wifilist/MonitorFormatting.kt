package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

internal fun formatMonitorByteCount(value: Long): String {
    val units = listOf(
        "GB" to 1024L * 1024L * 1024L,
        "MB" to 1024L * 1024L,
        "kB" to 1024L,
    )
    val selected = units.firstOrNull { (_, factor) -> value.toDouble() >= factor * 0.8 }
        ?: return "$value B"
    return "%.2f %s".format(value.toDouble() / selected.second, selected.first)
}
