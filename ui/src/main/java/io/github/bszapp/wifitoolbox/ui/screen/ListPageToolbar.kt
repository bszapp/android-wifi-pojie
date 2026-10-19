package io.github.bszapp.wifitoolbox.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.WifiProtectedSetup
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal enum class ListPageSubmode(val title: String, val normalSubmode: NormalWifiSubmode?) {
    SCAN("扫描模式", NormalWifiSubmode.SCAN),
    PSK_AUDIT("弱密码检测", NormalWifiSubmode.PSK_SECURITY_AUDIT),
    PIN_AUDIT("PIN漏洞检测", NormalWifiSubmode.WPS_SECURITY_AUDIT),
    MONITOR("监听模式", null);

    val toolbarActions: List<ListToolbarAction>
        get() = when (this) {
            SCAN -> listOf(ListToolbarAction.QR, ListToolbarAction.PBC, ListToolbarAction.MORE, ListToolbarAction.REFRESH)
            PSK_AUDIT -> listOf(ListToolbarAction.HISTORY, ListToolbarAction.MORE, ListToolbarAction.REFRESH)
            PIN_AUDIT -> listOf(ListToolbarAction.HISTORY, ListToolbarAction.MORE, ListToolbarAction.REFRESH)
            MONITOR -> listOf(ListToolbarAction.CAPTURE, ListToolbarAction.EXPORT, ListToolbarAction.CLEAR, ListToolbarAction.REFRESH)
        }

    val floatingActions: List<ListFloatingAction>
        get() = when (this) {
            SCAN -> emptyList()
            PSK_AUDIT, PIN_AUDIT -> listOf(ListFloatingAction.CONFIGURE, ListFloatingAction.TASK)
            MONITOR -> listOf(ListFloatingAction.CAPTURE, ListFloatingAction.FREQUENCY)
        }

    companion object {
        fun current(mode: WifiMode, submode: NormalWifiSubmode): ListPageSubmode =
            if (mode == WifiMode.MONITOR) MONITOR else entries.first { it.normalSubmode == submode }

        fun available(mode: WifiMode): List<ListPageSubmode> = when (mode) {
            WifiMode.NORMAL -> listOf(SCAN, PSK_AUDIT, PIN_AUDIT)
            WifiMode.MONITOR -> listOf(MONITOR)
        }
    }
}

internal enum class ListToolbarAction(val description: String, val icon: ImageVector) {
    HISTORY("检测历史", Icons.Rounded.History),
    QR("扫码连接网络", Icons.Rounded.QrCodeScanner),
    PBC("WPS-PBC", Icons.Rounded.WifiProtectedSetup),
    MORE("更多", Icons.Rounded.MoreVert),
    REFRESH("刷新", MiuixIcons.Refresh),
    CAPTURE("抓包解析", Icons.Rounded.ManageSearch),
    EXPORT("导出全部PCAP", Icons.Rounded.Download),
    CLEAR("清理抓取数据", Icons.Rounded.DeleteSweep),
}
internal enum class ListFloatingAction { CAPTURE, FREQUENCY, CONFIGURE, TASK }

internal data class ListPageToolbarButton(
    val action: ListToolbarAction,
    val enabled: Boolean,
    val menuGroups: List<List<ListPageMenuOption>>,
    val onClick: () -> Unit,
)

internal data class ListPageMenuOption(
    val id: Any,
    val text: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val summary: String? = null,
    val onSelect: () -> Unit,
)

private sealed interface ListPageMenuRow {
    data class Divider(val groupIndex: Int) : ListPageMenuRow
    data class Option(val option: ListPageMenuOption, val index: Int, val count: Int) : ListPageMenuRow
}

@Composable
internal fun ListPageMenuContent(groups: List<List<ListPageMenuOption>>, onSelected: () -> Unit) {
    val rows = buildList<ListPageMenuRow> {
        groups.filter { it.isNotEmpty() }.forEachIndexed { groupIndex, options ->
            if (groupIndex > 0) add(ListPageMenuRow.Divider(groupIndex))
            options.forEachIndexed { index, option ->
                add(ListPageMenuRow.Option(option, index, options.size))
            }
        }
    }
    ListPopupColumn {
        rows.forEach { row ->
            when (row) {
                is ListPageMenuRow.Divider -> key("divider", row.groupIndex) { HorizontalDivider() }
                is ListPageMenuRow.Option -> key(row.option.id) {
                    DropdownImpl(
                        item = DropdownItem(
                            text = row.option.text,
                            summary = row.option.summary,
                            selected = row.option.selected,
                            enabled = row.option.enabled,
                        ),
                        optionSize = row.count,
                        index = row.index,
                        isSelected = row.option.selected,
                        enabled = row.option.enabled,
                        onSelectedIndexChange = {
                            onSelected()
                            row.option.onSelect()
                        },
                    )
                }
            }
        }
    }
}
