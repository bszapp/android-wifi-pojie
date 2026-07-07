package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import android.net.wifi.WifiConfiguration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.component.ActionButtonConfig
import io.github.bszapp.wifitoolbox.uidefault.component.ActionButtonGroupWithMenu
import io.github.bszapp.wifitoolbox.uidefault.component.MenuGroupConfig
import io.github.bszapp.wifitoolbox.uidefault.component.MenuItemConfig
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup

@Composable
fun WifiGroupCardActions(
    group: MergedWifiGroup,
    onConnect: () -> Unit = {},
    onOpenDetail: () -> Unit = {},
    onConnectWithConfig: (WifiConfiguration) -> Unit = {},
    onUpdateConfig: (networkId: Int, patch: WifiConfigPatch) -> Unit = { _, _ -> },
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    val supportsAutoJoin = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val menuGroups = buildList {
        add(
            MenuGroupConfig(
                icon = null,
                title = null,
                items = listOf(
                    MenuItemConfig(
                        title = "详细信息",
                        icon = Icons.Outlined.Info,
                        onCheckedChange = {
                            menuExpanded = false
                            onOpenDetail()
                        },
                    )
                )
            )
        )

        group.savedWifiList.forEach { config ->
            // 启用配置：status 为 ENABLED 或 CURRENT 均属于启用状态
            val isEnabled = config.status == WifiConfiguration.Status.ENABLED
                    || config.status == WifiConfiguration.Status.CURRENT

            // 自动连接：API 30+ 才有 allowAutojoin 字段；读不到就返回 null，不显示该项。
            val isAutoJoin = if (supportsAutoJoin) {
                config.getAllowAutojoinOrNull()
            } else null

            add(
                MenuGroupConfig(
                    title = "已保存的配置#${config.networkId}",
                    items = buildList {
                        add(
                            MenuItemConfig(
                                title = "使用此配置连接",
                                icon = Icons.Outlined.PlayArrow,
                                onCheckedChange = {
                                    menuExpanded = false
                                    onConnectWithConfig(config)
                                },
                            )
                        )
                        add(
                            MenuItemConfig(
                                title = "管理此配置",
                                icon = Icons.Outlined.EditNote,
                                onCheckedChange = {
                                    menuExpanded = false
                                    /* TODO */
                                },
                            )
                        )
                        if (isAutoJoin != null) {
                            // 自动连接
                            add(
                                MenuItemConfig(
                                    title = "自动连接",
                                    icon = Icons.Outlined.AutoFixHigh,
                                    checked = isAutoJoin,
                                    onCheckedChange = { newVal ->
                                        onUpdateConfig(
                                            config.networkId,
                                            WifiConfigPatch(autoJoin = newVal)
                                        )
                                    },
                                )
                            )
                        }
                        // 启用配置
                        add(
                            MenuItemConfig(
                                title = "启用配置",
                                icon = ImageVector.vectorResource(R.drawable.enable_24),
                                checked = isEnabled,
                                onCheckedChange = { newVal ->
                                    onUpdateConfig(
                                        config.networkId,
                                        WifiConfigPatch(enabled = newVal)
                                    )
                                },
                            )
                        )
                    }
                )
            )
        }
    }

    ActionButtonGroupWithMenu(
        buttonConfig = ActionButtonConfig(
            icon = Icons.Outlined.Link,
            text = "连接",
            onClick = onConnect,
        ),
        menuGroups = menuGroups,
        menuExpanded = menuExpanded,
        onMenuExpandedChange = { menuExpanded = it },
    )
}

@RequiresApi(Build.VERSION_CODES.R)
private fun WifiConfiguration.getAllowAutojoinOrNull(): Boolean? = try {
    WifiConfiguration::class.java
        .getField("allowAutojoin")
        .getBoolean(this)
} catch (_: Exception) {
    null
}