package io.github.bszapp.wifitoolbox.uidefault.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DoorFront
import androidx.compose.material.icons.twotone.SwapHorizontalCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.component.SplicedGroupItem

@Composable
fun ServiceStatusDialog(
    uidStr: String,
    versionText: String,
    onDismiss: () -> Unit,
    onExit: () -> Unit,
    onReselect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "服务管理",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "身份信息",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = uidStr,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "启动版本",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = versionText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "操作",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val actions = remember(onExit, onReselect) {
                    listOf(
                        ActionItem(
                            "exit",
                            "退出应用",
                            "结束所有运行中的任务，然后关闭服务并退出",
                            Icons.TwoTone.DoorFront,
                            onExit
                        ),
                        ActionItem(
                            "reselect",
                            "重选工作模式",
                            "结束所有运行中的任务并关闭服务，进入切换工作模式页面",
                            Icons.TwoTone.SwapHorizontalCircle,
                            onReselect
                        )
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    actions.forEachIndexed { index, item ->
                        SplicedGroupItem(
                            title = item.title,
                            description = item.desc,
                            icon = item.icon,
                            showArrow = true,
                            isFirst = index == 0,
                            isEnd = index == actions.size - 1,
                            onClick = item.onClick
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

data class ActionItem(
    val key: String,
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)