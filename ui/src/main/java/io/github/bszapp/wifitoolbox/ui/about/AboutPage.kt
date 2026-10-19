// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

@file:OptIn(ExperimentalScrollBarApi::class)

package io.github.bszapp.wifitoolbox.ui.about

import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bszapp.wifitoolbox.ui.R
import io.github.bszapp.wifitoolbox.ui.about.component.BackNavigationIcon
import io.github.bszapp.wifitoolbox.ui.about.component.blend.ColorBlendToken
import io.github.bszapp.wifitoolbox.ui.about.component.effect.BgEffectBackground
import io.github.bszapp.wifitoolbox.ui.about.misc.LocalVersionInfo
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.bszapp.wifitoolbox.ui.about.utils.BlurredBar
import io.github.bszapp.wifitoolbox.ui.about.utils.pageContentPadding
import io.github.bszapp.wifitoolbox.ui.about.utils.pageScrollModifiers
import io.github.bszapp.wifitoolbox.ui.about.utils.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.ui.component.SectionTitle
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

@Composable
fun AboutPage(
    padding: PaddingValues,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val navigator = LocalNavigator.current
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f

                else -> {
                    val spacer =
                        lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(
                            0f,
                            1f
                        )
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val backdrop = rememberBlurBackdrop()
    // Defer the frame-rate scroll read out of composition: these booleans only flip at the
    // single 1f threshold, so derivedStateOf recomposes the bar on flip rather than every frame.
    val collapsed by remember { derivedStateOf { scrollProgress == 1f } }
    val blurActive by remember(backdrop) { derivedStateOf { backdrop != null && scrollProgress == 1f } }

    Scaffold(
        topBar = {
            val barColor = if (blurActive) {
                Color.Transparent
            } else {
                if (collapsed) MiuixTheme.colorScheme.surface else Color.Transparent
            }
            val titleColor = MiuixTheme.colorScheme.onSurface.copy(
                alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
            )
            BlurredBar(backdrop, blurActive) {
                SmallTopAppBar(
                    title = "关于",
                    scrollBehavior = topAppBarScrollBehavior,
                    color = barColor,
                    titleColor = titleColor,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        BackNavigationIcon(
                            onClick = { navigator.pop() },
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            AboutContent(
                padding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                lazyListState = lazyListState,
                scrollProgressProvider = { scrollProgress },
            )
        }
    }
}

@Composable
private fun AboutContent(
    padding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
) {
    val appState = LocalAppState.current
    val isWideScreen = LocalIsWideScreen.current
    val uriHandler = LocalUriHandler.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appName = remember(context) {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }
    val versionInfo = LocalVersionInfo.current

    val backdrop = rememberBlurBackdrop()
    var isOs3Effect by remember { mutableStateOf(true) }
    var blurRadius by remember { mutableFloatStateOf(60f) }
    var noiseCoefficient by remember { mutableFloatStateOf(BlurDefaults.NoiseCoefficient) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    val scrollPadding = pageContentPadding(
        padding,
        padding,
        isWideScreen,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateRightPadding(LayoutDirection.Ltr),
    )
    val logoPadding = pageContentPadding(
        padding,
        padding,
        isWideScreen,
        extraTop = 40.dp,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateLeftPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateRightPadding(LayoutDirection.Ltr),
    )

    val isInDark = isInDarkTheme()
    val dynamicBackground = remember { mutableStateOf(isRuntimeShaderSupported()) }
    val isFullScreenBackground = remember { mutableStateOf(true) }

    val cardBlend =
        if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }

    BgEffectBackground(
        dynamicBackground = dynamicBackground.value,
        isOs3Effect = isOs3Effect,
        isFullSize = isFullScreenBackground.value,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
        alpha = { 1f - scrollProgressProvider() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateLeftPadding(LayoutDirection.Ltr),
                    end = logoPadding.calculateRightPadding(LayoutDirection.Ltr),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        clip = true
                        shape = RoundedCornerShape(24.dp)
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .background(Color.White),
            ) {
//                Image(
//                    modifier = Modifier.size(74.dp),
//                    painter = painterResource(R.drawable.miuix_about_icon),
//                    contentDescription = null,
//                )
                //TODO
                Text("（此处应有一个图标，求一个）")
            }
            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        val projectNameProgress =
                            ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (backdrop != null) {
                            Modifier
                                .textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 150f,
                                    noiseCoefficient = noiseCoefficient,
                                    colors = BlurDefaults.blurColors(
                                        blendColors = logoBlend,
                                    ),
                                    contentBlendMode = ComposeBlendMode.DstIn,
                                )
                        } else {
                            Modifier
                        },
                    ),
                text = appName,
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionCodeProgress =
                            ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                text = "v${versionInfo.versionName} (${versionInfo.versionCode})",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        // Scrollable content
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .pageScrollModifiers(
                    appState.enableScrollEndHaptic,
                    appState.showTopAppBar,
                    topAppBarScrollBehavior,
                ),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateLeftPadding(LayoutDirection.Ltr),
                end = scrollPadding.calculateRightPadding(LayoutDirection.Ltr),
            ),
        ) {
            // Transparent spacer matching logo height
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        )
                        .pointerInput(Unit) {
                            detectTapGestures {
                                //TODO:做点什么呢
                            }
                        },
                    contentAlignment = Alignment.TopCenter,
                    content = { },
                )
            }
            item(key = "about") {
                SectionTitle(text = "关于")
                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                ) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .then(
                                if (backdrop != null) {
                                    Modifier
                                        .textureBlur(
                                            backdrop = backdrop,
                                            shape = RoundedCornerShape(16.dp),
                                            blurRadius = blurRadius,
                                            noiseCoefficient = noiseCoefficient,
                                            colors = BlurDefaults.blurColors(
                                                blendColors = cardBlend,
                                                brightness = brightness,
                                                contrast = contrast,
                                                saturation = saturation,
                                            ),
                                        )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = "查看源代码",
                            summary = "在Github上查看源代码",
                            onClick = { uriHandler.openUri("https://github.com/bszapp/android-wifi-pojie") },
                        )
                        ArrowPreference(
                            title = "Telegram频道",
                            summary = "加入我们的Telegram频道",
                            onClick = { uriHandler.openUri("https://t.me/bszapp") },
                        )
                    }
                    SectionTitle(text = "附注")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .then(
                                if (backdrop != null) {
                                    Modifier
                                        .textureBlur(
                                            backdrop = backdrop,
                                            shape = RoundedCornerShape(16.dp),
                                            blurRadius = blurRadius,
                                            noiseCoefficient = noiseCoefficient,
                                            colors = BlurDefaults.blurColors(
                                                blendColors = cardBlend,
                                                brightness = brightness,
                                                contrast = contrast,
                                                saturation = saturation,
                                            ),
                                        )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        Text(
                            text = "当前为预览版本，与GitHub上最后一个发行版不属于同一项目。\n源码位于https://github.com/bszapp/android-wifi-pojie中的master分支。\n\n借鉴（上一版本有名词注释）了以下内容：\nKernelSU的界面\niamr0s/Ruto-GLM的服务启动方式\nShizuku的服务常驻原理\nLSPosed的日志页面(逆向)\nInstallerxRevived的零碎界面组件\nStryker的终端原理(部分)\nAndroidStudio的日志分类方式(逆向)\nLogFox的日志logcat启动命令\n\n其他作者：Claude(应用最初的架构)、ChatGPT(应用的的最新架构、终端容器构建、monitor的几乎所有解析解密数据功能、hashcat性能优化、旧版本大量功能移植等)，以及OpenCode众多开源免费模型（一些无道德的事情比如wpspin破解以及随机mac运行功能）",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(lazyListState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = scrollPadding,
        )
    }
}
