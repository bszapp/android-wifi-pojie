package io.github.bszapp.wifitoolbox.uidefault

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.bszapp.wifitoolbox.uidefault.component.bottombar.BottomBarMiuix
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.MainScreenState
import io.github.bszapp.wifitoolbox.uidefault.model.rememberMainScreenState
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.navigation.rememberNavigator
import io.github.bszapp.wifitoolbox.uidefault.screen.HomeScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.ListScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.LogScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.SettingsScreen
import io.github.bszapp.wifitoolbox.uidefault.screen.settings.ColorPaletteScreen
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBar
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBarBlur
import io.github.bszapp.wifitoolbox.uidefault.theme.WifiToolboxDefaultTheme
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val TAG = "DefaultUI"

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun DefaultUI(viewModel: DefaultViewModel = viewModel()) {
    val navigator = rememberNavigator(Route.Main)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel, snackbarHostState, context) {
        coroutineScope {
            viewModel.errors.collect { error ->
                // 每条错误使用独立协程进入 MIUIX SnackbarHostState，允许同时排队/堆叠显示。
                launch {
                    Log.e(
                        TAG,
                        "收到 App 统一错误广播：${error.message}\n${error.details}",
                    )
                    val result = snackbarHostState.showSnackbar(
                        message = error.message,
                        actionLabel = "复制",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val clipboard =
                            context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                "Wi-Fi Toolbox 错误详情",
                                error.details,
                            ),
                        )
                    }
                }
            }
        }
    }

    WifiToolboxDefaultTheme {
        CompositionLocalProvider(LocalNavigator provides navigator) {
            NavDisplay(
                backStack = navigator.backStack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                onBack = { navigator.pop() },
                entryProvider = entryProvider {
                    entry<Route.Main> {
                        MainPager(
                            viewModel = viewModel,
                            snackbarHostState = snackbarHostState,
                        )
                    }
                    entry<Route.ColorPalette> { ColorPaletteScreen() }
                },
            )
        }
    }
}

@Composable
private fun MainPager(
    viewModel: DefaultViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val navigator = LocalNavigator.current
    val pagerState = rememberPagerState(pageCount = { 4 })
    val mainState = rememberMainScreenState(pagerState)
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val currentPage = mainState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainState.syncPage()
    }

    MainScreenBackHandler(mainState = mainState, navigator = navigator)

    val pagerContent = @Composable { bottomInnerPadding: androidx.compose.ui.unit.Dp ->
        Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
            HorizontalPager(
                modifier = Modifier
                    .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                state = mainState.pagerState,
                beyondViewportPageCount = 4,
            ) { page ->
                when (page) {
                    0 -> HomeScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
                    1 -> ListScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
                    2 -> LogScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
                    3 -> SettingsScreen(viewModel = viewModel, bottomInnerPadding = bottomInnerPadding)
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

    Scaffold(
        bottomBar = bottomBar,
        snackbarHost = {
            SnackbarHost(state = snackbarHostState)
        },
    ) { innerPadding ->
        pagerContent(innerPadding.calculateBottomPadding())
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainScreenState,
    navigator: io.github.bszapp.wifitoolbox.uidefault.navigation.Navigator,
) {
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = navigator.current() is Route.Main && navigator.backStackSize() == 1 && mainState.selectedPage != 0,
        onBackCompleted = { mainState.animateToPage(0) },
    )
}
