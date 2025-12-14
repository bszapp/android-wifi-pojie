package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.items.BannerTip
import me.zhanghai.compose.preference.*

@Composable
fun SettingsPage(
    pojieSettings: PojieSettings,
    onPojieSettingsChange: (PojieSettings) -> Unit
) {

    val app = LocalContext.current.applicationContext as MyApplication

    val readLogValues = listOf("--请选择--", "命令行", "系统API")
    val connectValues = listOf(
        "--请选择--",
        "系统隐藏API（Shizuku）",
        "系统常规API-WifiManager",
        "系统常规API-连接到设备",
        "命令行"
    )
    val manageSavedValues = listOf("空闲", "系统常规API", "命令行")
    val scanValues = listOf("空闲", "系统隐藏API（Shizuku）", "系统常规API", "命令行")
    val turnOnValues = listOf("空闲", "系统隐藏API（Shizuku）", "系统常规API", "命令行")
    val commandMethodValues = listOf("--请选择--", "shizuku", "root")

    ProvidePreferenceLocals {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
        ) {
            item {
                PreferenceCategory(
                    title = { Text("运行方式") },
                )
            }

            item {
                BannerTip(
                    text = "实现方法越往前越推荐，“请选择”为必选项，“空闲”为可选项。如果不能使用请尝试更换不同的组合",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.readLogMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                readLogMode = newValue
                            )
                        )
                    },
                    title = { Text("读取网络日志") },
                    summary = { Text(readLogValues[pojieSettings.readLogMode]) },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ManageSearch,
                            contentDescription = null
                        )
                    },
                    values = readLogValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(readLogValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.connectMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                connectMode = newValue
                            )
                        )
                    },
                    title = { Text("连接wifi") },
                    summary = { Text(connectValues[pojieSettings.connectMode]) },
                    icon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    values = connectValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(connectValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.manageSavedMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                manageSavedMode = newValue
                            )
                        )
                    },
                    title = { Text("管理已保存wifi") },
                    summary = { Text(manageSavedValues[pojieSettings.manageSavedMode]) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    values = manageSavedValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(manageSavedValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.scanMode,
                    onValueChange = { newValue -> onPojieSettingsChange(pojieSettings.copy(scanMode = newValue)) },
                    title = { Text("扫描wifi") },
                    summary = { Text(scanValues[pojieSettings.scanMode]) },
                    icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                    values = scanValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(scanValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                AnimatedVisibility(
                    visible = pojieSettings.scanMode == 1,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    SwitchPreference(
                        value = pojieSettings.allowScanUseCommand,
                        onValueChange = { newValue ->
                            onPojieSettingsChange(
                                pojieSettings.copy(
                                    allowScanUseCommand = newValue
                                )
                            )
                        },
                        title = { Text("允许使用命令行") },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        summary = { Text("使用系统API发送开始扫描失败时，尝试使用 cmd wifi start-scan 代替") }
                    )
                }
            }

            item {
                ListPreference(
                    value = pojieSettings.enableMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                enableMode = newValue
                            )
                        )
                    },
                    title = { Text("开关wifi") },
                    summary = { Text(turnOnValues[pojieSettings.enableMode]) },
                    icon = { Icon(Icons.Outlined.ToggleOn, contentDescription = null) },
                    values = turnOnValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(turnOnValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            val showCommandMethod = pojieSettings.readLogMode == 1 ||
                    pojieSettings.connectMode == 4 ||
                    pojieSettings.manageSavedMode == 2 ||
                    pojieSettings.scanMode == 3 ||
                    pojieSettings.enableMode == 3

            item {
                AnimatedVisibility(
                    visible = showCommandMethod,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    ListPreference(
                        value = pojieSettings.commandMethod,
                        onValueChange = { newValue ->
                            onPojieSettingsChange(
                                pojieSettings.copy(
                                    commandMethod = newValue
                                )
                            )
                        },
                        title = { Text("命令行实现方式") },
                        summary = { Text(commandMethodValues[pojieSettings.commandMethod]) },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        values = commandMethodValues.indices.toList(),
                        valueToText = { item: Int -> AnnotatedString(commandMethodValues[item]) },
                        type = ListPreferenceType.DROPDOWN_MENU,
                    )
                }
            }

            item {
                PreferenceCategory(
                    title = { Text("运行行为") },
                )
            }

            item {
                SwitchPreference(
                    value = pojieSettings.screenAlwaysOn,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                screenAlwaysOn = newValue
                            )
                        )
                    },
                    title = { Text("屏幕常亮") },
                    icon = { Icon(Icons.Filled.BrightnessHigh, contentDescription = null) },
                    summary = { Text("运行时且保持在前台时永不息屏") }
                )
            }

            item {
                SwitchPreference(
                    value = true,//pojieSettings.showRunningNotification,
                    onValueChange = { newValue ->
//                        onPojieSettingsChange(
//                            pojieSettings.copy(
//                                showRunningNotification = newValue
//                            )
//                        )
                        app.snackbar("前面的区域，以后再来探索吧")
                    },
                    title = { Text("显示通知") },
                    icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    summary = { Text("运行时显示一个通知，展示当前进度") }
                )
            }

            item {
                SwitchPreference(
                    value = pojieSettings.exitToPictureInPicture,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                exitToPictureInPicture = newValue
                            )
                        )
                    },
                    title = { Text("自动进入小窗") },
                    icon = { Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null) },
                    summary = { Text("运行时切换到其他应用时以小窗形式继续运行") }
                )
            }
        }
    }
}