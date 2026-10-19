package io.github.bszapp.wifitoolbox.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import java.security.SecureRandom
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val macRandom = SecureRandom()

/** 本地管理单播地址：仅首字节设置本地位、清除组播位，其余字节保持随机。 */
private fun randomLocalMac(): String {
    val bytes = ByteArray(6).also(macRandom::nextBytes)
    bytes[0] = (bytes[0].toInt() and 0xfc or 0x02).toByte()
    return bytes.joinToString(":") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}

@Composable
internal fun RandomMacButton(onValue: (String) -> Unit, enabled: Boolean = true) {
    IconButton(onClick = { onValue(randomLocalMac()) }, enabled = enabled) {
        Icon(Icons.Rounded.Shuffle, contentDescription = "随机MAC", tint = MiuixTheme.colorScheme.primary)
    }
}
