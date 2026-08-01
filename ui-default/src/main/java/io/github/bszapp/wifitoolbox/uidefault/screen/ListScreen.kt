package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.wifilist.isScanning
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiInformationSource
import io.github.bszapp.wifitoolbox.uidefault.component.ListPopupDefaults
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.uidefault.widget.WifiList
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ListScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val wifiState by viewModel.wifiList.state.collectAsStateWithLifecycle()
    val isSendingScanRequest by
        viewModel.wifiList.isSendingScanRequest.collectAsStateWithLifecycle()
    val informationSourceState by
        viewModel.wifiList.informationSourceState.collectAsStateWithLifecycle()
    val selectedSource = informationSourceState?.source ?: WifiInformationSource.SYSTEM
    val isInitializing = informationSourceState?.initializing == true
    val isScanning = wifiState.isScanning || isSendingScanRequest
    val controlsBusy = isScanning || isInitializing
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = selectedSource.displayName,
                    navigationIcon = {
                        Box {
                            val showTopPopup = remember { mutableStateOf(false) }
                            OverlayListPopup(
                                show = showTopPopup.value,
                                popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showTopPopup.value = false },
                                content = {
                                    ListPopupColumn {
                                        //TODO:错了！是分组，选项都叫扫描，不是给每个选项加小标题
                                        DropdownImpl(
                                            item = DropdownItem(
                                                text = "系统模式",
                                                summary = "扫描",
                                            ),
                                            isSelected = selectedSource == WifiInformationSource.SYSTEM,
                                            optionSize = 2,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                viewModel.wifiList.setInformationSource(
                                                    WifiInformationSource.SYSTEM,
                                                )
                                            },
                                            index = 0,
                                        )
                                        DropdownImpl(
                                            item = DropdownItem(
                                                text = "混合模式",
                                                summary = "扫描",
                                            ),
                                            isSelected = selectedSource == WifiInformationSource.HYBRID,
                                            optionSize = 2,
                                            onSelectedIndexChange = {
                                                showTopPopup.value = false
                                                viewModel.wifiList.setInformationSource(
                                                    WifiInformationSource.HYBRID,
                                                )
                                            },
                                            index = 1,
                                        )
                                    }
                                },
                            )
                            IconButton(
                                onClick = { showTopPopup.value = true },
                                holdDownState = showTopPopup.value,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.MoreCircle,
                                    tint = colorScheme.onSurface,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.wifiList.startScan() },
                            enabled = !controlsBusy,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                tint = colorScheme.onSurface,
                                contentDescription = "刷新",
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
        val layoutDirection = LocalLayoutDirection.current
        val refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成")
        PullToRefresh(
            isRefreshing = isScanning,
            pullToRefreshState = pullState,
            onRefresh = {
                if (!isInitializing) viewModel.wifiList.startScan()
            },
            refreshTexts = refreshTexts,
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = bottomInnerPadding,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().let {
                    if (backdrop != null) it.layerBackdrop(backdrop) else it
                },
            ) {
                if (isInitializing) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = bottomInnerPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "初始化",
                            modifier = Modifier.padding(top = 12.dp),
                            color = colorScheme.onSurface,
                        )
                    }
                } else {
                    WifiList(
                        modifier = Modifier
                            .fillMaxHeight()
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        vm = viewModel,
                        listState = listState,
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + 14.dp,
                            start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                            end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                            bottom = bottomInnerPadding + 8.dp,
                        ),
                    )
                }
            }
        }
    }
}
