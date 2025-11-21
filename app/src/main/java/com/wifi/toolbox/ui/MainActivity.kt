package com.wifi.toolbox.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.screen.AboutScreen
import com.wifi.toolbox.ui.screen.HomeScreen
import com.wifi.toolbox.ui.screen.PojieScreen
import com.wifi.toolbox.ui.screen.SettingsScreen
import com.wifi.toolbox.ui.screen.TestScreen
import com.wifi.toolbox.ui.theme.AppTheme
import kotlinx.coroutines.launch

private val DrawerWidth = 300.dp
private val NavItemHeight = 48.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPreferences = getSharedPreferences("settings_global", Context.MODE_PRIVATE)

        setContent {
            var dynamicColor by remember {
                mutableStateOf(sharedPreferences.getBoolean("dynamic_color", true))
            }
            var darkThemeSetting by remember {
                mutableStateOf(sharedPreferences.getString("dark_theme", "跟随设备") ?: "跟随设备")
            }

            val useDarkTheme = when (darkThemeSetting) {
                "开启" -> true
                "关闭" -> false
                else -> isSystemInDarkTheme()
            }
            AppTheme(darkTheme = useDarkTheme, dynamicColor = dynamicColor) {
                DetailedDrawerExample(
                    dynamicColor = dynamicColor,
                    onDynamicColorChange = {
                        dynamicColor = it
                        sharedPreferences.edit { putBoolean("dynamic_color", it) }
                    },
                    darkThemeSetting = darkThemeSetting,
                    onDarkThemeSettingChange = {
                        darkThemeSetting = it
                        sharedPreferences.edit { putString("dark_theme", it) }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedDrawerExample(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    darkThemeSetting: String,
    onDarkThemeSettingChange: (String) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    if (currentRoute != "Home") {
        BackHandler {
            navController.popBackStack("Home", inclusive = false)
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

                    Text(
                        "工具箱",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    NavigationDrawerItem(
                        label = { Text("密码字典破解") },
                        selected = currentRoute == "Pojie",
                        icon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != "Pojie") {
                                navController.navigate("Pojie") {
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.height(NavItemHeight)
                    )
                    NavigationDrawerItem(
                        label = { Text("实验室") },
                        selected = currentRoute == "Test",
                        icon = { Icon(Icons.Outlined.Science, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != "Test") {
                                navController.navigate("Test") {
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.height(NavItemHeight)
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        "选项",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    NavigationDrawerItem(
                        label = { Text("设置") },
                        selected = currentRoute == "Settings",
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != "Settings") {
                                navController.navigate("Settings") {
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.height(NavItemHeight)
                    )
                    NavigationDrawerItem(
                        label = { Text("帮助&关于") },
                        selected = currentRoute == "About",
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != "About") {
                                navController.navigate("About") {
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier.height(NavItemHeight)
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "Home",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("Home") {
                    HomeScreen(onMenuClick = { scope.launch { drawerState.open() } })
                }
                composable("Settings") {
                    SettingsScreen(
                        dynamicColor = dynamicColor,
                        onDynamicColorChange = onDynamicColorChange,
                        darkTheme = darkThemeSetting,
                        onDarkThemeChange = onDarkThemeSettingChange,
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                }
                composable("Pojie") {
                    PojieScreen(onMenuClick = { scope.launch { drawerState.open() } })
                }
                composable("Test") {
                    TestScreen(onMenuClick = { scope.launch { drawerState.open() } })
                }
                composable("About") {
                    AboutScreen(onMenuClick = { scope.launch { drawerState.open() } })
                }
            }
        }
    }
}
