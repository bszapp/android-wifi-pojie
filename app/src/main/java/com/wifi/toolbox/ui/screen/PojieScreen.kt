package com.wifi.toolbox.ui.screen

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.wifi.toolbox.ui.screen.pojie.*
import androidx.core.content.edit
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.launch

sealed class PojieScreenPages(
    val route: String,
    val name: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : PojieScreenPages("home", "运行", Icons.Filled.Home, Icons.Outlined.Home)
    object History :
        PojieScreenPages("history", "历史", Icons.Filled.History, Icons.Outlined.History)

    object Resources :
        PojieScreenPages("resources", "资源", Icons.Filled.Inbox, Icons.Outlined.Inbox)

    object Settings :
        PojieScreenPages("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings)

    object Help : PojieScreenPages("help", "帮助", Icons.Filled.Info, Icons.Outlined.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PojieScreen(onMenuClick: () -> Unit) {
    val items = listOf(
        PojieScreenPages.Resources,
        PojieScreenPages.History,
        PojieScreenPages.Home,
        PojieScreenPages.Settings,
        PojieScreenPages.Help,
    )
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    var haveWifiScanResultState by rememberSaveable { mutableStateOf(false) }

    val app = LocalContext.current.applicationContext as MyApplication

    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings_pojie", Context.MODE_PRIVATE)

    var showPasswordSheet by remember { mutableStateOf(false) }
    var passwordInputText by remember { mutableStateOf("") }
    var currentTargetSsid by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()
    var pojieSettings by remember {
        mutableStateOf(
            PojieSettings(
                readLogMode = sharedPreferences.getInt(
                    PojieSettings.READ_LOG_MODE_KEY,
                    PojieSettings.READ_LOG_MODE_DEFAULT
                ),
                connectMode = sharedPreferences.getInt(
                    PojieSettings.CONNECT_MODE_KEY,
                    PojieSettings.CONNECT_MODE_DEFAULT
                ),
                manageSavedMode = sharedPreferences.getInt(
                    PojieSettings.MANAGE_SAVED_MODE_KEY,
                    PojieSettings.MANAGE_SAVED_MODE_DEFAULT
                ),
                scanMode = sharedPreferences.getInt(
                    PojieSettings.SCAN_MODE_KEY,
                    PojieSettings.SCAN_MODE_DEFAULT
                ),
                allowScanUseCommand = sharedPreferences.getBoolean(
                    PojieSettings.ALLOW_SCAN_USE_COMMAND_KEY,
                    PojieSettings.ALLOW_SCAN_USE_COMMAND_DEFAULT
                ),
                enableMode = sharedPreferences.getInt(
                    PojieSettings.ENABLE_MODE_KEY,
                    PojieSettings.ENABLE_MODE_DEFAULT
                ),
                screenAlwaysOn = sharedPreferences.getBoolean(
                    PojieSettings.SCREEN_ALWAYS_ON_KEY,
                    PojieSettings.SCREEN_ALWAYS_ON_DEFAULT
                ),
                showRunningNotification = sharedPreferences.getBoolean(
                    PojieSettings.SHOW_RUNNING_NOTIFICATION_KEY,
                    PojieSettings.SHOW_RUNNING_NOTIFICATION_DEFAULT
                ),
                exitToPictureInPicture = sharedPreferences.getBoolean(
                    PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_KEY,
                    PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_DEFAULT
                ),
                commandMethod = sharedPreferences.getInt(
                    PojieSettings.COMMAND_METHOD_KEY,
                    PojieSettings.COMMAND_METHOD_DEFAULT
                )
            )
        )
    }

    // OnValueChange callback for all settings
    val onPojieSettingsChange: (PojieSettings) -> Unit = { newSettings ->
        pojieSettings = newSettings
        sharedPreferences.edit {
            putInt(PojieSettings.READ_LOG_MODE_KEY, newSettings.readLogMode)
            putInt(PojieSettings.CONNECT_MODE_KEY, newSettings.connectMode)
            putInt(PojieSettings.MANAGE_SAVED_MODE_KEY, newSettings.manageSavedMode)
            putInt(PojieSettings.SCAN_MODE_KEY, newSettings.scanMode)
            putBoolean(PojieSettings.ALLOW_SCAN_USE_COMMAND_KEY, newSettings.allowScanUseCommand)
            putInt(PojieSettings.ENABLE_MODE_KEY, newSettings.enableMode)
            putBoolean(PojieSettings.SCREEN_ALWAYS_ON_KEY, newSettings.screenAlwaysOn)
            putBoolean(
                PojieSettings.SHOW_RUNNING_NOTIFICATION_KEY,
                newSettings.showRunningNotification
            )
            putBoolean(
                PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_KEY,
                newSettings.exitToPictureInPicture
            )
            putInt(PojieSettings.COMMAND_METHOD_KEY, newSettings.commandMethod)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val currentScreen = items.find { screen ->
                currentDestination?.hierarchy?.any { it.route == screen.route } == true
            } ?: PojieScreenPages.Home

            val topAppBarColors = if (currentScreen == PojieScreenPages.Settings) {
                TopAppBarDefaults.topAppBarColors()
            } else {
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            }

            TopAppBar(
                colors = topAppBarColors,
                title = {
                    Column(
                        modifier = Modifier.padding(0.dp, 8.dp)
                    ) {
                        Text(
                            text = currentScreen.name,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "密码字典破解",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu, contentDescription = ""
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.name
                            )
                        },
                        label = { Text(screen.name) },
                        selected = selected,
                        alwaysShowLabel = false,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        })
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PojieScreenPages.Home.route,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            enterTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }

                if (targetIndex > initialIndex) {
                    slideInHorizontally(initialOffsetX = { it })
                } else {
                    slideInHorizontally(initialOffsetX = { -it })
                }
            },
            exitTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }
                if (targetIndex > initialIndex) {
                    slideOutHorizontally(targetOffsetX = { -it })
                } else {
                    slideOutHorizontally(targetOffsetX = { it })
                }
            },
            popEnterTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }

                if (initialIndex > targetIndex) {
                    slideInHorizontally(initialOffsetX = { -it })
                } else {
                    slideInHorizontally(initialOffsetX = { it })
                }
            },
            popExitTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }
                if (initialIndex > targetIndex) {
                    slideOutHorizontally(targetOffsetX = { it })
                } else {
                    slideOutHorizontally(targetOffsetX = { -it })
                }
            })
        {
            composable(PojieScreenPages.Home.route) {
                HomePage(
                    runListView = {
                        RunListView(
                            scanWifi = {
                                val wifiManager =
                                    context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                                if (pojieSettings.scanMode == 0) {
                                    StartScanResult(
                                        StartScanResult.CODE_SCAN_FAIL,
                                        "扫描wifi实现为空，请先去设置中选择"
                                    )
                                } else if (wifiManager == null) {
                                    StartScanResult(StartScanResult.CODE_UNKNOWN)
                                } else if (!wifiManager.isWifiEnabled) {
                                    StartScanResult(StartScanResult.CODE_WIFI_NOT_ENABLED)
                                } else {
                                    try {
                                        when (pojieSettings.scanMode) {
                                            1 -> {
                                                if (checkShizukuUI(app)) {
                                                    if (ShizukuUtil.startWifiScan(pojieSettings.allowScanUseCommand)) {
                                                        haveWifiScanResultState = true
                                                        StartScanResult(StartScanResult.CODE_SUCCESS)
                                                    } else {
                                                        StartScanResult(
                                                            StartScanResult.CODE_SCAN_FAIL,
                                                            "扫描频率过快，被系统拒绝"
                                                        )
                                                    }
                                                } else
                                                    StartScanResult(
                                                        StartScanResult.CODE_SCAN_FAIL,
                                                        "未授予Shizuku权限"
                                                    )

                                            }

                                            else -> StartScanResult()
                                        }
                                    } catch (e: Exception) {
                                        StartScanResult(StartScanResult.CODE_UNKNOWN, e.message)
                                    }
                                }
                            },
                            getScanResult = {
                                try {
                                    when (pojieSettings.scanMode) {
                                        0 -> {
                                            ScanResult(
                                                ScanResult.CODE_SCAN_FAIL,
                                                "扫描wifi实现为空，请先去设置中选择"
                                            )
                                        }

                                        1 -> {
                                            if (!haveWifiScanResultState) {
                                                ScanResult(ScanResult.CODE_NOT_SCANNED)
                                            } else {
                                                val result = ShizukuUtil.getWifiScanResults()
                                                ScanResult(
                                                    ScanResult.CODE_SUCCESS,
                                                    null,
                                                    result
                                                        .filter { it.ssid.isNotEmpty() }
                                                        .distinctBy { it.ssid }
                                                )
                                            }
                                        }

                                        else -> ScanResult()
                                    }
                                } catch (e: Exception) {
                                    ScanResult(errorMessage = e.message)
                                }
                            },
                            enableWifi = {
                                when (pojieSettings.enableMode) {
                                    0 -> {
                                        app.alert(
                                            "缺失参数",
                                            "打开wifi实现为空，请先在设置中选择"
                                        )
                                    }

                                    1 -> {
                                        checkShizukuUI(app) {
                                            ShizukuUtil.setWifiEnabled(true)
                                        }
                                    }
                                }
                            },
                            runningTasks = app.runningPojieTasks,

                            onStartClick = { ssid ->
                                currentTargetSsid = ssid
                                showPasswordSheet = true
                            },

                            onStopClick = { ssid ->
                                app.stopTask(ssid)
                            },
                        )
                    }
                )
            }
            composable(PojieScreenPages.History.route) {
                HistoryPage()
            }
            composable(PojieScreenPages.Resources.route) {
                ResourcesPage()
            }
            composable(PojieScreenPages.Settings.route) {
                SettingsPage(
                    pojieSettings = pojieSettings,
                    onPojieSettingsChange = onPojieSettingsChange
                )
            }
            composable(PojieScreenPages.Help.route) {
                HelpPage()
            }
        }
        if (showPasswordSheet) {
            val scope = rememberCoroutineScope()
            ModalBottomSheet(
                onDismissRequest = { showPasswordSheet = false },
                sheetState = sheetState,
            ) {
                var isError by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = "输入密码本 - $currentTargetSsid",
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
                            if (isError) {
                                Text(
                                    text = "请输入至少一个密码",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        label = { Text("密码本") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 16.dp)
                            .heightIn(min = 200.dp),
                        placeholder = { Text("一行一个密码...") }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                }.invokeOnCompletion {
                                    if (!sheetState.isVisible) {
                                        showPasswordSheet = false
                                    }
                                }
                            }
                        ) {
                            Text("取消")
                        }

                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val passwordList = passwordInputText.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }

                                if (passwordList.isNotEmpty()) {
                                    app.startTask(
                                        PojieRunInfo(
                                            ssid = currentTargetSsid,
                                            tryList = passwordList,
                                            lastTryTime = System.currentTimeMillis()
                                        )
                                    )
                                    scope.launch {
                                        sheetState.hide()
                                    }.invokeOnCompletion {
                                        if (!sheetState.isVisible) {
                                            showPasswordSheet = false
                                            passwordInputText = ""
                                        }
                                    }
                                } else {
                                    isError = true
                                }
                            }
                        ) {
                            Text("开始")
                        }
                    }
                }
            }
        }
    }
}