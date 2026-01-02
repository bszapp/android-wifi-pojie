package com.wifi.toolbox.ui.items

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.screen.AboutScreen
import com.wifi.toolbox.ui.screen.HomeScreen
import com.wifi.toolbox.ui.screen.ManageScreen
import com.wifi.toolbox.ui.screen.PojieScreen
import com.wifi.toolbox.ui.screen.SettingsScreen
import com.wifi.toolbox.ui.screen.TestScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold


private val DrawerWidth = 300.dp
private val NavItemHeight = 48.dp


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailedDrawer(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    dynamicColorSeed: Int,
    onDynamicColorSeedChange: (Int) -> Unit,
    darkThemeSetting: Int,
    onDarkThemeSettingChange: (Int) -> Unit,
    hiddenApiBypass: Int,
    onHiddenApiBypassChange: (Int) -> Unit,
    pendingNavigation: MutableState<String?>
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val view = androidx.compose.ui.platform.LocalView.current

    LaunchedEffect(pendingNavigation.value) {
        pendingNavigation.value?.let { route ->
            navController.navigate(route) {
                popUpTo("Home") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            pendingNavigation.value = null
        }
    }

    val canGoBack = currentRoute != "Home"

    BackHandler(enabled = canGoBack) {
        navController.navigate("Home") {
            popUpTo("Home") { inclusive = true }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(DrawerWidth)) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.app_name),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.headlineMedium
                    )

                    NavigationDrawerItem(
                        label = { Text("主页") },
                        selected = currentRoute == "Home",
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        onClick = {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                            scope.launch { drawerState.close() }
                            if (currentRoute != "Home") {
                                navController.navigate("Home") {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.height(NavItemHeight)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("工具箱", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

                    val tools = listOf(
                        Triple("密码字典破解", "Pojie", Icons.Outlined.LockOpen),
                        Triple("wifi管理器", "Viewer", Icons.Filled.Dns),
                        Triple("实验室", "Test", Icons.Outlined.Science)
                    )

                    tools.forEach { (label, route, icon) ->
                        NavigationDrawerItem(
                            label = { Text(label) },
                            selected = currentRoute == route,
                            icon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo("Home") {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            modifier = Modifier.height(NavItemHeight)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("选项", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)

                    val options = listOf(
                        Triple("设置", "Settings", Icons.Outlined.Settings),
                        Triple("帮助&关于", "About", Icons.Filled.Info)
                    )

                    options.forEach { (label, route, icon) ->
                        NavigationDrawerItem(
                            label = { Text(label) },
                            selected = currentRoute == route,
                            icon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo("Home") {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            modifier = Modifier.height(NavItemHeight)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    ) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "Home",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("Home") { HomeScreen { scope.launch { drawerState.open() } } }
                composable("Settings") {
                    SettingsScreen(
                        dynamicColor = dynamicColor,
                        onDynamicColorChange = onDynamicColorChange,
                        dynamicColorSeed = dynamicColorSeed,
                        onDynamicColorSeedChange = onDynamicColorSeedChange,
                        darkTheme = darkThemeSetting,
                        onDarkThemeChange = onDarkThemeSettingChange,
                        hiddenApiBypass = hiddenApiBypass,
                        onHiddenApiBypassChange = onHiddenApiBypassChange,
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                }
                composable("Pojie") { PojieScreen { scope.launch { drawerState.open() } } }
                composable("Viewer") { ManageScreen { scope.launch { drawerState.open() } } }
                composable("Test") { TestScreen { scope.launch { drawerState.open() } } }
                composable("About") { AboutScreen { scope.launch { drawerState.open() } } }
            }
        }
    }
}