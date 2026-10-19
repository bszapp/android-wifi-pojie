package io.github.bszapp.wifitoolbox.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.bszapp.wifitoolbox.ui.component.bottombar.BottomBarMiuix
import io.github.bszapp.wifitoolbox.ui.component.CommonConfirmationDialog
import io.github.bszapp.wifitoolbox.ui.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.ui.model.MainScreenState
import io.github.bszapp.wifitoolbox.ui.model.rememberMainScreenState
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.navigation.rememberNavigator
import io.github.bszapp.wifitoolbox.ui.screen.HomeScreen
import io.github.bszapp.wifitoolbox.ui.screen.ListScreen
import io.github.bszapp.wifitoolbox.ui.screen.LogScreen
import io.github.bszapp.wifitoolbox.ui.screen.SettingsScreen
import io.github.bszapp.wifitoolbox.ui.screen.ToolsScreen
import io.github.bszapp.wifitoolbox.ui.dictionary.DictionaryScreen
import io.github.bszapp.wifitoolbox.ui.dictionary.DictionaryEditorScreen
import io.github.bszapp.wifitoolbox.ui.dictionary.DictionaryViewModel
import io.github.bszapp.wifitoolbox.ui.screen.settings.ColorPaletteScreen
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableFloatingBottomBar
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableFloatingBottomBarBlur
import io.github.bszapp.wifitoolbox.ui.theme.WifiToolboxDefaultTheme
import io.github.bszapp.wifitoolbox.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val TAG = "DefaultUI"

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun DefaultUI(
    dictionaryViewModel: DictionaryViewModel,
    viewModel: DefaultViewModel = viewModel(),
    openPskSecurityAudit: Boolean = false,
    onPskSecurityAuditOpened: () -> Unit = {},
) {
    val navigator = rememberNavigator(Route.Main)
    val dictionaryState = dictionaryViewModel.state
    val sheetPresentation = remember { io.github.bszapp.wifitoolbox.ui.component.SheetPresentationManager() }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val confirmationDialogs by
        viewModel.confirmationDialogs.collectAsStateWithLifecycle()
    val containerState by viewModel.containerState.collectAsStateWithLifecycle()
    LaunchedEffect(openPskSecurityAudit) {
        if (openPskSecurityAudit) {
            navigator.backStack.clear()
            navigator.backStack.add(Route.Main)
            viewModel.wifiList.selectSubmode(io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode.PSK_SECURITY_AUDIT)
        }
    }

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
        CompositionLocalProvider(
            LocalNavigator provides navigator,
            io.github.bszapp.wifitoolbox.ui.component.LocalSheetPresentationManager provides sheetPresentation,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                                    openPskSecurityAudit = openPskSecurityAudit,
                                    onPskSecurityAuditOpened = onPskSecurityAuditOpened,
                                )
                            }
                            entry<Route.ColorPalette> { ColorPaletteScreen() }
                            entry<Route.About> { io.github.bszapp.wifitoolbox.ui.about.MiuixAboutScreen() }
                            entry<Route.WifiQrScanner> {
                                io.github.bszapp.wifitoolbox.ui.screen.WifiQrScannerScreen(
                                    controller = viewModel.wifiQrCamera,
                                    onNetwork = { network ->
                                        if (navigator.current() == Route.WifiQrScanner) {
                                            viewModel.acceptWifiQrNetwork(network)
                                            navigator.pop()
                                        }
                                    },
                                )
                            }
                            entry<Route.Resources> { DictionaryScreen(dictionaryState) }
                            entry<Route.DictionaryEditor> { DictionaryEditorScreen(dictionaryState) }
                            entry<Route.Log> {
                                LogScreen(
                                    viewModel = viewModel,
                                    snackbarHostState = snackbarHostState,
                                )
                            }
                            entry<Route.Capture> { route -> io.github.bszapp.wifitoolbox.ui.screen.CaptureScreen(route.bssid, route.deviceMac) }
                            entry<Route.CaptureDetail> { route -> io.github.bszapp.wifitoolbox.ui.screen.CaptureDetailScreen(route) }
                            entry<Route.WpaHashcat> { io.github.bszapp.wifitoolbox.ui.hashcat.HashcatScreen(snackbarHostState = snackbarHostState) }
                            entry<Route.PskSecurityAuditHistory> { io.github.bszapp.wifitoolbox.ui.psksecurityaudit.PskSecurityAuditHistoryScreen(viewModel) }
                            entry<Route.HashcatRun> { route -> io.github.bszapp.wifitoolbox.ui.hashcat.HashcatScreen(
                                snackbarHostState = snackbarHostState, initialHandshake = route.handshake) }
                        },
                    )

                    io.github.bszapp.wifitoolbox.ui.screen.settings.ContainerProgressSheet(
                        state = containerState,
                        onInterrupt = viewModel::interruptContainer,
                    )

                    io.github.bszapp.wifitoolbox.ui.psksecurityaudit.PskSecurityAuditSheets(viewModel.pskSecurityAudit)

                    io.github.bszapp.wifitoolbox.ui.psksecurityaudit.WpsAuditSheets(viewModel.pskSecurityAudit)

                    confirmationDialogs.forEach { dialog ->
                        key(dialog.id) {
                            CommonConfirmationDialog(
                                show = dialog.show,
                                title = dialog.title,
                                content = dialog.content,
                                cancelButtonText = dialog.cancelButtonText,
                                confirmButtonText = dialog.confirmButtonText,
                                onDismissRequest = {
                                    viewModel.dismissConfirmationDialog(dialog.id)
                                },
                                onCancel = {
                                    viewModel.cancelConfirmationDialog(dialog.id)
                                },
                                onConfirm = {
                                    viewModel.confirmConfirmationDialog(dialog.id)
                                },
                                onDismissFinished = {
                                    viewModel.confirmationDialogDismissFinished(dialog.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPager(
    viewModel: DefaultViewModel,
    snackbarHostState: SnackbarHostState,
    openPskSecurityAudit: Boolean = false,
    onPskSecurityAuditOpened: () -> Unit = {},
) {
    val navigator = LocalNavigator.current
    val pagerState = rememberPagerState(pageCount = { 4 })
    val mainState = rememberMainScreenState(pagerState)
    LaunchedEffect(openPskSecurityAudit) {
        if (openPskSecurityAudit) {
            pagerState.scrollToPage(1)
            mainState.syncPage()
            onPskSecurityAuditOpened()
        }
    }
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
                    2 -> ToolsScreen(bottomInnerPadding = bottomInnerPadding)
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
    navigator: io.github.bszapp.wifitoolbox.ui.navigation.Navigator,
) {
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = navigator.current() is Route.Main && navigator.backStackSize() == 1 && mainState.selectedPage != 0,
        onBackCompleted = { mainState.animateToPage(0) },
    )
}
