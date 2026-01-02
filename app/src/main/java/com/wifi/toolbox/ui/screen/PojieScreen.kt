package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.screen.pojie.*
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.rememberPojieSettings
import com.wifi.toolbox.utils.rememberPojieWifiController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PojieScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MyApplication

    var showPasswordSheet by rememberSaveable { mutableStateOf(false) }
    var passwordInputText by rememberSaveable { mutableStateOf("") }
    var currentTargetSsid by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    var pojieSettings by rememberPojieSettings(context)
    val pojieWifiController = rememberPojieWifiController(context, app, pojieSettings)

    val pages = remember(pojieSettings, pojieWifiController) {
        listOf(
            object : NavPage {
                override val name = "资源"
                override val selectedIcon = Icons.Filled.Inbox
                override val unselectedIcon = Icons.Outlined.Inbox
                override val content = @Composable { ResourcesPage() }
            },
            object : NavPage {
                override val name = "历史"
                override val selectedIcon = Icons.Filled.History
                override val unselectedIcon = Icons.Outlined.History
                override val content = @Composable { HistoryPage() }
            },
            object : NavPage {
                override val name = "运行"
                override val selectedIcon = Icons.Filled.Home
                override val unselectedIcon = Icons.Outlined.Home
                override val content = @Composable {
                    HomePage {
                        RunListView(
                            controller = pojieWifiController,
                            onStartClick = { ssid ->
                                currentTargetSsid = ssid
                                showPasswordSheet = true
                            },
                            onStopClick = { ssid -> app.stopTaskByName(ssid) }
                        )
                    }
                }
            },
            object : NavPage {
                override val name = "设置"
                override val selectedIcon = Icons.Filled.Settings
                override val unselectedIcon = Icons.Outlined.Settings
                override val content = @Composable {
                    SettingsPage(pojieSettings) {
                        pojieSettings = it
                    }
                }
            },
            object : NavPage {
                override val name = "帮助"
                override val selectedIcon = Icons.Filled.Info
                override val unselectedIcon = Icons.Outlined.Info
                override val content = @Composable { HelpPage() }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        NavContainer(pages, 2, "密码字典破解", onMenuClick)

        if (showPasswordSheet) {
            val sheetScope = rememberCoroutineScope()
            ModalBottomSheet(
                onDismissRequest = { showPasswordSheet = false },
                sheetState = sheetState
            ) {
                var isError by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding()
                ) {
                    Text(
                        "输入密码本 - $currentTargetSsid",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    OutlinedTextField(
                        value = passwordInputText,
                        onValueChange = {
                            passwordInputText = it
                            isError = false
                        },
                        isError = isError,
                        supportingText = {
                            if (isError) Text(
                                "请输入至少一个密码",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        label = { Text("密码本") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, false)
                            .padding(vertical = 16.dp)
                            .heightIn(min = 200.dp),
                        placeholder = { Text("一行一个密码...") }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                sheetScope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) {
                                        showPasswordSheet = false
                                    }
                                }
                            }
                        ) { Text("取消") }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val passwordList = passwordInputText.lines().map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                if (passwordList.isNotEmpty()) {
                                    app.startTask(
                                        PojieRunInfo(
                                            ssid = currentTargetSsid,
                                            tryList = passwordList,
                                            lastTryTime = System.currentTimeMillis()
                                        )
                                    )
                                    sheetScope.launch { sheetState.hide() }.invokeOnCompletion {
                                        if (!sheetState.isVisible) {
                                            showPasswordSheet = false
                                            passwordInputText = ""
                                        }
                                    }
                                } else {
                                    isError = true
                                }
                            }
                        ) { Text("开始") }
                    }
                }
            }
        }
    }
}