package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBar
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableFloatingBottomBarBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppBottomBar(
    selectedIndex: Int,
    items: List<AppBottomBarItem>,
    blurBackdrop: LayerBackdrop?,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val floating = LocalEnableFloatingBottomBar.current
    if (!floating) {
        BlurredBar(blurBackdrop) {
            NavigationBar(
                modifier = modifier,
                color = if (blurBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        modifier = Modifier.weight(1f),
                        selected = selectedIndex == index,
                        onClick = { onSelected(index) },
                        icon = if (selectedIndex == index) item.selectedIcon else item.unselectedIcon,
                        label = item.label,
                    )
                }
            }
        }
    } else {
        val glass = LocalEnableFloatingBottomBarBlur.current
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minWidth = 260.dp)
                    .height(64.dp)
                    .clip(CircleShape)
                    .background(
                        if (glass) MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.68f)
                        else MiuixTheme.colorScheme.surfaceContainer,
                    )
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onSelected(index) }
                            .weight(1f)
                            .height(56.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = if (selectedIndex == index) item.selectedIcon else item.unselectedIcon,
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
}

data class AppBottomBarItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)
