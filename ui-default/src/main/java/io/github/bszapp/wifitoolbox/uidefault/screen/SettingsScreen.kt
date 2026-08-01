package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bszapp.wifitoolbox.contract.container.ContainerSystemStatus
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.screen.settings.ContainerProgressSheet
import io.github.bszapp.wifitoolbox.uidefault.screen.settings.InstallContainerConfirmationSheet
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.RestartAlt
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val containerState by viewModel.containerState.collectAsStateWithLifecycle()
    var showInstallConfirmation by rememberSaveable { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    InstallContainerConfirmationSheet(
        show = showInstallConfirmation,
        onDismiss = { showInstallConfirmation = false },
        onConfirm = {
            showInstallConfirmation = false
            viewModel.installContainer()
        },
    )
    ContainerProgressSheet(state = containerState)

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "设置",
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
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "主题设置",
                            summary = "自定义更多主题选项",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "主题设置",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { navigator.push(Route.ColorPalette) },
                        )
                    }
                }
                item {
                    //TODO:容器重置二次确认？还有需要一个更新容器功能
                    SmallTitle(text = "容器系统")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        if (!containerState.installed) {
                            ArrowPreference(
                                title = "安装容器系统",
                                summary = when (containerState.systemStatus) {
                                    ContainerSystemStatus.CHECKING -> "正在检查容器状态"
                                    ContainerSystemStatus.ERROR -> containerState.errorMessage ?: "容器操作失败"
                                    else -> "解压 APK 内置的容器系统"
                                },
                                enabled = containerState.systemStatus != ContainerSystemStatus.CHECKING && !containerState.isBusy,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Inventory2,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showInstallConfirmation = true },
                            )
                        } else {
                            ArrowPreference(
                                title = "重置容器",
                                summary = "删除现有容器并重新解压内置容器系统",
                                enabled = !containerState.isBusy,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.RestartAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = viewModel::resetContainer,
                            )
                            ArrowPreference(
                                title = "卸载容器",
                                summary = "删除已解压的容器系统",
                                enabled = !containerState.isBusy,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeleteForever,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = viewModel::uninstallContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}
