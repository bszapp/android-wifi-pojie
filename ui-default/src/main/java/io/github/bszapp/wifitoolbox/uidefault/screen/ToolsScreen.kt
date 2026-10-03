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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ToolsScreen(bottomInnerPadding: Dp = 0.dp) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "工具",
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
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
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "日志",
                            summary = "查看日志与终端",
                            startAction = {
                                Icon(
                                    imageVector = MiuixIcons.Notes,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "日志",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { navigator.push(Route.Log) },
                        )
                        ArrowPreference(
                            title = "资源",
                            summary = "管理密码本与内置资源",
                            startAction = {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "资源",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { navigator.push(Route.Resources) },
                        )
                        ArrowPreference(
                            title = "WPA Hashcat",
                            summary = "使用字典评估 WPA/WPA2 密码安全性",
                            startAction = {
                                Icon(imageVector = Icons.Filled.Security, contentDescription = "WPA Hashcat",
                                    modifier = Modifier.padding(end = 6.dp), tint = colorScheme.onBackground)
                            },
                            onClick = { navigator.push(Route.WpaHashcat) },
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(bottomInnerPadding + 12.dp))
                }
            }
        }
    }
}
