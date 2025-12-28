package com.wifi.toolbox.ui.screen

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.screen.pojie.*
import androidx.core.content.edit
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.launch

sealed class PojieScreenPages(
    val name: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : PojieScreenPages("运行", Icons.Filled.Home, Icons.Outlined.Home)
    object History :
        PojieScreenPages("历史", Icons.Filled.History, Icons.Outlined.History)
    object Resources :
        PojieScreenPages("资源", Icons.Filled.Inbox, Icons.Outlined.Inbox)
    object Settings :
        PojieScreenPages("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
    object Help : PojieScreenPages("帮助", Icons.Filled.Info, Icons.Outlined.Info)
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

    var currentSelectedIndex by rememberSaveable { mutableIntStateOf(2) }
    var previousSelectedIndex by rememberSaveable { mutableIntStateOf(2) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var haveWifiScanResultState by rememberSaveable { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as MyApplication
    val view = androidx.compose.ui.platform.LocalView.current
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings_pojie", Context.MODE_PRIVATE)

    var showPasswordSheet by remember { mutableStateOf(false) }
    var passwordInputText by remember { mutableStateOf("") }
    var currentTargetSsid by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    var pojieSettings by remember {
        mutableStateOf(
            PojieSettings(
                readLogMode = sharedPreferences.getInt(PojieSettings.READ_LOG_MODE_KEY, PojieSettings.READ_LOG_MODE_DEFAULT),
                connectMode = sharedPreferences.getInt(PojieSettings.CONNECT_MODE_KEY, PojieSettings.CONNECT_MODE_DEFAULT),
                manageSavedMode = sharedPreferences.getInt(PojieSettings.MANAGE_SAVED_MODE_KEY, PojieSettings.MANAGE_SAVED_MODE_DEFAULT),
                scanMode = sharedPreferences.getInt(PojieSettings.SCAN_MODE_KEY, PojieSettings.SCAN_MODE_DEFAULT),
                allowScanUseCommand = sharedPreferences.getBoolean(PojieSettings.ALLOW_SCAN_USE_COMMAND_KEY, PojieSettings.ALLOW_SCAN_USE_COMMAND_DEFAULT),
                enableMode = sharedPreferences.getInt(PojieSettings.ENABLE_MODE_KEY, PojieSettings.ENABLE_MODE_DEFAULT),
                screenAlwaysOn = sharedPreferences.getBoolean(PojieSettings.SCREEN_ALWAYS_ON_KEY, PojieSettings.SCREEN_ALWAYS_ON_DEFAULT),
                showRunningNotification = sharedPreferences.getBoolean(PojieSettings.SHOW_RUNNING_NOTIFICATION_KEY, PojieSettings.SHOW_RUNNING_NOTIFICATION_DEFAULT),
                exitToPictureInPicture = sharedPreferences.getBoolean(PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_KEY, PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_DEFAULT),
                commandMethod = sharedPreferences.getInt(PojieSettings.COMMAND_METHOD_KEY, PojieSettings.COMMAND_METHOD_DEFAULT)
            )
        )
    }

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
            putBoolean(PojieSettings.SHOW_RUNNING_NOTIFICATION_KEY, newSettings.showRunningNotification)
            putBoolean(PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_KEY, newSettings.exitToPictureInPicture)
            putInt(PojieSettings.COMMAND_METHOD_KEY, newSettings.commandMethod)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val currentScreen = items[currentSelectedIndex]
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
                    Column(modifier = Modifier.padding(0.dp, 8.dp)) {
                        Text(text = currentScreen.name, style = MaterialTheme.typography.titleLarge)
                        Text(text = "密码字典破解", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, screen ->
                    val selected = currentSelectedIndex == index
                    NavigationBarItem(
                        icon = { Icon(imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon, contentDescription = screen.name) },
                        label = { Text(screen.name) },
                        selected = selected,
                        alwaysShowLabel = false,
                        onClick = {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            if (currentSelectedIndex != index) {
                                previousSelectedIndex = currentSelectedIndex
                                currentSelectedIndex = index
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            items.forEachIndexed { index, page ->
                val isVisible = index == currentSelectedIndex
                val isForward = currentSelectedIndex > previousSelectedIndex

                AnimatedVisibility(
                    visible = isVisible,
                    enter = if (isForward) {
                        Log.d("PojieNav", "页面 ${page.name} (Index $index) 入场: 从右向左滑动")
                        slideInHorizontally(tween(300)) { it } + fadeIn()
                    } else {
                        Log.d("PojieNav", "页面 ${page.name} (Index $index) 入场: 从左向右滑动")
                        slideInHorizontally(tween(300)) { -it } + fadeIn()
                    },
                    exit = if (isForward) {
                        Log.d("PojieNav", "页面 ${page.name} (Index $index) 出场: 向左消失")
                        slideOutHorizontally(tween(300)) { -it } + fadeOut()
                    } else {
                        Log.d("PojieNav", "页面 ${page.name} (Index $index) 出场: 向右消失")
                        slideOutHorizontally(tween(300)) { it } + fadeOut()
                    }
                ) {
                    Box(Modifier.fillMaxSize()) {
                        key(page) {
                            when (page) {
                                PojieScreenPages.Home -> HomePage(
                                    runListView = {
                                        RunListView(
                                            scanWifi = {
                                                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                                                if (pojieSettings.scanMode == 0) {
                                                    StartScanResult(StartScanResult.CODE_SCAN_FAIL, "扫描wifi实现为空，请先去设置中选择")
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
                                                                        StartScanResult(StartScanResult.CODE_SCAN_FAIL, "扫描频率过快，被系统拒绝")
                                                                    }
                                                                } else StartScanResult(StartScanResult.CODE_SCAN_FAIL, "未授予Shizuku权限")
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
                                                        0 -> ScanResult(ScanResult.CODE_SCAN_FAIL, "扫描wifi实现为空，请先去设置中选择")
                                                        1 -> {
                                                            if (!haveWifiScanResultState) {
                                                                ScanResult(ScanResult.CODE_NOT_SCANNED)
                                                            } else {
                                                                val result = ShizukuUtil.getWifiScanResults()
                                                                ScanResult(ScanResult.CODE_SUCCESS, null, result.filter { it.ssid.isNotEmpty() }.distinctBy { it.ssid })
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
                                                    0 -> app.alert("缺失参数", "打开wifi实现为空，请先在设置中选择")
                                                    1 -> checkShizukuUI(app) { ShizukuUtil.setWifiEnabled(true) }
                                                }
                                            },
                                            runningTasks = app.runningPojieTasks,
                                            onStartClick = { ssid -> currentTargetSsid = ssid; showPasswordSheet = true },
                                            onStopClick = { ssid -> app.stopTask(ssid) },
                                        )
                                    }
                                )
                                PojieScreenPages.History -> HistoryPage()
                                PojieScreenPages.Resources -> ResourcesPage()
                                PojieScreenPages.Settings -> SettingsPage(pojieSettings = pojieSettings, onPojieSettingsChange = onPojieSettingsChange)
                                PojieScreenPages.Help -> HelpPage()
                            }
                        }
                    }
                }
            }
        }

        if (showPasswordSheet) {
            val sheetScope = rememberCoroutineScope()
            ModalBottomSheet(
                onDismissRequest = { showPasswordSheet = false },
                sheetState = sheetState,
            ) {
                var isError by remember { mutableStateOf(false) }
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                    Text(text = "输入密码本 - $currentTargetSsid", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(
                        value = passwordInputText,
                        onValueChange = { passwordInputText = it; isError = false },
                        isError = isError,
                        supportingText = { if (isError) { Text(text = "请输入至少一个密码", color = MaterialTheme.colorScheme.error) } },
                        label = { Text("密码本") },
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(vertical = 16.dp).heightIn(min = 200.dp),
                        placeholder = { Text("一行一个密码...") }
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { sheetScope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) { showPasswordSheet = false } } }
                        ) { Text("取消") }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val passwordList = passwordInputText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                if (passwordList.isNotEmpty()) {
                                    app.startTask(PojieRunInfo(ssid = currentTargetSsid, tryList = passwordList, lastTryTime = System.currentTimeMillis()))
                                    sheetScope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) { showPasswordSheet = false; passwordInputText = "" } }
                                } else { isError = true }
                            }
                        ) { Text("开始") }
                    }
                }
            }
        }
    }
}