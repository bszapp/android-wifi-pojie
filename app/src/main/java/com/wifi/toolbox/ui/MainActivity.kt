package com.wifi.toolbox.ui

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
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
import top.yukonga.miuix.kmp.theme.ThemeController

private val DrawerWidth = 300.dp
private val NavItemHeight = 48.dp

class MainActivity : ComponentActivity() {

    private var pendingNavigation = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getStringExtra("target")
        if (target != null) {
            pendingNavigation.value = target
        }
    }

    private fun handleIntent(intent: Intent) {
        val target = intent.getStringExtra("target")
        if (target != null) {
            pendingNavigation.value = target
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

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
                mutableIntStateOf(sharedPreferences.getInt("dynamic_color_seed", defaultColorSeed.toArgb()))
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

            MiuixTheme(ThemeController(isDark = useDarkTheme)) {
                AppTheme(
                    darkTheme = useDarkTheme,
                    dynamicColor = dynamicColor,
                    dynamicColorSeed = Color(dynamicColorSeed)
                ) {
                    val alertDialogData by app.alertDialogState.collectAsState(initial = null)
                    val showDialog = remember { mutableStateOf(false) }

                    LaunchedEffect(alertDialogData) {
                        if (alertDialogData != null) showDialog.value = true
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
                                    val restartIntent = packageManager.getLaunchIntentForPackage(packageName)
                                    val intent = Intent.makeRestartActivityTask(restartIntent?.component)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                                    startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
                                    Runtime.getRuntime().exit(0)
                                }
                            },
                            pendingNavigation = pendingNavigation
                        )
                    }
                }
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
                                    navController.navigate(route) { launchSingleTop = true }
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
                                    navController.navigate(route) { launchSingleTop = true }
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