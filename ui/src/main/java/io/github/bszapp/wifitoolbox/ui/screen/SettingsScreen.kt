package io.github.bszapp.wifitoolbox.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import io.github.bszapp.wifitoolbox.ui.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.ui.screen.settings.InstallContainerConfirmationDialog
import io.github.bszapp.wifitoolbox.ui.screen.settings.ResetContainerConfirmationDialog
import io.github.bszapp.wifitoolbox.ui.screen.settings.UninstallContainerConfirmationDialog
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.ui.util.BlurredBar
import io.github.bszapp.wifitoolbox.ui.util.rememberBlurBackdrop
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
import io.github.bszapp.wifitoolbox.ui.component.SectionTitle
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
    var showUpdateConfirmation by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showUninstallConfirmation by rememberSaveable { mutableStateOf(false) }
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    InstallContainerConfirmationDialog(
        show = showInstallConfirmation,
        onDismiss = { showInstallConfirmation = false },
        onConfirm = {
            showInstallConfirmation = false
            viewModel.installContainer()
        },
    )
    InstallContainerConfirmationDialog(
        show = showUpdateConfirmation,
        title = "更新容器系统",
        onDismiss = { showUpdateConfirmation = false },
        onConfirm = {
            showUpdateConfirmation = false
            viewModel.updateContainer()
        },
    )
    ResetContainerConfirmationDialog(
        show = showResetConfirmation,
        onDismiss = { showResetConfirmation = false },
        onConfirm = {
            showResetConfirmation = false
            viewModel.resetContainer()
        },
    )
    UninstallContainerConfirmationDialog(
        show = showUninstallConfirmation,
        onDismiss = { showUninstallConfirmation = false },
        onConfirm = {
            showUninstallConfirmation = false
            viewModel.uninstallContainer()
        },
    )

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
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    SectionTitle(text = "主题外观")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
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
                    SectionTitle(text = "容器系统")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth(),
                    ) {
                io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
                    targetState = containerState.installed,
                    label = "container-install-options",
                ) { installed ->
                    Column {
                        if (!installed) {
                            ArrowPreference(
                                title = "安装容器系统",
                                summary = "解压所选来源的容器系统",
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
                                title = "更新容器系统",
                                summary = "将所选来源覆盖安装到当前容器系统",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Inventory2,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showUpdateConfirmation = true },
                            )
                            ArrowPreference(
                                title = "重置容器",
                                summary = "删除现有容器并重新解压内置容器系统",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.RestartAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showResetConfirmation = true },
                            )
                            ArrowPreference(
                                title = "卸载容器",
                                summary = "删除已解压的容器系统",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeleteForever,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showUninstallConfirmation = true },
                            )
                        }
                    }
                }
                    }
                }

                item {
                    SectionTitle(text = "关于")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "关于",
                            onClick = { navigator.push(Route.About) },
                        )
                    }

                    Spacer(Modifier.height(bottomInnerPadding + 12.dp))
                }
            }
        }
    }
}
