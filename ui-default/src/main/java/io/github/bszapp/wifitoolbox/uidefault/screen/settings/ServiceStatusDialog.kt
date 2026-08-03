package io.github.bszapp.wifitoolbox.uidefault.screen.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ServiceStatusDialog(
    show: Boolean,
    modeText: String,
    pidText: String,
    uidStr: String,
    versionText: String,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onReselect: () -> Unit,
) {
    OverlayBottomSheet(
        title = "服务状态",
        show = show,
        allowDismiss = true,
        enableNestedScroll = true,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEndHaptic()
                .overScrollVertical(),
        ) {
            item {
                SmallTitle(
                    text = "服务信息",
                    insideMargin = PaddingValues(16.dp, 8.dp),
                )
                Card(
                    modifier = Modifier.padding(bottom = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    ServiceInfoItem("启动方式", modeText)
                    ServiceInfoItem("服务 PID", pidText)
                    ServiceInfoItem("服务版本", versionText)
                    ServiceInfoItem("UID", uidStr)
                }
            }
            item {
                SmallTitle(
                    text = "服务控制",
                    insideMargin = PaddingValues(16.dp, 8.dp),
                )
                Card(
                    modifier = Modifier.padding(bottom = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    BasicComponent(
                        title = "重选工作模式",
                        summary = "关闭当前服务并返回工作模式选择",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(24.dp),
                            )
                        },
                        onClick = onReselect,
                    )
                    BasicComponent(
                        title = "退出应用",
                        summary = "关闭当前服务并结束应用",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(24.dp),
                            )
                        },
                        onClick = onExit,
                    )
                }
                Spacer(
                    modifier = Modifier.height(
                        WindowInsets.safeDrawing
                            .asPaddingValues()
                            .calculateBottomPadding(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ServiceInfoItem(label: String, value: String) {
    BasicComponent(
        title = label,
        summary = value,
    )
}

@Composable
private fun BottomSheetActionButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}
