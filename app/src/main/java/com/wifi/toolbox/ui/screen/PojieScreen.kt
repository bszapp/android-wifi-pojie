package com.wifi.toolbox.ui.screen

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.*
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
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.ShizukuUtil

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


    val app = LocalContext.current.applicationContext as MyApplication

    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("settings_pojie", Context.MODE_PRIVATE)

    // Settings states
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
                                app.alert("缺失参数", "")
                            },
                            enableWifi = {
                                when (pojieSettings.enableMode) {
                                    0 -> {
                                        app.alert("缺失参数", "打开wifi实现为空，请先在设置中选择")
                                    }

                                    1 -> {
                                        CheckShizukuUI(app) {
                                            ShizukuUtil.setWifiEnabled(true)
                                        }
                                    }
                                }
                            }
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
    }
}