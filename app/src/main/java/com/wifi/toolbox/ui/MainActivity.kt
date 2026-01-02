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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.core.content.edit
import androidx.navigation.compose.*
import com.wifi.toolbox.BuildConfig
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.items.AppDetailedDrawer
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
import androidx.core.graphics.toColorInt

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

                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
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
                            AppDetailedDrawer(
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
                                        this@MainActivity.startActivity(intent, ActivityOptions.makeCustomAnimation(this@MainActivity, 0, 0).toBundle())
                                        Runtime.getRuntime().exit(0)
                                    }
                                },
                                pendingNavigation = pendingNavigation
                            )
                        }

                        if (BuildConfig.DEBUG) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.save()
                                    canvas.nativeCanvas.translate(drawContext.size.width, 0f)
                                    canvas.nativeCanvas.rotate(45f)

                                    val paint = android.graphics.Paint().apply {
                                        color = "#77FF0000".toColorInt()
                                        style = android.graphics.Paint.Style.FILL
                                    }
                                    canvas.nativeCanvas.drawRect(-200f, 90f, 200f, 170f, paint)

                                    val textPaint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.WHITE
                                        textSize = 32f
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        isFakeBoldText = true
                                    }
                                    canvas.nativeCanvas.drawText("DEBUG", 0f, 130f, textPaint)

                                    textPaint.textSize = 24f
                                    textPaint.isFakeBoldText = false
                                    canvas.nativeCanvas.drawText("${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})_${BuildConfig.BUILD_COUNT}", 0f, 160f, textPaint)

                                    canvas.nativeCanvas.restore()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}