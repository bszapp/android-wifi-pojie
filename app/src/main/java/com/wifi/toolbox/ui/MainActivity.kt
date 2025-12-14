package com.wifi.toolbox.ui

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.compose.*
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.screen.*
import com.wifi.toolbox.ui.theme.AppTheme
import com.wifi.toolbox.ui.theme.defaultColorSeed
import kotlinx.coroutines.launch
import com.wifi.toolbox.utils.ShizukuUtil
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val DrawerWidth = 300.dp
private val NavItemHeight = 48.dp

class MainActivity : ComponentActivity() {

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ShizukuUtil.initialize(applicationContext)

        val sharedPreferences = getSharedPreferences("settings_global", MODE_PRIVATE)

        setContent {
            val app = LocalContext.current.applicationContext as MyApplication

            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(Unit) {
                app.snackbarState.collect { data ->
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = data.message,
                        actionLabel = data.actionLabel,
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        data.onActionClick?.invoke()
                    }
                }
            }

            var dynamicColor by remember {
                mutableStateOf(sharedPreferences.getBoolean("dynamic_color", true))
            }
            var dynamicColorSeed by remember {
                mutableStateOf(sharedPreferences.getInt("dynamic_color_seed", defaultColorSeed.toArgb()))
            }
            var darkThemeSetting by remember {
                mutableIntStateOf(sharedPreferences.getInt("dark_theme", 0))
            }
            var hiddenApiBypassIndex by remember {
                val initialValue = try {
                    sharedPreferences.getInt("hidden_api_bypass", 1)
                } catch (_: ClassCastException) {
                    val stringValue = sharedPreferences.getString("hidden_api_bypass", null)
                    val parsedValue = stringValue?.toIntOrNull() ?: 1
                    sharedPreferences.edit { putInt("hidden_api_bypass", parsedValue) }
                    parsedValue
                }
                mutableIntStateOf(initialValue)
            }

            val useDarkTheme = when (darkThemeSetting) {
                1 -> true
                2 -> false
                else -> isSystemInDarkTheme()
            }
            AppTheme(darkTheme = useDarkTheme, dynamicColor = dynamicColor, dynamicColorSeed = Color(dynamicColorSeed)) {
                val alertDialogData by app.alertDialogState.collectAsState(initial = null)
                val showDialog = remember { mutableStateOf(false) }

                LaunchedEffect(alertDialogData) {
                    if (alertDialogData != null) {
                        showDialog.value = true
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
                ) {
                    if (alertDialogData != null) {
                        SuperDialog(
                            title = alertDialogData?.title ?: "",
                            summary = alertDialogData?.text ?: "",
                            show = showDialog,
                            onDismissRequest = { app.dismissAlert() }
                        ) {
                            TextButton(
                                text = "确定",
                                onClick = { app.dismissAlert() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    DetailedDrawerExample(
                        dynamicColor = dynamicColor,
                        onDynamicColorChange = {
                            dynamicColor = it
                            sharedPreferences.edit { putBoolean("dynamic_color", it) }
                        },
                        dynamicColorSeed = dynamicColorSeed,
                        onDynamicColorSeedChange = {
                            dynamicColorSeed = it
                            sharedPreferences.edit { putInt("dynamic_color_seed", it) }
                        },
                        darkThemeSetting = darkThemeSetting,
                        onDarkThemeSettingChange = {
                            darkThemeSetting = it
                            sharedPreferences.edit { putInt("dark_theme", it) }
                        },
                        hiddenApiBypass = hiddenApiBypassIndex,
                        onHiddenApiBypassChange = {
                            hiddenApiBypassIndex = it
                            sharedPreferences.edit { putInt("hidden_api_bypass", it) }
                            app.snackbar("重启应用生效", "重启") {
                                val intent = packageManager.getLaunchIntentForPackage(packageName)
                                val restartIntent =
                                    Intent.makeRestartActivityTask(intent?.component)
                                restartIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                val options = ActivityOptions.makeCustomAnimation(this, 0, 0)
                                startActivity(restartIntent, options.toBundle())
                                Runtime.getRuntime().exit(0)
                            }
                        }
                    )

                }
//                alertDialogData?.let { data ->
//                    AlertDialog(
//                        onDismissRequest = { app.dismissAlert() },
//                        title = { Text(data.title) },
//                        text = { Text(data.text) },
//                        confirmButton = {
//                            TextButton(onClick = { app.dismissAlert() }) {
//                                Text("确定")
//                            }
//                        }
//                    )
//                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedDrawerExample(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    dynamicColorSeed: Int,
    onDynamicColorSeedChange: (Int) -> Unit,
    darkThemeSetting: Int,
    onDarkThemeSettingChange: (Int) -> Unit,
    hiddenApiBypass: Int,
    onHiddenApiBypassChange: (Int) -> Unit
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
                        label = { Text("wifi管理器") },
                        selected = currentRoute == "Viewer",
                        icon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (currentRoute != "Viewer") {
                                navController.navigate("Viewer") {
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
                        hiddenApiBypass = hiddenApiBypass,
                        onHiddenApiBypassChange = onHiddenApiBypassChange,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        dynamicColorSeed = dynamicColorSeed,
                        onDynamicColorSeedChange = onDynamicColorSeedChange
                    )
                }
                composable("Pojie") {
                    PojieScreen(onMenuClick = { scope.launch { drawerState.open() } })
                }
                composable("Viewer") {
                    ManageScreen(onMenuClick = { scope.launch { drawerState.open() } })
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
