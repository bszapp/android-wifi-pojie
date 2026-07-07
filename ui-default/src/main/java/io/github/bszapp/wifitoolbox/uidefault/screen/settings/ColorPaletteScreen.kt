package io.github.bszapp.wifitoolbox.uidefault.screen.settings

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.theme.ColorMode
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalSettingsManager
import io.github.bszapp.wifitoolbox.uidefault.theme.keyColorOptions
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ColorPaletteScreen() {
    val navigator = LocalNavigator.current
    val settingsManager = LocalSettingsManager.current
    val theme = settingsManager.settings.theme
    val colorModeName by theme.colorMode.value.collectAsStateWithLifecycle()
    val miuixMonet by theme.miuixMonet.value.collectAsStateWithLifecycle()
    val keyColor by theme.keyColor.value.collectAsStateWithLifecycle()
    val colorStyle by theme.colorStyle.value.collectAsStateWithLifecycle()
    val colorSpec by theme.colorSpec.value.collectAsStateWithLifecycle()
    val enableBlur by theme.enableBlur.value.collectAsStateWithLifecycle()
    val enableFloatingBottomBar by theme.enableFloatingBottomBar.value.collectAsStateWithLifecycle()
    val enableFloatingBottomBarBlur by theme.enableFloatingBottomBarBlur.value.collectAsStateWithLifecycle()
    val enablePredictiveBack by theme.enablePredictiveBack.value.collectAsStateWithLifecycle()
    val pageScale by theme.pageScale.value.collectAsStateWithLifecycle()

    val colorMode = ColorMode.fromName(colorModeName).let { mode ->
        when {
            miuixMonet && !mode.isMonet -> mode.toMonetMode()
            !miuixMonet && mode.isMonet -> mode.toNonMonetMode()
            else -> mode
        }
    }
    val currentPaletteStyle = runCatching { PaletteStyle.valueOf(colorStyle) }.getOrDefault(PaletteStyle.TonalSpot)
    val currentColorSpec = runCatching { ColorSpec.SpecVersion.valueOf(colorSpec) }.getOrDefault(ColorSpec.SpecVersion.SPEC_2025)
    val isDark = colorMode.isDark || colorMode.isSystem && isSystemInDarkTheme()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    val showScaleDialog = rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "主题设置",
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = colorScheme.onBackground,
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Spacer(modifier = Modifier.height(32.dp))
                    ThemePreviewCard(
                        keyColor = keyColor,
                        isDark = isDark,
                        miuixMonet = miuixMonet,
                        enableFloatingBottomBar = enableFloatingBottomBar,
                        enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                        paletteStyle = currentPaletteStyle,
                        colorSpec = currentColorSpec,
                    )
                    Spacer(modifier = Modifier.height(72.dp))

                    val themeItems = listOf("跟随系统", "浅色", "深色")
                    TabRow(
                        tabs = themeItems,
                        selectedTabIndex = colorMode.baseIndex.coerceIn(0, 2),
                        onTabSelected = { index ->
                            val values = if (miuixMonet) {
                                listOf(ColorMode.MONET_SYSTEM, ColorMode.MONET_LIGHT, ColorMode.MONET_DARK)
                            } else {
                                listOf(ColorMode.SYSTEM, ColorMode.LIGHT, ColorMode.DARK)
                            }
                            theme.colorMode.setEnum(values[index].name)
                        },
                        height = 48.dp,
                    )

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = "动态取色",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Wallpaper,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "动态取色",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = miuixMonet,
                            onCheckedChange = { enabled ->
                                theme.miuixMonet.set(enabled)
                                val currentMode = ColorMode.fromName(theme.colorMode.current)
                                theme.colorMode.setEnum(
                                    if (enabled) currentMode.toMonetMode().name else currentMode.toNonMonetMode().name,
                                )
                            },
                        )
                        AnimatedVisibility(visible = miuixMonet) {
                            Column {
                                val colors = listOf(0) + keyColorOptions
                                val colorNames = listOf(
                                    "默认", "红色", "粉红", "紫色", "深紫", "靛青", "蓝色", "青色", "青绿", "绿色",
                                    "黄色", "琥珀", "橙色", "棕色", "灰蓝", "樱花",
                                )
                                OverlayDropdownPreference(
                                    title = "强调色",
                                    items = colorNames,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.Colorize,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = "强调色",
                                            tint = colorScheme.onBackground,
                                        )
                                    },
                                    selectedIndex = colors.indexOf(keyColor).coerceAtLeast(0),
                                    onSelectedIndexChange = { theme.keyColor.set(colors[it]) },
                                )
                                AnimatedVisibility(visible = keyColor != 0) {
                                    Column {
                                        val styles = PaletteStyle.entries
                                        OverlayDropdownPreference(
                                            title = "色彩风格",
                                            items = styles.map { it.name },
                                            startAction = {
                                                Icon(
                                                    Icons.Rounded.Style,
                                                    modifier = Modifier.padding(end = 6.dp),
                                                    contentDescription = "色彩风格",
                                                    tint = colorScheme.onBackground,
                                                )
                                            },
                                            selectedIndex = styles.indexOf(currentPaletteStyle).coerceAtLeast(0),
                                            onSelectedIndexChange = { theme.colorStyle.set(styles[it].name) },
                                        )
                                        val specs = ColorSpec.SpecVersion.entries
                                        OverlayDropdownPreference(
                                            title = "色彩标准",
                                            items = specs.map { it.name },
                                            startAction = {
                                                Icon(
                                                    Icons.Rounded.DesignServices,
                                                    modifier = Modifier.padding(end = 6.dp),
                                                    contentDescription = "色彩标准",
                                                    tint = colorScheme.onBackground,
                                                )
                                            },
                                            selectedIndex = specs.indexOf(currentColorSpec).coerceAtLeast(0),
                                            onSelectedIndexChange = { theme.colorSpec.set(specs[it].name) },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            SwitchPreference(
                                title = "模糊",
                                summary = "启用顶栏和底栏的模糊效果",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.BlurOn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "模糊",
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                checked = enableBlur,
                                onCheckedChange = { theme.enableBlur.set(it) },
                            )
                        }
                        SwitchPreference(
                            title = "悬浮底栏",
                            summary = "使用 Apple 风格的悬浮底栏",
                            startAction = {
                                Icon(
                                    Icons.Rounded.CallToAction,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "悬浮底栏",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = enableFloatingBottomBar,
                            onCheckedChange = { theme.enableFloatingBottomBar.set(it) },
                        )
                        AnimatedVisibility(visible = enableFloatingBottomBar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            SwitchPreference(
                                title = "液态玻璃",
                                summary = "启用悬浮底栏的液态玻璃效果",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.WaterDrop,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "液态玻璃",
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                checked = enableFloatingBottomBarBlur,
                                onCheckedChange = { theme.enableFloatingBottomBarBlur.set(it) },
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            SwitchPreference(
                                title = "预测性返回手势",
                                summary = "启用对预测性返回手势的支持",
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.MenuOpen,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "预测性返回手势",
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                checked = enablePredictiveBack,
                                onCheckedChange = { theme.enablePredictiveBack.set(it) },
                            )
                        }

                        var sliderValue by remember(pageScale) { mutableFloatStateOf(pageScale.coerceIn(0.8f, 1.1f)) }
                        ArrowPreference(
                            title = "界面缩放",
                            summary = "调整全局显示比例",
                            startAction = {
                                Icon(
                                    Icons.Rounded.AspectRatio,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "界面缩放",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            endActions = {
                                Text(
                                    text = "${(sliderValue * 100).toInt()}%",
                                    color = colorScheme.onSurfaceVariantActions,
                                )
                            },
                            onClick = { showScaleDialog.value = !showScaleDialog.value },
                            holdDownState = showScaleDialog.value,
                            bottomAction = {
                                Slider(
                                    value = sliderValue,
                                    onValueChange = { sliderValue = it },
                                    onValueChangeFinished = { theme.pageScale.set(sliderValue) },
                                    valueRange = 0.8f..1.1f,
                                    showKeyPoints = true,
                                    keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                                    magnetThreshold = 0.01f,
                                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                )
                            },
                        )
                        ScaleDialog(
                            show = showScaleDialog.value,
                            onDismissRequest = { showScaleDialog.value = false },
                            volumeState = { pageScale },
                            onVolumeChange = { theme.pageScale.set(it) },
                        )
                    }
                }
                item {
                    Spacer(
                        Modifier.height(
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() +
                                    12.dp,
                        ),
                    )
                }
            }
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun ThemePreviewCard(
    keyColor: Int,
    isDark: Boolean,
    miuixMonet: Boolean,
    enableFloatingBottomBar: Boolean = false,
    enableFloatingBottomBarBlur: Boolean = false,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2021,
) {
    val configuration = LocalConfiguration.current
    val screenRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
    val seedColor = if (keyColor == 0) colorScheme.primary else Color(keyColor)
    val effectiveStyle = if (keyColor == 0) PaletteStyle.TonalSpot else paletteStyle
    val effectiveSpec = if (keyColor == 0) ColorSpec.SpecVersion.Default else colorSpec
    val dynamicCs = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style = effectiveStyle,
        specVersion = effectiveSpec,
    )
    val bgColor = if (miuixMonet) dynamicCs.background else colorScheme.surface
    val textColor = if (miuixMonet) dynamicCs.onSurface else colorScheme.onBackground
    val accentCardColor = when {
        miuixMonet -> dynamicCs.secondaryContainer
        isDark -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }
    val cardColor = if (miuixMonet) dynamicCs.surfaceContainerHighest else colorScheme.surfaceVariant
    val navBarColor = if (miuixMonet) dynamicCs.surfaceContainer else colorScheme.surface
    val iconColor = if (miuixMonet) dynamicCs.primary else colorScheme.primary
    val navSelectedColor = colorScheme.onSurfaceContainer
    val navUnselectedColor = colorScheme.onSurfaceContainer.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .aspectRatio(screenRatio)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .border(1.dp, colorScheme.outline, RoundedCornerShape(20.dp)),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "Wi-Fi Toolbox", fontSize = 12.sp, color = textColor)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(65.dp)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentCardColor),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardColor),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardColor),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (enableFloatingBottomBar) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (enableFloatingBottomBarBlur) navBarColor.copy(alpha = 0.55f) else navBarColor)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == 0) 10.dp else 8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (index == 0) iconColor else navUnselectedColor),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .background(navBarColor)
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == 0) 7.dp else 6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (index == 0) iconColor else navSelectedColor.copy(alpha = 0.35f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaleDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    volumeState: () -> Float,
    onVolumeChange: (Float) -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "界面缩放",
        summary = "80% - 110%",
        onDismissRequest = onDismissRequest,
        content = {
            var text by remember(show) {
                mutableStateOf((volumeState() * 100).toInt().toString())
            }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                trailingIcon = {
                    Text(
                        text = "%",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.onSurfaceVariantActions,
                    )
                },
                onValueChange = { newValue ->
                    if (newValue.isEmpty()) {
                        text = ""
                    } else if (newValue.all { it.isDigit() }) {
                        text = newValue
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "取消",
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        val parsed = text.toIntOrNull()
                        val clamped = parsed?.coerceIn(80, 110) ?: (volumeState() * 100).toInt()
                        onVolumeChange(clamped / 100f)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
