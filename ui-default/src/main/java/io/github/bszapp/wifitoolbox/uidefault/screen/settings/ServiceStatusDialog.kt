package io.github.bszapp.wifitoolbox.uidefault.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ServiceStatusDialog(
    show: Boolean,
    title: String,
    modeText: String,
    pidText: String,
    uidStr: String,
    versionText: String,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onReselect: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = "服务详情",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                InfoRow("启动方式", modeText)
                InfoRow("服务 PID", pidText)
                InfoRow("服务版本", versionText)
                InfoRow("UID", uidStr, monospace = true)
            }

            Text(
                text = "操作",
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )

            val actions = remember(onExit, onReselect) {
                listOf(
                    ActionItem(
                        "exit",
                        "退出应用",
                        "结束所有运行中的任务，然后关闭服务并退出",
                        MiuixIcons.Close,
                        onExit,
                    ),
                    ActionItem(
                        "reselect",
                        "重选工作模式",
                        "结束所有运行中的任务并关闭服务，进入切换工作模式页面",
                        MiuixIcons.Settings,
                        onReselect,
                    ),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions.forEachIndexed { index, item ->
                    ServiceActionItem(
                        title = item.title,
                        description = item.desc,
                        icon = item.icon,
                        isFirst = index == 0,
                        isEnd = index == actions.size - 1,
                        onClick = item.onClick,
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text("关闭")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ServiceActionItem(
    title: String,
    description: String,
    icon: ImageVector,
    isFirst: Boolean,
    isEnd: Boolean,
    onClick: () -> Unit,
) {
    val topRadius = if (isFirst) 16.dp else 4.dp
    val bottomRadius = if (isEnd) 16.dp else 4.dp
    val topPadding = if (isFirst) 8.dp else 1.dp
    val bottomPadding = if (isEnd) 8.dp else 0.dp

    Card(
        onClick = onClick,
        modifier = Modifier
            .padding(top = topPadding, bottom = bottomPadding)
            .fillMaxWidth(),
        cornerRadius = topRadius,
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            color = MiuixTheme.colorScheme.onSurface,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
    }
}

data class ActionItem(
    val key: String,
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)
