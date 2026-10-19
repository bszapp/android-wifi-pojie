package io.github.bszapp.wifitoolbox.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** 普通网卡下拉单选；保留已配置但暂未发现的网卡。 */
@Composable
internal fun WifiInterfaceSelector(selected: String, available: List<String>, onSelected: (String) -> Unit,
    enabled: Boolean = true) {
    val interfaces = (listOf("wlan0") + available + selected).distinct()
    Card(Modifier.fillMaxWidth()) {
        OverlayDropdownPreference(title = "使用网卡", enabled = enabled,
            items = interfaces.map { if (it in available) it else "$it（未发现）" },
            selectedIndex = interfaces.indexOf(selected),
            onSelectedIndexChange = { onSelected(interfaces[it]) })
    }
}
