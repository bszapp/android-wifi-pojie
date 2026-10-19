package io.github.bszapp.wifitoolbox.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.navigation.rememberNavigator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 无头渲染主题注入点：QA 渲染器提供值后，包内所有走 [PreviewSheetHost] 的
 * 弹层 Preview 真身即按渲染主题出图；生产（Android 端、Android Studio 预览面板）
 * 恒为 null，视觉与现状完全一致。
 */
val LocalQaThemeController = staticCompositionLocalOf<ThemeController?> { null }

/**
 * 弹层类预览的统一宿主：miuix 树内弹层（OverlayBottomSheet / OverlayDialog）
 * 都挂在 Scaffold 的弹层槽位上，且单 Sheet 显示权由
 * [LocalSheetPresentationManager] 授予。预览真身包一层本宿主即可自包含地
 * 在 Android Studio 面板与无头渲染里同时成立。
 * （平台窗口式的 WindowDialog 已在 BlockingLoadingDialog 换成 OverlayDialog，全项目无窗口级弹层。）
 *
 * 生产在 DefaultUIMain 根部提供 [LocalNavigator]；预览宿主自带一个以
 * Route.Main 为起点的导航器，避免读取 navigator 的弹层真身抛错。
 */
@Composable
fun PreviewSheetHost(content: @Composable () -> Unit) {
    val qaController = LocalQaThemeController.current
    if (qaController == null) {
        MiuixTheme {
            PreviewSheetHostBody(content)
        }
    } else {
        MiuixTheme(controller = qaController) {
            PreviewSheetHostBody(content)
        }
    }
}

@Composable
private fun PreviewSheetHostBody(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSheetPresentationManager provides SheetPresentationManager(),
        LocalNavigator provides rememberNavigator(Route.Main),
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
            Box(Modifier.fillMaxSize().padding(paddingValues)) { content() }
        }
    }
}
