package io.github.bszapp.wifitoolbox.uidefault.component.bottombar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bszapp.wifitoolbox.uidefault.component.FloatingBottomBar
import io.github.bszapp.wifitoolbox.uidefault.component.FloatingBottomBarItem
import io.github.bszapp.wifitoolbox.uidefault.model.MainScreenState
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBar
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBarBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BottomBarMiuix(
    mainState: MainScreenState,
    blurBackdrop: LayerBackdrop?,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val items = BottomBarDestination.entries.map { destination ->
        NavigationItem(label = destination.label, icon = destination.icon)
    }

    if (!enableFloatingBottomBar) {
        BlurredBar(blurBackdrop) {
            NavigationBar(
                modifier = modifier,
                color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        modifier = Modifier.weight(1f),
                        icon = item.icon,
                        label = item.label,
                        selected = mainState.selectedPage == index,
                        onClick = { mainState.animateToPage(index) },
                    )
                }
            }
        }
    } else {
        FloatingBottomBar(
            modifier = modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            selectedIndex = { mainState.selectedPage },
            onSelected = { mainState.animateToPage(it) },
            backdrop = backdrop,
            tabsCount = items.size,
            isBlurEnabled = enableFloatingBottomBarBlur,
        ) {
            items.forEachIndexed { index, item ->
                FloatingBottomBarItem(
                    onClick = { mainState.animateToPage(index) },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

private enum class BottomBarDestination(
    val label: String,
    val icon: ImageVector,
) {
    Home("主页", Icons.Rounded.Cottage),
    List("列表", Icons.Rounded.Security),
    Setting("设置", Icons.Rounded.Settings),
}
