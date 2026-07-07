package io.github.bszapp.wifitoolbox.uidefault

import android.annotation.SuppressLint
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.uidefault.component.bottombar.BottomBarMiuix
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.MainScreenState
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.navigation.rememberNavigator
import io.github.bszapp.wifitoolbox.uidefault.screen.HomeScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.ListScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.SettingsScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.settings.ColorPaletteScreen
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBar
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBarBlur
import io.github.bszapp.wifitoolbox.uidefault.theme.WifiToolboxMiuixTheme
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun DefaultUI(viewModel: DefaultViewModel = viewModel()) {
    WifiToolboxMiuixTheme {
        val navigator = rememberNavigator(Route.Main)
        CompositionLocalProvider(LocalNavigator provides navigator) {
            when (navigator.current) {
                Route.Main -> MainPager(viewModel = viewModel)
                Route.ColorPalette -> ColorPaletteScreen()
            }
        }
    }
}

@Composable
private fun MainPager(viewModel: DefaultViewModel) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val mainState = MainScreenState(pagerState, rememberCoroutineScope())
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }


    val pagerContent = @Composable { bottomInnerPadding: androidx.compose.ui.unit.Dp ->
        Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
            HorizontalPager(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                state = pagerState,
                beyondViewportPageCount = 3,
            ) { page ->
                when (page) {
                    0 -> HomeScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
                    1 -> ListScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
                    2 -> SettingsScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
                }
            }
        }
    }

    val bottomBar = @Composable {
        Box(modifier = Modifier.fillMaxWidth()) {
            BottomBarMiuix(
                mainState = mainState,
                blurBackdrop = blurBackdrop,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    Scaffold(bottomBar = bottomBar) { innerPadding ->
        pagerContent(innerPadding.calculateBottomPadding())
    }
}
