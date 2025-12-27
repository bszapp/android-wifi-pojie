package com.wifi.toolbox.ui.screen.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.items.ActionChip
import com.wifi.toolbox.utils.LogState
import com.wifi.toolbox.ui.items.SectionDivider
import com.wifi.toolbox.ui.items.SectionTitle
import com.wifi.toolbox.utils.ShizukuUtil
import androidx.compose.ui.res.colorResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.ui.graphics.Color
import com.wifi.toolbox.utils.CommandRunner


data class ActionChipItem(val icon: ImageVector, val text: String, val command: String)

@Composable
fun ShellTest(logState: LogState, modifier: Modifier = Modifier) {

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    var command by remember { mutableStateOf("") }
    val buttonLabels = arrayOf("Normal", "Shizuku", "Root")

    val actionChips = listOf(
        ActionChipItem(
            Icons.Filled.Search,
            "查看当前身份",
            "id"
        ),
        ActionChipItem(
            Icons.Filled.Build,
            "修复Shizuku隐藏API调用",
            "settings put global hidden_api_policy 1"
        ),
        ActionChipItem(
            Icons.Filled.Link,
            "连接wifi",
            "cmd wifi connect-network 名称 wpa2 密码"
        ),
        ActionChipItem(
            Icons.Filled.Radar,
            "扫描wifi",
            "sh -c \"cmd wifi start-scan && echo 3秒后获取结果 && sleep 3 && cmd wifi list-scan-results\""
        ),
        ActionChipItem(
            Icons.Filled.Timelapse,
            "等待10秒",
            $$"sh -c \"for i in $(seq 1 10); do echo $i; sleep 1; done\""
        ),
        ActionChipItem(
            Icons.AutoMirrored.Filled.ManageSearch,
            "wifi logcat",
            "sh -c \"logcat -c && logcat -s \\\"WifiService:D\\\" \\\"wpa_supplicant:D\\\" \\\"DhcpClient:D\\\"\""
        )
    )

    var isCommandRunning by remember { mutableStateOf(false) }
    var stopCommandRunnable by remember { mutableStateOf<Runnable?>(null) }


    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(title = "输入命令", icon = Icons.Default.Code)
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("命令") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    actionChips.forEach { item ->
                        ActionChip(
                            text = item.text,
                            icon = item.icon,
                            onClick = {
                                command = item.command
                            }
                        )
                    }
                }

                SectionDivider()

                SectionTitle(title = "运行方式", icon = Icons.Default.Build)

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    buttonLabels.forEachIndexed { index,
                                                  label ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = buttonLabels.size
                            ),
                            onClick = {
                                selectedTabIndex = index
                            },
                            selected = selectedTabIndex == index
                        ) {
                            Text(label)
                        }
                    }
                }

                Button(
                    onClick = {
                        if (!isCommandRunning) {
                            isCommandRunning = true
                            val commandToExecute = command
                            val onOutput: (String) -> Unit = { output -> logState.addLog(output) }
                            val onFinish: (CommandRunner.CommandResult) -> Unit = { result ->
                                logState.addLog("[执行完毕 退出码${result.exitCode}]")
                                isCommandRunning = false
                                stopCommandRunnable = null
                            }

                            when (selectedTabIndex) {
                                0 -> { // Normal mode
                                    logState.addLog("执行命令：$commandToExecute")
                                    stopCommandRunnable = CommandRunner.executeCommand(
                                        command = commandToExecute,
                                        isRoot = false,
                                        onOutputReceived = onOutput,
                                        onCommandFinished = onFinish
                                    )
                                }

                                1 -> { // Shizuku mode
                                    logState.addLog("使用Shizuku执行：$commandToExecute")
                                    stopCommandRunnable = ShizukuUtil.executeCommand(
                                        command = commandToExecute,
                                        onOutputReceived = onOutput,
                                        onCommandFinished = onFinish
                                    )
                                }

                                2 -> { // Root mode
                                    logState.addLog("使用Root执行：$commandToExecute")
                                    stopCommandRunnable = CommandRunner.executeCommand(
                                        command = commandToExecute,
                                        isRoot = true,
                                        onOutputReceived = onOutput,
                                        onCommandFinished = onFinish
                                    )
                                }
                            }
                        } else {
                            logState.addLog("[执行中断]")
                            stopCommandRunnable?.run()
                            isCommandRunning = false
                            stopCommandRunnable = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(0.dp, 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCommandRunning) colorResource(android.R.color.holo_red_light)
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isCommandRunning) "结束运行" else "开始运行",
                        color = if (isCommandRunning) Color.White else MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}