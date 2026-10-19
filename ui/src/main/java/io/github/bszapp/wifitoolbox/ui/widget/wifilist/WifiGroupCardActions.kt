package io.github.bszapp.wifitoolbox.ui.widget.wifilist

import android.net.wifi.WifiConfiguration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Password
import io.github.bszapp.wifitoolbox.contract.wifilist.supportsWps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiConfigPatch
import io.github.bszapp.wifitoolbox.ui.component.ActionButtonConfig
import io.github.bszapp.wifitoolbox.ui.component.ActionButtonGroupWithMenu
import io.github.bszapp.wifitoolbox.ui.component.MenuGroupConfig
import io.github.bszapp.wifitoolbox.ui.component.MenuItemConfig
import io.github.bszapp.wifitoolbox.ui.model.MergedWifiGroup

@Composable
fun WifiGroupCardActions(
    group: MergedWifiGroup,
    isConnected: Boolean,
    buttonContainerColor: Color?,
    buttonContentColor: Color?,
    auditMode: Boolean = false,
    auditRunning: Boolean = false,
    onAppendAudit: (() -> Unit)? = null,
    auditStopAll: Boolean = false,
    monitorMode: Boolean = false,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onOpenDetail: () -> Unit = {},
    allowWpsPin: Boolean = false,
    onWpsPin: () -> Unit = {},
    onUpdateConfig: (networkId: Int, patch: WifiConfigPatch) -> Unit = { _, _ -> },
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }

    val supportsAutoJoin = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val menuGroups = buildList {
        if (allowWpsPin && group.ssid.isNotBlank() && group.networks.any { it.supportsWps() }) {
            add(MenuGroupConfig(title = "WPS", items = listOf(MenuItemConfig(
                title = "使用PIN获取密码", icon = Icons.Outlined.Password,
                onCheckedChange = { menuExpanded = false; onWpsPin() },
            ))))
        }
        add(
            MenuGroupConfig(
                icon = null,
                title = null,
                items = buildList {
                    add(MenuItemConfig(
                        title = "详细信息",
                        icon = Icons.Outlined.Info,
                        onCheckedChange = {
                            menuExpanded = false
                            onOpenDetail()
                        },
                    ))
                    if (isConnected) add(MenuItemConfig(
                        title = "断开连接",
                        icon = Icons.Outlined.LinkOff,
                        onCheckedChange = {
                            menuExpanded = false
                            onDisconnect()
                        },
                    ))
                }
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
                                icon = Enable24,
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

        if (auditMode && auditRunning && onAppendAudit != null) {
            add(MenuGroupConfig(title = null, items = listOf(MenuItemConfig(
                title = "追加任务",
                icon = Icons.Outlined.PlayArrow,
                onCheckedChange = { menuExpanded = false; onAppendAudit() },
            ))))
        }
    }

    // auditRunning + auditStopAll 时按钮文案改为"停止全部"，块级停止由展开区承担。
    ActionButtonGroupWithMenu(
        buttonConfig = ActionButtonConfig(
            icon = when {
                monitorMode -> Icons.Outlined.Info
                auditMode && auditRunning -> Icons.Outlined.Stop
                auditMode -> Icons.Outlined.PlayArrow
                isConnected -> Icons.Outlined.LinkOff
                else -> Icons.Outlined.Link
            },
            text = when {
                monitorMode -> "详情"
                auditMode && auditRunning -> if (auditStopAll) "停止全部" else "停止"
                auditMode -> "运行"
                isConnected -> "断开"
                else -> "连接"
            },
            containerColor = buttonContainerColor,
            contentColor = buttonContentColor,
            onClick = when {
                monitorMode -> onOpenDetail
                auditMode -> onConnect
                isConnected -> onDisconnect
                else -> onConnect
            },
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

/**
 * 原 res/drawable/enable_24.xml 内嵌为代码 ImageVector（res 目录已删除）。
 * 图标本体白色填充，着色仍由 Icon 的 tint 完成，与原 vectorResource 行为一致。
 */
internal val Enable24: ImageVector by lazy {
    ImageVector.Builder(
        name = "enable_24",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(ENABLE_24_PATH).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()
}

private const val ENABLE_24_PATH =
    "M324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480Q80,348 157,242.5Q234,137 360,98L360,184Q269,221 214.5,301.5Q160,382 160,480Q160,614 253,707Q346,800 480,800Q614,800 707,707Q800,614 800,480Q800,382 745.5,301.5Q691,221 600,184L600,98Q726,137 803,242.5Q880,348 880,480Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880Q397,880 324,848.5ZM480,640L280,440L336,384L440,487L440,80L520,80L520,487L624,384L680,440L480,640Z"
